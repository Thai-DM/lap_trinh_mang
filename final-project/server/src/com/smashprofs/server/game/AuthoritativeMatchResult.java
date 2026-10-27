package com.smashprofs.server.game;

import com.smashprofs.common.model.PlayerId;

import java.util.List;

/** K?t qu? n?i b? b?t bi?n; kh?ng thay common.MatchResult ho?c t? t?o timestamp/score/ranking. */
public record AuthoritativeMatchResult(
        String matchId,
        String roomId,
        long finishedAtTick,
        PlayerId winnerId,
        FinishReason finishReason,
        List<ParticipantResult> participants,
        com.smashprofs.common.model.GameplaySnapshot gameplay
) {
    public AuthoritativeMatchResult(String matchId,String roomId,long finishedAtTick,PlayerId winnerId,FinishReason finishReason,List<ParticipantResult> participants) {
        this(matchId,roomId,finishedAtTick,winnerId,finishReason,participants,null);
    }
    public AuthoritativeMatchResult { participants = List.copyOf(participants); }

    /** PvP uses a null winner for draws; boss matches use the explicit team finish reason. */
    public boolean isDraw() { return winnerId == null && (gameplay==null || !gameplay.rules().boss()); }

    public enum FinishReason { LAST_PLAYER_STANDING, ALL_PLAYERS_ELIMINATED, TIME_LIMIT, BOSS_DEFEATED, TEAM_ELIMINATED, BOSS_TIMEOUT }

    /** eliminated la ban sao dead authoritative tai tick cuoi, khong phai nguon death state moi. displayName chi de hien thi. */
    public record ParticipantResult(PlayerId playerId, int slotIndex, String characterId,
                                    float finalHp, boolean eliminated, String displayName) {
        public ParticipantResult(PlayerId playerId, int slotIndex, String characterId, float finalHp, boolean eliminated) {
            this(playerId, slotIndex, characterId, finalHp, eliminated, null);
        }
        public ParticipantResult { displayName = com.smashprofs.common.model.DisplayName.sanitize(displayName, characterId); }
    }
}
