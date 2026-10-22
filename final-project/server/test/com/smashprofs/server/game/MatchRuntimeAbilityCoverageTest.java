package com.smashprofs.server.game;

import com.badlogic.gdx.physics.box2d.*;
import com.badlogic.gdx.utils.Array;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import com.smashprofs.server.game.physics.HeadlessPhysicsWorld;
import com.smashprofs.server.game.physics.PhysicsWorldFactory;
import com.smashprofs.server.game.projectile.ProjectileManager;
import com.smashprofs.server.game.projectile.ProjectileType;
import com.smashprofs.server.game.projectile.ServerProjectile;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.smashprofs.server.game.InputQueue.OfferResult.*;
import static org.junit.Assert.*;

/** Kiểm thử gameplay bằng World/TMX/Box2D thật và manual tick; không Desktop, sleep hoặc hit giả. */
public class MatchRuntimeAbilityCoverageTest {
    private static final float EPS = 0.0001f;
    private final List<MatchRuntime> runtimes = new ArrayList<>();

    @After public void closeResources() { runtimes.forEach(MatchRuntime::close); }

    @Test public void allFiveCharactersAreAudited() {
        // Bảng khảo sát Bước 37 và cập nhật Bước 38; test giữ mapping/coverage executable.
        Map<String, String> coverage = Map.of("Alex", "COMPLETE", "Leo", "COMPLETE", "Luca", "MISSING",
                "Maurice", "COMPLETE", "Viktor", "COMPLETE");
        assertEquals(5, coverage.size());
        for (var character : coverage.entrySet()) {
            ServerPlayer player = new ServerPlayer(id(0), character.getKey(), 0);
            assertEquals(character.getValue().equals("COMPLETE"),
                    ProjectileType.forCharacter(player.getCharacterId()) != null);
        }
    }

    @Test public void abilityResolverUsesCorrectCharacter() {
        assertEquals(ProjectileType.MINE, ProjectileType.forCharacter("Leo"));
        assertEquals(ProjectileType.IBM, ProjectileType.forCharacter("Viktor"));
        assertEquals(ProjectileType.STAR, ProjectileType.forCharacter("Maurice"));
        assertEquals(ProjectileType.ROCKET, ProjectileType.forCharacter("Alex"));
        Probe p = create("Leo", "Maurice", "Viktor");
        for (int slot = 2; slot >= 0; slot--) { sendAbility(p, slot, 0); }
        p.runtime.tick();
        String[] types = {"MINE", "STAR", "IBM"};
        int[] counts = {1, 4, 1};
        for (int slot = 0; slot < 3; slot++) {
            final int owner = slot;
            var owned = p.runtime.getProjectiles().stream().filter(v -> v.ownerPlayerId().equals(id(owner))).toList();
            assertEquals(counts[slot], owned.size());
            for (var view : owned) { assertEquals(types[slot], view.projectileType()); }
        }
    }

    @Test public void unknownCharacterCannotActivateAbility() {
        for (String value : new String[]{null, "", "Unknown", "leo"}) {
            assertNull(ProjectileType.forCharacter(value));
        }
        MatchRuntime invalid = new MatchRuntime(new MatchSetup("invalid", "invalid-room", List.of(
                new MatchSetup.Participant(id(0), 0, "Unknown"), new MatchSetup.Participant(id(1), 1, "Leo"))));
        runtimes.add(invalid);
        assertThrows(IllegalArgumentException.class, invalid::start);
        assertFalse(invalid.isStarted());
        Probe p = mineRoom();
        assertEquals(UNKNOWN_PLAYER, p.runtime.submitInput(input(9, 0, true, false, false, false)));
        p.runtime.tick();
        assertEquals(0, p.runtime.getActiveProjectileCount());
    }

    @Test public void wrongCharacterCannotUseOtherAbility() {
        for (String character : List.of("Alex", "Luca", "Maurice", "Viktor")) {
            Probe p = create(character, "Leo");
            ability(p, 0, 0);
            assertTrue(views(p, "MINE").isEmpty());
            if (character.equals("Luca")) {
                assertEquals(0, p.runtime.getActiveProjectileCount());
            } else if (character.equals("Alex")) {
                assertEquals(1, views(p, "ROCKET").size());
            }
        }
    }

    @Test public void validAbilityCanBeActivated() {
        Probe p = mineRoom();
        int originalBodies = p.world().getBodyCount();
        ability(p, 0, 0);
        assertEquals(1, p.runtime.getActiveProjectileCount());
        assertEquals(originalBodies + 1, p.world().getBodyCount());
        var mine = mine(p);
        assertEquals("MINE", mine.projectileType());
        assertEquals(id(0), mine.ownerPlayerId());
        assertEquals(p.runtime.getMatchId(), mine.matchId());
        assertEquals(0, mine.spawnTick());
    }

    @Test public void abilityActivationMatchesOriginalRules() {
        Probe air = mineRoom();
        assertFalse(air.runtime.getJumpState(id(0)).grounded());
        ability(air, 0, 0);
        assertEquals(1, views(air, "MINE").size());
        assertEquals(100, air.player(0).getHp(), EPS);
        Probe ground = mineRoom();
        position(ground, 0, 4.5f, 0.85f);
        position(ground, 1, 7.25f, 0.5f);
        for (int tick = 0; tick < 180 && !ground.runtime.getJumpState(id(0)).grounded(); tick++) {
            ground.runtime.tick();
        }
        assertTrue(ground.runtime.getJumpState(id(0)).grounded());
        ability(ground, 0, 0);
        assertEquals(1, views(ground, "MINE").size());
        assertEquals(100, ground.player(0).getHp(), EPS);
    }

    @Test public void deadPlayerCannotActivateMine() {
        Probe p = mineRoom();
        p.player(0).applyDamage(100);
        ability(p, 0, 0);
        assertEquals(0, p.runtime.getActiveProjectileCount());
    }

    @Test public void abilityCooldownMatchesOriginal() {
        assertEquals(300, ProjectileManager.MINE_COOLDOWN_TICKS);
        Probe p = mineRoom();
        ability(p, 0, 0);
        for (int tick = 1; tick < 299; tick++) { keepPlayersInAir(p); p.runtime.tick(); }
        keepPlayersInAir(p);
        ability(p, 0, 1); // Tick 299 chưa được phép.
        assertEquals(1, views(p, "MINE").size());
        keepPlayersInAir(p);
        ability(p, 0, 2); // Tick 300 được phép; request bị từ chối không kéo dài cooldown.
        assertEquals(2, views(p, "MINE").size());
        assertEquals(List.of(0L, 300L), views(p, "MINE").stream().map(ServerProjectile.View::spawnTick).toList());
    }

    @Test public void abilityCannotBeSpammed() {
        Probe p = mineRoom();
        ability(p, 0, 0);
        for (int tick = 1; tick < 300; tick++) {
            keepPlayersInAir(p);
            ability(p, 0, tick);
            assertEquals(1, views(p, "MINE").size());
        }
        keepPlayersInAir(p);
        ability(p, 0, 300);
        assertEquals(2, views(p, "MINE").size());
    }

    @Test public void duplicateInputDoesNotDoubleActivate() {
        Probe p = mineRoom();
        sendAbility(p, 0, 0);
        assertEquals(DUPLICATE_OR_STALE, p.runtime.submitInput(input(0, 0, true, false, false, false)));
        p.runtime.tick();
        assertEquals(1, views(p, "MINE").size());
        for (int tick = 1; tick <= 300; tick++) { keepPlayersInAir(p); p.runtime.tick(); }
        assertEquals(DUPLICATE_OR_STALE, p.runtime.submitInput(input(0, 0, true, false, false, false)));
        p.runtime.tick();
        assertEquals(1, views(p, "MINE").size());
    }

    @Test public void multipleAbilityEdgesInOneBatchProduceOnlyOneMine() {
        Probe p = mineRoom();
        for (long sequence = 0; sequence < 8; sequence++) { sendAbility(p, 0, sequence); }
        assertEquals(0, p.runtime.getActiveProjectileCount());
        assertEquals(1, p.runtime.tick().size());
        assertEquals(1, views(p, "MINE").size());
    }

    @Test public void mineHitDoesNotResetOwnersCooldown() {
        Probe p = contactScene(2, 0, 1, false);
        resolve(p, 0, 1, false, false);
        keepPlayersInAir(p);
        ability(p, 0, 1);
        assertEquals(0, views(p, "MINE").size());
        while (p.runtime.getMatchState().getServerTick() < 300) { keepPlayersInAir(p); p.runtime.tick(); }
        keepPlayersInAir(p);
        ability(p, 0, 2);
        assertEquals(1, views(p, "MINE").size());
        assertEquals(300, mine(p).spawnTick());
    }

    @Test public void twoMineOwnersKeepDifferentCooldownDeadlines() {
        Probe p = create("Leo", "Leo");
        ability(p, 0, 0); // Cho phép lại từ tick 300.
        ability(p, 1, 0); // Cho phép lại từ tick 301.
        while (p.runtime.getMatchState().getServerTick() < 300) { keepPlayersInAir(p); p.runtime.tick(); }
        keepPlayersInAir(p);
        sendAbility(p, 1, 1);
        sendAbility(p, 0, 1);
        p.runtime.tick();
        assertEquals(3, views(p, "MINE").size());
        assertEquals(2, views(p, "MINE").stream().filter(v -> v.ownerPlayerId().equals(id(0))).count());
        assertEquals(1, views(p, "MINE").stream().filter(v -> v.ownerPlayerId().equals(id(1))).count());
        keepPlayersInAir(p);
        ability(p, 1, 2);
        assertEquals(4, views(p, "MINE").size());
        assertEquals(301, views(p, "MINE").get(3).spawnTick());
    }

    @Test public void cooldownExpiryWithoutNewInputDoesNotAutoActivate() {
        Probe p = mineRoom();
        ability(p, 0, 0);
        for (int tick = 1; tick <= 600; tick++) { keepPlayersInAir(p); p.runtime.tick(); }
        assertEquals(1, views(p, "MINE").size()); // Source không có lifetime tự hết hạn.
    }

    @Test public void abilityPhysicsMatchesOriginal() {
        Probe p = mineRoom();
        ability(p, 0, 0);
        var view = mine(p);
        Body body = p.projectile(view.projectileId());
        Fixture fixture = body.getFixtureList().first();
        assertEquals(BodyDef.BodyType.DynamicBody, body.getType());
        assertFalse(body.isFixedRotation());
        assertFalse(body.isBullet());
        assertEquals(0, body.getAngularVelocity(), EPS);
        assertEquals(Shape.Type.Circle, fixture.getShape().getType());
        assertEquals(0.05f, fixture.getShape().getRadius(), EPS);
        assertEquals(3, fixture.getFilterData().categoryBits);
        assertEquals((short) 0xffff, fixture.getFilterData().maskBits);
        assertEquals(0, fixture.getFilterData().groupIndex);
        assertFalse(fixture.isSensor());
        assertEquals(0, fixture.getDensity(), 0);
        assertEquals(1, body.getMass(), EPS);
        assertEquals(0.2f, fixture.getFriction(), EPS);
        assertEquals(0, fixture.getRestitution(), 0);
        assertEquals(0, p.world().getGravity().y, 0);
        assertEquals(5.15f, view.x(), EPS);
        assertEquals(3, view.y(), EPS);
        assertEquals(0, view.velocityX(), EPS);
        assertEquals(0, view.velocityY(), EPS); // Update trước spawn, chưa áp gravity ở tick tạo.
        p.runtime.tick();
        assertEquals(-0.098f, mine(p).velocityY(), EPS);
        assertEquals(3 - 0.098f / 60, mine(p).y(), EPS);
        p.runtime.tick();
        assertEquals(-0.196f, mine(p).velocityY(), EPS);
    }

    @Test public void mineGravityPreservesExistingVelocity() {
        Probe p = mineRoom();
        ability(p, 0, 0);
        p.projectile(mine(p).projectileId()).setLinearVelocity(0.3f, -0.5f);
        p.runtime.tick();
        assertEquals(0.3f, mine(p).velocityX(), EPS);
        assertEquals(-0.598f, mine(p).velocityY(), EPS);
    }

    @Test public void mineUsesAuthoritativeFacingAndRetainsItWhenStationary() {
        Probe p = mineRoom();
        p.body(0).setLinearVelocity(-0.3f, 0);
        ability(p, 0, 0);
        assertFalse(p.player(0).isFacingRight());
        assertEquals(4.85f, mine(p).x(), EPS);
        position(p, 0, 5, 3);
        p.runtime.tick();
        assertFalse(p.player(0).isFacingRight());
        assertEquals(0, mine(p).velocityX(), EPS); // Facing chỉ chọn offset, không phóng mìn ngang.
    }

    @Test public void abilityDamageMatchesOriginal() {
        Probe p = contactScene(2, 0, 1, false);
        assertEquals(100, p.player(1).getHp(), EPS); // Contact tick chưa consume hit.
        resolve(p, 0, 1, false, false);
        assertEquals(90, p.player(1).getHp(), EPS);
        assertEquals(10, ProjectileManager.MINE_DAMAGE, 0); // Không dùng damageOnHit=25 chưa được consume.
        assertEquals(100, p.player(0).getHp(), EPS);
    }

    @Test public void abilityHitDetectionMatchesOriginal() {
        Probe p = mineRoom();
        ability(p, 0, 0);
        position(p, 1, 5.35f, 3); // Ngoài tổng radius 0.05 + 0.05.
        p.runtime.tick();
        assertEquals(1, views(p, "MINE").size());
        assertEquals(100, p.player(1).getHp(), EPS);
        position(p, 1, mine(p).x(), mine(p).y());
        p.runtime.tick();
        assertEquals(0, views(p, "MINE").size());
        resolve(p, 0, 1, false, false);
        assertEquals(90, p.player(1).getHp(), EPS);
        for (int tick = 0; tick < 10; tick++) { p.runtime.tick(); }
        assertEquals(90, p.player(1).getHp(), EPS); // Không lặp lại contact/hit sau khi cleanup.
    }

    @Test public void minePersistsOnOriginalTmxGroundUntilAPlayerTouchesIt() {
        Probe p = mineRoom();
        AtomicInteger mapContacts = new AtomicInteger();
        p.afterStep.set(world -> {
            for (Contact contact : world.getContactList()) {
                if (!contact.isTouching()) { continue; }
                Object a = contact.getFixtureA().getBody().getUserData();
                Object b = contact.getFixtureB().getBody().getUserData();
                if ((a instanceof ServerProjectile && "Tile".equals(b))
                        || (b instanceof ServerProjectile && "Tile".equals(a))) { mapContacts.incrementAndGet(); }
            }
        });
        ability(p, 0, 0);
        long mineId = mine(p).projectileId();
        for (int tick = 0; tick < 150; tick++) { keepPlayersInAir(p); p.runtime.tick(); }
        assertTrue(mapContacts.get() > 0);
        assertEquals(mineId, mine(p).projectileId());
        assertEquals(0, mine(p).velocityY(), EPS);
        assertEquals(100, p.player(0).getHp(), EPS);
        assertEquals(100, p.player(1).getHp(), EPS);
        awaitMineContact(p, 1);
        p.runtime.tick();
        assertEquals(90, p.player(1).getHp(), EPS);
    }

    @Test public void mapContactWorksForTilesCreatedBeforeOrAfterMine() {
        for (boolean tileFirst : new boolean[]{true, false}) {
            Probe p = mineRoom();
            if (tileFirst) { tile(p, 5.15f, 3); }
            ability(p, 0, 0);
            if (!tileFirst) { tile(p, 5.15f, 3); p.runtime.tick(); }
            assertEquals(1, views(p, "MINE").size());
            p.runtime.tick();
            assertEquals(1, views(p, "MINE").size());
            assertEquals(100, p.player(1).getHp(), EPS);
        }
    }

    @Test public void mineContactWithAnotherProjectileDestroysBothWithoutAoe() {
        Probe p = create("Leo", "Viktor", "Alex");
        sendAbility(p, 0, 0);
        sendAbility(p, 1, 0);
        p.runtime.tick();
        var ibm = views(p, "IBM").get(0);
        for (int tick = 0; tick < 8 && p.runtime.getActiveProjectileCount() > 0; tick++) {
            var current = mine(p);
            p.projectile(ibm.projectileId()).setTransform(current.x(), current.y(), 0);
            p.runtime.tick();
        }
        assertEquals(0, p.runtime.getActiveProjectileCount());
        p.runtime.tick();
        for (int slot = 0; slot < 3; slot++) { assertEquals(100, p.player(slot).getHp(), EPS); }
    }

    @Test public void sourceDoesNotGiveMineOwnerSelfHitImmunity() {
        Probe p = mineRoom();
        ability(p, 0, 0);
        position(p, 0, mine(p).x(), mine(p).y());
        p.runtime.tick();
        assertEquals(0, views(p, "MINE").size());
        p.runtime.tick();
        assertEquals(90, p.player(0).getHp(), EPS);
        assertEquals(100, p.player(1).getHp(), EPS);
    }

    @Test public void abilityCleanupIsSafe() {
        Probe p = mineRoom();
        ability(p, 0, 0);
        ServerProjectile model = (ServerProjectile) p.projectile(mine(p).projectileId()).getUserData();
        int bodyCount = p.world().getBodyCount();
        AtomicInteger contacts = new AtomicInteger();
        p.afterStep.set(world -> {
            for (Contact contact : world.getContactList()) {
                if (contact.isTouching() && (contact.getFixtureA().getBody().getUserData() == model
                        || contact.getFixtureB().getBody().getUserData() == model)) {
                    contacts.incrementAndGet();
                    assertTrue(model.isActive());
                    assertEquals(bodyCount, world.getBodyCount()); // Chưa destroy trong callback/step.
                    assertEquals(100, p.player(1).getHp(), EPS);
                }
            }
        });
        awaitMineContact(p, 1);
        assertTrue(contacts.get() > 0);
        assertFalse(model.isActive());
        assertEquals(bodyCount - 1, p.world().getBodyCount());
        p.afterStep.set(world -> { });
        resolve(p, 0, 1, false, false);
        assertEquals(90, p.player(1).getHp(), EPS);
        p.runtime.close();
        p.runtime.close();
        assertTrue(p.physics.get().isDisposed());
        assertThrows(IllegalStateException.class, p.runtime::tick);
    }

    @Test public void closingRuntimeWithActiveMineReleasesItsNativeHandle() {
        Probe p = mineRoom();
        ability(p, 0, 0);
        ServerProjectile model = (ServerProjectile) p.projectile(mine(p).projectileId()).getUserData();
        p.runtime.close();
        assertFalse(model.isActive());
        assertTrue(p.physics.get().isDisposed());
        assertThrows(IllegalStateException.class, p.runtime::getProjectiles);
    }

    @Test public void mineLethalDamageClampsFloatHpToZero() {
        Probe p = contactScene(2, 0, 1, false);
        p.player(1).applyDamage(99.75f);
        resolve(p, 0, 1, false, false);
        assertEquals(0, p.player(1).getHp(), 0);
        assertTrue(p.player(1).isDead());
        assertFalse(p.player(1).isBlocking());
    }

    @Test public void deadTargetContactCleansUpMineWithoutApplyingAnotherHit() {
        Probe p = create("Leo", "Alex", "Luca"); // Hai survivor: kiểm tra cleanup khi trận còn PLAYING.
        ability(p, 0, 0);
        p.player(1).applyDamage(100);
        awaitMineContact(p, 1);
        position(p, 1, 5.15f, 3);
        p.runtime.tick();
        assertEquals(0, p.player(1).getHp(), 0);
        assertEquals(0, p.body(1).getLinearVelocity().x, EPS);
        assertEquals(-0.098f, p.body(1).getLinearVelocity().y, EPS);
    }

    @Test public void mineStillExistsAfterOwnerDies() {
        Probe p = create("Leo", "Alex", "Luca");
        ability(p, 0, 0);
        p.player(0).applyDamage(100);
        awaitMineContact(p, 1);
        resolve(p, 0, 1, false, false);
        assertEquals(90, p.player(1).getHp(), EPS);
    }

    @Test public void oldProjectileTypeOneStillWorks() {
        Probe p = create("Viktor", "Leo");
        ability(p, 0, 0);
        p.runtime.tick();
        var ibm = views(p, "IBM").get(0);
        assertEquals(0.7f, ibm.velocityX(), EPS);
        assertEquals(0, ibm.velocityY(), EPS);
        assertEquals(4, p.projectile(ibm.projectileId()).getAngularVelocity(), EPS);
        assertEquals(180, ProjectileManager.IBM_COOLDOWN_TICKS);
    }

    @Test public void oldProjectileTypeTwoStillWorks() {
        Probe p = create("Maurice", "Leo");
        ability(p, 0, 0);
        p.runtime.tick();
        var stars = views(p, "STAR");
        assertEquals(4, stars.size());
        float[][] vectors = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int i = 0; i < 4; i++) {
            assertEquals(vectors[i][0], stars.get(i).velocityX(), EPS);
            assertEquals(vectors[i][1], stars.get(i).velocityY(), EPS);
            assertEquals(-12, p.projectile(stars.get(i).projectileId()).getAngularVelocity(), EPS);
        }
        assertEquals(45, ProjectileManager.STAR_COOLDOWN_TICKS);
    }

    @Test public void meleeStillWorks() {
        Probe p = mineRoom();
        position(p, 1, 5.15f, 3);
        assertEquals(ACCEPTED, p.runtime.submitInput(input(0, 0, false, false, true, false)));
        p.runtime.tick();
        assertEquals(90, p.player(1).getHp(), EPS);
        assertEquals(1.5f, p.body(1).getLinearVelocity().x, EPS);
        assertEquals(0.004f, p.body(1).getLinearVelocity().y, EPS);
        assertEquals(0, p.runtime.getActiveProjectileCount());
    }

    @Test public void stompStillWorks() {
        Probe p = mineRoom();
        position(p, 0, 4.5f, 0.85f);
        position(p, 1, 4.6f, 0.85f);
        for (int tick = 0; tick < 180 && !p.runtime.getJumpState(id(0)).grounded(); tick++) { p.runtime.tick(); }
        assertTrue(p.runtime.getJumpState(id(0)).grounded());
        float floor = p.body(0).getPosition().y;
        position(p, 0, 4.5f, floor + 0.3f);
        position(p, 1, 4.6f, floor);
        p.runtime.tick();
        assertFalse(p.runtime.getJumpState(id(0)).grounded());
        assertEquals(ACCEPTED, p.runtime.submitInput(input(0, 0, false, true, false, false)));
        p.runtime.tick();
        assertTrue(p.runtime.getStompState(id(0)).active());
        assertEquals(99.9f, p.player(0).getHp(), EPS);
        for (int tick = 0; tick < 120 && !p.runtime.getStompState(id(0)).pendingLanding(); tick++) { p.runtime.tick(); }
        assertTrue(p.runtime.getStompState(id(0)).pendingLanding());
        p.runtime.tick();
        assertEquals(90, p.player(1).getHp(), EPS);
    }

    @Test public void blockingStillWorks() {
        Probe p = mineRoom();
        position(p, 1, 5.15f, 3);
        assertEquals(ACCEPTED, p.runtime.submitInput(input(1, 0, false, false, false, true)));
        assertEquals(ACCEPTED, p.runtime.submitInput(input(0, 0, false, false, true, false)));
        p.runtime.tick();
        assertTrue(p.player(1).isBlocking());
        assertEquals(100, p.player(1).getHp(), EPS);
        assertEquals(0, p.body(1).getLinearVelocity().x, EPS);
        Probe mineHit = contactScene(2, 0, 1, false);
        resolve(mineHit, 0, 1, false, true);
        assertTrue(mineHit.player(1).isBlocking());
        assertEquals(90, mineHit.player(1).getHp(), EPS); // Nhánh projectile gốc không check blocking.
    }

    @Test public void abilityDoesNotChangeOwnerMovementOrBlocking() {
        Probe withMine = mineRoom();
        Probe control = mineRoom();
        for (Probe p : List.of(withMine, control)) {
            assertEquals(ACCEPTED, p.runtime.submitInput(new PlayerInput(id(0), 0, 123,
                    1, false, false, false, true, p == withMine)));
            p.runtime.tick();
        }
        assertEquals(control.body(0).getLinearVelocity(), withMine.body(0).getLinearVelocity());
        assertEquals(control.body(0).getPosition(), withMine.body(0).getPosition());
        assertTrue(withMine.player(0).isBlocking());
        assertEquals(control.player(0).getHp(), withMine.player(0).getHp(), 0);
    }

    @Test public void twoPlayersUseAbilitiesIndependently() {
        Probe p = create("Leo", "Leo");
        ability(p, 0, 0);
        ability(p, 1, 0); // Player 0 đang cooldown không cản player 1.
        assertEquals(2, views(p, "MINE").size());
        assertEquals(List.of(id(0), id(1)), views(p, "MINE").stream().map(ServerProjectile.View::ownerPlayerId).toList());
        ability(p, 0, 1);
        assertEquals(2, views(p, "MINE").size());
    }

    @Test public void fourPlayersHaveIndependentAbilityState() {
        for (int count = 2; count <= 4; count++) {
            Probe p = create(Collections.nCopies(count, "Leo").toArray(String[]::new));
            for (int slot = count - 1; slot >= 0; slot--) { sendAbility(p, slot, 0); }
            p.runtime.tick();
            assertEquals(count, views(p, "MINE").size());
            for (int slot = 0; slot < count; slot++) {
                var mine = views(p, "MINE").get(slot);
                assertEquals(id(slot), mine.ownerPlayerId()); // Runtime sort theo slot dù input/roster ngược.
                assertEquals(0, mine.spawnTick());
            }
            for (int slot = 0; slot < count; slot++) { sendAbility(p, slot, 1); }
            p.runtime.tick();
            assertEquals(count, views(p, "MINE").size());
            for (var mine : views(p, "MINE")) { assertEquals(-0.098f, mine.velocityY(), EPS); }
        }
    }

    @Test public void abilityBehaviorDoesNotDependOnSlot() {
        for (int count = 2; count <= 4; count++) {
            for (int attacker = 0; attacker < count; attacker++) {
                for (boolean left : new boolean[]{false, true}) {
                    int target = attacker == 0 ? 1 : 0;
                    Probe p = contactScene(count, attacker, target, left);
                    resolve(p, attacker, target, left, false);
                    assertEquals(90, p.player(target).getHp(), EPS);
                    assertEquals(left ? -1.5f : 1.5f, p.body(target).getLinearVelocity().x, EPS);
                    assertEquals(1.804f, p.body(target).getLinearVelocity().y, EPS);
                    assertEquals(100, p.player(attacker).getHp(), EPS);
                }
            }
        }
    }

    @Test public void twoRoomsHaveIndependentAbilities() {
        Probe a = mineRoom();
        Probe b = mineRoom(); // Cố ý cùng PlayerId để phát hiện global cooldown/state.
        ability(a, 0, 0);
        ability(b, 0, 0);
        assertNotSame(a.world(), b.world());
        assertNotEquals(mine(a).matchId(), mine(b).matchId());
        ServerProjectile bModel = (ServerProjectile) b.projectile(mine(b).projectileId()).getUserData();
        a.runtime.close();
        b.runtime.tick();
        assertTrue(bModel.isActive());
        assertEquals(1, views(b, "MINE").size());
        assertFalse(b.physics.get().isDisposed());
    }

    @Test public void abilityCannotDamageOtherRoom() {
        Probe a = contactScene(2, 0, 1, false);
        Probe b = mineRoom();
        position(b, 1, 5.15f, 3); // Cùng tọa độ và PlayerId, nhưng World/match khác.
        resolve(a, 0, 1, false, false);
        b.runtime.tick();
        assertEquals(90, a.player(1).getHp(), EPS);
        assertEquals(100, b.player(1).getHp(), EPS);
        assertEquals(0, b.runtime.getActiveProjectileCount());
    }

    @Test public void worldStepsExactlyOncePerTick() {
        Probe p = create("Leo", "Maurice", "Viktor", "Leo");
        for (int slot = 3; slot >= 0; slot--) { sendAbility(p, slot, 0); }
        p.runtime.tick();
        assertEquals(7, p.runtime.getActiveProjectileCount()); // 1 Mine + 4 Star + 1 IBM + 1 Mine.
        for (int tick = 1; tick < 10; tick++) { keepPlayersInAir(p); p.runtime.tick(); }
        assertEquals(10, p.steps.get());
        assertEquals(10, p.runtime.getMatchState().getServerTick());
    }

    @Test(timeout = 15000) public void networkThreadsOnlyEnqueueAndSimulationOwnerActivatesAbility() throws Exception {
        Probe p = create("Leo", "Leo");
        p.runtime.tick(); // Thread test là simulation owner.
        var results = InputQueueTest.runConcurrently(List.<Callable<InputQueue.OfferResult>>of(
                () -> p.runtime.submitInput(input(0, 0, true, false, false, false)),
                () -> p.runtime.submitInput(input(1, 0, true, false, false, false))));
        assertEquals(List.of(ACCEPTED, ACCEPTED), results);
        InputQueueTest.runConcurrently(List.<Callable<Boolean>>of(() -> {
            assertThrows(IllegalStateException.class, p.runtime::tick);
            return true;
        }));
        assertEquals(0, p.runtime.getActiveProjectileCount());
        assertEquals(1, p.steps.get());
        p.runtime.tick();
        assertEquals(2, views(p, "MINE").size());
    }

    private Probe mineRoom() { return create("Leo", "Alex"); }

    private Probe create(String... characters) {
        List<MatchSetup.Participant> roster = new ArrayList<>();
        for (int slot = 0; slot < characters.length; slot++) {
            roster.add(new MatchSetup.Participant(id(slot), slot, characters[slot]));
        }
        Collections.reverse(roster);
        String matchId = "ability-coverage-" + runtimes.size();
        MatchSetup setup = new MatchSetup(matchId, "room-" + matchId, roster);
        AtomicReference<HeadlessPhysicsWorld> physics = new AtomicReference<>();
        AtomicInteger steps = new AtomicInteger();
        AtomicReference<Consumer<World>> afterStep = new AtomicReference<>(world -> { });
        MatchRuntime runtime = new MatchRuntime(setup, () -> {
            var created = new PhysicsWorldFactory().create();
            physics.set(created);
            return created;
        }, (world, dt, velocity, position) -> {
            assertEquals(1.0f / 60, dt, 0);
            assertEquals(6, velocity);
            assertEquals(2, position);
            steps.incrementAndGet();
            world.step(dt, velocity, position);
            afterStep.get().accept(world);
        });
        runtimes.add(runtime);
        runtime.start();
        Probe p = new Probe(runtime, physics, steps, afterStep);
        keepPlayersInAir(p);
        return p;
    }

    private Probe contactScene(int count, int attacker, int target, boolean left) {
        String[] characters = Collections.nCopies(count, "Alex").toArray(String[]::new);
        characters[attacker] = "Leo";
        Probe p = create(characters);
        position(p, attacker, 5, 3);
        if (left) { p.body(attacker).setLinearVelocity(-0.3f, 0); }
        ability(p, attacker, 0);
        var mine = mine(p);
        assertEquals(left ? 4.85f : 5.15f, mine.x(), EPS);
        awaitMineContact(p, target);
        return p;
    }

    private static void awaitMineContact(Probe p, int target) {
        // Box2D broadphase/contact discovery có thể cần step kế tiếp sau setTransform trong test.
        // Chờ bằng số manual ticks hữu hạn, không sửa production collision để khớp giả định của test.
        for (int tick = 0; tick < 8 && !views(p, "MINE").isEmpty(); tick++) {
            var current = mine(p);
            position(p, target, current.x(), current.y());
            p.runtime.tick();
        }
        assertEquals("Không đạt native Mine contact trong 8 ticks", 0, views(p, "MINE").size());
    }

    private static void resolve(Probe p, int attacker, int target, boolean left, boolean block) {
        position(p, attacker, 5, 3);
        position(p, target, left ? 4.85f : 5.15f, 3);
        if (block) { assertEquals(ACCEPTED, p.runtime.submitInput(input(target, 0, false, false, false, true))); }
        p.runtime.tick();
    }

    private static void keepPlayersInAir(Probe p) {
        for (int slot = 0; slot < p.runtime.getParticipants().size(); slot++) {
            position(p, slot, 5 + slot * 0.8f, 3 + slot * 2);
        }
    }

    private static void tile(Probe p, float x, float y) {
        BodyDef definition = new BodyDef();
        definition.type = BodyDef.BodyType.StaticBody;
        definition.position.set(x, y);
        Body body = p.world().createBody(definition);
        body.setUserData("Tile");
        PolygonShape shape = new PolygonShape();
        try {
            shape.setAsBox(0.04f, 0.04f);
            FixtureDef fixture = new FixtureDef();
            fixture.shape = shape;
            fixture.filter.categoryBits = 2;
            fixture.filter.maskBits = 1 | 3;
            body.createFixture(fixture);
        } finally { shape.dispose(); }
    }

    private static List<ServerProjectile.View> views(Probe p, String type) {
        return p.runtime.getProjectiles().stream().filter(v -> v.projectileType().equals(type)).toList();
    }
    private static ServerProjectile.View mine(Probe p) { return views(p, "MINE").get(0); }
    private static void position(Probe p, int slot, float x, float y) {
        p.body(slot).setTransform(x, y, 0);
        p.body(slot).setLinearVelocity(0, 0);
    }
    private static void ability(Probe p, int slot, long sequence) { sendAbility(p, slot, sequence); p.runtime.tick(); }
    private static void sendAbility(Probe p, int slot, long sequence) {
        assertEquals(ACCEPTED, p.runtime.submitInput(input(slot, sequence, true, false, false, false)));
    }
    private static PlayerInput input(int slot, long sequence, boolean ability, boolean stomp, boolean melee, boolean block) {
        return new PlayerInput(id(slot), sequence, Long.MAX_VALUE, 0, false, stomp, melee, block, ability);
    }
    private static PlayerId id(int slot) { return new PlayerId("ability-player-" + slot); }
    private record Probe(MatchRuntime runtime, AtomicReference<HeadlessPhysicsWorld> physics, AtomicInteger steps,
                         AtomicReference<Consumer<World>> afterStep) {
        World world() { return physics.get().getWorld(); }
        ServerPlayer player(int slot) { return runtime.getMatchState().getPlayer(id(slot)); }
        Body body(int slot) {
            Array<Body> bodies = new Array<>();
            world().getBodies(bodies);
            for (Body body : bodies) { if (id(slot).equals(body.getUserData())) { return body; } }
            throw new AssertionError("Thiếu player body: " + slot);
        }
        Body projectile(long id) {
            Array<Body> bodies = new Array<>();
            world().getBodies(bodies);
            for (Body body : bodies) {
                if (body.getUserData() instanceof ServerProjectile projectile && projectile.getProjectileId() == id) { return body; }
            }
            throw new AssertionError("Thiếu projectile: " + id);
        }
    }
}
