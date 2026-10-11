package com.smashprofs.server.game;

import com.smashprofs.common.model.PlayerId;

import java.util.Objects;
import java.util.Set;

/** Metadata/HP phía server; public API chỉ đọc, damage chỉ runtime simulation owner áp dụng. */
public final class ServerPlayer {
    // Khớp PlayerTypes và GameRoom hiện tại, không phụ thuộc module core.
    private static final Set<String> VALID_CHARACTER_IDS = Set.of("Alex", "Luca", "Maurice", "Leo", "Viktor");
    private final PlayerId playerId;
    private final String characterId;
    private final int slotIndex;
    private static final float FALL_PENALTY = 10.0f; // Player.respawnPlayers gốc.
    private volatile float hp = 100.0f;
    private volatile boolean dead = false;
    // Nguồn trạng thái blocking chính thức duy nhất; request giữ phím được runtime quản lý timeout.
    private volatile boolean blocking = false;
    // Player.managePlayerInput gốc giữ hướng khi vx=0, khởi đầu nhìn sang phải.
    private volatile boolean facingRight = true;

    public ServerPlayer(PlayerId playerId, String characterId, int slotIndex) {
        this.playerId = Objects.requireNonNull(playerId, "playerId không được null");
        String value = Objects.requireNonNull(playerId.value(), "playerId.value không được null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("playerId.value không được blank");
        }
        this.characterId = Objects.requireNonNull(characterId, "characterId không được null");
        if (!VALID_CHARACTER_IDS.contains(characterId)) {
            throw new IllegalArgumentException("characterId không hợp lệ: " + characterId);
        }
        if (slotIndex < 0 || slotIndex > 3) {
            throw new IllegalArgumentException("slotIndex phải nằm trong khoảng 0 đến 3");
        }
        this.slotIndex = slotIndex;
    }

    public PlayerId getPlayerId() {
        return playerId;
    }

    public String getCharacterId() {
        return characterId;
    }

    public int getSlotIndex() {
        return slotIndex;
    }

    public float getHp() {
        return hp;
    }

    public boolean isDead() {
        return dead;
    }

    public boolean isBlocking() {
        return blocking;
    }

    public boolean isFacingRight() { return facingRight; }

    /** Chỉ simulation owner cập nhật từ vận tốc Body, không nhận hướng bắn từ client. */
    void updateFacing(float velocityX) {
        if (!Float.isFinite(velocityX)) {
            throw new IllegalArgumentException("Vận tốc phải hữu hạn");
        }
        if (velocityX > 0) { facingRight = true; }
        else if (velocityX < 0) { facingRight = false; }
    }

    /** Chỉ simulation owner/lifecycle runtime cập nhật, network chỉ enqueue PlayerInput. */
    void setBlocking(boolean requested) {
        blocking = requested && !dead;
    }

    /** Chỉ runtime gọi một lần cho mỗi fall event; dùng chung đường cập nhật HP với melee. */
    void applyFallPenalty() {
        applyDamage(FALL_PENALTY);
    }

    /** Nội bộ simulation, không phải public HP setter cho network; không hồi sinh player đã chết. */
    void applyDamage(float damage) {
        if (!Float.isFinite(damage) || damage <= 0.0f) {
            throw new IllegalArgumentException("damage phải hữu hạn và lớn hơn 0");
        }
        hp = Math.max(0.0f, hp - damage);
        if (hp == 0.0f) {
            dead = true; // Quy tắc HP <= 0 của checkHealth gốc, không phát GAME_END hoặc tạo respawn mới.
            blocking = false;
        }
    }
}
