package com.smashprofs.common.model;

import java.util.List;

/** Final authoritative result for one completed match. */
public record MatchResult(
        String matchId,
        String roomId,
        PlayerId winnerId,
        List<PlayerId> participantIds,
        long finishedAtEpochMs
) {
    public MatchResult {
        participantIds = List.copyOf(participantIds);
    }
}
