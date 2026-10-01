package com.smashprofs.common.model;

/** One immutable row in a leaderboard snapshot. */
public record RankingEntry(
        int rank,
        PlayerId playerId,
        String displayName,
        int wins,
        int losses,
        int totalMatches,
        double winRate,
        int rating
) {
}
