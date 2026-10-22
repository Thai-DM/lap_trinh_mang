package com.smashprofs.server.game;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/** Handoff bounded, không giữ runtime/room/World; idempotency chỉ trong một server lifecycle. */
public final class MatchResultPublisher implements AutoCloseable {
    public static final int DEFAULT_QUEUE_CAPACITY = 128;
    public static final int DEFAULT_TRACKED_CAPACITY = 4096;
    private static final Logger LOGGER = Logger.getLogger(MatchResultPublisher.class.getName());
    public enum Status {
        QUEUED, IN_FLIGHT, SUBMITTED, FAILED, NOT_CONFIGURED, QUEUE_FULL,
        TRACKING_FULL, CLOSED, CANCELLED_SHUTDOWN, SHUTDOWN_UNCONFIRMED
    }
    private final Object lock = new Object();
    private final Map<String, AtomicReference<Status>> statuses = new HashMap<>();
    private final int trackedCapacity;
    private final long shutdownNanos;
    private final RankingGateway gateway;
    private final ThreadPoolExecutor executor;
    private volatile Thread worker;
    private volatile boolean closed;
    private long trackingRejections;

    /** null nghĩa là chưa cấu hình, tuyệt đối không dùng no-op/fake làm production success. */
    public MatchResultPublisher(RankingGateway gateway) {
        this(gateway, DEFAULT_QUEUE_CAPACITY, DEFAULT_TRACKED_CAPACITY, Duration.ofSeconds(1));
    }
    public MatchResultPublisher(RankingGateway gateway, int queueCapacity, int trackedCapacity, Duration shutdownWait) {
        if (queueCapacity <= 0 || trackedCapacity <= 0) { throw new IllegalArgumentException("Capacity phải > 0"); }
        shutdownNanos = Objects.requireNonNull(shutdownWait).toNanos();
        if (shutdownNanos < 0) { throw new IllegalArgumentException("Shutdown wait không được âm"); }
        this.gateway = gateway; this.trackedCapacity = trackedCapacity;
        executor = gateway == null ? null : new ThreadPoolExecutor(1, 1, 0, TimeUnit.NANOSECONDS,
                new ArrayBlockingQueue<>(queueCapacity), command -> {
                    var thread = new Thread(command, "smashprofs-ranking-handoff");
                    // Không thể cưỡng bức dừng Java gateway không hợp tác; không cho nó giữ JVM sống vô hạn.
                    thread.setDaemon(true); worker = thread; return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
    /** Không chờ gateway hoặc queue space; rejected/failure vẫn giữ tombstone, không tự retry. */
    public Status offer(AuthoritativeMatchResult result) {
        Objects.requireNonNull(result); Objects.requireNonNull(result.matchId());
        synchronized (lock) {
            var previous = statuses.get(result.matchId());
            if (previous != null) { return previous.get(); }
            if (closed) { return Status.CLOSED; }
            if (statuses.size() >= trackedCapacity) {
                if (trackingRejections++ == 0) { LOGGER.warning("Ranking publication tracking đầy; không nhận trận mới trong lifecycle này"); }
                return Status.TRACKING_FULL;
            }
            var status = new AtomicReference<>(gateway == null ? Status.NOT_CONFIGURED : Status.QUEUED);
            statuses.put(result.matchId(), status);
            if (gateway == null) {
                LOGGER.warning("RankingGateway chưa cấu hình; result chưa được chuyển: " + result.matchId());
            } else {
                try { executor.execute(new Delivery(result, status)); }
                catch (RejectedExecutionException full) {
                    status.set(Status.QUEUE_FULL); LOGGER.warning("Ranking handoff queue đầy; không chuyển result: " + result.matchId());
                }
            }
            return status.get();
        }
    }
    public Status getStatus(String matchId) {
        synchronized (lock) { var status = statuses.get(matchId); return status == null ? null : status.get(); }
    }
    public int getTrackedCount() { synchronized (lock) { return statuses.size(); } }
    public long getTrackingRejections() { synchronized (lock) { return trackingRejections; } }
    public int getPendingCount() { return executor == null ? 0 : executor.getQueue().size(); }
    public boolean isTerminated() { return executor == null || executor.isTerminated(); }
    private final class Delivery implements Runnable {
        private final AuthoritativeMatchResult result;
        private final AtomicReference<Status> status;
        private Delivery(AuthoritativeMatchResult result, AtomicReference<Status> status) { this.result = result; this.status = status; }
        @Override public void run() {
            if (!status.compareAndSet(Status.QUEUED, Status.IN_FLIGHT)) { return; }
            try {
                gateway.submitMatchResult(result);
                status.compareAndSet(Status.IN_FLIGHT, Status.SUBMITTED);
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) { Thread.currentThread().interrupt(); }
                status.compareAndSet(Status.IN_FLIGHT, Status.FAILED);
                LOGGER.warning("RankingGateway submit thất bại; không retry hoặc thay đổi kết quả: " + result.matchId());
            }
        }
    }
    /** Drain hữu hạn; pending bị cancel có trạng thái, in-flight quá hạn là chưa xác nhận, không giả success. */
    @Override public void close() {
        synchronized (lock) { if (closed) { return; } closed = true; }
        if (executor == null) { return; }
        executor.shutdown(); boolean terminated = false;
        if (Thread.currentThread() != worker) {
            try { terminated = executor.awaitTermination(shutdownNanos, TimeUnit.NANOSECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        if (!terminated) {
            synchronized (lock) {
                for (var status : statuses.values()) {
                    status.compareAndSet(Status.QUEUED, Status.CANCELLED_SHUTDOWN);
                    status.compareAndSet(Status.IN_FLIGHT, Status.SHUTDOWN_UNCONFIRMED);
                }
            }
            executor.shutdownNow();
            LOGGER.warning("Ranking shutdown hết thời gian drain; pending canceled, in-flight chưa được xác nhận");
        }
    }
}
