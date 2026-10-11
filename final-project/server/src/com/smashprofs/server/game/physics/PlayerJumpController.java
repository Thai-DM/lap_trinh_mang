package com.smashprofs.server.game.physics;

import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.Contact;
import com.badlogic.gdx.physics.box2d.ContactImpulse;
import com.badlogic.gdx.physics.box2d.ContactListener;
import com.badlogic.gdx.physics.box2d.Manifold;
import com.smashprofs.common.model.PlayerId;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** State nhảy/ground riêng từng player, chỉ MatchRuntime simulation owner cập nhật. */
public final class PlayerJumpController implements AutoCloseable {
    // Player.java: applyForces(0,0), jumpForce và maxExtraJumps; không nhân client deltaTime.
    public static final float GRAVITY_IMPULSE = -0.098f;
    public static final float JUMP_FORCE = 2.2f;
    public static final float STOMP_DOWNWARD_IMPULSE = -5.0f; // Player.stompSpeed qua applyForces, KHÔNG set velocity.
    private static final int MAX_JUMPS = 2;
    private static final float RESTING_VELOCITY_TOLERANCE = 0.0001f;
    private final PlayerPhysicsFactory.PlayerPhysicsBodies players;
    private final GroundContactListener contacts;
    private final Map<PlayerId, MutableJumpState> states = new HashMap<>();
    private boolean closed;

    /** Gắn listener trước khi runtime được publish/start ticking; không thay đổi fixture/filter. */
    public PlayerJumpController(PlayerPhysicsFactory.PlayerPhysicsBodies players) {
        this(players, null);
    }

    /** World chỉ có một listener: ghép projectile callbacks, không thay thế ground detection. */
    public PlayerJumpController(PlayerPhysicsFactory.PlayerPhysicsBodies players, ContactListener projectileContacts) {
        this.players = Objects.requireNonNull(players, "players không được null");
        for (PlayerId playerId : players.getPlayerIds()) {
            states.put(playerId, new MutableJumpState());
        }
        if (states.isEmpty()) {
            throw new IllegalStateException("Cần player bodies đang hoạt động trước jump controller");
        }
        contacts = new GroundContactListener(players);
        Body first = players.getBody(states.keySet().iterator().next());
        requireUnlocked(first);
        first.getWorld().setContactListener(projectileContacts == null ? contacts : new ContactListener() {
            @Override public void beginContact(Contact contact) {
                contacts.beginContact(contact);
                projectileContacts.beginContact(contact);
            }
            @Override public void endContact(Contact contact) {
                contacts.endContact(contact);
                projectileContacts.endContact(contact);
            }
            @Override public void preSolve(Contact contact, Manifold oldManifold) {
                contacts.preSolve(contact, oldManifold);
                projectileContacts.preSolve(contact, oldManifold);
            }
            @Override public void postSolve(Contact contact, ContactImpulse impulse) {
                contacts.postSolve(contact, impulse);
                projectileContacts.postSolve(contact, impulse);
            }
        });
    }

    /** Chỉ gọi cho edge input mới từ batch drain, không giữ/replay jumpPressed giữa các tick. */
    public boolean jump(PlayerId playerId) {
        MutableJumpState state = requireState(playerId);
        if (state.jumpsUsed >= MAX_JUMPS || (!state.grounded && !state.extraJumpReady)) {
            return false;
        }
        Body body = players.getBody(playerId);
        requireUnlocked(body);
        var center = body.getWorldCenter();
        float impulse = -0.8f * body.getLinearVelocity().y + JUMP_FORCE;
        body.applyLinearImpulse(0.0f, impulse, center.x, center.y, true);
        state.jumpsUsed++;
        state.extraJumpReady = true;
        state.grounded = false;
        return true;
    }

    /** Gốc áp dụng gravity một lần mỗi update, kể cả trước checkHealth; chưa có death logic ở đây. */
    public void applyGravity() {
        requireOpen();
        for (PlayerId playerId : states.keySet()) {
            Body body = players.getBody(playerId);
            requireUnlocked(body);
            var center = body.getWorldCenter();
            body.applyLinearImpulse(0.0f, states.get(playerId).gravityImpulse, center.x, center.y, true);
        }
    }

    /**
     * Port Player.applyForces(x,y) cho melee: J=(x, y + gravity hiện tại), không set velocity/teleport.
     * Source gọi applyForces cả trong Player.update và khi trúng hit: giữ phần gravity bổ sung trên hit,
     * kể cả khi boundary/fall đã giảm gravity. Không tạo thêm nguồn gravity hoặc reset quyền jump.
     * Chỉ simulation owner gọi cho hit đã validate, trước World.step duy nhất của tick.
     */
    public void applyMeleeKnockback(PlayerId playerId, float impulseX, float impulseY) {
        applyForcesWithGravity(playerId, impulseX, impulseY);
    }

    /** Controller đã kiểm tra airborne trước input; không đổi quyền jump hoặc vận tốc ngang. */
    public void applyStompDownwardImpulse(PlayerId playerId) {
        applyForcesWithGravity(playerId, 0.0f, STOMP_DOWNWARD_IMPULSE);
    }

    /** Stomp và melee dùng chung Player.applyForces gốc; profile lực do từng đòn phía server quyết định. */
    public void applyStompKnockback(PlayerId playerId, float impulseX, float impulseY) {
        applyForcesWithGravity(playerId, impulseX, impulseY);
    }

    /** CombatManager dùng cùng applyForces cho projectile, profile không phụ thuộc slot. */
    public void applyProjectileKnockback(PlayerId playerId, float impulseX, float impulseY) {
        applyForcesWithGravity(playerId, impulseX, impulseY);
    }

    private void applyForcesWithGravity(PlayerId playerId, float impulseX, float impulseY) {
        MutableJumpState state = requireState(playerId);
        if (!Float.isFinite(impulseX) || !Float.isFinite(impulseY)) {
            throw new IllegalArgumentException("Impulse phải hữu hạn");
        }
        Body body = players.getBody(playerId);
        requireUnlocked(body);
        var center = body.getWorldCenter();
        body.applyLinearImpulse(impulseX, impulseY + state.gravityImpulse, center.x, center.y, true);
    }

    /** Boundary/fall gốc dùng startingGravity * 0.3, không cấp thêm jump hoặc đổi grounded. */
    public void useReducedGravity(PlayerId playerId) {
        requireState(playerId).gravityImpulse = GRAVITY_IMPULSE * 0.3f;
    }

    /** Đồng bộ sau step thành công, chỉ landing từ phía dưới mới reset quyền jump. */
    public void synchronizeAfterStep() {
        requireOpen();
        for (var entry : states.entrySet()) {
            MutableJumpState state = entry.getValue();
            // Contact cũ còn touching trong tick cất cánh không được reset jumps khi player đang đi lên.
            boolean grounded = contacts.hasGroundContact(entry.getKey())
                    && players.getBody(entry.getKey()).getLinearVelocity().y <= RESTING_VELOCITY_TOLERANCE;
            if (grounded && !state.grounded) {
                state.jumpsUsed = 0;
                state.extraJumpReady = false;
            }
            if (grounded) {
                state.gravityImpulse = GRAVITY_IMPULSE; // checkGrounded gốc trả gravity về startingGravity.
            }
            state.grounded = grounded;
        }
    }

    /** Snapshot bất biến chỉ đọc; Body vẫn là nguồn vị trí/vận tốc duy nhất. */
    public JumpState getJumpState(PlayerId playerId) {
        MutableJumpState state = requireState(playerId);
        return new JumpState(state.grounded, state.jumpsUsed, state.extraJumpReady);
    }

    private MutableJumpState requireState(PlayerId playerId) {
        requireOpen();
        MutableJumpState state = states.get(Objects.requireNonNull(playerId, "playerId không được null"));
        if (state == null) {
            throw new IllegalArgumentException("Player không thuộc trận: " + playerId);
        }
        return state;
    }

    private void requireOpen() {
        if (closed) { throw new IllegalStateException("Jump controller đã đóng"); }
    }

    private static void requireUnlocked(Body body) {
        if (body.getWorld().isLocked()) {
            throw new IllegalStateException("Không mutate jump/gravity trong contact callback");
        }
    }

    @Override
    public void close() {
        closed = true;
        states.clear();
        contacts.close(); // Không JNI sau World.dispose, không destroy body hoặc fixture.
    }

    private static final class MutableJumpState {
        private boolean grounded;
        private int jumpsUsed;
        private boolean extraJumpReady;
        private float gravityImpulse = GRAVITY_IMPULSE;
    }

    public record JumpState(boolean grounded, int jumpsUsed, boolean extraJumpReady) { }
}
