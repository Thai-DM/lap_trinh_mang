package com.smashprofs.server.game;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.server.game.projectile.ServerProjectile;

import java.util.List;

/**
 * Snapshot nội bộ server, không phải common.GameSnapshot hoặc một protocol mới.
 * Giữ HP float, slot và projectile thật; phase/timer chưa thuộc runtime nên không tạo giá trị giả.
 * TODO: phối hợp Member 3 về HP/schema/adapter trước khi dùng làm payload network.
 */
public record AuthoritativeGameSnapshot(
        String matchId,
        String roomId,
        long serverTick,
        List<PlayerSnapshot> players,
        List<ServerProjectile.View> projectiles
) {
    public AuthoritativeGameSnapshot {
        players = List.copyOf(players);
        projectiles = List.copyOf(projectiles);
    }

    /** Chỉ giữ giá trị và PlayerId bất biến; không giữ ServerPlayer, Body hoặc Vector2. */
    public record PlayerSnapshot(
            PlayerId playerId,
            int slotIndex,
            String characterId,
            float x,
            float y,
            float velocityX,
            float velocityY,
            float hp,
            boolean grounded,
            boolean blocking,
            boolean dead,
            boolean facingRight
    ) { }
}
