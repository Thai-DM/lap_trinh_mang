package com.smashprofs.server.game;

import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.World;
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
import java.util.function.Consumer;

import static com.smashprofs.server.game.InputQueue.OfferResult.*;
import static org.junit.Assert.*;

/** Box2D/MatchRuntime thật, manual tick; không socket, Timer hoặc Thread.sleep. */
public class MatchRuntimeBlockingKnockbackTest {
    private static final float EPSILON = 0.00001f;
    private static final int TIMEOUT = MatchRuntime.DEFAULT_MOVEMENT_TIMEOUT_TICKS;
    private final List<MatchRuntime> runtimes = new ArrayList<>();

    @After
    public void closeResources() { runtimes.forEach(MatchRuntime::close); }

    @Test
    public void playerCanStartBlocking() {
        Probe probe = closePair();
        assertFalse(probe.player(0).isBlocking());
        send(probe, 0, 0, false, true);
        assertFalse(probe.player(0).isBlocking()); // Submit không mutate state.
        probe.runtime.tick();
        assertTrue(probe.player(0).isBlocking());
    }

    @Test
    public void playerCanStopBlocking() {
        Probe probe = closePair();
        send(probe, 0, 0, false, true);
        probe.runtime.tick();
        send(probe, 0, 1, false, false);
        probe.runtime.tick();
        assertFalse(probe.player(0).isBlocking());
    }

    @Test
    public void blockingIsIndependentForEachPlayer() {
        Probe probe = create(4);
        for (int slot = 0; slot < 4; slot++) { send(probe, slot, 0, false, slot % 2 == 0); }
        probe.runtime.tick();
        for (int slot = 0; slot < 4; slot++) { assertEquals(slot % 2 == 0, probe.player(slot).isBlocking()); }
    }

    @Test
    public void blockingHeldStatePersistsAcrossTicks() {
        Probe probe = closePair();
        send(probe, 0, 0, false, true);
        probe.runtime.tick();
        for (int tick = 1; tick < TIMEOUT; tick++) {
            assertTrue(probe.runtime.tick().isEmpty());
            assertTrue(probe.player(0).isBlocking());
        }
    }

    @Test
    public void blockingExpiresAfterInputTimeout() {
        Probe probe = create(2, 3);
        send(probe, 0, 0, false, true);
        for (int tick = 0; tick < 3; tick++) {
            probe.runtime.tick();
            assertTrue(probe.player(0).isBlocking());
        }
        probe.runtime.tick(); // Input ở k=0 hết hạn đúng k=3, cùng quy tắc held movement.
        assertFalse(probe.player(0).isBlocking());
    }

    @Test
    public void nonBlockingTargetReceivesNormalDamage() {
        Probe probe = closePair();
        attack(probe, 0, 0);
        assertEquals(90, probe.player(1).getHp(), EPSILON);
        assertFalse(probe.player(1).isBlocking());
        assertFalse(probe.player(1).isDead());
    }

    @Test
    public void blockingTargetUsesOriginalDamageRule() {
        Probe probe = closePair();
        send(probe, 0, 0, true, false);
        send(probe, 1, 0, false, true);
        probe.runtime.tick();
        assertTrue(probe.player(1).isBlocking());
        assertEquals(100, probe.player(1).getHp(), EPSILON); // Gốc chặn toàn bộ, không giảm 50%.
    }

    @Test
    public void blockingHasNoDirectionRestrictionInOriginal() {
        for (float targetX : new float[]{4.9f, 5.1f}) {
            Probe probe = closePair();
            position(probe, 1, targetX, 3);
            probe.body(1).setLinearVelocity(0.1f, 0); // Di chuyển sang phải, block cả hai phía.
            send(probe, 1, 0, false, true);
            send(probe, 0, 0, true, false);
            probe.runtime.tick();
            assertEquals(100, probe.player(1).getHp(), EPSILON);
            assertEquals(0.1f, probe.body(1).getLinearVelocity().x, EPSILON);
        }
    }

    @Test
    public void meleeHitAppliesKnockback() {
        Probe probe = closePair();
        float startX = probe.body(1).getPosition().x;
        attack(probe, 0, 0);
        assertTrue(probe.body(1).getPosition().x > startX);
        assertEquals(1.5f, probe.body(1).getLinearVelocity().x, EPSILON);
        assertEquals(0.2f - 2 * 0.098f, probe.body(1).getLinearVelocity().y, EPSILON);
    }

    @Test
    public void knockbackDirectionMatchesOriginal() {
        for (float targetX : new float[]{4.9f, 5.1f, 5.0f}) {
            Probe probe = closePair();
            position(probe, 1, targetX, targetX == 5.0f ? 3.1f : 3);
            probe.body(0).setLinearVelocity(targetX < 5 ? 0.1f : -0.1f, 0);
            attack(probe, 0, 0);
            assertEquals(targetX > 5 ? 1.5f : -1.5f, probe.body(1).getLinearVelocity().x, EPSILON);
            // Dùng vị trí, không dùng hướng di chuyển; cùng X giữ nhánh else đẩy trái của source.
        }
    }

    @Test
    public void knockbackMagnitudeMatchesOriginal() {
        Probe probe = closePair();
        Body target = probe.body(1);
        target.setLinearVelocity(0.2f, -0.3f);
        float mass = target.getMass();
        float originalX = target.getPosition().x;
        float originalY = target.getPosition().y;
        probe.beforeStep.set(world -> {
            // Đo ngay trước step: J/m được cộng vào vận tốc, không set velocity hoặc teleport.
            assertEquals(0.2f + 1.5f / mass, target.getLinearVelocity().x, EPSILON);
            assertEquals(-0.3f + (0.2f - 2 * 0.098f) / mass, target.getLinearVelocity().y, EPSILON);
            assertEquals(originalX, target.getPosition().x, 0.0f);
            assertEquals(originalY, target.getPosition().y, 0.0f);
        });
        attack(probe, 0, 0);
        assertEquals(90, probe.player(1).getHp(), EPSILON);
    }

    @Test
    public void slotOneThroughThreeUseSameStandardMeleeImpulseAsSlotZero() {
        for (int attacker = 1; attacker <= 3; attacker++) {
            Probe probe = create(4);
            for (int slot = 0; slot < 4; slot++) { position(probe, slot, 7 + slot * 0.25f, 3); }
            position(probe, attacker, 5, 3);
            position(probe, 0, 5.1f, 3);
            attack(probe, attacker, 0);
            assertEquals(90, probe.player(0).getHp(), EPSILON);
            assertEquals(1.5f, probe.body(0).getLinearVelocity().x, EPSILON);
            assertEquals(0.2f - 2 * 0.098f, probe.body(0).getLinearVelocity().y, EPSILON);
        }
    }

    @Test
    public void missedAttackDoesNotKnockback() {
        Probe probe = closePair();
        position(probe, 1, 6, 3);
        attack(probe, 0, 0);
        assertEquals(100, probe.player(1).getHp(), EPSILON);
        assertNoCombatImpulse(probe, 1);
    }

    @Test
    public void rejectedAttackDoesNotKnockback() {
        Probe probe = closePair();
        assertEquals(UNKNOWN_PLAYER, probe.runtime.submitInput(input(9, 0, 0, false, true, false)));
        assertEquals(INVALID_INPUT, probe.runtime.submitInput(input(0, 0, Float.NaN, false, true, false)));
        assertTrue(probe.runtime.tick().isEmpty());
        assertEquals(100, probe.player(1).getHp(), EPSILON);
        assertNoCombatImpulse(probe, 1);
    }

    @Test
    public void duplicateAttackDoesNotApplyKnockbackTwice() {
        Probe probe = closePair();
        attack(probe, 0, 10);
        for (int tick = 1; tick < 9; tick++) { probe.runtime.tick(); }
        pair(probe); // Cooldown đã hết và range hợp lệ, nên duplicate không thể bị che bởi miss/cooldown.
        assertEquals(DUPLICATE_OR_STALE, probe.runtime.submitInput(input(0, 10, 0, false, true, false)));
        assertEquals(DUPLICATE_OR_STALE, probe.runtime.submitInput(input(0, 9, 0, false, true, false)));
        probe.runtime.tick();
        assertEquals(90, probe.player(1).getHp(), EPSILON);
        assertNoCombatImpulse(probe, 1);
    }

    @Test
    public void cooldownPreventsRepeatedKnockback() {
        Probe probe = closePair();
        attack(probe, 0, 0);
        pair(probe);
        attack(probe, 0, 1);
        assertEquals(90, probe.player(1).getHp(), EPSILON);
        assertNoCombatImpulse(probe, 1);
    }

    @Test
    public void blockedHitUsesOriginalKnockbackRule() {
        Probe probe = closePair();
        send(probe, 1, 0, false, true);
        attack(probe, 0, 0);
        assertNoCombatImpulse(probe, 1); // Chặn toàn bộ knockback, không chỉ damage.
        assertEquals(100, probe.player(1).getHp(), EPSILON);
    }

    @Test
    public void knockbackAffectsOnlyTargetBody() {
        Probe probe = create(4);
        for (int slot = 0; slot < 4; slot++) { position(probe, slot, 5 + slot * 0.05f, 3); }
        attack(probe, 0, 0);
        assertEquals(90, probe.player(1).getHp(), EPSILON);
        for (int slot : new int[]{0, 2, 3}) {
            assertEquals(100, probe.player(slot).getHp(), EPSILON);
            assertNoCombatImpulse(probe, slot);
        }
    }

    @Test
    public void attackerBodyIsNotModifiedAccidentally() {
        Probe probe = closePair();
        attack(probe, 0, 0);
        assertEquals(100, probe.player(0).getHp(), EPSILON);
        assertNoCombatImpulse(probe, 0);
    }

    @Test
    public void deadTargetDoesNotReceiveInvalidHits() {
        Probe probe = closePair();
        for (int event = 0; event < 10; event++) { probe.player(1).applyFallPenalty(); }
        attack(probe, 0, 0);
        assertEquals(0, probe.player(1).getHp(), EPSILON);
        assertTrue(probe.player(1).isDead());
        assertNoCombatImpulse(probe, 1);
    }

    @Test
    public void twoPlayersCombatRemainsConsistent() {
        Probe probe = closePair();
        send(probe, 1, 0, true, false);
        send(probe, 0, 0, true, false);
        probe.runtime.tick();
        assertEquals(90, probe.player(0).getHp(), EPSILON);
        assertEquals(90, probe.player(1).getHp(), EPSILON);
        assertEquals(-1.5f, probe.body(0).getLinearVelocity().x, EPSILON);
        assertEquals(0.2f - 2 * 0.098f, probe.body(0).getLinearVelocity().y, EPSILON);
        assertEquals(1.5f, probe.body(1).getLinearVelocity().x, EPSILON);
        assertEquals(0.2f - 2 * 0.098f, probe.body(1).getLinearVelocity().y, EPSILON);
    }

    @Test
    public void fourPlayersHaveIndependentCombatState() {
        Probe probe = create(4);
        pair(probe);
        position(probe, 2, 7, 3);
        position(probe, 3, 7.1f, 3);
        send(probe, 3, 0, false, false);
        send(probe, 2, 0, true, true); // Vừa block vừa đánh được phép theo source gốc.
        send(probe, 1, 0, false, true);
        send(probe, 0, 0, true, false);
        probe.runtime.tick();
        for (int slot = 0; slot < 4; slot++) {
            assertEquals(slot == 3 ? 90 : 100, probe.player(slot).getHp(), EPSILON);
            assertEquals(slot == 1 || slot == 2, probe.player(slot).isBlocking());
        }
        for (int slot : new int[]{0, 1, 2}) { assertNoCombatImpulse(probe, slot); }
        assertEquals(1.5f, probe.body(3).getLinearVelocity().x, EPSILON);
        assertEquals(0.2f - 2 * 0.098f, probe.body(3).getLinearVelocity().y, EPSILON);
    }

    @Test
    public void simultaneousAttacksAreDeterministic() {
        Probe probe = closePair();
        for (int event = 0; event < 9; event++) {
            probe.player(0).applyFallPenalty();
            probe.player(1).applyFallPenalty();
        }
        send(probe, 1, 0, true, false); // Roster và packet cố ý đảo thứ tự slot.
        send(probe, 0, 0, true, false);
        probe.runtime.tick();
        assertEquals(10, probe.player(0).getHp(), EPSILON);
        assertFalse(probe.player(0).isDead());
        assertEquals(0, probe.player(1).getHp(), EPSILON);
        assertTrue(probe.player(1).isDead());
        assertNoCombatImpulse(probe, 0); // Slot 1 chết trước lượt nên không damage/knockback ngược.
        assertEquals(1.5f, probe.body(1).getLinearVelocity().x, EPSILON);
    }

    @Test
    public void blockingDoesNotBreakMovement() {
        Probe blocking = closePair();
        Probe reference = closePair();
        assertEquals(ACCEPTED, blocking.runtime.submitInput(input(0, 0, 0.5f, false, false, true)));
        assertEquals(ACCEPTED, reference.runtime.submitInput(input(0, 0, 0.5f, false, false, false)));
        for (int tick = 0; tick < 3; tick++) {
            blocking.runtime.tick();
            reference.runtime.tick();
            assertTrue(blocking.player(0).isBlocking());
            assertEquals(reference.body(0).getLinearVelocity().x, blocking.body(0).getLinearVelocity().x, EPSILON);
            assertEquals(reference.body(0).getPosition().x, blocking.body(0).getPosition().x, EPSILON);
        }
        assertEquals(3 * 0.0275f, blocking.body(0).getLinearVelocity().x, EPSILON);
    }

    @Test
    public void blockingDoesNotBreakJump() {
        Probe probe = create(2);
        for (int tick = 0; tick < 180 && !probe.runtime.getJumpState(id(0)).grounded(); tick++) {
            probe.runtime.tick();
        }
        assertTrue(probe.runtime.getJumpState(id(0)).grounded());
        for (int sequence = 0; sequence < 2; sequence++) {
            assertEquals(ACCEPTED, probe.runtime.submitInput(input(0, sequence, 0, true, false, true)));
            probe.runtime.tick();
            assertTrue(probe.player(0).isBlocking());
            assertEquals(sequence + 1, probe.runtime.getJumpState(id(0)).jumpsUsed());
            assertTrue(probe.body(0).getLinearVelocity().y > 0);
        }
    }

    @Test
    public void knockbackDoesNotStepWorldTwice() {
        Probe probe = create(4);
        for (int slot = 0; slot < 4; slot++) {
            position(probe, slot, 5 + slot * 0.04f, 3);
            send(probe, slot, 0, true, false);
        }
        probe.runtime.tick();
        assertEquals(1, probe.steps.get());
        assertEquals(1, probe.runtime.getMatchState().getServerTick());
        assertEquals(22, probe.world().getBodyCount());
        assertEquals(22, probe.world().getFixtureCount());
    }

    @Test
    public void twoRoomsHaveIndependentBlockingState() {
        Probe first = closePair();
        Probe second = closePair(); // Cùng PlayerId nhưng state riêng.
        send(first, 0, 0, false, true);
        first.runtime.tick();
        second.runtime.tick();
        assertTrue(first.player(0).isBlocking());
        assertFalse(second.player(0).isBlocking());
        send(second, 1, 0, false, true);
        second.runtime.tick();
        assertTrue(second.player(1).isBlocking());
        assertFalse(first.player(1).isBlocking());
        assertNotSame(first.player(0), second.player(0));
    }

    @Test
    public void twoRoomsHaveIndependentKnockback() {
        Probe first = closePair();
        Probe second = closePair();
        attack(first, 0, 0);
        second.runtime.tick();
        assertEquals(90, first.player(1).getHp(), EPSILON);
        assertEquals(1.5f, first.body(1).getLinearVelocity().x, EPSILON);
        assertEquals(100, second.player(1).getHp(), EPSILON);
        assertNoCombatImpulse(second, 1);
        assertNotSame(first.world(), second.world());
        first.runtime.close();
        pair(second);
        attack(second, 0, 0); // Cooldown/cleanup của room thứ nhất không ảnh hưởng room thứ hai.
        assertEquals(90, second.player(1).getHp(), EPSILON);
        assertEquals(1.5f, second.body(1).getLinearVelocity().x, EPSILON);
    }

    @Test
    public void existingServerTestsStillPass() {
        Probe probe = closePair();
        var state = probe.runtime.getMatchState();
        send(probe, 0, 0, false, true);
        probe.runtime.tick();
        probe.runtime.start();
        probe.runtime.tick();
        assertSame(state, probe.runtime.getMatchState());
        assertTrue(probe.player(0).isBlocking());
        assertEquals(100, probe.player(0).getHp(), EPSILON);
        assertEquals(20, probe.world().getBodyCount());
        assertEquals(20, probe.world().getFixtureCount());
    }

    @Test
    public void deadPlayerCannotStartBlocking() {
        Probe probe = closePair();
        for (int event = 0; event < 10; event++) { probe.player(0).applyFallPenalty(); }
        send(probe, 0, 0, false, true);
        probe.runtime.tick();
        assertTrue(probe.player(0).isDead());
        assertFalse(probe.player(0).isBlocking());
        assertEquals(0, probe.player(0).getHp(), EPSILON);
    }

    @Test
    public void dyingClearsBlockingState() {
        Probe probe = closePair();
        for (int event = 0; event < 9; event++) { probe.player(0).applyFallPenalty(); }
        send(probe, 0, 0, false, true);
        probe.runtime.tick();
        assertTrue(probe.player(0).isBlocking());
        position(probe, 0, 5, -0.001f);
        probe.runtime.tick(); // Fall có hiệu lực dù blocking, không thêm miễn nhiễm damage khác.
        assertEquals(0, probe.player(0).getHp(), EPSILON);
        assertTrue(probe.player(0).isDead());
        assertFalse(probe.player(0).isBlocking());
    }

    @Test
    public void latestBlockHeldInBatchWinsInsteadOfOrAggregation() {
        Probe probe = closePair();
        send(probe, 0, 0, false, true);
        send(probe, 0, 1, false, false);
        List<PlayerInput> batch = probe.runtime.tick();
        assertEquals(1, batch.size());
        assertFalse(batch.get(0).blockHeld());
        assertFalse(probe.player(0).isBlocking());
    }

    @Test
    public void rejectedInputsDoNotRefreshOrReleaseBlocking() {
        Probe probe = create(2, 2);
        send(probe, 0, 10, false, true);
        probe.runtime.tick();
        assertEquals(DUPLICATE_OR_STALE, probe.runtime.submitInput(input(0, 10, 0, false, false, false)));
        assertEquals(INVALID_INPUT, probe.runtime.submitInput(input(0, 11, Float.NaN, false, false, false)));
        assertEquals(UNKNOWN_PLAYER, probe.runtime.submitInput(input(9, 0, 0, false, false, true)));
        probe.runtime.tick();
        assertTrue(probe.player(0).isBlocking()); // Request false bị reject không bỏ block ở k=1.
        probe.runtime.tick();
        assertFalse(probe.player(0).isBlocking()); // Request bị reject không gia hạn đến k=3.
    }

    @Test
    public void acceptedInputRefreshesBlockingTimeout() {
        Probe probe = create(2, 3);
        send(probe, 0, 0, false, true);
        probe.runtime.tick();
        probe.runtime.tick();
        send(probe, 0, 1, false, true); // Cập nhật ở k=2: hết hạn tại k=5.
        for (int tick = 2; tick < 5; tick++) {
            probe.runtime.tick();
            assertTrue(probe.player(0).isBlocking());
        }
        probe.runtime.tick();
        assertFalse(probe.player(0).isBlocking());
    }

    @Test
    public void blockReleaseIsAppliedBeforeMeleeInSameTick() {
        Probe probe = closePair();
        send(probe, 1, 0, false, true);
        probe.runtime.tick();
        send(probe, 0, 0, true, false);
        send(probe, 1, 1, false, false);
        probe.runtime.tick();
        assertFalse(probe.player(1).isBlocking());
        assertEquals(90, probe.player(1).getHp(), EPSILON);
        assertEquals(1.5f, probe.body(1).getLinearVelocity().x, EPSILON);
    }

    @Test
    public void expiredBlockingNoLongerShieldsTarget() {
        Probe probe = create(2, 2);
        pair(probe);
        send(probe, 1, 0, false, true);
        probe.runtime.tick();
        probe.runtime.tick();
        attack(probe, 0, 0); // Chính xác k=2, held block cũ hết hạn trước melee.
        assertFalse(probe.player(1).isBlocking());
        assertEquals(90, probe.player(1).getHp(), EPSILON);
        assertEquals(1.5f, probe.body(1).getLinearVelocity().x, EPSILON);
    }

    @Test
    public void blockedNearestTargetDoesNotRedirectToAnotherPlayer() {
        Probe probe = create(3);
        position(probe, 0, 5, 3);
        position(probe, 1, 5.05f, 3);
        position(probe, 2, 5.1f, 3);
        send(probe, 1, 0, false, true);
        attack(probe, 0, 0);
        for (int slot = 0; slot < 3; slot++) {
            assertEquals(100, probe.player(slot).getHp(), EPSILON);
            assertNoCombatImpulse(probe, slot);
        }
    }

    @Test
    public void blockedAttackStillConsumesExistingCooldown() {
        Probe probe = closePair();
        send(probe, 1, 0, false, true);
        attack(probe, 0, 0);
        pair(probe);
        send(probe, 1, 1, false, false);
        attack(probe, 0, 1);
        assertEquals(100, probe.player(1).getHp(), EPSILON);
        assertNoCombatImpulse(probe, 1);
    }

    @Test
    public void blockingPlayerCanAttackWithoutGroundRestriction() {
        Probe probe = closePair();
        assertFalse(probe.runtime.getJumpState(id(0)).grounded());
        send(probe, 0, 0, true, true);
        probe.runtime.tick();
        assertTrue(probe.player(0).isBlocking());
        assertEquals(90, probe.player(1).getHp(), EPSILON);
        assertEquals(1.5f, probe.body(1).getLinearVelocity().x, EPSILON);
    }

    @Test
    public void knockbackUsesTargetsCurrentReducedGravity() {
        Probe probe = closePair();
        position(probe, 1, 8.6f, 3);
        probe.runtime.tick(); // Boundary đặt gravity target về -0.098 * 0.3.
        pair(probe);
        attack(probe, 0, 0);
        assertEquals(90, probe.player(1).getHp(), EPSILON);
        assertEquals(0.2f - 2 * 0.098f * 0.3f, probe.body(1).getLinearVelocity().y, EPSILON);
    }

    @Test
    public void knockbackDoesNotGrantExtraJump() {
        Probe probe = closePair();
        attack(probe, 1, 0); // Slot 1 đẩy slot 0 lên, không phải slot 0 chủ động jump.
        assertTrue(probe.body(0).getLinearVelocity().y > 0);
        assertFalse(probe.runtime.getJumpState(id(0)).extraJumpReady());
        assertEquals(ACCEPTED, probe.runtime.submitInput(input(0, 0, 0, true, false, false)));
        probe.runtime.tick();
        assertEquals(0, probe.runtime.getJumpState(id(0)).jumpsUsed());
    }

    @Test
    public void heldBlockingDoesNotReplayMeleeEdge() {
        Probe probe = closePair();
        send(probe, 0, 0, true, true);
        probe.runtime.tick();
        for (int tick = 1; tick < TIMEOUT; tick++) {
            pair(probe); // Luôn trong range, kể cả khi melee cooldown hết tại tick 9.
            assertTrue(probe.runtime.tick().isEmpty());
            assertTrue(probe.player(0).isBlocking());
            assertEquals(90, probe.player(1).getHp(), EPSILON);
            assertNoCombatImpulse(probe, 1);
        }
    }

    @Test
    public void closeClearsHeldBlockingWithoutChangingHP() {
        Probe probe = closePair();
        send(probe, 0, 0, false, true);
        probe.runtime.tick();
        assertTrue(probe.player(0).isBlocking());
        probe.runtime.close();
        probe.runtime.close();
        assertFalse(probe.player(0).isBlocking());
        assertEquals(100, probe.player(0).getHp(), EPSILON);
        assertTrue(probe.physics.get().isDisposed());
        assertThrows(IllegalStateException.class, probe.runtime::tick);
        assertThrows(NoSuchMethodException.class, () -> ServerPlayer.class.getMethod("setBlocking", boolean.class));
    }

    @Test(timeout = 15000)
    public void wrongThreadCannotConsumeBlockingOrApplyKnockback() throws Exception {
        Probe probe = closePair();
        probe.runtime.tick();
        send(probe, 0, 0, true, true);
        InputQueueTest.runConcurrently(List.<Callable<Boolean>>of(() -> {
            assertThrows(IllegalStateException.class, probe.runtime::tick);
            return true;
        }));
        assertFalse(probe.player(0).isBlocking());
        assertEquals(100, probe.player(1).getHp(), EPSILON);
        assertEquals(0.0f, probe.body(1).getLinearVelocity().x, EPSILON);
        assertEquals(1, probe.steps.get());
        probe.runtime.tick();
        assertTrue(probe.player(0).isBlocking());
        assertEquals(90, probe.player(1).getHp(), EPSILON);
        assertEquals(1.5f, probe.body(1).getLinearVelocity().x, EPSILON);
    }

    private Probe closePair() {
        Probe probe = create(2);
        pair(probe);
        return probe;
    }

    private Probe create(int count) { return create(count, TIMEOUT); }

    private Probe create(int count, int timeoutTicks) {
        String[] characters = {"Alex", "Luca", "Maurice", "Viktor"};
        List<MatchSetup.Participant> participants = new ArrayList<>();
        for (int slot = 0; slot < count; slot++) {
            participants.add(new MatchSetup.Participant(id(slot), slot, characters[slot]));
        }
        Collections.reverse(participants); // Không để kết quả phụ thuộc thứ tự roster/packet.
        String matchId = "blocking-knockback-" + runtimes.size();
        MatchSetup setup = new MatchSetup(matchId, "room-" + matchId, participants);
        AtomicReference<HeadlessPhysicsWorld> physics = new AtomicReference<>();
        AtomicInteger steps = new AtomicInteger();
        AtomicReference<Consumer<World>> beforeStep = new AtomicReference<>(world -> { });
        MatchRuntime runtime = new MatchRuntime(setup, () -> {
            HeadlessPhysicsWorld created = new PhysicsWorldFactory().create();
            physics.set(created);
            return created;
        }, (world, dt, velocity, position) -> {
            beforeStep.get().accept(world);
            steps.incrementAndGet();
            assertEquals(1.0f / 60, dt, 0.0f);
            assertEquals(6, velocity);
            assertEquals(2, position);
            world.step(dt, velocity, position);
        }, ignored -> { }, timeoutTicks);
        runtimes.add(runtime);
        runtime.start();
        return new Probe(runtime, physics, steps, beforeStep);
    }

    private static void pair(Probe probe) {
        position(probe, 0, 5, 3);
        position(probe, 1, 5.1f, 3);
    }

    private static void position(Probe probe, int slot, float x, float y) {
        // Chỉ test bố trí tình huống. Production knockback không setTransform hoặc tin tọa độ client.
        probe.body(slot).setTransform(x, y, 0);
        probe.body(slot).setLinearVelocity(0, 0);
    }

    private static void assertNoCombatImpulse(Probe probe, int slot) {
        assertEquals(0.0f, probe.body(slot).getLinearVelocity().x, EPSILON);
        assertEquals(-0.098f, probe.body(slot).getLinearVelocity().y, EPSILON); // Chỉ gravity một tick.
    }

    private static void attack(Probe probe, int slot, long sequence) {
        send(probe, slot, sequence, true, false);
        probe.runtime.tick();
    }

    private static void send(Probe probe, int slot, long sequence, boolean melee, boolean block) {
        assertEquals(ACCEPTED, probe.runtime.submitInput(input(slot, sequence, 0, false, melee, block)));
    }

    private static PlayerInput input(int slot, long sequence, float axis, boolean jump, boolean melee, boolean block) {
        return new PlayerInput(id(slot), sequence, Long.MAX_VALUE, axis, jump, false, melee, block, false);
    }

    private static PlayerId id(int slot) { return new PlayerId("arbitrary-blocking-player-" + slot); }

    private record Probe(MatchRuntime runtime, AtomicReference<HeadlessPhysicsWorld> physics, AtomicInteger steps,
                         AtomicReference<Consumer<World>> beforeStep) {
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
