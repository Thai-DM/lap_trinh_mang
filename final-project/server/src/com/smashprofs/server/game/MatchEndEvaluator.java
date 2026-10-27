package com.smashprofs.server.game;

import com.smashprofs.common.model.PlayerId;

/** Pure evaluator tren snapshot cuoi tick; khong doc session, sua combat hoac tao dinh danh tran moi. */
final class MatchEndEvaluator {
    private MatchEndEvaluator() { }

    static AuthoritativeMatchResult evaluate(AuthoritativeGameSnapshot snapshot, boolean timeLimitReached) {
        return evaluate(snapshot, timeLimitReached, java.util.Map.of());
    }

    static AuthoritativeMatchResult evaluate(AuthoritativeGameSnapshot snapshot, boolean timeLimitReached,
                                             java.util.Map<PlayerId, String> displayNames) {
        var survivors = snapshot.players().stream().filter(player -> snapshot.gameplay()==null?!player.dead():snapshot.gameplay().player(player.playerId().value()).lives()>0).toList();
        PlayerId winner = null;
        AuthoritativeMatchResult.FinishReason reason;
        if(snapshot.gameplay()!=null && snapshot.gameplay().boss()!=null) {
            if(snapshot.gameplay().boss().hp()==0) { reason=AuthoritativeMatchResult.FinishReason.BOSS_DEFEATED; }
            else if(survivors.isEmpty()) { reason=AuthoritativeMatchResult.FinishReason.TEAM_ELIMINATED; }
            else if(timeLimitReached) { reason=AuthoritativeMatchResult.FinishReason.BOSS_TIMEOUT; }
            else { return null; }
        } else if (survivors.isEmpty()) {
            reason = AuthoritativeMatchResult.FinishReason.ALL_PLAYERS_ELIMINATED;
        } else if (survivors.size() == 1) {
            winner = survivors.get(0).playerId();
            reason = AuthoritativeMatchResult.FinishReason.LAST_PLAYER_STANDING;
        } else if (timeLimitReached) {
            reason = AuthoritativeMatchResult.FinishReason.TIME_LIMIT;
            if(snapshot.gameplay()!=null && snapshot.gameplay().rules().fighter()) {
                int stock=survivors.stream().mapToInt(p->snapshot.gameplay().player(p.playerId().value()).lives()).max().orElseThrow();
                var stockLeaders=survivors.stream().filter(p->snapshot.gameplay().player(p.playerId().value()).lives()==stock).toList();
                float highest=stockLeaders.stream().map(AuthoritativeGameSnapshot.PlayerSnapshot::hp).max(Float::compare).orElseThrow();
                var leaders=stockLeaders.stream().filter(p->Float.compare(p.hp(),highest)==0).toList();
                if(leaders.size()==1) { winner=leaders.get(0).playerId(); }
            } else {
            float highestHp = survivors.stream().map(AuthoritativeGameSnapshot.PlayerSnapshot::hp)
                    .max(Float::compare).orElseThrow();
            var leaders = survivors.stream().filter(player -> Float.compare(player.hp(), highestHp) == 0).toList();
            // Policy multiplayer da chot: hoa HP cao nhat thi draw, khong uu tien slot/iteration order.
            if (leaders.size() == 1) { winner = leaders.get(0).playerId(); }
            }
        } else {
            return null;
        }
        var participants = snapshot.players().stream().map(player ->
                new AuthoritativeMatchResult.ParticipantResult(player.playerId(), player.slotIndex(),
                        player.characterId(), player.hp(),
                        snapshot.gameplay()==null?player.dead():snapshot.gameplay().player(player.playerId().value()).lives()==0,
                        displayNames.get(player.playerId()))).toList();
        return new AuthoritativeMatchResult(snapshot.matchId(), snapshot.roomId(), snapshot.serverTick(),
                winner, reason, participants,snapshot.gameplay());
    }
}
