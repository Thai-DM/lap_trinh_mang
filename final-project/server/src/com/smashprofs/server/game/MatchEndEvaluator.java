package com.smashprofs.server.game;

import com.smashprofs.common.model.PlayerId;

/** Pure evaluator trên snapshot cuối tick; không đọc session, sửa combat hoặc tạo định danh trận mới. */
final class MatchEndEvaluator {
    private MatchEndEvaluator() { }

    static AuthoritativeMatchResult evaluate(AuthoritativeGameSnapshot snapshot, boolean timeLimitReached) {
        var survivors = snapshot.players().stream().filter(player -> snapshot.gameplay()==null?!player.dead():snapshot.gameplay().player(player.playerId().value()).lives()>0).toList();
        PlayerId winner = null;
        AuthoritativeMatchResult.FinishReason reason;
        if (survivors.isEmpty()) {
            reason = AuthoritativeMatchResult.FinishReason.ALL_PLAYERS_ELIMINATED;
        } else if (survivors.size() == 1) {
            winner = survivors.get(0).playerId();
            reason = AuthoritativeMatchResult.FinishReason.LAST_PLAYER_STANDING;
        } else if (timeLimitReached) {
            reason = AuthoritativeMatchResult.FinishReason.TIME_LIMIT;
            if(snapshot.gameplay()!=null && snapshot.gameplay().rules().fighter()) {
                int stock=survivors.stream().mapToInt(p->snapshot.gameplay().player(p.playerId().value()).lives()).max().orElseThrow();
                var stockLeaders=survivors.stream().filter(p->snapshot.gameplay().player(p.playerId().value()).lives()==stock).toList();
                double lowest=stockLeaders.stream().mapToDouble(p->snapshot.gameplay().fighter().player(p.playerId().value()).damagePercent()).min().orElseThrow();
                var leaders=stockLeaders.stream().filter(p->Double.compare(snapshot.gameplay().fighter().player(p.playerId().value()).damagePercent(),lowest)==0).toList();
                if(leaders.size()==1) { winner=leaders.get(0).playerId(); }
            } else {
            float highestHp = survivors.stream().map(AuthoritativeGameSnapshot.PlayerSnapshot::hp)
                    .max(Float::compare).orElseThrow();
            var leaders = survivors.stream().filter(player -> Float.compare(player.hp(), highestHp) == 0).toList();
            // Policy multiplayer đã chốt: hòa HP cao nhất thì draw, không ưu tiên slot/iteration order.
            if (leaders.size() == 1) { winner = leaders.get(0).playerId(); }
            }
        } else {
            return null;
        }
        var participants = snapshot.players().stream().map(player ->
                new AuthoritativeMatchResult.ParticipantResult(player.playerId(), player.slotIndex(),
                        player.characterId(), player.hp(), snapshot.gameplay()==null?player.dead():snapshot.gameplay().player(player.playerId().value()).lives()==0)).toList();
        return new AuthoritativeMatchResult(snapshot.matchId(), snapshot.roomId(), snapshot.serverTick(),
                winner, reason, participants,snapshot.gameplay());
    }
}
