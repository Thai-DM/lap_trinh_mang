package com.smashprofs.server.game;

import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef;
import com.badlogic.gdx.utils.Array;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import com.smashprofs.server.game.physics.HeadlessPhysicsWorld;
import com.smashprofs.server.game.physics.PhysicsWorldFactory;
import com.smashprofs.server.game.projectile.ProjectileType;
import com.smashprofs.server.game.projectile.ServerProjectile;
import com.smashprofs.server.room.GameRoom;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.Assert.*;

/** Audit bằng World native và tick thủ công; không sleep, socket hoặc renderer. */
public class MatchRuntimeGameplayIntegrationTest {
    private static final float EPSILON = 0.0001f;
    private final List<Probe> probes = new ArrayList<>();
    private final List<FixedTickScheduler> schedulers = new ArrayList<>();
    private static final String[] CHARACTERS = {"Alex", "Leo", "Luca", "Maurice", "Viktor"};

    @After
    public void closeRuntimes() {
        for (FixedTickScheduler scheduler : schedulers) { scheduler.stop(); }
        for (Probe probe : probes) { probe.runtime.close(); }
    }

    @Test
    public void deadPlayerCannotApplyFreshMovement() {
        Probe probe = create("Alex", "Viktor");
        probe.place(0, 5, 3);
        probe.player(0).applyDamage(100);
        probe.send(0, 1, false, false, false, true, false);
        probe.runtime.tick();
        assertEquals(0, probe.body(0).getLinearVelocity().x, EPSILON);
        assertFalse(probe.player(0).isBlocking());
    }

    @Test
    public void deadPlayerCannotJumpFromGround() {
        Probe probe = create("Alex", "Viktor");
        probe.land(0);
        probe.player(0).applyDamage(100);
        probe.send(0, 0, true, false, false, false, false);
        probe.runtime.tick();
        assertEquals(0, probe.runtime.getJumpState(probe.id(0)).jumpsUsed());
        assertTrue(probe.runtime.getJumpState(probe.id(0)).grounded());
    }

    @Test
    public void deathClearsPreviouslyHeldMovement() {
        Probe probe = create("Alex", "Viktor");
        probe.place(0, 5, 3);
        probe.send(0, 1, false, false, false, false, false);
        probe.runtime.tick();
        assertTrue(probe.body(0).getLinearVelocity().x > 0);
        probe.player(0).applyDamage(100);
        // Xóa vận tốc fixture để đo riêng impulse từ held input cũ, không chờ timeout.
        probe.body(0).setLinearVelocity(0, 0);
        probe.runtime.tick();
        assertEquals(0, probe.body(0).getLinearVelocity().x, EPSILON);
    }

    @Test
    public void deadPlayerCannotUseRemainingDoubleJump() {
        Probe probe = create("Alex", "Viktor");
        probe.land(0);
        probe.send(0, 0, true, false, false, false, false);
        probe.runtime.tick();
        assertEquals(1, probe.runtime.getJumpState(probe.id(0)).jumpsUsed());
        float vy = probe.body(0).getLinearVelocity().y;
        probe.player(0).applyDamage(100);
        probe.send(0, 0, true, false, false, false, false);
        probe.runtime.tick();
        assertEquals(1, probe.runtime.getJumpState(probe.id(0)).jumpsUsed());
        assertEquals(vy - 0.098f, probe.body(0).getLinearVelocity().y, EPSILON);
    }

    @Test
    public void inputQueuedBeforeDeathIsConsumedWithoutApplyingActions() {
        Probe probe = create("Viktor", "Alex", "Luca"); // Chưa FINISHED để vẫn kiểm tra sequence watermark.
        probe.place(0, 5, 3);
        probe.place(1, 5.15f, 3);
        PlayerInput input = probe.send(0, 1, true, true, true, true, true);
        probe.player(0).applyDamage(100);
        assertEquals(List.of(input), probe.runtime.tick());
        assertEquals(InputQueue.OfferResult.DUPLICATE_OR_STALE, probe.runtime.submitInput(input));
        assertEquals(0, probe.body(0).getLinearVelocity().x, EPSILON);
        assertEquals(100, probe.player(1).getHp(), EPSILON);
        assertEquals(0, probe.runtime.getActiveProjectileCount());
        assertFalse(probe.runtime.getStompState(probe.id(0)).active());
        assertFalse(probe.player(0).isBlocking());
    }

    @Test
    public void lethalMeleeClearsVictimsHeldMovementOnFollowingTick() {
        Probe probe = create("Viktor", "Alex", "Luca");
        probe.place(0, 5, 3);
        probe.place(1, 5.15f, 3);
        probe.player(1).applyDamage(90);
        probe.send(1, 1, false, false, false, false, false);
        probe.send(0, 0, false, false, true, false, false);
        probe.runtime.tick();
        assertTrue(probe.player(1).isDead());
        assertEquals(0, probe.player(1).getHp(), EPSILON);
        probe.place(1, 6, 3);
        probe.runtime.tick();
        assertEquals(0, probe.body(1).getLinearVelocity().x, EPSILON);
        probe.send(0, 1, false, false, false, false, false);
        probe.runtime.tick();
        assertTrue("Player sống vẫn nhận movement", probe.body(0).getLinearVelocity().x > 0);
    }

    @Test
    public void deadBodyRetainsPassiveMotionAndNativeIdentity() {
        Probe probe = create("Alex", "Viktor");
        probe.place(0, 5, 3);
        Body body = probe.body(0);
        body.setLinearVelocity(0.4f, 0.2f);
        probe.player(0).applyDamage(100);
        probe.send(0, -1, true, true, true, true, true);
        probe.runtime.tick();
        assertSame(body, probe.body(0));
        assertTrue(body.isActive());
        assertEquals(20, probe.physics.getWorld().getBodyCount());
        assertEquals(0.4f, body.getLinearVelocity().x, EPSILON);
        assertEquals(0.102f, body.getLinearVelocity().y, EPSILON);
        assertTrue(body.getPosition().x > 5);
        assertEquals(0, probe.player(0).getHp(), EPSILON);
    }

    @Test
    public void deathInputGateIsIndependentOfCharacterAndSlotInTwoToFourPlayers() {
        for (int count = 2; count <= 4; count++) {
            for (String character : CHARACTERS) {
                String[] roster = new String[count];
                java.util.Arrays.fill(roster, character);
                for (int victim = 0; victim < count; victim++) {
                    Probe probe = create(roster);
                    probe.airborne();
                    probe.player(victim).applyDamage(100);
                    probe.send(victim, 1, true, true, true, true, true);
                    int survivor = (victim + 1) % count;
                    probe.send(survivor, 1, false, false, false, false, false);
                    probe.runtime.tick();
                    assertEquals(0, probe.body(victim).getLinearVelocity().x, EPSILON);
                    assertEquals(0, probe.runtime.getJumpState(probe.id(victim)).jumpsUsed());
                    assertEquals(0, probe.runtime.getActiveProjectileCount());
                    assertFalse(probe.player(victim).isBlocking());
                    assertEquals(0.055f, probe.body(survivor).getLinearVelocity().x, EPSILON);
                }
            }
        }
    }

    @Test
    public void rocketKeepsFollowingDeadTargetsPassiveBody() {
        Probe probe = create("Alex", "Viktor", "Leo");
        probe.place(0, 5, 3);
        probe.place(1, 7, 3);
        probe.place(2, 8, 8);
        probe.send(0, 0, false, false, false, false, true);
        probe.runtime.tick();
        ServerProjectile model = probe.projectileModel(probe.runtime.getProjectiles().get(0).projectileId());
        assertEquals(probe.id(1), model.getTargetPlayerId());
        Body target = probe.body(1);
        probe.player(1).applyDamage(100);
        target.setLinearVelocity(0.2f, 0);
        probe.send(1, -1, true, false, false, false, false);
        probe.runtime.tick();
        assertSame(target, probe.body(1));
        assertEquals(probe.id(1), model.getTargetPlayerId());
        var rocket = probe.runtime.getProjectiles().get(0);
        assertEquals(0.5f, (float) Math.hypot(rocket.velocityX(), rocket.velocityY()), EPSILON);
        assertEquals(0.2f, target.getLinearVelocity().x, EPSILON);
        assertTrue(target.getPosition().x > 7);
    }

    @Test
    public void twoPlayerMatchCanSimulateMovementAndCombat() {
        Probe probe = create("Alex", "Viktor");
        probe.airborne();
        float x = probe.body(0).getPosition().x;
        probe.send(0, 1, false, false, false, false, false);
        probe.send(1, -1, false, false, false, false, false);
        probe.runtime.tick();
        assertTrue(probe.body(0).getPosition().x > x);
        assertTrue(probe.body(1).getLinearVelocity().x < 0);
        probe.place(0, 5, 3);
        probe.place(1, 5.15f, 3);
        probe.send(0, 0, false, false, true, false, false);
        probe.send(1, 0, false, false, false, false, false);
        probe.runtime.tick();
        assertEquals(100, probe.player(0).getHp(), EPSILON);
        assertEquals(90, probe.player(1).getHp(), EPSILON);
        assertEquals(1.5f, probe.body(1).getLinearVelocity().x, EPSILON);
    }

    @Test
    public void threePlayerMatchMaintainsIndependentPlayerStates() { assertIndependentPlayers(3); }

    @Test
    public void fourPlayerMatchMaintainsIndependentPlayerStates() { assertIndependentPlayers(4); }

    private void assertIndependentPlayers(int count) {
        Probe probe = create(java.util.Arrays.copyOf(CHARACTERS, count));
        probe.airborne();
        probe.place(0, 5, 3);
        probe.place(1, 5.15f, 3);
        probe.send(0, 0, false, false, true, true, false);
        for (int i = 2; i < count; i++) { probe.send(i, -0.5f, false, false, false, false, false); }
        probe.runtime.tick();
        assertEquals(count, probe.runtime.getMatchState().getPlayerCount());
        assertTrue(probe.player(0).isBlocking());
        assertEquals(90, probe.player(1).getHp(), EPSILON);
        for (int i = 2; i < count; i++) {
            assertNotSame(probe.body(0), probe.body(i));
            assertEquals(100, probe.player(i).getHp(), EPSILON);
            assertFalse(probe.player(i).isBlocking());
            assertEquals(-0.0275f, probe.body(i).getLinearVelocity().x, EPSILON);
        }
    }

    @Test
    public void allFiveCharactersCanBeInitialized() {
        for (int count = 2; count <= 4; count++) {
            for (int offset = 0; offset < CHARACTERS.length; offset++) {
                AtomicLong clock = new AtomicLong();
                GameRoom room = new GameRoom("audit-lobby-" + count + "-" + offset, clock::get);
                for (int role = 0; role < count; role++) {
                    PlayerId id = new PlayerId("audit-player-" + role);
                    assertEquals(GameRoom.JoinResult.JOINED, room.joinPlayer(id));
                    assertTrue(room.selectCharacter(id, CHARACTERS[(offset + role) % CHARACTERS.length]));
                    assertTrue(room.setReady(id, true));
                }
                assertTrue(room.startCountdown());
                clock.set(3_000_000_000L);
                assertTrue(room.updateCountdown());
                Probe probe = create(room.prepareMatch());
                assertEquals(18 + count, probe.physics.getWorld().getBodyCount());
                for (int role = 0; role < count; role++) {
                    assertEquals(room.getSelectedCharacter(probe.id(role)), probe.player(role).getCharacterId());
                    assertEquals(BodyDef.BodyType.DynamicBody, probe.body(role).getType());
                    assertEquals(probe.id(role), probe.body(role).getUserData());
                    assertTrue(probe.body(role).getPosition().x >= 3 && probe.body(role).getPosition().x <= 8.5f);
                    assertTrue(probe.body(role).getPosition().y > 0);
                }
                probe.runtime.tick();
                for (int role = 0; role < count; role++) { assertEquals(100, probe.player(role).getHp(), EPSILON); }
            }
        }
    }

    @Test
    public void selectedCharacterMapsToCorrectAbility() {
        String[] expected = {"ROCKET", "MINE", null, "STAR", "IBM"};
        for (int i = 0; i < CHARACTERS.length; i++) {
            Probe probe = create(CHARACTERS[i], "Luca");
            probe.airborne();
            probe.send(0, 0, false, false, false, false, true);
            probe.runtime.tick();
            if (expected[i] == null) {
                // GAP xác nhận: Luca không có Circle; không fake skill để báo COMPLETE.
                assertNull(ProjectileType.forCharacter("Luca"));
                assertEquals(0, probe.runtime.getActiveProjectileCount());
            } else {
                assertEquals(expected[i], ProjectileType.forCharacter(CHARACTERS[i]).name());
                assertEquals(i == 3 ? 4 : 1, probe.runtime.getActiveProjectileCount());
                for (var view : probe.runtime.getProjectiles()) {
                    assertEquals(expected[i], view.projectileType());
                    assertEquals(probe.id(0), view.ownerPlayerId());
                }
            }
        }
    }

    @Test
    public void allFiveCharactersShareImplementedGroundJumpAndFallMechanics() {
        for (String character : CHARACTERS) {
            Probe probe = create(character, "Luca");
            probe.land(0);
            probe.send(0, 1, true, false, false, false, false);
            probe.runtime.tick();
            assertEquals(1, probe.runtime.getJumpState(probe.id(0)).jumpsUsed());
            assertTrue(probe.body(0).getLinearVelocity().x > 0);
            assertTrue(probe.body(0).getLinearVelocity().y > 0);
            probe.send(0, 0, true, false, false, false, false);
            probe.runtime.tick();
            assertEquals(2, probe.runtime.getJumpState(probe.id(0)).jumpsUsed());
            for (int i = 0; i < 180 && !probe.runtime.getJumpState(probe.id(0)).grounded(); i++) { probe.runtime.tick(); }
            assertTrue(probe.runtime.getJumpState(probe.id(0)).grounded());
            assertEquals(0, probe.runtime.getJumpState(probe.id(0)).jumpsUsed());
            probe.place(0, 3.1f, -0.1f);
            probe.runtime.tick();
            assertEquals(90, probe.player(0).getHp(), EPSILON);
            assertEquals(2.6f, probe.body(0).getLinearVelocity().y, EPSILON);
        }
    }

    @Test
    public void meleeAndBlockingInteractCorrectly() {
        for (String character : CHARACTERS) {
            Probe probe = create(character, character);
            probe.place(0, 5, 3);
            probe.place(1, 5.15f, 3);
            probe.send(1, 0, false, false, false, true, false);
            probe.send(0, 0, false, false, true, false, false);
            probe.runtime.tick();
            assertTrue(probe.player(1).isBlocking());
            assertEquals(100, probe.player(1).getHp(), EPSILON);
            assertEquals(0, probe.body(1).getLinearVelocity().x, EPSILON);
            for (int i = 0; i < 8; i++) { probe.runtime.tick(); }
            probe.place(0, 5, 3);
            probe.place(1, 5.15f, 3);
            probe.send(1, 0, false, false, false, false, false);
            probe.send(0, 0, false, false, true, false, false);
            probe.runtime.tick();
            assertEquals(90, probe.player(1).getHp(), EPSILON);
        }
    }

    @Test
    public void meleeAndKnockbackInteractCorrectly() {
        for (String character : CHARACTERS) {
            Probe probe = create(character, character);
            probe.place(0, 5, 3);
            probe.place(1, 5.15f, 3);
            probe.send(0, 0, false, false, true, false, false);
            probe.runtime.tick();
            assertEquals(90, probe.player(1).getHp(), EPSILON);
            assertEquals(1.5f, probe.body(1).getLinearVelocity().x, EPSILON);
            assertEquals(0.004f, probe.body(1).getLinearVelocity().y, EPSILON);
            assertEquals(100, probe.player(0).getHp(), EPSILON);
        }
    }

    @Test
    public void jumpAndStompDoNotConflict() {
        for (String character : CHARACTERS) {
            Probe probe = create(character, "Luca");
            probe.land(0);
            probe.send(0, 0, true, true, false, false, false);
            probe.runtime.tick();
            assertEquals(1, probe.runtime.getJumpState(probe.id(0)).jumpsUsed());
            assertEquals(100, probe.player(0).getHp(), EPSILON);
            assertFalse(probe.runtime.getStompState(probe.id(0)).active());
            probe.send(0, 0, false, true, false, false, false);
            probe.runtime.tick();
            assertEquals(99.9f, probe.player(0).getHp(), EPSILON);
            assertTrue(probe.runtime.getStompState(probe.id(0)).active()
                    || probe.runtime.getStompState(probe.id(0)).pendingLanding());
        }
    }

    @Test
    public void allFiveCharactersCanLandStompDamageAgainstBlockingTarget() {
        for (String character : CHARACTERS) {
            Probe probe = create(character, "Luca");
            probe.land(0);
            float floorY = probe.body(0).getPosition().y;
            probe.place(0, 4.5f, floorY + 0.3f);
            probe.place(1, 4.6f, floorY);
            probe.runtime.tick();
            assertFalse(probe.runtime.getJumpState(probe.id(0)).grounded());
            probe.send(0, 0, false, true, false, false, false);
            probe.send(1, 0, false, false, false, true, false);
            for (int i = 0; i < 120 && !probe.runtime.getStompState(probe.id(0)).pendingLanding(); i++) {
                probe.runtime.tick();
            }
            assertTrue(probe.runtime.getStompState(probe.id(0)).pendingLanding());
            assertEquals(100, probe.player(1).getHp(), EPSILON);
            probe.runtime.tick();
            assertEquals(90, probe.player(1).getHp(), EPSILON);
            assertEquals(99.9f, probe.player(0).getHp(), EPSILON);
            assertEquals(2, Math.abs(probe.body(1).getLinearVelocity().x), EPSILON);
            probe.runtime.tick();
            assertEquals(90, probe.player(1).getHp(), EPSILON);
        }
    }

    @Test
    public void implementedAbilitiesUseIndependentServerTickCooldownsWithoutAutoFire() {
        String[] characters = {"Alex", "Leo", "Maurice", "Viktor"};
        int[] cooldowns = {180, 300, 45, 180};
        for (int type = 0; type < characters.length; type++) {
            Probe probe = create(characters[type], "Luca");
            probe.place(0, 5, 100);
            probe.place(1, 7, 100);
            probe.send(0, 0, false, false, false, false, true);
            probe.runtime.tick();
            int burst = type == 2 ? 4 : 1;
            assertEquals(burst, probe.runtime.getActiveProjectileCount());
            probe.send(0, 0, false, false, false, false, true);
            probe.runtime.tick();
            assertFalse(probe.runtime.getProjectiles().stream().anyMatch(view -> view.spawnTick() == 1));
            while (probe.runtime.getMatchState().getServerTick() < cooldowns[type]) {
                // Giữ owner/đối thủ sống ở vùng trống để cooldown không bị lẫn với fall/death/contact.
                probe.place(0, 5, 100);
                probe.place(1, 7, 100);
                probe.runtime.tick();
            }
            assertTrue(probe.runtime.getProjectiles().stream().allMatch(view -> view.spawnTick() == 0));
            long tick = probe.runtime.getMatchState().getServerTick();
            probe.send(0, 0, false, false, false, false, true);
            probe.runtime.tick();
            assertEquals(burst, probe.runtime.getProjectiles().stream().filter(view -> view.spawnTick() == tick).count());
        }
    }

    @Test
    public void nativeProjectileContactDamagesBlockingTargetExactlyOnce() {
        for (String character : new String[]{"Alex", "Leo", "Maurice", "Viktor"}) {
            Probe probe = create(character, "Luca");
            probe.place(0, 5, 3);
            probe.place(1, 7, 3);
            probe.send(0, 0, false, false, false, false, true);
            probe.send(1, 0, false, false, false, true, false);
            probe.runtime.tick();
            long id = probe.runtime.getProjectiles().get(0).projectileId();
            ServerProjectile model = probe.projectileModel(id);
            for (int i = 0; i < 8 && model.isActive(); i++) {
                var view = probe.runtime.getProjectiles().stream()
                        .filter(projectile -> projectile.projectileId() == id).findFirst().orElseThrow();
                probe.place(1, view.x(), view.y());
                probe.runtime.tick();
            }
            assertFalse("Native contact phải kết thúc projectile trong 8 ticks", model.isActive());
            assertEquals(100, probe.player(1).getHp(), EPSILON);
            assertTrue(probe.player(1).isBlocking());
            probe.place(0, 5, 3);
            probe.place(1, 7, 3);
            probe.runtime.tick();
            assertEquals(90, probe.player(1).getHp(), EPSILON);
            assertEquals(1.5f, probe.body(1).getLinearVelocity().x, EPSILON);
            assertEquals(1.804f, probe.body(1).getLinearVelocity().y, EPSILON);
            probe.runtime.tick();
            assertEquals(90, probe.player(1).getHp(), EPSILON);
        }
    }

    @Test
    public void projectileAndMeleeCanCoexist() {
        Probe probe = create("Viktor", "Alex");
        probe.place(0, 5, 3);
        probe.place(1, 4.85f, 3);
        probe.send(0, 0, false, false, true, false, true);
        probe.runtime.tick();
        assertEquals(90, probe.player(1).getHp(), EPSILON);
        assertEquals(1, probe.runtime.getActiveProjectileCount());
        assertEquals("IBM", probe.runtime.getProjectiles().get(0).projectileType());
        probe.runtime.tick();
        assertEquals(90, probe.player(1).getHp(), EPSILON);
        assertEquals(0.7f, probe.runtime.getProjectiles().get(0).velocityX(), EPSILON);
    }

    @Test
    public void multipleProjectilesCanCoexist() {
        Probe probe = create("Alex", "Leo", "Maurice", "Viktor");
        probe.airborne();
        for (int i = 0; i < 4; i++) { probe.send(i, 0, false, false, false, false, true); }
        probe.runtime.tick();
        assertEquals(7, probe.runtime.getActiveProjectileCount());
        assertEquals(29, probe.physics.getWorld().getBodyCount());
        probe.runtime.tick();
        assertEquals(7, probe.runtime.getActiveProjectileCount());
        assertEquals(7, probe.runtime.getProjectiles().stream().map(ServerProjectile.View::projectileId).distinct().count());
        for (int i = 0; i < 4; i++) { assertEquals(100, probe.player(i).getHp(), EPSILON); }
    }

    @Test
    public void fallDamageAndCombatDamageRemainConsistent() {
        Probe probe = create("Viktor", "Alex");
        probe.place(0, 3.1f, -0.1f);
        probe.place(1, 3.2f, -0.1f);
        probe.send(0, 0, false, true, true, false, false);
        probe.runtime.tick();
        assertEquals(89.9f, probe.player(0).getHp(), EPSILON);
        assertEquals(80, probe.player(1).getHp(), EPSILON);
        assertFalse(probe.runtime.getStompState(probe.id(0)).active());
        probe.runtime.tick();
        assertEquals(89.9f, probe.player(0).getHp(), EPSILON);
        assertEquals(80, probe.player(1).getHp(), EPSILON);
    }

    @Test
    public void duplicateInputDoesNotDuplicateActions() {
        Probe probe = create("Viktor", "Alex");
        probe.place(0, 5, 3);
        probe.place(1, 4.85f, 3);
        PlayerInput input = probe.send(0, 0, false, true, true, false, true);
        assertEquals(InputQueue.OfferResult.DUPLICATE_OR_STALE, probe.runtime.submitInput(input));
        assertEquals(1, probe.runtime.tick().size());
        assertEquals(99.9f, probe.player(0).getHp(), EPSILON);
        assertEquals(90, probe.player(1).getHp(), EPSILON);
        assertEquals(1, probe.runtime.getActiveProjectileCount());
        assertEquals(InputQueue.OfferResult.DUPLICATE_OR_STALE, probe.runtime.submitInput(input));
        assertTrue(probe.runtime.tick().isEmpty());
        assertEquals(99.9f, probe.player(0).getHp(), EPSILON);
    }

    @Test
    public void edgeActionsDoNotRepeatAcrossTicks() {
        Probe probe = create("Viktor", "Luca");
        probe.place(0, 5, 8);
        probe.place(1, 4.85f, 8);
        probe.send(0, 0, false, true, true, false, true);
        probe.runtime.tick();
        for (int i = 0; i < 12; i++) {
            // Giữ fixture trên không/gần nhau để melee giả ở tick sau cooldown sẽ bị phát hiện.
            probe.place(0, 5, 8);
            probe.place(1, 4.85f, 8);
            assertTrue(probe.runtime.tick().isEmpty());
        }
        assertEquals(99.9f, probe.player(0).getHp(), EPSILON);
        assertEquals(90, probe.player(1).getHp(), EPSILON);
        assertEquals(1, probe.runtime.getActiveProjectileCount());
        Probe jumper = create("Leo", "Luca");
        jumper.land(0);
        jumper.send(0, 0, true, false, false, false, false);
        jumper.runtime.tick();
        jumper.runtime.tick();
        assertEquals(1, jumper.runtime.getJumpState(jumper.id(0)).jumpsUsed());
    }

    @Test
    public void heldInputExpiresWithoutInventingEdges() {
        Probe probe = create("Viktor", "Luca");
        probe.airborne();
        probe.send(0, 0.5f, false, false, false, true, false);
        for (int i = 0; i < MatchRuntime.DEFAULT_MOVEMENT_TIMEOUT_TICKS; i++) { probe.runtime.tick(); }
        assertTrue(probe.player(0).isBlocking());
        probe.body(0).setLinearVelocity(0, 0);
        assertTrue(probe.runtime.tick().isEmpty());
        assertFalse(probe.player(0).isBlocking());
        assertEquals(0, probe.body(0).getLinearVelocity().x, EPSILON);
        assertEquals(0, probe.runtime.getActiveProjectileCount());
        assertEquals(100, probe.player(0).getHp(), EPSILON);
    }

    @Test
    public void slotPermutationDoesNotChangeEquivalentCombat() {
        for (int count = 2; count <= 4; count++) {
            for (int shift = 0; shift < count; shift++) {
                int[] slots = new int[count];
                String[] roster = new String[count];
                for (int i = 0; i < count; i++) { slots[i] = (i + shift) % count; roster[i] = "Viktor"; }
                for (boolean mirror : new boolean[]{false, true}) {
                    Probe probe = create(slots, roster);
                    probe.airborne();
                    probe.place(0, 5, 3);
                    probe.place(1, mirror ? 4.85f : 5.15f, 3);
                    probe.send(0, 0, false, false, true, false, false);
                    probe.runtime.tick();
                    assertEquals(90, probe.player(1).getHp(), EPSILON);
                    assertEquals(mirror ? -1.5f : 1.5f, probe.body(1).getLinearVelocity().x, EPSILON);
                    assertEquals(0.004f, probe.body(1).getLinearVelocity().y, EPSILON);
                    for (int i = 2; i < count; i++) { assertEquals(100, probe.player(i).getHp(), EPSILON); }
                }
            }
        }
    }

    @Test
    public void roomIsolationIsPreserved() {
        Probe a = create("Viktor", "Alex");
        Probe b = create("Viktor", "Alex");
        assertEquals(a.id(0), b.id(0)); // Cùng PlayerId vẫn không chia sẻ state/cooldown/queue.
        assertNotSame(a.physics.getWorld(), b.physics.getWorld());
        assertNotSame(a.body(0), b.body(0));
        a.place(0, 5, 3); a.place(1, 4.85f, 3);
        b.place(0, 5, 3); b.place(1, 4.85f, 3);
        PlayerInput packet = a.send(0, 0, false, false, true, true, true);
        a.runtime.tick();
        assertEquals(90, a.player(1).getHp(), EPSILON);
        assertEquals(100, b.player(1).getHp(), EPSILON);
        assertEquals(0, b.runtime.getMatchState().getServerTick());
        assertEquals(1, a.runtime.getActiveProjectileCount());
        assertEquals(0, b.runtime.getActiveProjectileCount());
        assertEquals(InputQueue.OfferResult.ACCEPTED, b.runtime.submitInput(packet));
        b.runtime.tick();
        assertEquals(90, b.player(1).getHp(), EPSILON);
        assertEquals(1, b.runtime.getActiveProjectileCount());
        assertEquals(1, a.runtime.getMatchState().getServerTick());
        assertNotSame(a.projectileModel(1), b.projectileModel(1));
        a.player(0).applyDamage(100);
        assertFalse(b.player(0).isDead());
        assertTrue(b.player(0).isBlocking());
    }

    @Test
    public void closingOneMatchDoesNotAffectAnother() {
        Probe a = create("Viktor", "Alex");
        Probe b = create("Viktor", "Alex");
        a.airborne(); b.airborne();
        a.send(0, 0, false, false, false, false, true);
        b.send(0, 0, false, false, false, false, true);
        ManualScheduledExecutor executor = new ManualScheduledExecutor();
        FixedTickScheduler scheduler = new FixedTickScheduler(executor, Logger.getAnonymousLogger());
        schedulers.add(scheduler);
        assertTrue(scheduler.register(a.runtime));
        assertTrue(scheduler.register(b.runtime));
        scheduler.start();
        executor.runCycle();
        a.runtime.close();
        executor.runCycle();
        executor.runCycle();
        assertEquals(1, a.steps);
        assertTrue(a.physics.isDisposed());
        assertEquals(3, b.steps);
        assertEquals(3, b.runtime.getMatchState().getServerTick());
        assertFalse(b.physics.isDisposed());
        assertEquals(1, b.runtime.getActiveProjectileCount());
    }

    @Test
    public void resourcesAreDisposedSafely() {
        Probe probe = create("Alex", "Leo", "Maurice", "Viktor");
        probe.airborne();
        for (int i = 0; i < 4; i++) { probe.send(i, 0, false, false, false, true, true); }
        probe.runtime.tick();
        List<ServerProjectile> models = probe.runtime.getProjectiles().stream()
                .map(view -> probe.projectileModel(view.projectileId())).toList();
        assertEquals(7, models.size());
        assertThrows(UnsupportedOperationException.class, () -> probe.runtime.getProjectiles().clear());
        probe.runtime.close();
        probe.runtime.close();
        assertTrue(probe.physics.isDisposed());
        assertTrue(probe.runtime.isPhysicsDisposed());
        for (ServerProjectile model : models) { assertFalse(model.isActive()); }
        for (int i = 0; i < 4; i++) { assertFalse(probe.player(i).isBlocking()); }
        assertThrows(IllegalStateException.class, probe.runtime::tick);
        assertThrows(IllegalStateException.class, () -> probe.runtime.submitInput(
                probe.input(0, 0, false, false, false, false, false)));
    }

    @Test
    public void worldStepsExactlyOncePerServerTick() {
        Probe probe = create("Alex", "Leo", "Maurice", "Viktor");
        probe.airborne();
        for (int i = 0; i < 4; i++) { probe.send(i, 1, true, true, true, true, true); }
        for (int tick = 1; tick <= 20; tick++) {
            probe.runtime.tick();
            assertEquals(tick, probe.steps);
            assertEquals(tick, probe.runtime.getMatchState().getServerTick());
        }
    }

    @Test
    public void serverTickIsMonotonic() {
        Probe probe = create("Alex", "Viktor");
        long previous = probe.runtime.getMatchState().getServerTick();
        for (int i = 0; i < 60; i++) {
            probe.runtime.tick();
            assertEquals(previous + 1, probe.runtime.getMatchState().getServerTick());
            previous = probe.runtime.getMatchState().getServerTick();
        }
        probe.runtime.close();
        assertThrows(IllegalStateException.class, probe.runtime::tick);
        assertEquals(previous, probe.runtime.getMatchState().getServerTick());
    }

    private Probe create(String... characters) {
        int[] slots = new int[characters.length];
        for (int i = 0; i < slots.length; i++) { slots[i] = i; }
        return create(slots, characters);
    }

    private Probe create(int[] slots, String... characters) {
        List<MatchSetup.Participant> participants = new ArrayList<>();
        for (int i = 0; i < characters.length; i++) {
            participants.add(new MatchSetup.Participant(new PlayerId("audit-player-" + i), slots[i], characters[i]));
        }
        return create(new MatchSetup(UUID.randomUUID().toString(), UUID.randomUUID().toString(), participants));
    }

    private Probe create(MatchSetup setup) {
        Probe probe = new Probe(setup);
        probes.add(probe);
        probe.runtime.start();
        return probe;
    }

    /** Chỉ test giữ native World qua seam đã có; không thêm mutable getter vào production. */
    private static final class Probe {
        private final MatchRuntime runtime;
        private final MatchSetup setup;
        private final long[] sequences;
        private HeadlessPhysicsWorld physics;
        private int steps;

        private Probe(MatchSetup setup) {
            this.setup = setup;
            sequences = new long[setup.participants().size()];
            runtime = new MatchRuntime(setup, () -> {
                physics = new PhysicsWorldFactory().create();
                return physics;
            }, (world, dt, velocityIterations, positionIterations) -> {
                assertEquals(1.0f / 60, dt, EPSILON);
                assertEquals(6, velocityIterations);
                assertEquals(2, positionIterations);
                steps++;
                world.step(dt, velocityIterations, positionIterations);
            });
        }

        private PlayerId id(int role) { return setup.participants().get(role).playerId(); }
        private ServerPlayer player(int role) { return runtime.getMatchState().getPlayer(id(role)); }

        private Body body(int role) {
            Array<Body> bodies = new Array<>();
            physics.getWorld().getBodies(bodies);
            for (Body body : bodies) {
                if (id(role).equals(body.getUserData())) { return body; }
            }
            throw new AssertionError("Không tìm thấy Body cho " + id(role));
        }

        private void place(int role, float x, float y) {
            body(role).setTransform(x, y, 0);
            body(role).setLinearVelocity(0, 0);
        }

        private void airborne() {
            for (int role = 0; role < sequences.length; role++) { place(role, 5 + role * 0.8f, 3 + role * 2); }
        }

        private ServerProjectile projectileModel(long id) {
            Array<Body> bodies = new Array<>();
            physics.getWorld().getBodies(bodies);
            for (Body body : bodies) {
                if (body.getUserData() instanceof ServerProjectile projectile
                        && projectile.getProjectileId() == id) { return projectile; }
            }
            throw new AssertionError("Không tìm thấy projectile " + id);
        }

        private void land(int role) {
            place(role, 4.5f, 0.85f);
            for (int i = 0; i < 180 && !runtime.getJumpState(id(role)).grounded(); i++) {
                runtime.tick();
            }
            assertTrue("Phải chạm platform TMX thật", runtime.getJumpState(id(role)).grounded());
        }

        private PlayerInput input(int role, float axis, boolean jump, boolean stomp,
                                  boolean melee, boolean block, boolean ability) {
            return new PlayerInput(id(role), sequences[role]++, 0, axis, jump, stomp, melee, block, ability);
        }

        private PlayerInput send(int role, float axis, boolean jump, boolean stomp,
                                 boolean melee, boolean block, boolean ability) {
            PlayerInput input = input(role, axis, jump, stomp, melee, block, ability);
            assertEquals(InputQueue.OfferResult.ACCEPTED, runtime.submitInput(input));
            return input;
        }
    }
}
