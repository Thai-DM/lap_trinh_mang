package com.smashprofs.server.game;

import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.World;
import com.badlogic.gdx.utils.Array;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import com.smashprofs.server.game.physics.ArenaBoundaryController;
import com.smashprofs.server.game.physics.HeadlessPhysicsWorld;
import com.smashprofs.server.game.physics.PhysicsWorldFactory;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static com.smashprofs.server.game.InputQueue.OfferResult.ACCEPTED;
import static org.junit.Assert.*;

/** Manual tick, World/Body thật; setTransform chỉ để bố trí tình huống test, không có trong controller. */
public class MatchRuntimeBoundaryTest {
    private static final float EPSILON = 0.00001f;
    private final List<MatchRuntime> runtimes = new ArrayList<>();

    @After
    public void closeResources() { runtimes.forEach(MatchRuntime::close); }

    @Test
    public void playerInsideArenaHasNoBoundaryCorrection() {
        Probe probe = create(2);
        position(probe, 0, 5, 3, 0.4f, -0.3f);
        probe.runtime.tick();
        assertEquals(0.4f, probe.body(0).getLinearVelocity().x, EPSILON);
        assertEquals(-0.398f, probe.body(0).getLinearVelocity().y, EPSILON);
        assertEquals(5 + 0.4f / 60, probe.body(0).getPosition().x, EPSILON);
        assertEquals(100, probe.player(0).getHp(), 0.0f);
    }

    @Test
    public void leftBoundaryTriggersOriginalCorrection() {
        Probe probe = create(2);
        position(probe, 0, 2.9f, 3, -0.4f, -0.2f);
        probe.runtime.tick();
        assertEquals(1.0f, probe.body(0).getLinearVelocity().x, EPSILON);
        assertEquals(-0.2f - 0.098f + 0.1f, probe.body(0).getLinearVelocity().y, EPSILON);
        assertEquals(2.9f + 1.0f / 60, probe.body(0).getPosition().x, EPSILON);
        assertTrue(probe.body(0).getPosition().x < 3); // Không teleport về trong biên.
    }

    @Test
    public void rightBoundaryTriggersOriginalCorrection() {
        Probe probe = create(2);
        position(probe, 0, 8.6f, 3, 0.4f, -0.2f);
        probe.runtime.tick();
        assertEquals(-1.0f, probe.body(0).getLinearVelocity().x, EPSILON);
        assertEquals(-0.2f - 0.098f + 0.1f, probe.body(0).getLinearVelocity().y, EPSILON);
        assertEquals(8.6f - 1.0f / 60, probe.body(0).getPosition().x, EPSILON);
        assertTrue(probe.body(0).getPosition().x > 8.5f);
    }

    @Test
    public void boundaryDoesNotModifyOtherPlayers() {
        Probe affected = create(2);
        Probe reference = create(2);
        position(affected, 0, 2.9f, 3, -0.4f, -0.2f);
        affected.runtime.tick();
        reference.runtime.tick();
        assertEquals(reference.body(1).getPosition().x, affected.body(1).getPosition().x, EPSILON);
        assertEquals(reference.body(1).getPosition().y, affected.body(1).getPosition().y, EPSILON);
        assertEquals(reference.body(1).getLinearVelocity().x, affected.body(1).getLinearVelocity().x, EPSILON);
        assertEquals(reference.body(1).getLinearVelocity().y, affected.body(1).getLinearVelocity().y, EPSILON);
        assertEquals(100, affected.player(1).getHp(), 0.0f);
    }

    @Test
    public void fallingBelowThresholdTriggersRecovery() {
        Probe probe = create(2);
        position(probe, 0, 3.1f, -0.1f, 0, -2);
        probe.runtime.tick();
        assertEquals(2.6f, probe.body(0).getLinearVelocity().y, EPSILON);
        assertTrue(probe.body(0).getPosition().y > -0.1f);
        assertEquals(90, probe.player(0).getHp(), 0.0f);
    }

    @Test
    public void recoveryUsesOriginalMechanics() {
        Probe probe = create(2);
        position(probe, 0, 3.1f, -0.2f, 0.6f, -1);
        probe.runtime.tick();
        assertEquals(0.6f * 0.1f, probe.body(0).getLinearVelocity().x, EPSILON);
        assertEquals(2.6f, probe.body(0).getLinearVelocity().y, EPSILON);
        assertEquals(-0.2f + 2.6f / 60, probe.body(0).getPosition().y, EPSILON);
        probe.runtime.tick();
        // Gốc tiếp tục set velocity khi còn dưới Y=0, KHÔNG cộng impulse 2.6 mỗi frame.
        assertEquals(0.6f * 0.1f * 0.1f, probe.body(0).getLinearVelocity().x, EPSILON);
        assertEquals(2.6f, probe.body(0).getLinearVelocity().y, EPSILON);
        assertEquals(90, probe.player(0).getHp(), 0.0f);
    }

    @Test
    public void fallPenaltyMatchesOriginalValue() {
        Probe probe = create(2);
        float hpBefore = probe.player(0).getHp();
        position(probe, 0, 3.1f, -1, 0, 0);
        probe.runtime.tick();
        assertEquals(hpBefore - 10, probe.player(0).getHp(), 0.0f);
        assertEquals(100, probe.player(1).getHp(), 0.0f);
    }

    @Test
    public void fallPenaltyIsNotAppliedRepeatedly() {
        Probe probe = create(2);
        position(probe, 0, 3.1f, -2, 0, 0);
        for (int tick = 0; tick < 20; tick++) {
            probe.runtime.tick();
            assertTrue(probe.body(0).getPosition().y < 0);
            assertEquals(2.6f, probe.body(0).getLinearVelocity().y, EPSILON);
            assertEquals(90, probe.player(0).getHp(), 0.0f);
        }
    }

    @Test
    public void playerHPDoesNotBecomeNegative() {
        Probe probe = create(3); // HP clamp sau elimination trong trận vẫn còn hai survivor.
        for (int event = 1; event <= 20; event++) {
            // Mỗi tick đi từ dưới lên trên Y=0; lần đặt dưới tiếp theo là fall event mới.
            position(probe, 0, 3.1f, -0.001f, 0, 0);
            probe.runtime.tick();
            assertTrue(probe.body(0).getPosition().y >= 0);
            assertEquals(Math.max(0, 100 - 10 * event), probe.player(0).getHp(), 0.0f);
            assertEquals(event >= 10, probe.player(0).isDead());
        }
    }

    @Test
    public void fallRecoveryDoesNotMarkPlayerGrounded() {
        Probe probe = landed(2);
        position(probe, 0, 3.1f, -0.1f, 0, 0);
        probe.runtime.tick();
        assertFalse(probe.runtime.getJumpState(id(0)).grounded());
        assertEquals(0, probe.runtime.getJumpState(id(0)).jumpsUsed());
        assertFalse(probe.runtime.getJumpState(id(0)).extraJumpReady());
    }

    @Test
    public void fallRecoveryDoesNotGrantInfiniteJumps() {
        Probe probe = landed(2);
        jump(probe, 0, 0);
        jump(probe, 0, 1);
        assertEquals(2, probe.runtime.getJumpState(id(0)).jumpsUsed());
        position(probe, 0, 3.1f, -0.001f, 0, 0);
        probe.runtime.tick();
        for (int sequence = 2; sequence < 8; sequence++) {
            jump(probe, 0, sequence);
            assertEquals(2, probe.runtime.getJumpState(id(0)).jumpsUsed());
            assertFalse(probe.runtime.getJumpState(id(0)).grounded());
        }
    }

    @Test
    public void normalMovementStillWorks() {
        Probe probe = create(2);
        position(probe, 0, 5, 3, 0, 0);
        assertEquals(ACCEPTED, probe.runtime.submitInput(input(0, 0, 1, false)));
        probe.runtime.tick();
        assertEquals(0.055f, probe.body(0).getLinearVelocity().x, EPSILON);
        assertTrue(probe.body(0).getPosition().x > 5);
        assertEquals(100, probe.player(0).getHp(), 0.0f);
    }

    @Test
    public void jumpAndDoubleJumpStillWork() {
        Probe probe = landed(2);
        jump(probe, 0, 0);
        assertEquals(1, probe.runtime.getJumpState(id(0)).jumpsUsed());
        jump(probe, 0, 1);
        assertEquals(2, probe.runtime.getJumpState(id(0)).jumpsUsed());
        assertTrue(probe.body(0).getLinearVelocity().y > 0);
        assertEquals(100, probe.player(0).getHp(), 0.0f);
    }

    @Test
    public void twoPlayersHaveIndependentFallState() {
        Probe probe = create(2);
        position(probe, 0, 3.1f, -1, 0, 0);
        probe.runtime.tick();
        assertEquals(90, probe.player(0).getHp(), 0.0f);
        assertEquals(100, probe.player(1).getHp(), 0.0f);
        position(probe, 1, 8.2f, -0.001f, 0, 0);
        probe.runtime.tick();
        assertEquals(90, probe.player(0).getHp(), 0.0f);
        assertEquals(90, probe.player(1).getHp(), 0.0f);
        position(probe, 1, 8.2f, -0.001f, 0, 0);
        probe.runtime.tick();
        assertEquals(90, probe.player(0).getHp(), 0.0f);
        assertEquals(80, probe.player(1).getHp(), 0.0f);
    }

    @Test
    public void fourPlayersHaveIndependentFallState() {
        Probe probe = create(4);
        position(probe, 0, 3.1f, -1, 0, 0);
        position(probe, 2, 8.2f, -1, 0, 0);
        probe.runtime.tick();
        for (int slot = 0; slot < 4; slot++) {
            assertEquals(slot == 0 || slot == 2 ? 90 : 100, probe.player(slot).getHp(), 0.0f);
        }
        assertEquals(2.6f, probe.body(0).getLinearVelocity().y, EPSILON);
        assertEquals(2.6f, probe.body(2).getLinearVelocity().y, EPSILON);
        assertEquals(22, probe.world().getBodyCount());
    }

    @Test
    public void twoRoomsRemainIsolated() {
        Probe first = create(2);
        Probe second = create(2); // Cùng PlayerId nhưng state/runtime/World riêng.
        position(first, 0, 3.1f, -1, 0, 0);
        first.runtime.tick();
        second.runtime.tick();
        assertEquals(90, first.player(0).getHp(), 0.0f);
        assertEquals(100, second.player(0).getHp(), 0.0f);
        assertNotSame(first.world(), second.world());
        first.runtime.close();
        second.runtime.tick();
        assertEquals(100, second.player(0).getHp(), 0.0f);
        assertFalse(second.physics.get().isDisposed());
    }

    @Test
    public void eachTickStepsWorldExactlyOnce() {
        Probe probe = create(4);
        position(probe, 0, 2.9f, 3, 0, 0);
        position(probe, 1, 8.6f, 3, 0, 0);
        position(probe, 2, 3.1f, -1, 0, 0);
        probe.runtime.tick();
        assertEquals(1, probe.steps.get());
        assertEquals(1, probe.runtime.getMatchState().getServerTick());
        assertEquals(90, probe.player(2).getHp(), 0.0f);
    }

    @Test
    public void closedRuntimeDoesNotProcessRecovery() {
        Probe probe = create(2);
        position(probe, 0, 3.1f, -1, 0, 0);
        probe.runtime.close();
        probe.runtime.close();
        assertThrows(IllegalStateException.class, probe.runtime::tick);
        assertThrows(IllegalStateException.class, () -> probe.runtime.submitInput(input(0, 0, 0, false)));
        assertEquals(100, probe.player(0).getHp(), 0.0f);
        assertEquals(0, probe.steps.get());
        assertTrue(probe.physics.get().isDisposed());
    }

    @Test
    public void repeatedRecoveryDoesNotLeakResources() throws Exception {
        Probe probe = create(4);
        HeadlessPhysicsWorld original = probe.physics.get();
        for (int event = 0; event < 50; event++) {
            position(probe, 0, 3.1f, -0.001f, 0, 0);
            probe.runtime.tick();
            assertSame(original, probe.physics.get());
            assertEquals(22, probe.world().getBodyCount());
            assertEquals(22, probe.world().getFixtureCount());
        }
        probe.runtime.close();
        assertTrue(original.isDisposed());
        var field = MatchRuntime.class.getDeclaredField("boundaryController");
        field.setAccessible(true);
        assertNull(field.get(probe.runtime));
    }

    @Test
    public void existingServerTestsStillPass() {
        Probe probe = create(2);
        var state = probe.runtime.getMatchState();
        probe.runtime.start();
        probe.runtime.tick();
        assertSame(state, probe.runtime.getMatchState());
        assertEquals(20, probe.world().getBodyCount());
        assertEquals(20, probe.world().getFixtureCount());
        assertEquals(100, probe.player(0).getHp(), 0.0f);
        assertEquals(id(0), probe.body(0).getUserData());
    }

    @Test
    public void boundaryThenFallOrderingMatchesOriginal() {
        Probe probe = create(2);
        position(probe, 0, 2.9f, -0.1f, -0.7f, -0.5f);
        probe.runtime.tick();
        assertEquals(0.1f, probe.body(0).getLinearVelocity().x, EPSILON);
        assertEquals(2.6f, probe.body(0).getLinearVelocity().y, EPSILON);
        assertEquals(90, probe.player(0).getHp(), 0.0f);
    }

    @Test
    public void reducedGravityPersistsUntilActualLanding() {
        Probe probe = create(2);
        position(probe, 0, 3.1f, -0.001f, 0, 0);
        probe.runtime.tick();
        assertTrue(probe.body(0).getPosition().y >= 0);
        probe.runtime.tick();
        assertEquals(2.6f - 0.098f * 0.3f, probe.body(0).getLinearVelocity().y, EPSILON);
        position(probe, 0, 4.5f, 0.85f, 0, 0); // Trở lại platform TMX thật để kiểm tra reset gravity.
        until(probe, () -> probe.runtime.getJumpState(id(0)).grounded(), 120);
        position(probe, 0, 8.2f, 3.5f, 0, 0);
        probe.runtime.tick();
        assertEquals(-0.098f, probe.body(0).getLinearVelocity().y, EPSILON);
        assertEquals(0.0f, probe.world().getGravity().y, 0.0f);
    }

    @Test
    public void edgeCorrectionReducesOnlyThatPlayersGravity() {
        Probe probe = create(2);
        position(probe, 0, 2.9f, 3, 0, 0);
        position(probe, 1, 8.2f, 3, 0, 0);
        probe.runtime.tick();
        assertEquals(-0.098f + 0.1f, probe.body(0).getLinearVelocity().y, EPSILON);
        probe.runtime.tick();
        assertEquals(-0.098f + 0.1f - 0.098f * 0.3f + 0.1f,
                probe.body(0).getLinearVelocity().y, EPSILON);
        assertEquals(-0.098f * 2, probe.body(1).getLinearVelocity().y, EPSILON);
    }

    @Test
    public void fallThresholdIsStrictAndCheckedBeforeStep() {
        Probe probe = create(2);
        position(probe, 0, 3.1f, 0, 0, 0);
        probe.runtime.tick();
        assertEquals(100, probe.player(0).getHp(), 0.0f);
        assertTrue(probe.body(0).getPosition().y < 0);
        probe.runtime.tick();
        assertEquals(90, probe.player(0).getHp(), 0.0f);
    }

    @Test
    public void exactHorizontalBoundsAreNotCorrected() {
        Probe probe = create(2);
        position(probe, 0, 3, 3, 0, 0);
        position(probe, 1, 8.5f, 3, 0, 0);
        probe.runtime.tick();
        assertEquals(0.0f, probe.body(0).getLinearVelocity().x, EPSILON);
        assertEquals(0.0f, probe.body(1).getLinearVelocity().x, EPSILON);
        assertEquals(-0.098f, probe.body(0).getLinearVelocity().y, EPSILON);
        assertEquals(-0.098f, probe.body(1).getLinearVelocity().y, EPSILON);
    }

    @Test
    public void originalBoundsConfigurationIsImmutableAndValidated() {
        var bounds = ArenaBoundaryController.ORIGINAL_BOUNDS;
        assertTrue(bounds.getClass().isRecord());
        assertEquals(3.0f, bounds.leftMeters(), 0.0f);
        assertEquals(8.5f, bounds.rightMeters(), 0.0f);
        assertEquals(0.0f, bounds.fallThresholdMeters(), 0.0f);
        assertThrows(IllegalArgumentException.class, () -> new ArenaBoundaryController.Bounds(3, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> new ArenaBoundaryController.Bounds(Float.NaN, 8.5f, 0));
        assertThrows(IllegalArgumentException.class, () -> new ArenaBoundaryController.Bounds(3, 8.5f, Float.POSITIVE_INFINITY));
    }

    @Test
    public void fallPenaltyMutationIsNotPublic() {
        assertThrows(NoSuchMethodException.class, () -> ServerPlayer.class.getMethod("applyFallPenalty"));
        assertThrows(NoSuchMethodException.class, () -> ServerPlayer.class.getMethod("setHP", float.class));
        assertThrows(NoSuchMethodException.class, () -> ServerPlayer.class.getMethod("setHp", float.class));
    }

    @Test(timeout = 15000)
    public void wrongThreadCannotApplyBoundaryOrPenalty() throws Exception {
        Probe probe = create(2);
        probe.runtime.tick();
        position(probe, 0, 3.1f, -1, 0, 0);
        InputQueueTest.runConcurrently(List.<Callable<Boolean>>of(() -> {
            assertThrows(IllegalStateException.class, probe.runtime::tick);
            return true;
        }));
        assertEquals(100, probe.player(0).getHp(), 0.0f);
        assertEquals(1, probe.steps.get());
        assertEquals(-1.0f, probe.body(0).getPosition().y, EPSILON);
        probe.runtime.tick();
        assertEquals(90, probe.player(0).getHp(), 0.0f);
    }

    private Probe landed(int count) {
        Probe probe = create(count);
        until(probe, () -> probe.runtime.getJumpState(id(0)).grounded(), 180);
        return probe;
    }

    private static void until(Probe probe, BooleanSupplier condition, int maxTicks) {
        for (int tick = 0; tick < maxTicks && !condition.getAsBoolean(); tick++) { probe.runtime.tick(); }
        assertTrue("Không đạt điều kiện trong " + maxTicks + " ticks", condition.getAsBoolean());
    }

    private Probe create(int count) {
        String matchId = "boundary-match-" + runtimes.size();
        List<MatchSetup.Participant> participants = new ArrayList<>();
        String[] characters = {"Alex", "Luca", "Leo", "Viktor"};
        for (int slot = 0; slot < count; slot++) {
            participants.add(new MatchSetup.Participant(id(slot), slot, characters[slot]));
        }
        MatchSetup setup = new MatchSetup(matchId, "room-" + matchId, participants);
        AtomicReference<HeadlessPhysicsWorld> physics = new AtomicReference<>();
        AtomicInteger steps = new AtomicInteger();
        MatchRuntime runtime = new MatchRuntime(setup, () -> {
            HeadlessPhysicsWorld created = new PhysicsWorldFactory().create();
            physics.set(created);
            return created;
        }, (world, dt, velocity, position) -> {
            assertEquals(1.0f / 60, dt, 0.0f);
            assertEquals(6, velocity);
            assertEquals(2, position);
            steps.incrementAndGet();
            world.step(dt, velocity, position);
        });
        runtimes.add(runtime);
        runtime.start();
        return new Probe(runtime, physics, steps);
    }

    private static void position(Probe probe, int slot, float x, float y, float vx, float vy) {
        probe.body(slot).setTransform(x, y, 0);
        probe.body(slot).setLinearVelocity(vx, vy);
    }

    private static void jump(Probe probe, int slot, long sequence) {
        assertEquals(ACCEPTED, probe.runtime.submitInput(input(slot, sequence, 0, true)));
        probe.runtime.tick();
    }

    private static PlayerInput input(int slot, long sequence, float axis, boolean jump) {
        return new PlayerInput(id(slot), sequence, Long.MAX_VALUE, axis, jump, false, false, false, false);
    }

    private static PlayerId id(int slot) { return new PlayerId("arbitrary-boundary-player-" + slot); }

    private record Probe(MatchRuntime runtime, AtomicReference<HeadlessPhysicsWorld> physics, AtomicInteger steps) {
        World world() { return physics.get().getWorld(); }
        ServerPlayer player(int slot) { return runtime.getMatchState().getPlayer(id(slot)); }
        Body body(int slot) {
            Array<Body> bodies = new Array<>();
            world().getBodies(bodies);
            for (Body body : bodies) {
                if (id(slot).equals(body.getUserData())) { return body; }
            }
            throw new AssertionError("Thiếu player body, slot=" + slot);
        }
    }
}
