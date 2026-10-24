package com.smashprofs.server.game.projectile;

import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.math.Vector2;
import com.smashprofs.common.model.PlayerId;

import java.util.Objects;

/** Metadata và handle native nội bộ một match; không texture, session hoặc public Body getter. */
public final class ServerProjectile {
    private final long projectileId;
    private final String matchId;
    private final PlayerId ownerPlayerId;
    private final long spawnTick;
    private final ProjectileType projectileType;
    private final float velocityX;
    private final float velocityY;
    private final PlayerId targetPlayerId;
    // Chỉ Rocket dùng; không giữ Body/ServerPlayer của target hoặc chia sẻ steering giữa projectile.
    private final Vector2 targetVector;
    private Body body;

    ServerProjectile(long projectileId, String matchId, PlayerId ownerPlayerId, long spawnTick,
                     ProjectileType projectileType, float velocityX, float velocityY, Body body) {
        this(projectileId, matchId, ownerPlayerId, spawnTick, projectileType, velocityX, velocityY, body, null);
    }

    ServerProjectile(long projectileId, String matchId, PlayerId ownerPlayerId, long spawnTick,
                     ProjectileType projectileType, float velocityX, float velocityY, Body body, PlayerId targetPlayerId) {
        this.projectileId = projectileId;
        this.matchId = matchId;
        this.ownerPlayerId = ownerPlayerId;
        this.spawnTick = spawnTick;
        this.projectileType = projectileType;
        this.velocityX = velocityX;
        this.velocityY = velocityY;
        this.body = body;
        this.targetPlayerId = projectileType == ProjectileType.ROCKET
                ? Objects.requireNonNull(targetPlayerId, "Rocket phải có target phía server") : null;
        this.targetVector = projectileType == ProjectileType.ROCKET ? new Vector2() : null;
    }

    public long getProjectileId() { return projectileId; }
    public String getMatchId() { return matchId; }
    public PlayerId getOwnerPlayerId() { return ownerPlayerId; }
    public String getProjectileType() { return projectileType.name(); }
    public long getSpawnTick() { return spawnTick; }
    public boolean isActive() { return body != null; }
    /** Metadata chỉ đọc: target Rocket khóa khi spawn; các type khác trả null. Không phải protocol field. */
    public PlayerId getTargetPlayerId() { return targetPlayerId; }

    Body body() { return body; }
    ProjectileType type() { return projectileType; }
    float velocityX() { return velocityX; }
    float velocityY() { return velocityY; }
    void updateHoming(float targetX, float targetY, float inertia, float speed) {
        var position = body.getPosition();
        // Đúng chuỗi mutate Vector2 gốc: lerp -> nor -> scl; vector đã scale là history của tick sau.
        targetVector.lerp(new Vector2(targetX - position.x, targetY - position.y), inertia).nor().scl(speed);
        body.setLinearVelocity(targetVector);
    }
    void releaseBody() { body = null; } // Không JNI, dùng được cả sau World.dispose.

    View view() {
        var position = body.getPosition();
        var velocity = body.getLinearVelocity();
        return new View(projectileId, matchId, ownerPlayerId, getProjectileType(), spawnTick,
                position.x, position.y, velocity.x, velocity.y);
    }

    /** Bản sao giá trị chỉ đọc phục vụ quan sát nội bộ; không phải protocol/GameSnapshot. */
    public record View(long projectileId, String matchId, PlayerId ownerPlayerId, String projectileType,
                       long spawnTick, float x, float y, float velocityX, float velocityY) { }
}
