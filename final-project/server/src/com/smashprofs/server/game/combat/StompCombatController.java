package com.smashprofs.server.game.combat;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.server.game.MatchState;
import com.smashprofs.server.game.ServerPlayer;
import com.smashprofs.server.game.physics.ArenaBoundaryController;
import com.smashprofs.server.game.physics.PlayerJumpController;
import com.smashprofs.server.game.physics.PlayerPhysicsFactory.PlayerPhysicsBodies;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Một controller mỗi runtime, chỉ simulation owner gọi. Port stomp trên không -> landing -> distance hit.
 * Không dùng player-player contact/AoE; không có cooldown hoặc timeout stomp trong source gốc.
 */
public final class StompCombatController implements AutoCloseable {
    public static final float ACTIVATION_HP_COST = 0.1f;
    public static final float STOMP_DAMAGE = 10.0f;
    public static final float ATTACK_RANGE_METERS = 0.2f;
    // CombatManager gốc dùng (2,2) cho cả hai stomp, khác profile melee thường (1.5,0.2).
    public static final float HORIZONTAL_KNOCKBACK = 2.0f;
    public static final float VERTICAL_KNOCKBACK = 2.0f;
    private final MatchState state;
    private final PlayerPhysicsBodies bodies;
    private final PlayerJumpController jumps;
    private final List<ServerPlayer> orderedPlayers;
    private final Set<PlayerId> active = new HashSet<>();
    private final Set<PlayerId> pendingLandings = new HashSet<>();
    private boolean closed;

    public StompCombatController(MatchState state, PlayerPhysicsBodies bodies, PlayerJumpController jumps) {
        this.state = Objects.requireNonNull(state, "state không được null");
        this.bodies = Objects.requireNonNull(bodies, "bodies không được null");
        this.jumps = Objects.requireNonNull(jumps, "jumps không được null");
        orderedPlayers = state.getPlayerIds().stream().map(state::getPlayer)
                .sorted(Comparator.comparingInt(ServerPlayer::getSlotIndex)).toList();
    }

    /**
     * Chỉ edge mới qua queue; runtime còn kiểm tra grounded TRƯỚC jump input như Player.managePlayerInput gốc.
     * Source cho phép một lần nhấn mới khi đang stomp tiếp tục thêm impulse và mất 0.1 HP,
     * nhưng vẫn chỉ có một landing cho đợt đang bay. HP cost do runtime áp dụng bằng damage API nội bộ.
     */
    public boolean activate(PlayerId playerId) {
        requireOpen();
        ServerPlayer player = playerId == null ? null : state.getPlayer(playerId);
        if (player == null || player.isDead() || jumps.getJumpState(playerId).grounded()) { return false; }
        jumps.applyStompDownwardImpulse(playerId);
        active.add(playerId);
        return true;
    }

    /** Sau ground sync của step hiện tại: landing được xử lý trong combat TRƯỚC step kế tiếp như baseline. */
    public void captureLandingsAfterStep() {
        requireOpen();
        for (ServerPlayer player : orderedPlayers) {
            PlayerId id = player.getPlayerId();
            if (player.isDead()) {
                cancel(id);
            } else if (active.contains(id) && jumps.getJumpState(id).grounded()) {
                active.remove(id);
                pendingLandings.add(id);
            }
        }
    }

    /** respawnPlayers gốc hủy stomp mỗi update dưới Y=0, kể cả khi fall penalty đã được chống lặp. */
    public void cancelDeadOrRecoveredPlayers() {
        requireOpen();
        for (ServerPlayer player : orderedPlayers) {
            if (player.isDead() || bodies.getPosition(player.getPlayerId()).yMeters()
                    < ArenaBoundaryController.ORIGINAL_BOUNDS.fallThresholdMeters()) {
                cancel(player.getPlayerId());
            }
        }
    }

    /**
     * Consume landing đúng một lần kể cả miss: không giữ stompHitground stale khi đi/nhảy khỏi nền.
     * Không kiểm tra blocking: CombatManager gốc không có !isBlocking trong nhánh stomp.
     * 3–4 người: một target sống gần nhất; bằng khoảng cách chọn slot nhỏ hơn, không AoE.
     */
    public StompHit consumeLanding(PlayerId attackerId) {
        requireOpen();
        if (!pendingLandings.remove(attackerId)) { return null; }
        ServerPlayer attacker = state.getPlayer(attackerId);
        if (attacker == null || attacker.isDead()) { return null; }
        var origin = bodies.getPosition(attackerId);
        ServerPlayer nearest = null;
        float nearestDistance = ATTACK_RANGE_METERS;
        for (ServerPlayer target : orderedPlayers) {
            if (target.getPlayerId().equals(attackerId) || target.isDead()) { continue; }
            var position = bodies.getPosition(target.getPlayerId());
            float dx = position.xMeters() - origin.xMeters();
            float dy = position.yMeters() - origin.yMeters();
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            if (distance < nearestDistance) {
                nearest = target;
                nearestDistance = distance;
            }
        }
        if (nearest == null) { return null; }
        float targetX = bodies.getPosition(nearest.getPlayerId()).xMeters();
        float knockbackX = origin.xMeters() < targetX ? HORIZONTAL_KNOCKBACK : -HORIZONTAL_KNOCKBACK;
        return new StompHit(nearest.getPlayerId(), knockbackX);
    }

    public StompState getState(PlayerId playerId) {
        requireOpen();
        if (!state.containsPlayer(playerId)) { throw new IllegalArgumentException("Player không thuộc trận"); }
        return new StompState(active.contains(playerId), pendingLandings.contains(playerId));
    }

    private void cancel(PlayerId playerId) {
        active.remove(playerId);
        pendingLandings.remove(playerId);
    }

    private void requireOpen() {
        if (closed) { throw new IllegalStateException("Stomp controller đã đóng"); }
    }

    @Override
    public void close() {
        active.clear();
        pendingLandings.clear();
        closed = true;
    }

    /** Kết quả nội bộ server, không phải protocol hoặc HP state thứ hai. */
    public record StompHit(PlayerId targetId, float knockbackX) { }
    /** View bất biến để quan sát lifecycle, không phải GameSnapshot/network DTO. */
    public record StompState(boolean active, boolean pendingLanding) { }
}
