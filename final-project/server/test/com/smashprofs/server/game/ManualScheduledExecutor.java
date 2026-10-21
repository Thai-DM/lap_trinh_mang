package com.smashprofs.server.game;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Executor giả: test tự chạy từng chu kỳ trên cùng thread, không chờ thời gian thực. */
final class ManualScheduledExecutor extends AbstractExecutorService implements ScheduledExecutorService {
    private Runnable command;
    private ManualFuture future;
    private boolean shutdown;
    private int scheduleCount;
    private long initialDelay;
    private long period;
    private TimeUnit timeUnit;

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay,
                                                  long period, TimeUnit unit) {
        if (shutdown) {
            throw new RejectedExecutionException("Executor giả đã shutdown");
        }
        this.command = command;
        this.initialDelay = initialDelay;
        this.period = period;
        this.timeUnit = unit;
        scheduleCount++;
        future = new ManualFuture();
        return future;
    }

    void runCycle() {
        if (!shutdown && future != null && !future.isCancelled()) {
            command.run();
        }
    }

    int getScheduleCount() {
        return scheduleCount;
    }

    long getInitialDelay() {
        return initialDelay;
    }

    long getPeriodNanos() {
        return timeUnit.toNanos(period);
    }

    boolean isTaskCancelled() {
        return future != null && future.isCancelled();
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown();
        return List.of();
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return shutdown;
    }

    // Chỉ hỗ trợ API mà FixedTickScheduler cần; không giả lập một executor đa năng.
    @Override
    public void execute(Runnable command) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay,
                                                     long delay, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    private static final class ManualFuture implements ScheduledFuture<Object> {
        private boolean cancelled;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean changed = !cancelled;
            cancelled = true;
            return changed;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0L;
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public Object get() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
    }
}
