package com.smashprofs.server.network;

import java.time.Duration;
import java.util.Objects;

/** Chính sách heartbeat độc lập với simulation; chuyển đơn vị có kiểm tra overflow. */
public record HeartbeatConfiguration(Duration interval, Duration timeout) {
    public static final HeartbeatConfiguration DEFAULT = new HeartbeatConfiguration(Duration.ofSeconds(5), Duration.ofSeconds(15));
    public HeartbeatConfiguration {
        Objects.requireNonNull(interval); Objects.requireNonNull(timeout);
        long intervalNanos = interval.toNanos(), timeoutNanos = timeout.toNanos();
        if (intervalNanos <= 0 || timeoutNanos <= intervalNanos) {
            throw new IllegalArgumentException("Interval phải > 0 và timeout phải > interval");
        }
    }
    long intervalNanos() { return interval.toNanos(); }
    long timeoutNanos() { return timeout.toNanos(); }
}
