package com.smashprofs.common.model;

import java.util.List;

/** Immutable authoritative game snapshot sent to clients. */
public record GameSnapshot(
        String roomId,
        long serverTick,
        String phase,
        long remainingTimeMs,
        List<PlayerState> players
) {
    public GameSnapshot {
        players = List.copyOf(players);
    }
}
