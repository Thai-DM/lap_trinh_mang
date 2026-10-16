package com.smashprofs.server.game;

import com.smashprofs.server.game.physics.PlayerJumpController;
import com.smashprofs.server.game.physics.PlayerPhysicsFactory.PlayerPhysicsBodies;
import com.smashprofs.server.game.projectile.ProjectileManager;

import java.util.ArrayList;
import java.util.Comparator;

/** Chỉ runtime simulation owner gọi sau cleanup/increment, dưới khóa tick đã có; không public native reader. */
final class GameSnapshotBuilder {
    private GameSnapshotBuilder() { }

    static AuthoritativeGameSnapshot build(MatchState state, PlayerPhysicsBodies bodies,
                                           PlayerJumpController jumps, ProjectileManager projectiles) {
        var players = new ArrayList<AuthoritativeGameSnapshot.PlayerSnapshot>(state.getPlayerCount());
        for (ServerPlayer player : state.playersInSlotOrder()) {
            var id = player.getPlayerId();
            // Body thiếu/đã đóng là lỗi lifecycle: getter hiện có sẽ throw, không fallback (0,0).
            // Player đã chết vẫn có Body tới match close, vì vậy vẫn xuất vị trí/vận tốc thực tế.
            var position = bodies.getPosition(id);
            var velocity = bodies.getVelocity(id);
            players.add(new AuthoritativeGameSnapshot.PlayerSnapshot(id, player.getSlotIndex(),
                    player.getCharacterId(), position.xMeters(), position.yMeters(),
                    velocity.xMetersPerSecond(), velocity.yMetersPerSecond(), player.getHp(),
                    jumps.getJumpState(id).grounded(), player.isBlocking(), player.isDead(), player.isFacingRight()));
        }
        // Manager đã cleanup và giữ active registry theo ID tăng dần; View là record giá trị sẵn có.
        return new AuthoritativeGameSnapshot(state.getMatchId(), state.getRoomId(), state.getServerTick(),
                players, projectiles.getProjectiles());
    }
}
