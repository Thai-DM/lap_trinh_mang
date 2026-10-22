package com.smashprofs.server.game.physics;

import com.badlogic.gdx.physics.box2d.Body;
import com.smashprofs.common.model.PlayerId;

import java.util.Objects;

/** Chỉ chuyển phần impulse ngang độc lập của Player.managePlayerInput, không giả lập grounded. */
public final class PlayerMovementController {
    // Player.java: impulse 0.05 * axis * walkingSpeedMultiplier, maxVelocity = 1.
    public static final float HORIZONTAL_IMPULSE = 0.05f * 1.1f;
    public static final float MAX_HORIZONTAL_SPEED = 1.0f;

    /**
     * Chỉ runtime simulation owner gọi trước World.step; không trả Body cho network handler.
     * InputQueue đã validate axis; kiểm tra phòng vệ cho caller nội bộ dùng trực tiếp controller.
     * Damping 0.999/đổi axis trên không vẫn chưa port; Bước 30 chỉ thêm grounded/jump/gravity,
     * không thay đổi horizontal movement của Bước 29.
     */
    public void applyHorizontalMovement(PlayerPhysicsFactory.PlayerPhysicsBodies players,
                                        PlayerId playerId, float moveAxis) {
        Objects.requireNonNull(players, "players không được null");
        Objects.requireNonNull(playerId, "playerId không được null");
        if (!Float.isFinite(moveAxis) || moveAxis < -1.0f || moveAxis > 1.0f) {
            throw new IllegalArgumentException("moveAxis phải hữu hạn và nằm trong [-1, 1]");
        }
        Body body = players.getBody(playerId);
        if (body == null) {
            throw new IllegalArgumentException("Player không thuộc physics của trận: " + playerId);
        }
        if (body.getWorld().isLocked()) {
            throw new IllegalStateException("Không áp dụng movement trong World.step");
        }
        if (moveAxis != 0.0f) {
            var center = body.getWorldCenter();
            body.applyLinearImpulse(HORIZONTAL_IMPULSE * moveAxis, 0.0f, center.x, center.y, true);
        }
        // Clamp sau impulse tránh overshoot của gate gốc; vẫn cho phép impulse ngược hướng ở mức trần.
        var velocity = body.getLinearVelocity();
        float limitedX = Math.max(-MAX_HORIZONTAL_SPEED, Math.min(MAX_HORIZONTAL_SPEED, velocity.x));
        if (limitedX != velocity.x) {
            body.setLinearVelocity(limitedX, velocity.y);
        }
        // Axis 0 chỉ ngừng gia tốc; không tự phanh/coi player đang grounded, không thay đổi velocity Y.
    }
}
