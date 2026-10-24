package com.smashprofs.server.network;

import com.smashprofs.server.game.AuthoritativeGameSnapshot;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Dữ liệu bất biến chờ gửi; guard chỉ đọc metadata lifecycle, không đọc JNI. */
public record GameStateDelivery(AuthoritativeGameSnapshot snapshot, String phase, long remainingTimeMs,
                                BooleanSupplier stillValid) {
    public GameStateDelivery {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(stillValid);
        if (!"PLAYING".equals(phase) && !"FINISHED".equals(phase)) { throw new IllegalArgumentException("Sai phase snapshot"); }
        if (remainingTimeMs < 0 || remainingTimeMs > (snapshot.gameplay()==null?120_000:300_000)) { throw new IllegalArgumentException("Sai thời gian trận"); }
    }
}
