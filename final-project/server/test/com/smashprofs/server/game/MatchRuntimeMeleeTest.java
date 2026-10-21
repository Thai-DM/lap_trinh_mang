package com.smashprofs.server.game;

import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.World;
import com.badlogic.gdx.utils.Array;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import com.smashprofs.server.game.combat.MeleeCombatController;
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

import static com.smashprofs.server.game.InputQueue.OfferResult.*;
import static org.junit.Assert.*;

/** Manual tick với MatchRuntime/World thật; mọi mục tiêu trong test đều không blocking. */
public class MatchRuntimeMeleeTest {
    private static final float EPSILON = 0.00001f;
    private static final int COOLDOWN = MeleeCombatController.DEFAULT_COOLDOWN_TICKS;
    private final List<MatchRuntime> runtimes = new ArrayList<>();

    @After
    public void closeResources() { runtimes.forEach(MatchRuntime::close); }

    @Test
    public void validMeleeAttackCanHitTarget() {
        Probe probe = closePair();
        attack(probe, 0, 0);
        assertEquals(90, probe.player(1).getHp(), EPSILON);
    }

    @Test
    public void meleeAttackReducesTargetHP() {
        Probe probe = closePair();
        float before = probe.player(1).getHp();
        attack(probe, 0, 0);
        assertEquals(before - 10, probe.player(1).getHp(), EPSILON);
        assertSame(probe.player(1), probe.runtime.getMatchState().getPlayer(id(1)));
    }

    @Test
    public void meleeDamageMatchesOriginalValue() {
        // Bao gồm đủ năm character; source gốc không override attackDamage theo character.
        for (String character : List.of("Alex", "Luca", "Maurice", "Leo", "Viktor")) {
            Probe probe = create(2, COOLDOWN, character);
            pair(probe);
            attack(probe, 0, 0);
            assertEquals(10.0f, 100 - probe.player(1).getHp(), EPSILON);
        }
        assertEquals(10.0f, MeleeCombatController.MELEE_DAMAGE, 0.0f);
    }

    @Test
    public void attackOutsideRangeMisses() {
        Probe probe = closePair();
        position(probe, 1, 5.21f, 3);
        attack(probe, 0, 0);
        assertEquals(100, probe.player(1).getHp(), EPSILON);
    }

    @Test
    public void attackInsideRangeHits() {
        Probe probe = closePair();
        position(probe, 1, 5.19f, 3);
        attack(probe, 0, 0);
        assertEquals(90, probe.player(1).getHp(), EPSILON);
        assertEquals(0.2f, MeleeCombatController.ATTACK_RANGE_METERS, 0.0f);
    }

    @Test
    public void attackAtExactRangeBoundaryMisses() {
        Probe probe = closePair();
        // Dùng Y=0 và Y=0.2 để hiệu float đúng bằng range, tránh sai số phép trừ 5.2 - 5.
        position(probe, 0, 5, 0);
        position(probe, 1, 5, MeleeCombatController.ATTACK_RANGE_METERS);
        assertEquals(MeleeCombatController.ATTACK_RANGE_METERS,
                probe.body(1).getPosition().y - probe.body(0).getPosition().y, 0.0f);
        attack(probe, 0, 0);
        assertEquals(100, probe.player(1).getHp(), EPSILON); // Strict <, không phải <=.
    }

    @Test
    public void rangeIncludesVerticalDistanceWithoutExpandingByBodyRadius() {
        Probe probe = closePair();
        position(probe, 1, 5.15f, 3.15f); // Mỗi trục < 0.2 nhưng độ dài Euclidean > 0.2.
        attack(probe, 0, 0);
        assertEquals(100, probe.player(1).getHp(), EPSILON);
    }

    @Test
    public void attackBehindAttackerHitsBecauseOriginalHasNoFacingGate() {
        Probe probe = closePair();
        position(probe, 1, 4.9f, 3);
        probe.body(0).setLinearVelocity(0.1f, 0); // Đang di chuyển sang phải, target ở bên trái.
        attack(probe, 0, 0);
        assertEquals(90, probe.player(1).getHp(), EPSILON);
    }

    @Test
    public void attackerCannotHitSelf() {
        Probe probe = closePair();
        position(probe, 1, 6, 3);
        attack(probe, 0, 0);
        assertEquals(100, probe.player(0).getHp(), EPSILON);
        assertEquals(100, probe.player(1).getHp(), EPSILON);
    }

    @Test
    public void unknownAttackerCannotAttack() {
        Probe probe = closePair();
        assertEquals(UNKNOWN_PLAYER, probe.runtime.submitInput(input(9, 0, 0, false, true)));
        assertTrue(probe.runtime.tick().isEmpty());
        assertEquals(100, probe.player(0).getHp(), EPSILON);
        assertEquals(100, probe.player(1).getHp(), EPSILON);
    }

    @Test
    public void deadAttackerCannotAttack() {
        Probe probe = closePair();
        reduceByFalls(probe.player(0), 10);
        assertTrue(probe.player(0).isDead());
        attack(probe, 0, 0);
        assertEquals(100, probe.player(1).getHp(), EPSILON);
        assertEquals(0, probe.player(0).getHp(), EPSILON);
    }

    @Test
    public void deadTargetCannotBeDamagedAgain() {
        Probe probe = create(3);
        position(probe, 0, 5, 3);
        position(probe, 1, 5.05f, 3);
        position(probe, 2, 5.15f, 3);
        reduceByFalls(probe.player(1), 10);
        attack(probe, 0, 0);
        assertEquals(0, probe.player(1).getHp(), EPSILON);
        assertTrue(probe.player(1).isDead());
        assertEquals(90, probe.player(2).getHp(), EPSILON); // Bỏ qua target chết dù gần hơn.
    }

    @Test
    public void meleeCooldownPreventsSpam() {
        Probe probe = closePair();
        attack(probe, 0, 0); // Xử lý tại tick k=0; tick tiếp theo đủ điều kiện là k=9.
        for (int tick = 1; tick < COOLDOWN; tick++) {
            pair(probe); // Bước 33 có knockback: giữ target trong range để chỉ kiểm tra cooldown.
            attack(probe, 0, tick);
            assertEquals(90, probe.player(1).getHp(), EPSILON);
        }
        assertEquals(COOLDOWN, probe.runtime.getMatchState().getServerTick());
    }

    @Test
    public void meleeWorksAfterCooldownExpires() {
        Probe probe = closePair();
        attack(probe, 0, 0);
        tickUntil(probe, COOLDOWN - 1);
        pair(probe);
        attack(probe, 0, 1); // k=8 bị chặn, không đẩy deadline sang k=17.
        assertEquals(90, probe.player(1).getHp(), EPSILON);
        pair(probe);
        attack(probe, 0, 2); // Chính xác k=9 được nhận.
        assertEquals(80, probe.player(1).getHp(), EPSILON);
    }

    @Test
    public void missedAttackAlsoStartsCooldown() {
        Probe probe = closePair();
        position(probe, 1, 6, 3);
        attack(probe, 0, 0);
        pair(probe);
        attack(probe, 0, 1);
        assertEquals(100, probe.player(1).getHp(), EPSILON);
        tickUntil(probe, COOLDOWN);
        pair(probe);
        attack(probe, 0, 2);
        assertEquals(90, probe.player(1).getHp(), EPSILON);
    }

    @Test
    public void cooldownIsIndependentForEachPlayer() {
        Probe probe = closePair();
        send(probe, 0, 0, 0, false, true);
        send(probe, 1, 0, 0, false, true);
        probe.runtime.tick();
        assertEquals(90, probe.player(0).getHp(), EPSILON);
        assertEquals(90, probe.player(1).getHp(), EPSILON);
    }

    @Test
    public void cooldownCanBeConfiguredAndIsValidated() {
        Probe probe = create(2, 2, "Alex");
        pair(probe);
        attack(probe, 0, 0);
        pair(probe);
        attack(probe, 0, 1);
        assertEquals(90, probe.player(1).getHp(), EPSILON);
        pair(probe);
        attack(probe, 0, 2);
        assertEquals(80, probe.player(1).getHp(), EPSILON);
        MatchSetup setup = new MatchSetup("config", "room-config", probe.runtime.getParticipants());
        assertThrows(IllegalArgumentException.class, () -> new MatchRuntime(setup, 15, 0));
        assertThrows(IllegalArgumentException.class, () -> new MatchRuntime(setup, 15, -1));
        assertEquals(9, COOLDOWN);
    }

    @Test
    public void duplicateSequenceDoesNotRepeatAttack() {
        Probe probe = closePair();
        attack(probe, 0, 10);
        tickUntil(probe, COOLDOWN);
        pair(probe); // Không để knockback gây miss che mất lỗi duplicate/stale.
        assertEquals(DUPLICATE_OR_STALE, probe.runtime.submitInput(input(0, 10, 0, false, true)));
        assertEquals(DUPLICATE_OR_STALE, probe.runtime.submitInput(input(0, 9, 0, false, true)));
        assertTrue(probe.runtime.tick().isEmpty());
        assertEquals(90, probe.player(1).getHp(), EPSILON);
    }

    @Test
    public void singlePressDoesNotAttackEveryTick() {
        Probe probe = closePair();
        send(probe, 0, 0, 0.1f, false, true);
        probe.runtime.tick();
        for (int tick = 0; tick < COOLDOWN * 2; tick++) {
            pair(probe); // Range vẫn hợp lệ cả khi cooldown hết, nhưng không có edge mới.
            assertTrue(probe.runtime.tick().isEmpty());
            assertEquals(90, probe.player(1).getHp(), EPSILON);
        }
    }

    @Test
    public void multipleMeleePressesInBatchProduceOneAttack() {
        Probe probe = closePair();
        send(probe, 0, 0, 0, false, true);
        send(probe, 0, 1, 0, false, true);
        send(probe, 0, 2, 0, false, false);
        List<PlayerInput> batch = probe.runtime.tick();
        assertEquals(1, batch.size());
        assertEquals(2, batch.get(0).sequence());
        assertTrue(batch.get(0).meleePressed());
        assertEquals(90, probe.player(1).getHp(), EPSILON);
    }

    @Test
    public void otherPlayersAreNotDamagedAccidentally() {
        Probe probe = create(4);
        position(probe, 0, 5, 3);
        position(probe, 1, 5.1f, 3);
        position(probe, 2, 7, 3);
        position(probe, 3, 8, 3);
        attack(probe, 0, 0);
        for (int slot = 0; slot < 4; slot++) {
            assertEquals(slot == 1 ? 90 : 100, probe.player(slot).getHp(), EPSILON);
        }
    }

    @Test
    public void threePlayerTargetSelectionIsDeterministic() {
        Probe probe = create(3); // Roster cố ý đảo slot; không chọn theo thứ tự roster/HashMap.
        position(probe, 0, 5, 3);
        position(probe, 1, 4.9f, 3);
        position(probe, 2, 5.1f, 3);
        attack(probe, 0, 0);
        assertEquals(90, probe.player(1).getHp(), EPSILON); // Cùng khoảng cách, slot 1 trước slot 2.
        assertEquals(100, probe.player(2).getHp(), EPSILON);
    }

    @Test
    public void fourPlayerTargetSelectionIsDeterministic() {
        Probe probe = create(4);
        position(probe, 0, 5, 3);
        position(probe, 1, 5.15f, 3);
        position(probe, 2, 5.1f, 3);
        position(probe, 3, 5.05f, 3);
        attack(probe, 0, 0);
        assertEquals(90, probe.player(3).getHp(), EPSILON); // Gần nhất, dù slot lớn nhất.
        assertEquals(100, probe.player(1).getHp(), EPSILON);
        assertEquals(100, probe.player(2).getHp(), EPSILON); // Không AoE.
    }

    @Test
    public void attackDoesNotChangeOtherRoomHP() {
        Probe first = closePair();
        Probe second = closePair(); // Cùng PlayerId nhưng World/state/cooldown riêng.
        attack(first, 0, 0);
        assertEquals(100, second.player(1).getHp(), EPSILON);
        attack(second, 0, 0); // Cooldown room thứ nhất không ảnh hưởng room thứ hai.
        assertEquals(90, second.player(1).getHp(), EPSILON);
        assertEquals(90, first.player(1).getHp(), EPSILON);
        assertNotSame(first.world(), second.world());
        assertNotSame(first.player(0), second.player(0));
    }

    @Test
    public void simultaneousAttacksAreDeterministic() {
        Probe probe = closePair();
        reduceByFalls(probe.player(0), 9);
        reduceByFalls(probe.player(1), 9);
        send(probe, 1, 0, 0, false, true); // Packet và roster đều slot 1 trước slot 0.
        send(probe, 0, 0, 0, false, true);
        probe.runtime.tick();
        assertEquals(10, probe.player(0).getHp(), EPSILON);
        assertFalse(probe.player(0).isDead());
        assertEquals(0, probe.player(1).getHp(), EPSILON);
        assertTrue(probe.player(1).isDead()); // Chết trước lượt nên không đánh lại slot 0.
    }

    @Test
    public void damageClampsHPAndUsesSamePathAsFallPenalty() {
        Probe probe = create(3);
        pair(probe); // Target chết không kết thúc trận, vẫn kiểm tra hit/cooldown tiếp theo.
        position(probe, 2, 8, 3);
        reduceByFalls(probe.player(1), 9); // HP 10 từ đường damage của bước 31.
        attack(probe, 0, 0);
        assertEquals(0, probe.player(1).getHp(), EPSILON);
        assertTrue(probe.player(1).isDead());
        tickUntil(probe, COOLDOWN);
        attack(probe, 0, 1);
        probe.player(1).applyFallPenalty();
        assertEquals(0, probe.player(1).getHp(), EPSILON);
        assertTrue(probe.player(1).isDead());
        assertThrows(IllegalArgumentException.class, () -> probe.player(0).applyDamage(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> probe.player(0).applyDamage(-10));
        assertThrows(NoSuchMethodException.class, () -> ServerPlayer.class.getMethod("applyDamage", float.class));
    }

    @Test
    public void fatalFallPenaltyPreventsMeleeInSameTick() {
        Probe probe = closePair();
        reduceByFalls(probe.player(0), 9);
        position(probe, 0, 5, -0.001f);
        position(probe, 1, 5, 0.05f); // Target trong range nhưng không bị fall penalty.
        attack(probe, 0, 0);
        assertEquals(0, probe.player(0).getHp(), EPSILON);
        assertTrue(probe.player(0).isDead());
        assertEquals(100, probe.player(1).getHp(), EPSILON);
    }

    @Test
    public void meleeDoesNotBreakMovementOrJump() {
        Probe probe = create(2);
        for (int tick = 0; tick < 180 && !probe.runtime.getJumpState(id(0)).grounded(); tick++) {
            probe.runtime.tick();
        }
        assertTrue(probe.runtime.getJumpState(id(0)).grounded());
        float x = probe.body(0).getPosition().x;
        float y = probe.body(0).getPosition().y;
        position(probe, 1, x + 0.1f, y);
        send(probe, 0, 0, 0.5f, true, true);
        probe.runtime.tick();
        assertEquals(90, probe.player(1).getHp(), EPSILON);
        assertEquals(0.0275f, probe.body(0).getLinearVelocity().x, EPSILON);
        assertTrue(probe.body(0).getLinearVelocity().y > 0);
        assertEquals(1, probe.runtime.getJumpState(id(0)).jumpsUsed());
        assertEquals(100, probe.player(0).getHp(), EPSILON);
    }

    @Test
    public void meleeDoesNotStepWorldTwice() {
        Probe probe = create(4);
        for (int slot = 0; slot < 4; slot++) {
            position(probe, slot, 5 + 0.04f * slot, 3);
            send(probe, slot, 0, 0, false, true);
        }
        probe.runtime.tick();
        assertEquals(1, probe.steps.get());
        assertEquals(1, probe.runtime.getMatchState().getServerTick());
        assertEquals(22, probe.world().getBodyCount());
        probe.runtime.tick();
        assertEquals(2, probe.steps.get());
        assertEquals(2, probe.runtime.getMatchState().getServerTick());
    }

    @Test
    public void meleeNowAppliesAuthoritativeKnockback() {
        Probe probe = closePair();
        attack(probe, 0, 0);
        assertEquals(90, probe.player(1).getHp(), EPSILON);
        assertEquals(1.5f, probe.body(1).getLinearVelocity().x, EPSILON);
        assertEquals(0.2f - 2 * 0.098f, probe.body(1).getLinearVelocity().y, EPSILON);
    }

    @Test
    public void previousServerTestsStillPass() {
        Probe probe = closePair();
        var state = probe.runtime.getMatchState();
        attack(probe, 0, 0);
        probe.runtime.start(); // Start lặp lại không reset state/cooldown hoặc tạo World khác.
        pair(probe);
        attack(probe, 0, 1);
        assertSame(state, probe.runtime.getMatchState());
        assertEquals(90, probe.player(1).getHp(), EPSILON);
        assertEquals(20, probe.world().getBodyCount());
        assertEquals(20, probe.world().getFixtureCount());
        assertEquals(id(0), probe.body(0).getUserData());
    }

    @Test
    public void closedRuntimeCannotApplyQueuedMelee() throws Exception {
        Probe probe = closePair();
        send(probe, 0, 0, 0, false, true);
        probe.runtime.close();
        probe.runtime.close();
        assertThrows(IllegalStateException.class, probe.runtime::tick);
        assertThrows(IllegalStateException.class, () -> probe.runtime.submitInput(input(0, 1, 0, false, true)));
        assertEquals(100, probe.player(1).getHp(), EPSILON);
        assertEquals(0, probe.steps.get());
        assertTrue(probe.physics.get().isDisposed());
        var field = MatchRuntime.class.getDeclaredField("meleeController");
        field.setAccessible(true);
        assertNull(field.get(probe.runtime));
    }

    @Test
    public void unstartedRuntimeRejectsMeleeInputAndTick() {
        Probe probe = closePair();
        MatchRuntime runtime = new MatchRuntime(new MatchSetup("not-started", "room-not-started",
                probe.runtime.getParticipants()));
        runtimes.add(runtime);
        assertThrows(IllegalStateException.class, () -> runtime.submitInput(input(0, 0, 0, false, true)));
        assertThrows(IllegalStateException.class, runtime::tick);
        assertNull(runtime.getMatchState());
    }

    @Test(timeout = 15000)
    public void onlySimulationOwnerCanConsumeMeleeInput() throws Exception {
        Probe probe = closePair();
        probe.runtime.tick();
        send(probe, 0, 0, 0, false, true);
        InputQueueTest.runConcurrently(List.<Callable<Boolean>>of(() -> {
            assertThrows(IllegalStateException.class, probe.runtime::tick);
            return true;
        }));
        assertEquals(100, probe.player(1).getHp(), EPSILON);
        assertEquals(1, probe.steps.get());
        probe.runtime.tick();
        assertEquals(90, probe.player(1).getHp(), EPSILON);
    }

    private Probe closePair() {
        Probe probe = create(2);
        pair(probe);
        return probe;
    }

    private static void pair(Probe probe) {
        position(probe, 0, 5, 3);
        position(probe, 1, 5.1f, 3);
    }

    private Probe create(int count) { return create(count, COOLDOWN, "Alex"); }

    private Probe create(int count, int cooldownTicks, String firstCharacter) {
        String[] characters = {firstCharacter, "Luca", "Leo", "Viktor"};
        List<MatchSetup.Participant> participants = new ArrayList<>();
        for (int slot = 0; slot < count; slot++) {
            participants.add(new MatchSetup.Participant(id(slot), slot, characters[slot]));
        }
        Collections.reverse(participants); // Cố ý không cùng thứ tự xử lý slot.
        String matchId = "melee-match-" + runtimes.size();
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
        }, ignored -> { }, MatchRuntime.DEFAULT_MOVEMENT_TIMEOUT_TICKS, cooldownTicks);
        runtimes.add(runtime);
        runtime.start();
        return new Probe(runtime, physics, steps);
    }

    private static void position(Probe probe, int slot, float x, float y) {
        // Chỉ test bố trí Body native; production combat không teleport hoặc nhận tọa độ từ client.
        probe.body(slot).setTransform(x, y, 0);
        probe.body(slot).setLinearVelocity(0, 0);
    }

    private static void reduceByFalls(ServerPlayer player, int count) {
        for (int fall = 0; fall < count; fall++) { player.applyFallPenalty(); }
    }

    private static void tickUntil(Probe probe, long targetTick) {
        for (long tick = probe.runtime.getMatchState().getServerTick(); tick < targetTick; tick++) {
            probe.runtime.tick();
        }
        assertEquals(targetTick, probe.runtime.getMatchState().getServerTick());
    }

    private static void attack(Probe probe, int slot, long sequence) {
        send(probe, slot, sequence, 0, false, true);
        probe.runtime.tick();
    }

    private static void send(Probe probe, int slot, long sequence, float axis, boolean jump, boolean melee) {
        assertEquals(ACCEPTED, probe.runtime.submitInput(input(slot, sequence, axis, jump, melee)));
    }

    private static PlayerInput input(int slot, long sequence, float axis, boolean jump, boolean melee) {
        // clientTick không dùng để bypass cooldown; mọi mục tiêu đều không blocking.
        return new PlayerInput(id(slot), sequence, Long.MAX_VALUE, axis, jump, false, melee, false, false);
    }

    private static PlayerId id(int slot) { return new PlayerId("arbitrary-melee-player-" + slot); }

    private record Probe(MatchRuntime runtime, AtomicReference<HeadlessPhysicsWorld> physics, AtomicInteger steps) {
        World world() { return physics.get().getWorld(); }
        ServerPlayer player(int slot) { return runtime.getMatchState().getPlayer(id(slot)); }
        Body body(int slot) {
            Array<Body> bodies = new Array<>();
            world().getBodies(bodies);
            for (Body body : bodies) {
                if (id(slot).equals(body.getUserData())) { return body; }
            }
            throw new AssertionError("Thiếu player Body: " + slot);
        }
    }
}
