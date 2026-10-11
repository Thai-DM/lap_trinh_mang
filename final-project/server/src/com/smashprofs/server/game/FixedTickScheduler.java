package com.smashprofs.server.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.Consumer;

/** Điều phối 60 Hz trên một simulation thread chung; không tự tạo gameplay hoặc register room. */
public final class FixedTickScheduler {
    private static final long TICK_PERIOD_NANOS = TimeUnit.SECONDS.toNanos(1) / MatchRuntime.TICKS_PER_SECOND;
    private final Object registryLock = new Object();
    private final Map<MatchRuntime, Registration> registrations = new LinkedHashMap<>();
    private final ScheduledExecutorService executor;
    private final Logger logger;
    private volatile boolean running;
    private boolean stopped;
    private ScheduledFuture<?> task;
    private volatile Thread cycleThread;
    private Runnable lifecycleUpdate;
    private Runnable afterCycle;

    /** Hook metadata sau các tick hoàn tất, không tạo loop/thread thứ hai. */
    public void setAfterCycle(Runnable update) {
        Objects.requireNonNull(update);
        synchronized (registryLock) {
            if (running || stopped || afterCycle != null) { throw new IllegalStateException("Cấu hình hook trước start"); }
            afterCycle = update;
        }
    }

    public FixedTickScheduler() {
        this(Executors.newSingleThreadScheduledExecutor(command -> {
            Thread thread = new Thread(command, "smashprofs-simulation");
            thread.setDaemon(false);
            return thread;
        }), Logger.getLogger(FixedTickScheduler.class.getName()));
    }

    /**
     * Dành cho test: executor phải thực thi chu kỳ tuần tự trên cùng một thread.
     * Quyền sở hữu executor được chuyển cho scheduler; stop sẽ shutdown executor này.
     */
    FixedTickScheduler(ScheduledExecutorService executor, Logger logger) {
        this.executor = Objects.requireNonNull(executor, "executor không được null");
        this.logger = Objects.requireNonNull(logger, "logger không được null");
    }

    /** Bắt đầu đúng một periodic task; không hỗ trợ restart sau bất kỳ lần stop nào. */
    public void start() {
        synchronized (registryLock) {
            if (stopped) {
                throw new IllegalStateException("Scheduler đã stop, không hỗ trợ restart");
            }
            if (running) {
                return;
            }
            running = true;
            try {
                task = executor.scheduleAtFixedRate(this::runCycle, 0L, TICK_PERIOD_NANOS,
                        TimeUnit.NANOSECONDS);
            } catch (RuntimeException failure) {
                running = false;
                stopped = true;
                executor.shutdownNow();
                throw failure;
            }
        }
    }

    public boolean isRunning() {
        return running;
    }

    /** Gắn đúng một boundary lobby trước start; chạy trên simulation thread, không tạo scheduler mới. */
    public void setLifecycleUpdate(Runnable update) {
        Objects.requireNonNull(update, "update không được null");
        synchronized (registryLock) {
            if (running || stopped || lifecycleUpdate != null) {
                throw new IllegalStateException("Chỉ cấu hình lifecycle một lần trước khi scheduler start");
            }
            lifecycleUpdate = update;
        }
    }

    /**
     * Đăng ký theo identity runtime, gọi lại trả false thay vì tạo tick trùng.
     * Runtime chưa start được phép đăng ký nhưng sẽ bị bỏ qua khi tick.
     * Không gọi tick thủ công sau khi giao runtime cho scheduler; không tự chuyển simulation owner.
     */
    public boolean register(MatchRuntime runtime) {
        return register(runtime, completed -> { });
    }

    /** Completion chạy sau tick/unregister, ngoài khóa runtime/registration; không tự dispose World. */
    public boolean register(MatchRuntime runtime, Consumer<MatchRuntime> onFinished) {
        Objects.requireNonNull(runtime, "runtime không được null");
        Objects.requireNonNull(onFinished, "onFinished không được null");
        synchronized (registryLock) {
            if (stopped) {
                throw new IllegalStateException("Không đăng ký vào scheduler đã stop");
            }
            if (runtime.isClosed()) {
                throw new IllegalStateException("Không đăng ký runtime đã đóng");
            }
            if (runtime.isFinished()) {
                throw new IllegalStateException("Không đăng ký runtime đã FINISHED");
            }
            if (registrations.containsKey(runtime)) {
                return false;
            }
            registrations.put(runtime, new Registration(runtime, onFinished));
            return true;
        }
    }

    /** Hủy đăng ký và đợi tick đang chạy của entry kết thúc, không giữ khóa registry khi đợi. */
    public boolean unregister(MatchRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime không được null");
        Registration removed;
        synchronized (registryLock) {
            removed = registrations.remove(runtime);
            if (removed == null) {
                return false;
            }
            removed.active = false;
        }
        synchronized (removed) {
            // Đợi tick đã bắt đầu trước unregister, không cho entry cũ được tick sau khi trả về.
        }
        return true;
    }

    /**
     * Stop là terminal: hủy periodic task, bỏ registry, shutdown và chờ tối đa 2 lần 5 giây.
     * Nếu gọi từ chu kỳ simulation, không chờ chính thread đó; executor kết thúc khi chu kỳ trả về.
     */
    public void stop() {
        ScheduledFuture<?> scheduled;
        synchronized (registryLock) {
            running = false;
            stopped = true;
            scheduled = task;
            for (Registration registration : registrations.values()) {
                registration.active = false;
            }
            registrations.clear();
            lifecycleUpdate = null;
            afterCycle = null;
        }
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        executor.shutdown();
        if (Thread.currentThread() == cycleThread) {
            return;
        }
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Simulation executor chưa kết thúc sau shutdown");
                }
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Bị interrupt khi đợi scheduler dừng", interrupted);
        }
    }

    /** Cập nhật lobby trước, lấy snapshot runtime sau; không giữ khóa registry khi gọi callback/tick. */
    private void runCycle() {
        Runnable update;
        synchronized (registryLock) {
            if (!running) {
                return;
            }
            update = lifecycleUpdate;
            cycleThread = Thread.currentThread();
        }
        try {
            if (update != null && running) {
                try {
                    update.run();
                } catch (RuntimeException failure) {
                    // Lỗi boundary ngoài room cũng phải được báo và ngừng retry, không làm chết tick task.
                    synchronized (registryLock) {
                        lifecycleUpdate = null;
                    }
                    logger.log(Level.SEVERE, "Cô lập boundary cập nhật lifecycle bị lỗi", failure);
                }
            }
            List<Registration> snapshot;
            synchronized (registryLock) {
                if (!running) {
                    return;
                }
                snapshot = new ArrayList<>(registrations.values());
            }
            for (Registration registration : snapshot) {
                try {
                    boolean finished;
                    synchronized (registration) {
                        if (!running || !registration.active || !registration.runtime.isStarted()
                                || registration.runtime.isClosed()) {
                            continue;
                        }
                        if (!registration.runtime.isFinished()) { registration.runtime.tick(); }
                        finished = registration.runtime.isFinished();
                    }
                    if (finished && unregister(registration.runtime)) {
                        registration.onFinished.accept(registration.runtime);
                    }
                } catch (RuntimeException failure) {
                    synchronized (registryLock) {
                        registration.active = false;
                        registrations.remove(registration.runtime, registration);
                    }
                    logger.log(Level.SEVERE,
                            "Cô lập runtime lỗi khi tick: " + registration.runtime.getMatchId(), failure);
                }
            }
            Runnable publication;
            synchronized (registryLock) { publication = running ? afterCycle : null; }
            if (publication != null) {
                try { publication.run(); }
                catch (RuntimeException failure) { logger.log(Level.WARNING, "Lỗi publication metadata sau tick", failure); }
            }
        } finally {
            cycleThread = null;
        }
    }

    private static final class Registration {
        private final MatchRuntime runtime;
        private final Consumer<MatchRuntime> onFinished;
        private volatile boolean active = true;

        private Registration(MatchRuntime runtime, Consumer<MatchRuntime> onFinished) {
            this.runtime = runtime;
            this.onFinished = onFinished;
        }
    }
}
