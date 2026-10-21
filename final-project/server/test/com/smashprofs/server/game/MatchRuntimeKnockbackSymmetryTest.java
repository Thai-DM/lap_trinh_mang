package com.smashprofs.server.game;

import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.World;
import com.badlogic.gdx.utils.Array;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import com.smashprofs.server.game.physics.MapCollisionBuilder;
import com.smashprofs.server.game.physics.PhysicsWorldFactory;
import com.smashprofs.server.game.physics.PlayerJumpController;
import com.smashprofs.server.game.physics.PlayerMovementController;
import com.smashprofs.server.game.physics.PlayerPhysicsFactory;
import com.smashprofs.server.game.physics.TmxCollisionLoader;
import org.junit.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Đo hai candidate từ source và kiểm tra cân bằng bằng World/Body native, manual tick 60 Hz. */
public class MatchRuntimeKnockbackSymmetryTest {
    private static final float EPSILON = 0.00001f;
    private static final PlayerId PLAYER_A = new PlayerId("stable-player-a");
    private static final PlayerId PLAYER_B = new PlayerId("stable-player-b");

    @Test
    public void originalVerticalCandidatesAreMeasuredWithRealBox2D() {
        VerticalMeasurement punch = measureOriginalCandidate(0.2f);
        VerticalMeasurement launch = measureOriginalCandidate(2.0f);
        assertEquals(1.0f, punch.mass(), EPSILON);
        assertEquals(punch.mass(), launch.mass(), EPSILON);
        assertEquals(0.004f, punch.firstVelocityY(), EPSILON);
        assertEquals(1.804f, launch.firstVelocityY(), EPSILON);
        assertEquals(0.0000667f, punch.peakRiseMeters(), EPSILON);
        assertEquals(0.2919667f, launch.peakRiseMeters(), EPSILON);
        // Cùng fixture/gravity: 2.0 tạo launch rõ rệt, 0.2 giữ melee thường chủ yếu đẩy ngang.
        assertTrue(launch.peakRiseMeters() > 1000 * punch.peakRiseMeters());
        System.out.println("Box2D candidate Y=0.2: " + punch + "; Y=2.0: " + launch);
    }

    @Test
    public void twoPlayerMatchesHaveSlotIndependentKnockback() { assertSlotPermutations(2); }

    @Test
    public void threePlayerMatchesHaveSlotIndependentKnockback() { assertSlotPermutations(3); }

    @Test
    public void fourPlayerMatchesHaveSlotIndependentKnockback() { assertSlotPermutations(4); }

    @Test
    public void swappingPositionsMirrorsXWithoutChangingYOrDamage() {
        for (int count = 2; count <= 4; count++) {
            for (int slotA = 0; slotA < 4; slotA++) {
                for (int slotB = 0; slotB < 4; slotB++) {
                    if (slotA == slotB) { continue; }
                    Impact right = hit(count, slotA, slotB, false, false, false, 0, "Alex", "Luca");
                    Impact left = hit(count, slotA, slotB, true, false, false, 0, "Alex", "Luca");
                    assertEquals(-right.velocityX(), left.velocityX(), EPSILON);
                    assertEquals(right.velocityY(), left.velocityY(), EPSILON);
                    assertEquals(-right.displacementX(), left.displacementX(), EPSILON);
                    assertEquals(right.displacementY(), left.displacementY(), EPSILON);
                    assertEquals(right.hp(), left.hp(), EPSILON);
                }
            }
        }
    }

    @Test
    public void swappingAttackerTargetRolesAndSlotsPreservesMeleeProfile() {
        for (int count = 2; count <= 4; count++) {
            for (int slotA = 0; slotA < 4; slotA++) {
                for (int slotB = 0; slotB < 4; slotB++) {
                    if (slotA == slotB) { continue; }
                    // Hai PlayerId giữ nguyên; đổi người ra đòn đồng thời hoán đổi slot của họ.
                    Impact aHitsB = hit(count, slotA, slotB, false, false, false, 0, "Alex", "Luca");
                    Impact bHitsA = hit(count, slotB, slotA, false, true, false, 0, "Alex", "Luca");
                    assertEquals(-aHitsB.velocityX(), bHitsA.velocityX(), EPSILON);
                    assertEquals(aHitsB.velocityY(), bHitsA.velocityY(), EPSILON);
                    assertEquals(aHitsB.hp(), bHitsA.hp(), EPSILON);
                    assertEquals(aHitsB.mass(), bHitsA.mass(), EPSILON);
                }
            }
        }
    }

    @Test
    public void allVerifiedCharactersUseSameStandardMeleeProfile() {
        for (String attackerCharacter : List.of("Alex", "Luca", "Maurice", "Leo", "Viktor")) {
            for (String targetCharacter : List.of("Alex", "Luca", "Maurice", "Leo", "Viktor")) {
                for (int count = 2; count <= 4; count++) {
                    Impact impact = hit(count, count - 1, 0, false, false, false, 0,
                            attackerCharacter, targetCharacter);
                    assertNormalImpact(impact);
                }
            }
        }
    }

    @Test
    public void blockingAndMovementRulesRemainSymmetricAcrossSlots() {
        for (int count = 2; count <= 4; count++) {
            for (int slotA = 0; slotA < 4; slotA++) {
                for (int slotB = 0; slotB < 4; slotB++) {
                    if (slotA == slotB) { continue; }
                    for (boolean mirror : new boolean[]{false, true}) {
                        float axis = mirror ? -0.5f : 0.5f;
                        Impact blocked = hit(count, slotA, slotB, mirror, false, true, axis, "Alex", "Luca");
                        assertEquals(100, blocked.hp(), EPSILON);
                        assertEquals(0.055f * axis, blocked.velocityX(), EPSILON);
                        assertEquals(-0.098f, blocked.velocityY(), EPSILON);
                        assertTrue(blocked.blocking());
                        Impact open = hit(count, slotA, slotB, mirror, false, false, axis, "Alex", "Luca");
                        assertEquals(90, open.hp(), EPSILON);
                        assertEquals((mirror ? -1.5f : 1.5f) + 0.055f * axis, open.velocityX(), EPSILON);
                        assertEquals(0.004f, open.velocityY(), EPSILON);
                    }
                }
            }
        }
    }

    @Test
    public void movementClampAfterKnockbackRemainsSymmetricAcrossSlots() {
        for (int count = 2; count <= 4; count++) {
            for (int slotA = 0; slotA < 4; slotA++) {
                for (int slotB = 0; slotB < 4; slotB++) {
                    if (slotA == slotB) { continue; }
                    for (boolean mirror : new boolean[]{false, true}) {
                        Impact impact = hit(count, slotA, slotB, mirror, false, false,
                                mirror ? -0.5f : 0.5f, "Alex", "Luca", 3);
                        assertEquals(90, impact.hp(), EPSILON); // Không replay melee trong các tick sau.
                        assertEquals(mirror ? -1.0f : 1.0f, impact.velocityX(), EPSILON);
                        assertEquals(0.004f - 2 * 0.098f, impact.velocityY(), EPSILON);
                    }
                }
            }
        }
    }

    private static void assertSlotPermutations(int count) {
        for (int slotA = 0; slotA < 4; slotA++) {
            for (int slotB = 0; slotB < 4; slotB++) {
                if (slotA != slotB) {
                    assertNormalImpact(hit(count, slotA, slotB, false, false, false, 0, "Alex", "Luca"));
                }
            }
        }
    }

    private static void assertNormalImpact(Impact impact) {
        assertEquals(90, impact.hp(), EPSILON);
        assertEquals(1.0f, impact.mass(), EPSILON);
        assertEquals(1.5f, impact.velocityX(), EPSILON);
        assertEquals(0.004f, impact.velocityY(), EPSILON);
        assertEquals(1.5f / 60, impact.displacementX(), EPSILON);
        assertEquals(0.004f / 60, impact.displacementY(), EPSILON);
        assertFalse(impact.blocking());
    }

    /** Mỗi lần dựng một trận thật, PlayerId không gắn với slot, spectators ở ngoài range. */
    private static Impact hit(int count, int slotA, int slotB, boolean mirror, boolean reverseRoles,
                              boolean targetBlocking, float targetAxis, String characterA, String characterB) {
        return hit(count, slotA, slotB, mirror, reverseRoles, targetBlocking, targetAxis,
                characterA, characterB, 1);
    }

    private static Impact hit(int count, int slotA, int slotB, boolean mirror, boolean reverseRoles,
                              boolean targetBlocking, float targetAxis, String characterA, String characterB,
                              int ticksToSimulate) {
        List<MatchSetup.Participant> roster = new ArrayList<>();
        roster.add(new MatchSetup.Participant(PLAYER_A, slotA, characterA));
        roster.add(new MatchSetup.Participant(PLAYER_B, slotB, characterB));
        // Roster có thể thưa (ví dụ trận 2 người ở slot 1 và 3), không ép slot thành 0 và 1.
        for (int slot = 0; slot < 4 && roster.size() < count; slot++) {
            if (slot != slotA && slot != slotB) {
                roster.add(new MatchSetup.Participant(new PlayerId("observer-" + slot), slot, "Leo"));
            }
        }
        Collections.reverse(roster); // Không lấy thứ tự list làm nguồn quyết định knockback.
        MatchSetup setup = new MatchSetup("symmetry-match", "symmetry-room", roster);
        AtomicReference<World> worldRef = new AtomicReference<>();
        PlayerId attackerId = reverseRoles ? PLAYER_B : PLAYER_A;
        PlayerId targetId = reverseRoles ? PLAYER_A : PLAYER_B;
        try (MatchRuntime runtime = new MatchRuntime(setup, () -> {
            var physics = new PhysicsWorldFactory().create();
            worldRef.set(physics.getWorld());
            return physics;
        }, World::step)) {
            runtime.start();
            assertEquals(count, runtime.getMatchState().getPlayerCount());
            World world = worldRef.get();
            Body playerA = body(world, PLAYER_A);
            Body playerB = body(world, PLAYER_B);
            playerA.setTransform(mirror ? 5.1f : 5.0f, 3, 0);
            playerB.setTransform(mirror ? 5.0f : 5.1f, 3, 0);
            for (var participant : roster) {
                Body body = body(world, participant.playerId());
                if (!participant.playerId().equals(PLAYER_A) && !participant.playerId().equals(PLAYER_B)) {
                    body.setTransform(7 + participant.slotIndex() * 0.2f, 3, 0);
                }
                body.setLinearVelocity(0, 0);
            }
            Body target = body(world, targetId);
            float initialX = target.getPosition().x;
            float initialY = target.getPosition().y;
            assertEquals(InputQueue.OfferResult.ACCEPTED, runtime.submitInput(new PlayerInput(
                    targetId, 0, 0, targetAxis, false, false, false, targetBlocking, false)));
            assertEquals(InputQueue.OfferResult.ACCEPTED, runtime.submitInput(new PlayerInput(
                    attackerId, 0, 0, 0, false, false, true, false, false)));
            for (int tick = 0; tick < ticksToSimulate; tick++) { runtime.tick(); }
            assertEquals(ticksToSimulate, runtime.getMatchState().getServerTick());
            for (var participant : roster) {
                if (!participant.playerId().equals(targetId)) {
                    assertEquals(100, runtime.getMatchState().getPlayer(participant.playerId()).getHp(), EPSILON);
                    assertEquals(0, body(world, participant.playerId()).getLinearVelocity().x, EPSILON);
                }
            }
            var player = runtime.getMatchState().getPlayer(targetId);
            return new Impact(player.getHp(), target.getMass(), target.getLinearVelocity().x,
                    target.getLinearVelocity().y, target.getPosition().x - initialX,
                    target.getPosition().y - initialY, player.isBlocking());
        }
    }

    private static VerticalMeasurement measureOriginalCandidate(float nominalY) {
        MatchSetup setup = new MatchSetup("measure-" + nominalY, "measure-room", List.of(
                new MatchSetup.Participant(PLAYER_A, 0, "Alex"),
                new MatchSetup.Participant(PLAYER_B, 1, "Luca")));
        var geometry = new TmxCollisionLoader().loadOriginalMap();
        try (var physics = new PhysicsWorldFactory().create();
             var arena = new MapCollisionBuilder().build(physics, geometry);
             var players = new PlayerPhysicsFactory().build(physics, setup, geometry);
             var jump = new PlayerJumpController(players)) {
            World world = physics.getWorld();
            Body target = body(world, PLAYER_A);
            target.setTransform(5, 3, 0);
            target.setLinearVelocity(0, 0);
            body(world, PLAYER_B).setTransform(7, 3, 0);
            jump.applyGravity();
            jump.applyMeleeKnockback(PLAYER_A, 1.5f, nominalY);
            float firstVelocityY = target.getLinearVelocity().y;
            float maximumY = 3;
            PlayerMovementController movement = new PlayerMovementController();
            for (int tick = 0; tick < 60; tick++) {
                if (tick > 0) {
                    movement.applyHorizontalMovement(players, PLAYER_A, 0);
                    jump.applyGravity();
                }
                world.step(1.0f / 60, 6, 2);
                jump.synchronizeAfterStep();
                maximumY = Math.max(maximumY, target.getPosition().y);
                if (target.getLinearVelocity().y <= 0) {
                    return new VerticalMeasurement(target.getMass(), firstVelocityY, maximumY - 3);
                }
            }
            throw new AssertionError("Không đạt đỉnh quỹ đạo trong 60 manual ticks");
        }
    }

    private static Body body(World world, PlayerId id) {
        Array<Body> bodies = new Array<>();
        world.getBodies(bodies);
        for (Body body : bodies) {
            if (id.equals(body.getUserData())) { return body; }
        }
        throw new AssertionError("Thiếu body: " + id);
    }

    private record VerticalMeasurement(float mass, float firstVelocityY, float peakRiseMeters) { }
    private record Impact(float hp, float mass, float velocityX, float velocityY,
                          float displacementX, float displacementY, boolean blocking) { }
}
