package com.smashprofs.server.game.physics;

import com.smashprofs.common.model.PlayerId;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Port limitPlayersToEdge/respawnPlayers gốc; state fall event riêng từng player/trận. */
public final class ArenaBoundaryController implements AutoCloseable {
    // Bounds gameplay của Player.java, KHÔNG phải toàn bộ kích thước TMX (12 x 4 mét).
    public static final Bounds ORIGINAL_BOUNDS = new Bounds(3.0f, 8.5f, 0.0f);
    private static final float PUSHBACK_SPEED = 1.0f;
    private static final float EDGE_VERTICAL_BOOST = 0.1f;
    private static final float RECOVERY_X_DAMPING = 0.1f;
    private static final float RECOVERY_Y_SPEED = 2.6f;
    private final PlayerPhysicsFactory.PlayerPhysicsBodies players;
    private final PlayerJumpController jumps;
    private final Bounds bounds;
    private final Set<PlayerId> recovering = new HashSet<>();
    private boolean closed;
    private final boolean stableRosters=Boolean.parseBoolean(System.getProperty("smashprofs.performance.stableRosters","true"));

    public ArenaBoundaryController(PlayerPhysicsFactory.PlayerPhysicsBodies players, PlayerJumpController jumps) {
        this(players, jumps, ORIGINAL_BOUNDS);
    }

    public ArenaBoundaryController(PlayerPhysicsFactory.PlayerPhysicsBodies players, PlayerJumpController jumps,
                                   Bounds bounds) {
        this.players = Objects.requireNonNull(players, "players không được null");
        this.jumps = Objects.requireNonNull(jumps, "jumps không được null");
        this.bounds = Objects.requireNonNull(bounds, "bounds không được null");
    }

    /**
     * Simulation owner gọi sau gravity, trước World.step như Player.update gốc.
     * Trả đúng ID bắt đầu fall event để runtime áp dụng penalty nội bộ, không public setter HP.
     * Correction lặp khi còn dưới ngưỡng như baseline; chỉ event/penalty được chống lặp.
     */
    public Set<PlayerId> applyBoundaries() {
        requireOpen();
        Set<PlayerId> startedFalls = stableRosters?null:new HashSet<>();
        for (PlayerId playerId : players.getPlayerIds()) {
            var body = players.getBody(playerId);
            if (body.getWorld().isLocked()) {
                throw new IllegalStateException("Không sửa boundary/recovery trong World.step");
            }
            float x = body.getPosition().x;
            if (x > bounds.rightMeters()) {
                body.setLinearVelocity(-PUSHBACK_SPEED, body.getLinearVelocity().y + EDGE_VERTICAL_BOOST);
                jumps.useReducedGravity(playerId);
            } else if (x < bounds.leftMeters()) {
                body.setLinearVelocity(PUSHBACK_SPEED, body.getLinearVelocity().y + EDGE_VERTICAL_BOOST);
                jumps.useReducedGravity(playerId);
            }
            // Gốc xử lý edge trước fall: recovery ghi đè Y và damp X sau pushback nếu cùng xảy ra.
            if (body.getPosition().y < bounds.fallThresholdMeters()) {
                if (recovering.add(playerId)) {
                    if(startedFalls==null) { startedFalls=new HashSet<>(); }
                    startedFalls.add(playerId);
                }
                body.setLinearVelocity(body.getLinearVelocity().x * RECOVERY_X_DAMPING, RECOVERY_Y_SPEED);
                jumps.useReducedGravity(playerId);
            } else {
                recovering.remove(playerId);
            }
        }
        return startedFalls==null?Set.of():Set.copyOf(startedFalls);
    }

    /** Kết thúc event ngay khi kết quả step trở về Y >= threshold; không giả lập grounded/jump. */
    public void synchronizeAfterStep() {
        requireOpen();
        recovering.removeIf(playerId -> players.getBody(playerId).getPosition().y >= bounds.fallThresholdMeters());
    }

    private void requireOpen() {
        if (closed) { throw new IllegalStateException("Boundary controller đã đóng"); }
    }

    @Override
    public void close() {
        closed = true;
        recovering.clear(); // Không native allocation/disposal, an toàn sau World.dispose.
    }

    /** Cấu hình bất biến, giữ nguyên các giá trị baseline trong ORIGINAL_BOUNDS. */
    public record Bounds(float leftMeters, float rightMeters, float fallThresholdMeters) {
        public Bounds {
            if (!Float.isFinite(leftMeters) || !Float.isFinite(rightMeters)
                    || !Float.isFinite(fallThresholdMeters) || leftMeters >= rightMeters) {
                throw new IllegalArgumentException("Bounds phải hữu hạn và left < right");
            }
        }
    }
}
