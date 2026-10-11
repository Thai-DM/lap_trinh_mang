package com.smashprofs.server.game;

import com.badlogic.gdx.physics.box2d.*;
import com.badlogic.gdx.utils.Array;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import com.smashprofs.server.game.physics.HeadlessPhysicsWorld;
import com.smashprofs.server.game.physics.PhysicsWorldFactory;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static com.smashprofs.server.game.InputQueue.OfferResult.*;
import static org.junit.Assert.*;

/** Native World/Body/TMX thật với manual tick; không contact player-player hoặc Thread.sleep. */
public class MatchRuntimeStompTest {
    private static final float EPSILON = 0.0001f;
    private final List<MatchRuntime> runtimes = new ArrayList<>();

    @After
    public void closeResources() { runtimes.forEach(MatchRuntime::close); }

    @Test
    public void validStompCanBeActivated() {
        Probe probe = airborne(2);
        stomp(probe, 0, 0);
        assertTrue(probe.runtime.getStompState(id(0)).active());
        assertFalse(probe.runtime.getStompState(id(0)).pendingLanding());
        assertEquals(99.9f, probe.player(0).getHp(), EPSILON);
    }

    @Test
    public void stompActivationMatchesOriginalRules() {
        Probe grounded = landed(2);
        stomp(grounded, 0, 0);
        assertFalse(grounded.runtime.getStompState(id(0)).active());
        assertEquals(100, grounded.player(0).getHp(), EPSILON);
        Probe air = airborne(2);
        stomp(air, 0, 0);
        assertTrue(air.runtime.getStompState(id(0)).active());
    }

    @Test
    public void invalidStompIsRejected() {
        Probe probe = airborne(2);
        assertEquals(UNKNOWN_PLAYER, probe.runtime.submitInput(input(9, 0, 0, false, true, false, false)));
        assertEquals(INVALID_INPUT, probe.runtime.submitInput(input(0, 0, Float.NaN, false, true, false, false)));
        for (int event = 0; event < 10; event++) { probe.player(0).applyFallPenalty(); }
        stomp(probe, 0, 0);
        assertTrue(probe.player(0).isDead());
        assertFalse(probe.runtime.getStompState(id(0)).active());
        assertEquals(-0.098f, probe.body(0).getLinearVelocity().y, EPSILON);
        assertEquals(100, probe.player(1).getHp(), EPSILON);
    }

    @Test
    public void stompAppliesOriginalDownwardPhysics() {
        Probe probe = airborne(2);
        probe.body(0).setLinearVelocity(0.2f, 0.4f);
        float y = probe.body(0).getPosition().y;
        stomp(probe, 0, 0);
        assertEquals(0.4f - 5 - 2 * 0.098f, probe.body(0).getLinearVelocity().y, EPSILON);
        assertEquals(0.2f, probe.body(0).getLinearVelocity().x, EPSILON);
        assertEquals(y + (0.4f - 5 - 2 * 0.098f) / 60, probe.body(0).getPosition().y, EPSILON);
        assertEquals(0, probe.world().getGravity().y, 0.0f);
    }

    @Test
    public void stompDoesNotCreateExtraWorldStep() {
        Probe probe = airborne(4);
        for (int slot = 0; slot < 4; slot++) { send(probe, slot, 0, 0, false, true, false, false); }
        probe.runtime.tick();
        assertEquals(1, probe.steps.get());
        assertEquals(1, probe.runtime.getMatchState().getServerTick());
        assertEquals(22, probe.world().getBodyCount());
    }

    @Test
    public void validStompCanHitOpponent() {
        Scene scene = landingScene(2, 0);
        stomp(scene.probe, 0, 0);
        awaitLanding(scene);
        assertEquals(100, scene.probe.player(scene.target).getHp(), EPSILON);
        scene.probe.runtime.tick(); // Landing được resolve trước step kế tiếp như checkGrounded -> CombatManager.
        assertEquals(90, scene.probe.player(scene.target).getHp(), EPSILON);
        assertEquals(99.9f, scene.probe.player(0).getHp(), EPSILON);
    }

    @Test
    public void invalidContactDoesNotCauseDamage() {
        Scene scene = landingScene(2, 0);
        until(scene.probe, () -> scene.probe.runtime.getJumpState(id(0)).grounded(), 120);
        scene.probe.runtime.tick(); // Landing bình thường, không active stomp.
        assertEquals(100, scene.probe.player(0).getHp(), EPSILON);
        assertEquals(100, scene.probe.player(1).getHp(), EPSILON);
        assertFalse(scene.probe.runtime.getStompState(id(0)).pendingLanding());
    }

    @Test
    public void stompDoesNotDamageSelf() {
        Scene scene = landingScene(2, 0);
        position(scene.probe, 1, 7, 3);
        stomp(scene.probe, 0, 0);
        awaitLanding(scene);
        scene.probe.runtime.tick();
        assertEquals(99.9f, scene.probe.player(0).getHp(), EPSILON); // Chỉ self-cost 0.1, không tự nhận hit 10.
        assertEquals(100, scene.probe.player(1).getHp(), EPSILON);
    }

    @Test
    public void stompDamageMatchesOriginalValue() {
        Scene scene = landingScene(2, 0);
        stomp(scene.probe, 0, 0);
        awaitLanding(scene);
        scene.probe.runtime.tick();
        assertEquals(10, 100 - scene.probe.player(1).getHp(), EPSILON);
        assertEquals(0.1f, 100 - scene.probe.player(0).getHp(), EPSILON);
    }

    @Test
    public void stompDoesNotRepeatDamageIncorrectly() {
        Scene scene = landingScene(2, 0);
        stomp(scene.probe, 0, 0);
        awaitLanding(scene);
        scene.probe.runtime.tick();
        for (int tick = 0; tick < 12; tick++) {
            position(scene.probe, 1, 4.6f, scene.floorY); // Vẫn trong range để lộ lỗi stale flag.
            scene.probe.runtime.tick();
            assertEquals(90, scene.probe.player(1).getHp(), EPSILON);
            assertFalse(scene.probe.runtime.getStompState(id(0)).pendingLanding());
        }
    }

    @Test
    public void duplicateInputDoesNotRepeatStomp() {
        Probe probe = airborne(2);
        stomp(probe, 0, 10);
        float velocity = probe.body(0).getLinearVelocity().y;
        assertEquals(DUPLICATE_OR_STALE, probe.runtime.submitInput(input(0, 10, 0, false, true, false, false)));
        assertEquals(DUPLICATE_OR_STALE, probe.runtime.submitInput(input(0, 9, 0, false, true, false, false)));
        probe.runtime.tick();
        assertEquals(velocity - 0.098f, probe.body(0).getLinearVelocity().y, EPSILON);
        assertEquals(99.9f, probe.player(0).getHp(), EPSILON);
    }

    @Test
    public void stompHasNoAddedCooldownForFreshAirbornePresses() {
        Probe probe = airborne(2);
        stomp(probe, 0, 0);
        stomp(probe, 0, 1);
        assertEquals(99.8f, probe.player(0).getHp(), EPSILON);
        assertEquals(-10.392f, probe.body(0).getLinearVelocity().y, EPSILON);
        assertTrue(probe.runtime.getStompState(id(0)).active());
    }

    @Test
    public void stompEndsAccordingToOriginalRules() {
        Scene scene = landingScene(2, 0);
        stomp(scene.probe, 0, 0);
        awaitLanding(scene);
        assertFalse(scene.probe.runtime.getStompState(id(0)).active());
        assertTrue(scene.probe.runtime.getStompState(id(0)).pendingLanding());
        scene.probe.runtime.tick();
        assertFalse(scene.probe.runtime.getStompState(id(0)).pendingLanding());
    }

    @Test
    public void stompDoesNotBreakGroundDetection() {
        Scene scene = landingScene(2, 0);
        stomp(scene.probe, 0, 0);
        awaitLanding(scene);
        assertTrue(scene.probe.runtime.getJumpState(id(0)).grounded());
        assertEquals(0, scene.probe.runtime.getJumpState(id(0)).jumpsUsed());
        assertFalse(scene.probe.runtime.getJumpState(id(0)).extraJumpReady());
    }

    @Test
    public void stompDoesNotGrantInfiniteJump() {
        Probe probe = landed(2);
        for (int sequence = 0; sequence < 2; sequence++) {
            send(probe, 0, sequence, 0, true, false, false, false);
            probe.runtime.tick();
        }
        assertEquals(2, probe.runtime.getJumpState(id(0)).jumpsUsed());
        position(probe, 0, 5, 3); // Giữ trên không để không nhầm reset từ landing thật là infinite jump.
        stomp(probe, 0, 2);
        send(probe, 0, 3, 0, true, false, false, false);
        probe.runtime.tick();
        assertEquals(2, probe.runtime.getJumpState(id(0)).jumpsUsed());
        assertFalse(probe.runtime.getJumpState(id(0)).grounded());
    }

    @Test
    public void stompDoesNotBreakHorizontalMovement() {
        Probe probe = airborne(2);
        send(probe, 0, 0, 0.5f, false, true, false, false);
        probe.runtime.tick();
        assertEquals(0.0275f, probe.body(0).getLinearVelocity().x, EPSILON);
        assertEquals(-5.196f, probe.body(0).getLinearVelocity().y, EPSILON);
        assertTrue(probe.body(0).getPosition().x > 5);
    }

    @Test
    public void stompBlockingInteractionMatchesOriginal() {
        Scene scene = landingScene(2, 0);
        send(scene.probe, 1, 0, 0, false, false, false, true);
        stomp(scene.probe, 0, 0);
        awaitLanding(scene);
        scene.probe.runtime.tick();
        assertTrue(scene.probe.player(1).isBlocking());
        assertEquals(90, scene.probe.player(1).getHp(), EPSILON);
        assertEquals(2.0f, scene.probe.body(1).getLinearVelocity().x, EPSILON);
    }

    @Test
    public void stompKnockbackDoesNotDependOnSlot() {
        for (int attacker = 0; attacker < 4; attacker++) {
            Scene scene = landingScene(4, attacker);
            stomp(scene.probe, attacker, 0);
            awaitLanding(scene);
            scene.probe.runtime.tick();
            assertEquals(90, scene.probe.player(scene.target).getHp(), EPSILON);
            assertEquals(2.0f, scene.probe.body(scene.target).getLinearVelocity().x, EPSILON);
            assertEquals(2.0f - 2 * 0.098f, scene.probe.body(scene.target).getLinearVelocity().y, EPSILON);
        }
    }

    @Test
    public void twoPlayersStompIndependently() {
        Probe probe = airborne(2);
        stomp(probe, 0, 0);
        assertTrue(probe.runtime.getStompState(id(0)).active());
        assertFalse(probe.runtime.getStompState(id(1)).active());
        assertEquals(100, probe.player(1).getHp(), EPSILON);
        stomp(probe, 1, 0);
        assertTrue(probe.runtime.getStompState(id(1)).active());
        assertEquals(99.9f, probe.player(1).getHp(), EPSILON);
    }

    @Test
    public void fourPlayersStompIndependently() {
        Probe probe = airborne(4);
        for (int slot = 0; slot < 4; slot++) { send(probe, slot, 0, 0, false, true, false, false); }
        probe.runtime.tick();
        for (int slot = 0; slot < 4; slot++) {
            assertTrue(probe.runtime.getStompState(id(slot)).active());
            assertEquals(99.9f, probe.player(slot).getHp(), EPSILON);
            assertEquals(-5.196f, probe.body(slot).getLinearVelocity().y, EPSILON);
        }
    }

    @Test
    public void multiplayerTargetSelectionIsDeterministic() {
        for (int count : new int[]{3, 4}) {
            Scene scene = landingScene(count, 0);
            stomp(scene.probe, 0, 0);
            awaitLanding(scene);
            position(scene.probe, 1, 4.6f, scene.floorY);
            position(scene.probe, 2, 4.4f, scene.floorY);
            if (count == 4) { position(scene.probe, 3, 4.55f, scene.floorY); }
            scene.probe.runtime.tick();
            int target = count == 3 ? 1 : 3; // Tie -> slot nhỏ hơn; bốn người -> target gần nhất.
            for (int slot = 1; slot < count; slot++) {
                assertEquals(slot == target ? 90 : 100, scene.probe.player(slot).getHp(), EPSILON);
            }
        }
    }

    @Test
    public void simultaneousStompsAreDeterministic() {
        Scene scene = landingScene(2, 0);
        Probe probe = scene.probe;
        position(probe, 0, 4.5f, scene.floorY + 0.3f);
        position(probe, 1, 4.6f, scene.floorY + 0.3f);
        probe.runtime.tick();
        for (int event = 0; event < 9; event++) {
            probe.player(0).applyFallPenalty();
            probe.player(1).applyFallPenalty();
        }
        send(probe, 1, 0, 0, false, true, false, false); // Đảo thứ tự packet/roster.
        send(probe, 0, 0, 0, false, true, false, false);
        probe.runtime.tick();
        until(probe, () -> probe.runtime.getStompState(id(0)).pendingLanding()
                && probe.runtime.getStompState(id(1)).pendingLanding(), 120);
        probe.runtime.tick();
        assertEquals(9.9f, probe.player(0).getHp(), EPSILON);
        assertFalse(probe.player(0).isDead());
        assertEquals(0, probe.player(1).getHp(), EPSILON);
        assertTrue(probe.player(1).isDead());
        assertEquals(0, probe.body(0).getLinearVelocity().x, EPSILON); // Slot 1 chết trước lượt không đánh trả.
        assertEquals(2.0f, probe.body(1).getLinearVelocity().x, EPSILON);
    }

    @Test
    public void twoRoomsHaveIndependentStompState() {
        Probe first = airborne(2);
        Probe second = airborne(2);
        stomp(first, 0, 0);
        assertTrue(first.runtime.getStompState(id(0)).active());
        assertFalse(second.runtime.getStompState(id(0)).active());
        stomp(second, 1, 0);
        assertTrue(second.runtime.getStompState(id(1)).active());
        assertFalse(first.runtime.getStompState(id(1)).active());
        assertNotSame(first.world(), second.world());
    }

    @Test
    public void stompCannotDamageOtherRoom() {
        Scene first = landingScene(2, 0);
        Scene second = landingScene(2, 0);
        stomp(first.probe, 0, 0);
        awaitLanding(first);
        first.probe.runtime.tick();
        assertEquals(90, first.probe.player(1).getHp(), EPSILON);
        assertEquals(100, second.probe.player(1).getHp(), EPSILON);
        assertEquals(100, second.probe.player(0).getHp(), EPSILON);
        assertFalse(second.probe.runtime.getStompState(id(0)).active());
        assertEquals(0, second.probe.body(1).getLinearVelocity().x, EPSILON);
    }

    @Test
    public void closedRuntimeCannotProcessStomp() throws Exception {
        Probe probe = airborne(2);
        stomp(probe, 0, 0);
        int steps = probe.steps.get();
        probe.runtime.close();
        probe.runtime.close();
        assertThrows(IllegalStateException.class, probe.runtime::tick);
        assertThrows(IllegalStateException.class, () -> probe.runtime.submitInput(input(0, 1, 0, false, true, false, false)));
        assertThrows(IllegalStateException.class, () -> probe.runtime.getStompState(id(0)));
        assertEquals(steps, probe.steps.get());
        assertEquals(99.9f, probe.player(0).getHp(), EPSILON);
        assertEquals(100, probe.player(1).getHp(), EPSILON);
        assertTrue(probe.physics.get().isDisposed());
        var field = MatchRuntime.class.getDeclaredField("stompController");
        field.setAccessible(true);
        assertNull(field.get(probe.runtime));
    }

    @Test
    public void previousServerTestsStillPass() {
        Probe probe = airborne(2);
        var state = probe.runtime.getMatchState();
        probe.runtime.start();
        probe.runtime.tick();
        assertSame(state, probe.runtime.getMatchState());
        assertEquals(100, probe.player(0).getHp(), EPSILON);
        assertEquals(20, probe.world().getBodyCount());
        assertEquals(20, probe.world().getFixtureCount());
        assertFalse(probe.runtime.getStompState(id(0)).active());
    }

    @Test
    public void noNewInputDoesNotRepeatStompImpulseOrHpCost() {
        Probe probe = airborne(2);
        stomp(probe, 0, 0);
        for (int tick = 0; tick < 2; tick++) {
            float before = probe.body(0).getLinearVelocity().y;
            assertTrue(probe.runtime.tick().isEmpty());
            assertEquals(before - 0.098f, probe.body(0).getLinearVelocity().y, EPSILON);
            assertEquals(99.9f, probe.player(0).getHp(), EPSILON);
        }
    }

    @Test
    public void multipleEdgesInOneBatchApplyStompOnce() {
        Probe probe = airborne(2);
        send(probe, 0, 0, 0, false, true, false, false);
        send(probe, 0, 1, 0, false, true, false, false);
        send(probe, 0, 2, 0, false, false, false, false);
        var batch = probe.runtime.tick();
        assertEquals(1, batch.size());
        assertTrue(batch.get(0).stompPressed());
        assertEquals(2, batch.get(0).sequence());
        assertEquals(99.9f, probe.player(0).getHp(), EPSILON);
        assertEquals(-5.196f, probe.body(0).getLinearVelocity().y, EPSILON);
    }

    @Test
    public void groundedJumpAndStompSameTickDoesNotCreateNewCombo() {
        Probe probe = landed(2);
        send(probe, 0, 0, 0, true, true, false, false);
        probe.runtime.tick();
        assertEquals(1, probe.runtime.getJumpState(id(0)).jumpsUsed());
        assertTrue(probe.body(0).getLinearVelocity().y > 0);
        assertEquals(100, probe.player(0).getHp(), EPSILON);
        assertFalse(probe.runtime.getStompState(id(0)).active());
    }

    @Test
    public void airborneDoubleJumpAndStompKeepOriginalImpulseOrder() {
        Probe probe = landed(2);
        send(probe, 0, 0, 0, true, false, false, false);
        probe.runtime.tick();
        position(probe, 0, 5, 3);
        send(probe, 0, 1, 0, true, true, false, false);
        probe.runtime.tick();
        assertEquals(2, probe.runtime.getJumpState(id(0)).jumpsUsed());
        assertEquals(2.2f - 5 - 2 * 0.098f, probe.body(0).getLinearVelocity().y, EPSILON);
        assertEquals(99.9f, probe.player(0).getHp(), EPSILON);
    }

    @Test
    public void fallRecoveryCancelsActiveStompWithoutRepeatedFallPenalty() {
        Probe probe = airborne(2);
        stomp(probe, 0, 0);
        position(probe, 0, 3.1f, -0.1f);
        probe.runtime.tick();
        assertFalse(probe.runtime.getStompState(id(0)).active());
        assertFalse(probe.runtime.getStompState(id(0)).pendingLanding());
        assertEquals(89.9f, probe.player(0).getHp(), EPSILON);
        assertEquals(2.6f, probe.body(0).getLinearVelocity().y, EPSILON);
        stomp(probe, 0, 1); // Source vẫn nhận press khi dưới Y=0 rồi recovery ghi đè physics/hủy stomp.
        assertFalse(probe.runtime.getStompState(id(0)).active());
        assertEquals(89.8f, probe.player(0).getHp(), EPSILON); // Chỉ self-cost mới; không thêm fall event.
    }

    @Test
    public void fallRecoveryCancelsPendingImpact() {
        Scene scene = landingScene(2, 0);
        stomp(scene.probe, 0, 0);
        awaitLanding(scene);
        position(scene.probe, 0, 3.1f, -0.1f);
        scene.probe.runtime.tick();
        assertFalse(scene.probe.runtime.getStompState(id(0)).pendingLanding());
        assertEquals(100, scene.probe.player(1).getHp(), EPSILON);
    }

    @Test
    public void selfCostCanKillWithoutProducingStompHit() {
        Probe probe = airborne(2);
        probe.player(0).applyDamage(99.95f);
        stomp(probe, 0, 0);
        assertEquals(0, probe.player(0).getHp(), EPSILON);
        assertTrue(probe.player(0).isDead());
        assertFalse(probe.runtime.getStompState(id(0)).active());
        assertFalse(probe.runtime.getStompState(id(0)).pendingLanding());
        assertEquals(100, probe.player(1).getHp(), EPSILON);
    }

    @Test
    public void knockbackDoesNotInventAStompCancellationRule() {
        Probe probe = airborne(2);
        stomp(probe, 0, 0);
        position(probe, 1, probe.body(0).getPosition().x + 0.1f, probe.body(0).getPosition().y);
        send(probe, 1, 0, 0, false, false, true, false);
        probe.runtime.tick();
        assertEquals(89.9f, probe.player(0).getHp(), EPSILON);
        assertTrue(probe.runtime.getStompState(id(0)).active());
        assertEquals(-1.5f, probe.body(0).getLinearVelocity().x, EPSILON);
    }

    @Test
    public void sideContactDuringStompDoesNotEmitLandingOrDamage() {
        Probe probe = airborne(2);
        tile(probe, 8.35f, 2, 0.05f, 0.5f);
        position(probe, 0, 8.245f, 2);
        position(probe, 1, 8.18f, 2);
        stomp(probe, 0, 0);
        boolean touching = false;
        for (var contact : probe.world().getContactList()) {
            if (contact.isTouching() && (contact.getFixtureA().getBody() == probe.body(0)
                    || contact.getFixtureB().getBody() == probe.body(0))) { touching = true; }
        }
        assertTrue(touching);
        assertFalse(probe.runtime.getJumpState(id(0)).grounded());
        assertTrue(probe.runtime.getStompState(id(0)).active());
        assertFalse(probe.runtime.getStompState(id(0)).pendingLanding());
        assertEquals(100, probe.player(1).getHp(), EPSILON);
    }

    @Test
    public void verticalRangeMissIsConsumedAndCannotHitLater() {
        Scene scene = landingScene(2, 0);
        stomp(scene.probe, 0, 0);
        awaitLanding(scene);
        position(scene.probe, 1, 4.5f, scene.floorY + 0.21f);
        scene.probe.runtime.tick();
        assertEquals(100, scene.probe.player(1).getHp(), EPSILON);
        position(scene.probe, 1, 4.6f, scene.floorY);
        scene.probe.runtime.tick();
        assertEquals(100, scene.probe.player(1).getHp(), EPSILON);
        assertFalse(scene.probe.runtime.getStompState(id(0)).pendingLanding());
    }

    @Test
    public void repeatedFreshPressesInOneFlightStillProduceOnlyOneLandingHit() {
        Scene scene = landingScene(2, 0);
        stomp(scene.probe, 0, 0);
        stomp(scene.probe, 0, 1);
        awaitLanding(scene);
        scene.probe.runtime.tick();
        assertEquals(99.8f, scene.probe.player(0).getHp(), EPSILON);
        assertEquals(90, scene.probe.player(1).getHp(), EPSILON);
    }

    @Test
    public void laterFlightCanProduceNewIndependentStompHit() {
        Scene scene = landingScene(2, 0);
        stomp(scene.probe, 0, 0);
        awaitLanding(scene);
        scene.probe.runtime.tick();
        position(scene.probe, 0, 4.5f, scene.floorY + 0.3f);
        position(scene.probe, 1, 4.6f, scene.floorY);
        scene.probe.runtime.tick();
        stomp(scene.probe, 0, 1);
        awaitLanding(scene);
        scene.probe.runtime.tick();
        assertEquals(80, scene.probe.player(1).getHp(), EPSILON);
        assertEquals(99.8f, scene.probe.player(0).getHp(), EPSILON);
    }

    @Test
    public void playerCollisionFilterIsUnchanged() {
        Probe probe = airborne(2);
        position(probe, 1, 5.01f, 3); // Overlap có chủ ý để kiểm tra group -1.
        stomp(probe, 0, 0);
        for (int slot = 0; slot < 2; slot++) {
            var filter = probe.body(slot).getFixtureList().first().getFilterData();
            assertEquals(-1, filter.groupIndex);
            assertEquals(1, filter.categoryBits);
            assertEquals(4 | 3 | 2, filter.maskBits);
        }
        for (var contact : probe.world().getContactList()) {
            assertFalse(contact.isTouching() && contact.getFixtureA().getBody().getUserData() instanceof PlayerId
                    && contact.getFixtureB().getBody().getUserData() instanceof PlayerId);
        }
    }

    @Test(timeout = 15000)
    public void onlySimulationOwnerCanActivateStomp() throws Exception {
        Probe probe = airborne(2);
        probe.runtime.tick();
        send(probe, 0, 0, 0, false, true, false, false);
        InputQueueTest.runConcurrently(List.<Callable<Boolean>>of(() -> {
            assertThrows(IllegalStateException.class, probe.runtime::tick);
            return true;
        }));
        assertEquals(100, probe.player(0).getHp(), EPSILON);
        assertFalse(probe.runtime.getStompState(id(0)).active());
        assertEquals(1, probe.steps.get());
        probe.runtime.tick();
        assertEquals(99.9f, probe.player(0).getHp(), EPSILON);
    }

    private static void tile(Probe probe, float x, float y, float halfWidth, float halfHeight) {
        BodyDef definition = new BodyDef();
        definition.type = BodyDef.BodyType.StaticBody;
        definition.position.set(x, y);
        Body body = probe.world().createBody(definition);
        body.setUserData("Tile");
        PolygonShape shape = new PolygonShape();
        try {
            shape.setAsBox(halfWidth, halfHeight);
            FixtureDef fixture = new FixtureDef();
            fixture.shape = shape;
            fixture.filter.categoryBits = 2;
            fixture.filter.maskBits = 1 | 3;
            body.createFixture(fixture);
        } finally { shape.dispose(); }
    }

    private Probe create(int count) {
        String[] characters = {"Alex", "Luca", "Maurice", "Viktor"};
        List<MatchSetup.Participant> roster = new ArrayList<>();
        for (int slot = 0; slot < count; slot++) {
            roster.add(new MatchSetup.Participant(id(slot), slot, characters[slot]));
        }
        Collections.reverse(roster);
        String matchId = "stomp-" + runtimes.size();
        MatchSetup setup = new MatchSetup(matchId, "room-" + matchId, roster);
        AtomicReference<HeadlessPhysicsWorld> physics = new AtomicReference<>();
        AtomicInteger steps = new AtomicInteger();
        MatchRuntime runtime = new MatchRuntime(setup, () -> {
            var created = new PhysicsWorldFactory().create();
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

    private Probe airborne(int count) {
        Probe probe = create(count);
        for (int slot = 0; slot < count; slot++) { position(probe, slot, 5 + slot * 0.5f, 3); }
        return probe;
    }

    private Probe landed(int count) {
        Probe probe = create(count);
        until(probe, () -> {
            for (int slot = 0; slot < count; slot++) {
                if (!probe.runtime.getJumpState(id(slot)).grounded()) { return false; }
            }
            return true;
        }, 180);
        return probe;
    }

    private Scene landingScene(int count, int attacker) {
        Probe probe = landed(count);
        float floorY = probe.body(0).getPosition().y;
        int target = attacker == 0 ? 1 : 0;
        for (int slot = 0; slot < count; slot++) { position(probe, slot, 7 + slot * 0.1f, 3); }
        position(probe, attacker, 4.5f, floorY + 0.3f);
        position(probe, target, 4.6f, floorY);
        probe.runtime.tick(); // Cập nhật grounded từ contact thật sau khi bố trí, không giả lập grounded.
        assertFalse(probe.runtime.getJumpState(id(attacker)).grounded());
        return new Scene(probe, attacker, target, floorY);
    }

    private static void awaitLanding(Scene scene) {
        until(scene.probe, () -> scene.probe.runtime.getStompState(id(scene.attacker)).pendingLanding(), 120);
    }

    private static void until(Probe probe, BooleanSupplier condition, int maxTicks) {
        for (int tick = 0; tick < maxTicks && !condition.getAsBoolean(); tick++) { probe.runtime.tick(); }
        assertTrue("Không đạt điều kiện trong " + maxTicks + " manual ticks", condition.getAsBoolean());
    }

    private static void position(Probe probe, int slot, float x, float y) {
        probe.body(slot).setTransform(x, y, 0); // Chỉ bố trí native test; production stomp không teleport.
        probe.body(slot).setLinearVelocity(0, 0);
    }

    private static void stomp(Probe probe, int slot, long sequence) {
        send(probe, slot, sequence, 0, false, true, false, false);
        probe.runtime.tick();
    }

    private static void send(Probe probe, int slot, long sequence, float axis, boolean jump,
                             boolean stomp, boolean melee, boolean block) {
        assertEquals(ACCEPTED, probe.runtime.submitInput(input(slot, sequence, axis, jump, stomp, melee, block)));
    }

    private static PlayerInput input(int slot, long sequence, float axis, boolean jump,
                                     boolean stomp, boolean melee, boolean block) {
        return new PlayerInput(id(slot), sequence, Long.MAX_VALUE, axis, jump, stomp, melee, block, false);
    }

    private static PlayerId id(int slot) { return new PlayerId("arbitrary-stomp-player-" + slot); }
    private record Scene(Probe probe, int attacker, int target, float floorY) { }
    private record Probe(MatchRuntime runtime, AtomicReference<HeadlessPhysicsWorld> physics, AtomicInteger steps) {
        World world() { return physics.get().getWorld(); }
        ServerPlayer player(int slot) { return runtime.getMatchState().getPlayer(id(slot)); }
        Body body(int slot) {
            Array<Body> bodies = new Array<>();
            world().getBodies(bodies);
            for (Body body : bodies) {
                if (id(slot).equals(body.getUserData())) { return body; }
            }
            throw new AssertionError("Thiếu player body: " + slot);
        }
    }
}
