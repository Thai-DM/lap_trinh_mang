package com.smashprofs.server.room;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Clock giả dùng chung cho test room; chỉ tiến khi test yêu cầu, không tạo thread. */
final class ManualClock implements LongSupplier {
    long nowNanos = TimeUnit.SECONDS.toNanos(10);

    @Override
    public long getAsLong() {
        return nowNanos;
    }

    void advanceMillis(long millis) {
        nowNanos += TimeUnit.MILLISECONDS.toNanos(millis);
    }
}
