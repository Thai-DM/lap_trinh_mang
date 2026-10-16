package com.smashprofs.server.network;

import com.smashprofs.common.protocol.*;
import java.util.Comparator;

/** Chỉ map schema GAME_START đã chốt; không snapshot giả, tick/spawn mới hoặc World.step. */
public final class GameStartMessageMapper {
    private GameStartMessageMapper() { }
    public static ProtocolMessage map(GameStartDelivery delivery) {
        var setup = delivery.setup();
        var payload = ProtocolV1.object().put("matchId", setup.matchId()).put("roomId", setup.roomId());
        var players = payload.putArray("players");
        setup.participants().stream().sorted(Comparator.comparingInt(com.smashprofs.server.game.MatchSetup.Participant::slotIndex))
                .forEach(p -> players.addObject().put("playerId", p.playerId().value()).put("slotIndex", p.slotIndex()).put("characterId", p.characterId()));
        return new ProtocolMessage(MessageType.GAME_START, null, payload);
    }
}
