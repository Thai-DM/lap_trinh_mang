package com.smashprofs.server.game.combat;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.server.game.MatchState;
import com.smashprofs.server.game.ServerPlayer;
import com.smashprofs.server.game.physics.PlayerPhysicsFactory.PlayerPhysicsBodies;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Một controller mỗi runtime, chỉ simulation owner gọi sau InputQueue validation.
 * Quyết định hit từ Body thật; runtime áp dụng damage nội bộ ngay sau mỗi hit.
 * Blocking chặn toàn bộ damage/knockback melee; không có hướng block hoặc điều kiện grounded trong source gốc.
 */
public final class MeleeCombatController implements AutoCloseable {
    // Player.attackDamage/attackReach gốc giống nhau cho cả năm character.
    public static final float MELEE_DAMAGE = 10.0f;
    public static final float ATTACK_RANGE_METERS = 0.2f;
    public static final float HORIZONTAL_KNOCKBACK = 1.5f;
    /**
     * Profile melee thường dùng chung, không phải thuộc tính slot/player.
     * Source truyền Y=0.2/2 lên target theo nhánh PlayerOne/PlayerTwo, không có chỉ số character tương ứng.
     * Chuẩn hóa về candidate 0.2: Box2D mass=1, gravity=-0.098 cho vy=0.004 và rise~0.000067 m;
     * candidate 2 cho vy=1.804 và rise~0.292 m (launch mạnh gần jumpForce=2.2).
     * Giữ đấm thường chủ yếu đẩy ngang; không thêm HP scaling hoặc luật character chưa được xác minh.
     * Các số đo là trường hợp trên không từ đứng yên, không phải mọi vận tốc/va chạm ban đầu.
     */
    public static final float VERTICAL_KNOCKBACK = 0.2f;
    /**
     * Gốc KHÔNG có melee cooldown. Chính sách server mới: 9 tick ở 60 Hz = 150 ms,
     * lấy cửa sổ animation punch (3 frame x 0.05 s) làm mặc định, không coi nó là cooldown gốc.
     */
    public static final int DEFAULT_COOLDOWN_TICKS = 9;

    private final MatchState state;
    private final PlayerPhysicsBodies bodies;
    private final List<ServerPlayer> orderedPlayers;
    private final int cooldownTicks;
    private final Map<PlayerId, Long> nextAllowedTick = new HashMap<>();
    private boolean closed;

    public MeleeCombatController(MatchState state, PlayerPhysicsBodies bodies, int cooldownTicks) {
        this.state = Objects.requireNonNull(state, "state không được null");
        this.bodies = Objects.requireNonNull(bodies, "bodies không được null");
        if (cooldownTicks <= 0) {
            throw new IllegalArgumentException("cooldownTicks phải lớn hơn 0");
        }
        this.cooldownTicks = cooldownTicks;
        orderedPlayers = state.getPlayerIds().stream().map(state::getPlayer)
                .sorted(Comparator.comparingInt(ServerPlayer::getSlotIndex)).toList();
    }

    /**
     * Chỉ nhận edge đã validate, không nhận target/damage từ client. Null nghĩa là không có hit.
     * Attack hợp lệ tại tick k (kể cả hụt) cho phép lần tiếp theo từ k + cooldownTicks.
     * Request bị chặn không kéo dài cooldown; không có edge mới thì runtime không gọi method này.
     */
    public MeleeHit attack(PlayerId attackerId, long serverTick) {
        if (closed) {
            throw new IllegalStateException("Melee controller đã đóng");
        }
        if (serverTick < 0) {
            throw new IllegalArgumentException("serverTick không được âm");
        }
        ServerPlayer attacker = attackerId == null ? null : state.getPlayer(attackerId);
        if (attacker == null || attacker.isDead()
                || serverTick < nextAllowedTick.getOrDefault(attackerId, 0L)) {
            return null;
        }
        nextAllowedTick.put(attackerId, serverTick + cooldownTicks);
        var origin = bodies.getPosition(attackerId);
        ServerPlayer nearest = null;
        float nearestDistance = ATTACK_RANGE_METERS;
        for (ServerPlayer target : orderedPlayers) {
            if (target.getPlayerId().equals(attackerId) || target.isDead()) {
                continue;
            }
            var position = bodies.getPosition(target.getPlayerId());
            float dx = position.xMeters() - origin.xMeters();
            float dy = position.yMeters() - origin.yMeters();
            // CombatManager gốc dùng Vector2.len(): khoảng cách tâm Euclidean, strict < 0.2 m.
            // Không cộng radius, không thêm facing/line-of-sight gate mà source gốc không có.
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            if (distance < nearestDistance) {
                nearest = target;
                nearestDistance = distance;
            }
        }
        // Mở rộng 1v1: chỉ một target gần nhất; khoảng cách bằng nhau giữ slot nhỏ hơn đã duyệt trước.
        // Giữ nguyên lựa chọn target của Bước 32: target gần nhất blocking thì cú đánh bị chặn,
        // không tự xuyên qua để đánh player khác. Cú bị block vẫn tiêu thụ cooldown đã bắt đầu.
        if (nearest == null || nearest.isBlocking()) {
            return null;
        }
        float targetX = bodies.getPosition(nearest.getPlayerId()).xMeters();
        // Đúng phép so sánh CombatManager gốc, cùng X cũng đẩy sang trái; không dùng facing/vận tốc.
        float knockbackX = origin.xMeters() < targetX ? HORIZONTAL_KNOCKBACK : -HORIZONTAL_KNOCKBACK;
        return new MeleeHit(nearest.getPlayerId(), MELEE_DAMAGE, knockbackX, VERTICAL_KNOCKBACK);
    }

    @Override
    public void close() {
        nextAllowedTick.clear();
        closed = true;
    }

    /** Kết quả nội bộ server, không phải DTO/protocol hoặc payload tin từ client. */
    public record MeleeHit(PlayerId targetId, float damage, float knockbackX, float knockbackY) { }
}
