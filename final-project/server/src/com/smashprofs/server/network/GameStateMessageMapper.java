package com.smashprofs.server.network;

import com.smashprofs.common.protocol.MessageType;
import com.smashprofs.common.protocol.ProtocolMessage;
import com.smashprofs.common.protocol.ProtocolV1;
import com.smashprofs.server.game.AuthoritativeGameSnapshot;
import com.smashprofs.server.game.projectile.ServerProjectile;
import java.util.Comparator;

/** Chạy trên outbound worker, chỉ serialize bản sao giá trị đã publish, không đọc mutable gameplay. */
public final class GameStateMessageMapper {
    private GameStateMessageMapper() { }
    public static ProtocolMessage map(GameStateDelivery delivery) {
        var snapshot = delivery.snapshot();
        var payload = ProtocolV1.object().put("matchId", snapshot.matchId()).put("roomId", snapshot.roomId())
                .put("serverTick", snapshot.serverTick()).put("phase", delivery.phase())
                .put("remainingTimeMs", delivery.remainingTimeMs());
        var players = payload.putArray("players");
        for (var p : snapshot.players().stream().sorted(Comparator.comparingInt(AuthoritativeGameSnapshot.PlayerSnapshot::slotIndex)).toList()) {
            players.addObject().put("playerId", p.playerId().value()).put("slotIndex", p.slotIndex())
                    .put("characterId", p.characterId()).put("x", p.x()).put("y", p.y())
                    .put("velocityX", p.velocityX()).put("velocityY", p.velocityY()).put("hp", p.hp())
                    .put("grounded", p.grounded()).put("blocking", p.blocking()).put("dead", p.dead()).put("facingRight", p.facingRight());
        }
        var projectiles = payload.putArray("projectiles");
        for (var p : snapshot.projectiles().stream().sorted(Comparator.comparingLong(ServerProjectile.View::projectileId)).toList()) {
            projectiles.addObject().put("projectileId", p.projectileId()).put("matchId", p.matchId())
                    .put("ownerPlayerId", p.ownerPlayerId().value()).put("projectileType", p.projectileType())
                    .put("spawnTick", p.spawnTick()).put("x", p.x()).put("y", p.y())
                    .put("velocityX", p.velocityX()).put("velocityY", p.velocityY());
        }
        if(snapshot.gameplay()!=null) { payload.set("gameplay",com.smashprofs.common.protocol.GameplayProtocol.snapshot(snapshot.gameplay())); }
        return new ProtocolMessage(MessageType.GAME_STATE, null, payload);
    }
}
