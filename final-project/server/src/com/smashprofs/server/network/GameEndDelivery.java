package com.smashprofs.server.network;

import com.smashprofs.server.game.AuthoritativeMatchResult;
import java.util.Objects;

/** Gói terminal bất biến: snapshot cuối rồi kết quả chính thức, không phải trạng thái gameplay mới. */
public record GameEndDelivery(AuthoritativeMatchResult result, GameStateDelivery finalState) {
    public GameEndDelivery {
        Objects.requireNonNull(result);
        Objects.requireNonNull(finalState);
        var snapshot = finalState.snapshot();
        if (!result.matchId().equals(snapshot.matchId()) || !result.roomId().equals(snapshot.roomId())
                || result.finishedAtTick() != snapshot.serverTick() || !"FINISHED".equals(finalState.phase())) {
            throw new IllegalArgumentException("Kết quả và snapshot cuối phải thuộc cùng tick/trận");
        }
    }
}
