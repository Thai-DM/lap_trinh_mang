package com.smashprofs.server.game;

import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.World;
import com.badlogic.gdx.utils.Array;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import com.smashprofs.server.game.physics.HeadlessPhysicsWorld;
import com.smashprofs.server.game.physics.PhysicsWorldFactory;
import com.smashprofs.server.game.physics.TmxCollisionLoader;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.smashprofs.server.game.InputQueue.OfferResult.*;
import static org.junit.Assert.*;

/** Manual tick với Box2D native thật; Body chỉ được truy cập từ seam test, không thêm public getter. */
public class MatchRuntimeMovementTest {
    private static final float EPSILON = 0.000001f;
    private final List<MatchRuntime> runtimes = new ArrayList<>();

    @After
    public void closeResources() {
        runtimes.forEach(MatchRuntime::close);
    }

    @Test
    public void positiveMoveAxisMovesPlayerRight() {
        Probe probe = started(2);
        float initialX = probe.body(0).getPosition().x;
        send(probe, 0, 0, 1.0f);
        probe.runtime.tick();
        assertTrue(probe.body(0).getPosition().x > initialX);
        assertEquals(0.055f, probe.body(0).getLinearVelocity().x, EPSILON);
    }

    @Test
    public void negativeMoveAxisMovesPlayerLeft() {
        Probe probe = started(2);
        float initialX = probe.body(0).getPosition().x;
        send(probe, 0, 0, -1.0f);
        probe.runtime.tick();
        assertTrue(probe.body(0).getPosition().x < initialX);
        assertEquals(-0.055f, probe.body(0).getLinearVelocity().x, EPSILON);
    }

    @Test
    public void zeroMoveAxisDoesNotAcceleratePlayer() {
        Probe probe = started(2);
        float initialX = probe.body(0).getPosition().x;
        send(probe, 0, 0, 0.0f);
        for (int tick = 0; tick < 20; tick++) { probe.runtime.tick(); }
        assertEquals(initialX, probe.body(0).getPosition().x, EPSILON);
        assertEquals(0.0f, probe.body(0).getLinearVelocity().x, EPSILON);
    }

    @Test
    public void moveAxisMagnitudeAffectsMovement() {
        Probe full = started(2);
        Probe half = started(2);
        send(full, 0, 0, 1.0f);
        send(half, 0, 0, 0.5f);
        full.runtime.tick();
        half.runtime.tick();
        assertEquals(full.body(0).getLinearVelocity().x / 2, half.body(0).getLinearVelocity().x, EPSILON);
        send(half, 0, 1, -0.5f);
        half.runtime.tick();
        assertEquals(0.0f, half.body(0).getLinearVelocity().x, EPSILON);
    }

    @Test
    public void playerCannotExceedMaximumHorizontalSpeed() {
        Probe right = started(2);
        Probe left = started(2);
        // Vùng trống của TMX: kiểm tra speed cap, không để va chạm platform che kết quả.
        right.body(0).setTransform(4.5f, 3.5f, 0.0f);
        left.body(0).setTransform(5.5f, 3.5f, 0.0f);
        // 25 tick đủ đạt speed cap; chưa chạm platform hay biên ngang gốc 3..8.5.
        for (int tick = 0; tick < 25; tick++) {
            send(right, 0, tick, 1.0f);
            send(left, 0, tick, -1.0f);
            right.runtime.tick();
            left.runtime.tick();
            assertTrue(Math.abs(right.body(0).getLinearVelocity().x) <= 1.0f + EPSILON);
            assertTrue(Math.abs(left.body(0).getLinearVelocity().x) <= 1.0f + EPSILON);
        }
        assertEquals(1.0f, right.body(0).getLinearVelocity().x, EPSILON);
        assertEquals(-1.0f, left.body(0).getLinearVelocity().x, EPSILON);
    }

    @Test
    public void noInputPreservesHeldMovementTemporarily() {
        Probe probe = started(2);
        send(probe, 0, 0, 0.5f);
        probe.runtime.tick();
        float velocity = probe.body(0).getLinearVelocity().x;
        assertTrue(probe.runtime.tick().isEmpty());
        assertTrue(probe.body(0).getLinearVelocity().x > velocity);
    }

    @Test
    public void newNeutralInputStopsActiveAcceleration() {
        Probe probe = started(2);
        send(probe, 0, 0, 1.0f);
        probe.runtime.tick();
        float velocity = probe.body(0).getLinearVelocity().x;
        send(probe, 0, 1, 0.0f);
        probe.runtime.tick();
        probe.runtime.tick();
        assertEquals(velocity, probe.body(0).getLinearVelocity().x, EPSILON);
        // Chưa có grounded damping: nhả phím không đồng nghĩa phanh vận tốc về 0.
        assertTrue(velocity > 0.0f);
    }

    @Test
    public void movementExpiresAfterInputTimeout() {
        Probe probe = started(2);
        probe.body(0).setTransform(4.5f, 3.5f, 0.0f);
        send(probe, 0, 0, 0.2f);
        for (int tick = 0; tick < 15; tick++) {
            float before = probe.body(0).getLinearVelocity().x;
            probe.runtime.tick();
            assertTrue(probe.body(0).getLinearVelocity().x > before);
        }
        float expiredVelocity = probe.body(0).getLinearVelocity().x;
        for (int tick = 0; tick < 20; tick++) { probe.runtime.tick(); }
        assertEquals(expiredVelocity, probe.body(0).getLinearVelocity().x, EPSILON);
    }

    @Test
    public void movementUsesCorrectPlayerBody() {
        Probe probe = started(3);
        float firstX = probe.body(0).getPosition().x;
        float thirdX = probe.body(2).getPosition().x;
        send(probe, 1, 0, -0.5f);
        probe.runtime.tick();
        assertEquals(firstX, probe.body(0).getPosition().x, EPSILON);
        assertEquals(thirdX, probe.body(2).getPosition().x, EPSILON);
        assertEquals(-0.0275f, probe.body(1).getLinearVelocity().x, EPSILON);
    }

    @Test
    public void twoPlayersMoveIndependently() {
        Probe probe = started(2);
        send(probe, 0, 0, 1.0f);
        send(probe, 1, 0, -1.0f);
        probe.runtime.tick();
        assertEquals(0.055f, probe.body(0).getLinearVelocity().x, EPSILON);
        assertEquals(-0.055f, probe.body(1).getLinearVelocity().x, EPSILON);
    }

    @Test
    public void fourPlayersMoveIndependently() {
        Probe probe = started(4);
        float[] axes = {1.0f, -1.0f, 0.0f, 0.5f};
        float[] initialX = new float[4];
        for (int slot = 0; slot < 4; slot++) {
            initialX[slot] = probe.body(slot).getPosition().x;
            send(probe, slot, 0, axes[slot]);
        }
        for (int tick = 0; tick < 5; tick++) { probe.runtime.tick(); }
        for (int slot = 0; slot < 4; slot++) {
            assertEquals(5 * 0.055f * axes[slot], probe.body(slot).getLinearVelocity().x, EPSILON);
        }
        assertTrue(probe.body(0).getPosition().x > initialX[0]);
        assertTrue(probe.body(1).getPosition().x < initialX[1]);
        assertEquals(initialX[2], probe.body(2).getPosition().x, EPSILON);
        assertTrue(probe.body(3).getPosition().x > initialX[3]);
    }

    @Test
    public void movementDoesNotAffectHP() {
        Probe probe = started(4);
        send(probe, 0, 0, 1.0f);
        for (int tick = 0; tick < 30; tick++) { probe.runtime.tick(); }
        for (PlayerId id : probe.runtime.getMatchState().getPlayerIds()) {
            assertEquals(100.0f, probe.runtime.getMatchState().getPlayer(id).getHp(), 0.0f);
            assertFalse(probe.runtime.getMatchState().getPlayer(id).isDead());
        }
    }

    @Test
    public void movementDoesNotApplyJumpWithoutGroundPermission() {
        Probe probe = started(2);
        float initialY = probe.body(0).getPosition().y;
        // Không yêu cầu stomp: test này chỉ kiểm tra jump không có ground permission và gravity bình thường.
        PlayerInput actions = new PlayerInput(id(0), 0, Long.MAX_VALUE, 1.0f, true, false, true, true, true);
        assertEquals(ACCEPTED, probe.runtime.submitInput(actions));
        assertEquals(List.of(actions), probe.runtime.tick());
        for (int tick = 0; tick < 5; tick++) { assertTrue(probe.runtime.tick().isEmpty()); }
        // Bước 30 thêm gravity: player chưa tiếp đất không được nhảy, nhưng vẫn rơi bình thường.
        assertTrue(probe.body(0).getPosition().y < initialY);
        assertTrue(probe.body(0).getLinearVelocity().y < 0.0f);
        assertEquals(0, probe.runtime.getJumpState(id(0)).jumpsUsed());
        assertEquals(0.0f, probe.physics.get().getWorld().getGravity().y, 0.0f);
    }

    @Test
    public void movementDoesNotChangePlayerIdentity() {
        Probe probe = started(4);
        List<ServerPlayer> original = new ArrayList<>();
        for (int slot = 0; slot < 4; slot++) {
            original.add(probe.runtime.getMatchState().getPlayer(id(slot)));
            send(probe, slot, 0, 0.5f);
        }
        probe.runtime.tick();
        for (int slot = 0; slot < 4; slot++) {
            ServerPlayer player = probe.runtime.getMatchState().getPlayer(id(slot));
            assertSame(original.get(slot), player);
            assertEquals(slot, player.getSlotIndex());
            assertEquals(probe.runtime.getParticipants().get(slot).characterId(), player.getCharacterId());
            assertEquals(id(slot), player.getPlayerId());
            assertEquals(id(slot), probe.body(slot).getUserData());
        }
    }

    @Test
    public void movementDoesNotBypassArenaCollision() {
        Probe probe = started(2);
        var wall = new TmxCollisionLoader().loadOriginalMap().rectangles().stream()
                .filter(rectangle -> rectangle.objectId() == 52).findFirst().orElseThrow();
        Body player = probe.body(0);
        // Collider 53 đỡ phía dưới, collider 52 là mặt đứng bên phải: dùng TMX thật có gravity.
        player.setTransform(4.1f, 1.8f, 0.0f);
        for (int tick = 0; tick < 120; tick++) {
            send(probe, 0, tick, 1.0f);
            probe.runtime.tick();
            assertTrue(player.getPosition().x + 0.05f <= wall.xPixels() / 100 + 0.02f);
        }
        assertTrue(player.getPosition().x > 4.1f);
        boolean touchingArena = false;
        for (var contact : probe.physics.get().getWorld().getContactList()) {
            if (contact.isTouching()
                    && (contact.getFixtureA().getBody() == player || contact.getFixtureB().getBody() == player)) {
                touchingArena = true;
            }
        }
        assertTrue(touchingArena);
    }

    @Test
    public void oneTickStepsWorldExactlyOnce() {
        Probe probe = started(4);
        for (int slot = 0; slot < 4; slot++) { send(probe, slot, 0, 0.5f); }
        for (int tick = 1; tick <= 4; tick++) {
            probe.runtime.tick();
            assertEquals(tick, probe.steps.get());
            assertEquals(tick, probe.runtime.getMatchState().getServerTick());
        }
    }

    @Test
    public void playerCannotMoveBeforeRuntimeStarts() {
        Probe probe = create(2, 15, world -> { });
        assertThrows(IllegalStateException.class, () -> probe.runtime.submitInput(input(0, 0, 1.0f)));
        assertThrows(IllegalStateException.class, probe.runtime::tick);
        assertNull(probe.physics.get());
        assertEquals(0, probe.steps.get());
    }

    @Test
    public void playerCannotMoveAfterRuntimeCloses() {
        Probe probe = started(2);
        send(probe, 0, 0, 1.0f);
        probe.runtime.close();
        assertThrows(IllegalStateException.class, () -> probe.runtime.submitInput(input(0, 1, 1.0f)));
        assertThrows(IllegalStateException.class, probe.runtime::tick);
        assertTrue(probe.physics.get().isDisposed());
        assertEquals(0, probe.steps.get());
    }

    @Test
    public void twoMatchesHaveIndependentMovement() {
        // Cố ý dùng cùng PlayerId ở hai World: held state không được chia sẻ theo ID toàn cục.
        Probe first = started(2);
        Probe second = started(2);
        send(first, 0, 0, 1.0f);
        first.runtime.tick();
        second.runtime.tick();
        assertEquals(0.0f, second.body(0).getLinearVelocity().x, EPSILON);
        send(second, 0, 0, -0.5f);
        second.runtime.tick();
        first.runtime.tick();
        assertTrue(first.body(0).getLinearVelocity().x > 0);
        assertTrue(second.body(0).getLinearVelocity().x < 0);
        first.runtime.close();
        second.runtime.tick();
        assertFalse(second.physics.get().isDisposed());
    }

    @Test
    public void existingServerTestsStillPass() {
        Probe probe = started(2);
        send(probe, 0, 5, 0.5f);
        probe.runtime.start();
        assertEquals(List.of(input(0, 5, 0.5f)), probe.runtime.tick());
        assertTrue(probe.runtime.tick().isEmpty());
        assertEquals(DUPLICATE_OR_STALE, probe.runtime.submitInput(input(0, 5, 1.0f)));
        assertEquals(2, probe.steps.get());
        assertEquals(20, probe.physics.get().getWorld().getBodyCount());
    }

    @Test
    public void movementIsAppliedBeforePhysicsStep() {
        Probe probe = create(2, 15, world -> {
            assertEquals(0.055f, body(world, id(0)).getLinearVelocity().x, EPSILON);
            assertEquals(4.5f, body(world, id(0)).getPosition().x, EPSILON);
        });
        probe.runtime.start();
        send(probe, 0, 0, 1.0f);
        probe.runtime.tick();
        assertTrue(probe.body(0).getPosition().x > 4.5f);
    }

    @Test
    public void configurableTimeoutExpiresAtExactTickBoundary() {
        Probe probe = create(2, 3, world -> { });
        probe.runtime.start();
        send(probe, 0, 0, 0.5f);
        for (int tick = 0; tick < 3; tick++) { probe.runtime.tick(); }
        assertEquals(3 * 0.0275f, probe.body(0).getLinearVelocity().x, EPSILON);
        probe.runtime.tick();
        assertEquals(3 * 0.0275f, probe.body(0).getLinearVelocity().x, EPSILON);
    }

    @Test
    public void newInputRefreshesTimeoutAndCanReverseDirection() {
        Probe probe = create(2, 3, world -> { });
        probe.runtime.start();
        send(probe, 0, 0, 0.5f);
        probe.runtime.tick();
        probe.runtime.tick();
        send(probe, 0, 1, -0.5f);
        for (int tick = 0; tick < 3; tick++) { probe.runtime.tick(); }
        assertEquals(-0.0275f, probe.body(0).getLinearVelocity().x, EPSILON);
        probe.runtime.tick();
        assertEquals(-0.0275f, probe.body(0).getLinearVelocity().x, EPSILON);
    }

    @Test
    public void rejectedInputsDoNotRefreshHeldMovement() {
        Probe probe = create(2, 2, world -> { });
        probe.runtime.start();
        send(probe, 0, 0, 0.5f);
        probe.runtime.tick();
        probe.runtime.tick();
        assertEquals(DUPLICATE_OR_STALE, probe.runtime.submitInput(input(0, 0, -1.0f)));
        assertEquals(INVALID_INPUT, probe.runtime.submitInput(input(0, 1, Float.NaN)));
        assertEquals(INVALID_INPUT, probe.runtime.submitInput(input(0, 1, 1.01f)));
        assertEquals(UNKNOWN_PLAYER, probe.runtime.submitInput(input(3, 1, 1.0f)));
        probe.runtime.tick();
        assertEquals(0.055f, probe.body(0).getLinearVelocity().x, EPSILON);
    }

    @Test
    public void latestAggregatedAxisWinsWithoutExtraMovementSteps() {
        Probe probe = started(2);
        send(probe, 0, 0, 1.0f);
        send(probe, 0, 1, -1.0f);
        send(probe, 0, 2, 0.5f);
        assertEquals(List.of(input(0, 2, 0.5f)), probe.runtime.tick());
        assertEquals(0.0275f, probe.body(0).getLinearVelocity().x, EPSILON);
        assertEquals(1, probe.steps.get());
    }

    @Test
    public void submissionDoesNotMutateBodyBeforeSimulationTick() {
        Probe probe = started(2);
        float initialX = probe.body(0).getPosition().x;
        send(probe, 0, 0, 1.0f);
        assertEquals(0.0f, probe.body(0).getLinearVelocity().x, EPSILON);
        assertEquals(initialX, probe.body(0).getPosition().x, EPSILON);
        assertEquals(0, probe.runtime.getMatchState().getServerTick());
        probe.runtime.tick();
        assertTrue(probe.body(0).getLinearVelocity().x > 0);
    }

    @Test(timeout = 15000)
    public void wrongThreadCannotApplyMovementOrConsumeHeldInput() throws Exception {
        Probe probe = started(2);
        probe.runtime.tick(); // Thread này nhận simulation ownership.
        send(probe, 0, 0, 1.0f);
        InputQueueTest.runConcurrently(List.<Callable<Boolean>>of(() -> {
            assertThrows(IllegalStateException.class, probe.runtime::tick);
            return true;
        }));
        assertEquals(0.0f, probe.body(0).getLinearVelocity().x, EPSILON);
        assertEquals(1, probe.steps.get());
        probe.runtime.tick();
        assertEquals(0.055f, probe.body(0).getLinearVelocity().x, EPSILON);
    }

    @Test
    public void oppositeInputCanDeceleratePlayerAtSpeedLimit() {
        Probe probe = started(2);
        for (int tick = 0; tick < 25; tick++) {
            send(probe, 0, tick, 1.0f);
            probe.runtime.tick();
        }
        assertEquals(1.0f, probe.body(0).getLinearVelocity().x, EPSILON);
        send(probe, 0, 25, -1.0f);
        probe.runtime.tick();
        assertEquals(0.945f, probe.body(0).getLinearVelocity().x, EPSILON);
    }

    @Test
    public void movementPreservesVerticalVelocityAndCollider() {
        Probe probe = started(2);
        Probe neutral = started(2);
        Body player = probe.body(0);
        player.setLinearVelocity(0.0f, 0.1f); // Chỉ test đặt sẵn vận tốc, không triển khai jump.
        neutral.body(0).setLinearVelocity(0.0f, 0.1f);
        var fixture = player.getFixtureList().first();
        float mass = player.getMass();
        send(probe, 0, 0, 1.0f);
        probe.runtime.tick();
        neutral.runtime.tick();
        // Horizontal movement không đổi Y; cả hai runtime cùng chịu gravity của Bước 30.
        assertEquals(neutral.body(0).getLinearVelocity().y, player.getLinearVelocity().y, EPSILON);
        assertEquals(0.1f - 0.098f, player.getLinearVelocity().y, EPSILON);
        assertEquals(mass, player.getMass(), 0.0f);
        assertSame(fixture, player.getFixtureList().first());
        assertEquals(0.05f, fixture.getShape().getRadius(), 0.0f);
        assertEquals(0.0f, fixture.getDensity(), 0.0f);
        assertEquals(0.2f, fixture.getFriction(), 0.0f);
        assertEquals(1, fixture.getFilterData().categoryBits);
        assertEquals(7, fixture.getFilterData().maskBits);
        assertEquals(-1, fixture.getFilterData().groupIndex);
    }

    @Test
    public void movementTimeoutMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new MatchRuntime(setup(2, "invalid"), 0));
        assertThrows(IllegalArgumentException.class, () -> new MatchRuntime(setup(2, "invalid"), -1));
        assertEquals(15, MatchRuntime.DEFAULT_MOVEMENT_TIMEOUT_TICKS);
    }

    @Test
    public void repeatedStartDoesNotResetHeldMovement() {
        Probe probe = started(2);
        send(probe, 0, 0, 0.5f);
        probe.runtime.tick();
        probe.runtime.start();
        assertTrue(probe.runtime.tick().isEmpty());
        assertEquals(0.055f, probe.body(0).getLinearVelocity().x, EPSILON);
    }

    private Probe started(int count) {
        Probe probe = create(count, MatchRuntime.DEFAULT_MOVEMENT_TIMEOUT_TICKS, world -> { });
        probe.runtime.start();
        // Vùng trên platform và bên trong biên gốc; tách impulse khỏi correction của Bước 31.
        for (int slot = 0; slot < count; slot++) {
            probe.body(slot).setTransform(4.5f + slot * 0.5f, 3.5f, 0.0f);
        }
        return probe;
    }

    private Probe create(int count, int timeoutTicks, Consumer<World> beforeStep) {
        AtomicReference<HeadlessPhysicsWorld> physics = new AtomicReference<>();
        AtomicInteger steps = new AtomicInteger();
        MatchRuntime runtime = new MatchRuntime(setup(count, "match-" + runtimes.size()), () -> {
            HeadlessPhysicsWorld created = new PhysicsWorldFactory().create();
            physics.set(created);
            return created;
        }, (world, timeStep, velocityIterations, positionIterations) -> {
            assertEquals(1.0f / 60, timeStep, 0.0f);
            assertEquals(6, velocityIterations);
            assertEquals(2, positionIterations);
            beforeStep.accept(world);
            steps.incrementAndGet();
            world.step(timeStep, velocityIterations, positionIterations);
        }, world -> { }, timeoutTicks);
        runtimes.add(runtime);
        return new Probe(runtime, physics, steps);
    }

    private static MatchSetup setup(int count, String matchId) {
        List<MatchSetup.Participant> participants = new ArrayList<>();
        String[] characters = {"Alex", "Luca", "Leo", "Viktor"};
        for (int slot = 0; slot < count; slot++) {
            participants.add(new MatchSetup.Participant(id(slot), slot, characters[slot]));
        }
        return new MatchSetup(matchId, "room-" + matchId, participants);
    }

    private static void send(Probe probe, int slot, long sequence, float axis) {
        assertEquals(ACCEPTED, probe.runtime.submitInput(input(slot, sequence, axis)));
    }

    private static PlayerInput input(int slot, long sequence, float axis) {
        // clientTick cố ý không theo thời gian server: không được dùng để gia hạn timeout.
        return new PlayerInput(id(slot), sequence, Long.MAX_VALUE, axis, false, false, false, false, false);
    }

    private static PlayerId id(int slot) { return new PlayerId("arbitrary-person-" + slot); }

    private static Body body(World world, PlayerId id) {
        Array<Body> bodies = new Array<>();
        world.getBodies(bodies);
        for (Body body : bodies) {
            if (id.equals(body.getUserData())) { return body; }
        }
        throw new AssertionError("Không tìm thấy Body của " + id);
    }

    private record Probe(MatchRuntime runtime, AtomicReference<HeadlessPhysicsWorld> physics, AtomicInteger steps) {
        Body body(int slot) { return MatchRuntimeMovementTest.body(physics.get().getWorld(), id(slot)); }
    }
}
