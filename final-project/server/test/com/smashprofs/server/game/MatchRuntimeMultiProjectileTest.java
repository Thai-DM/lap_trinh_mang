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
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.smashprofs.server.game.InputQueue.OfferResult.*;
import static org.junit.Assert.*;

/** World/TMX native thật, tick thủ công; chỉ bố trí Body trong test, không mock hit hoặc sleep. */
public class MatchRuntimeMultiProjectileTest {
    private static final float EPS = 0.0001f;
    private final List<MatchRuntime> runtimes = new ArrayList<>();

    @After public void closeResources() { runtimes.forEach(MatchRuntime::close); }

    @Test public void existingProjectileStillWorks() {
        Probe p = create("Viktor", "Alex");
        ability(p, 0, 0);
        assertEquals(1, views(p, "IBM").size());
        assertTrue(views(p, "STAR").isEmpty());
        assertEquals(5.15f, views(p, "IBM").get(0).x(), EPS);
        p.runtime.tick();
        assertEquals(0.7f, views(p, "IBM").get(0).velocityX(), EPS);
        assertEquals(4, p.projectile(views(p, "IBM").get(0).projectileId()).getAngularVelocity(), EPS);
    }

    @Test public void secondProjectileTypeCanBeCreated() {
        Probe p = starRoom();
        ability(p, 0, 0);
        assertEquals(4, views(p, "STAR").size());
        assertEquals(24, p.world().getBodyCount());
        assertTrue(views(p, "IBM").isEmpty());
    }

    @Test public void projectileTypesAreDistinct() {
        Probe p = mixedRoom();
        fireBoth(p);
        assertNotEquals(ProjectileType.IBM, ProjectileType.STAR);
        assertEquals(2, p.runtime.getProjectiles().stream().map(ServerProjectile.View::projectileType).distinct().count());
        assertEquals(4, views(p, "STAR").size());
        assertEquals(1, views(p, "IBM").size());
        for (ServerProjectile.View view : p.runtime.getProjectiles()) {
            ServerProjectile model = (ServerProjectile) p.projectile(view.projectileId()).getUserData();
            assertEquals(view.projectileType(), model.getProjectileType());
        }
    }

    @Test public void characterUsesCorrectProjectileType() {
        Probe p = mixedRoom();
        fireBoth(p);
        assertTrue(p.runtime.getProjectiles().stream().filter(view -> view.ownerPlayerId().equals(id(0)))
                .allMatch(view -> view.projectileType().equals("STAR")));
        assertTrue(p.runtime.getProjectiles().stream().filter(view -> view.ownerPlayerId().equals(id(1)))
                .allMatch(view -> view.projectileType().equals("IBM")));
        assertEquals(4, p.runtime.getProjectiles().stream().filter(view -> view.ownerPlayerId().equals(id(0))).count());
    }

    @Test public void wrongCharacterCannotUseProjectile() {
        for (String character : List.of("Luca")) {
            Probe p = create(character, "Viktor");
            ability(p, 0, 0);
            assertEquals(0, p.runtime.getActiveProjectileCount());
        }
        Probe p = starRoom();
        assertEquals(UNKNOWN_PLAYER, p.runtime.submitInput(input(9, 0, true, false, false, false)));
        p.runtime.tick();
        assertEquals(0, p.runtime.getActiveProjectileCount());
    }

    @Test public void projectileHasCorrectOwner() {
        Probe p = mixedRoom();
        fireBoth(p);
        for (ServerProjectile.View star : views(p, "STAR")) {
            assertEquals(id(0), star.ownerPlayerId());
            assertEquals(p.runtime.getMatchId(), star.matchId());
            assertEquals(0, star.spawnTick());
        }
        assertEquals(id(1), views(p, "IBM").get(0).ownerPlayerId());
        assertEquals(5, p.runtime.getProjectiles().stream().map(ServerProjectile.View::projectileId).distinct().count());
    }

    @Test public void projectileSpawnMatchesOriginal() {
        Probe p = starRoom();
        ability(p, 0, 0);
        List<ServerProjectile.View> stars = views(p, "STAR");
        float[][] positions = {{5.1f, 3}, {4.9f, 3}, {5, 3.1f}, {5, 2.9f}};
        for (int direction = 0; direction < 4; direction++) {
            assertEquals(positions[direction][0], stars.get(direction).x(), EPS);
            assertEquals(positions[direction][1], stars.get(direction).y(), EPS);
            assertEquals(0, stars.get(direction).velocityX(), EPS);
            assertEquals(0, stars.get(direction).velocityY(), EPS);
        }
    }

    @Test public void starBurstDoesNotDependOnFacing() {
        Probe p = starRoom();
        p.body(0).setLinearVelocity(-0.3f, 0);
        ability(p, 0, 0);
        assertFalse(p.player(0).isFacingRight());
        assertEquals(5.1f, views(p, "STAR").get(0).x(), EPS);
        assertEquals(4.9f, views(p, "STAR").get(1).x(), EPS);
        p.runtime.tick();
        assertEquals(1, views(p, "STAR").get(0).velocityX(), EPS);
        assertEquals(-1, views(p, "STAR").get(1).velocityX(), EPS);
    }

    @Test public void projectilePhysicsMatchesOriginal() {
        Probe p = starRoom();
        ability(p, 0, 0);
        for (ServerProjectile.View star : views(p, "STAR")) {
            Body body = p.projectile(star.projectileId());
            Fixture fixture = body.getFixtureList().first();
            assertEquals(BodyDef.BodyType.DynamicBody, body.getType());
            assertFalse(body.isFixedRotation());
            assertFalse(body.isBullet());
            assertEquals(-12, body.getAngularVelocity(), EPS);
            assertEquals(-12.0f / 60, body.getAngle(), EPS);
            assertEquals(Shape.Type.Circle, fixture.getShape().getType());
            assertEquals(0.03f, fixture.getShape().getRadius(), EPS);
            assertEquals(3, fixture.getFilterData().categoryBits);
            assertEquals((short) 0xffff, fixture.getFilterData().maskBits);
            assertEquals(0, fixture.getFilterData().groupIndex);
            assertFalse(fixture.isSensor());
            assertEquals(0, fixture.getDensity(), 0);
            assertEquals(0.2f, fixture.getFriction(), EPS);
            assertEquals(0, fixture.getRestitution(), 0);
        }
        assertEquals(0, p.world().getGravity().x, 0);
        assertEquals(0, p.world().getGravity().y, 0);
    }

    @Test public void fourDirectionsMoveWithOriginalVectorsWithoutManualGravity() {
        Probe p = starRoom();
        ability(p, 0, 0);
        List<ServerProjectile.View> initial = views(p, "STAR");
        p.runtime.tick();
        float[][] vectors = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int direction = 0; direction < 4; direction++) {
            var current = views(p, "STAR").get(direction);
            assertEquals(vectors[direction][0], current.velocityX(), EPS);
            assertEquals(vectors[direction][1], current.velocityY(), EPS);
            assertEquals(initial.get(direction).x() + vectors[direction][0] / 60, current.x(), EPS);
            assertEquals(initial.get(direction).y() + vectors[direction][1] / 60, current.y(), EPS);
        }
    }

    @Test public void projectileDamageMatchesOriginal() {
        HitScene scene = hitScene();
        awaitHit(scene);
        assertEquals(100, scene.p.player(1).getHp(), EPS);
        resolve(scene, false);
        assertEquals(90, scene.p.player(1).getHp(), EPS);
        assertEquals(10, ProjectileManager.STAR_DAMAGE, 0); // Không dùng damageOnHit=15 chưa được consume ở source.
        assertEquals(100, scene.p.player(0).getHp(), EPS);
    }

    @Test public void projectileCollisionMatchesOriginal() {
        HitScene scene = hitScene();
        awaitHit(scene);
        assertFalse(isActive(scene.p, scene.rightId));
        assertEquals(3, scene.p.runtime.getActiveProjectileCount());
        assertEquals(23, scene.p.world().getBodyCount());
        resolve(scene, false);
        assertEquals(90, scene.p.player(1).getHp(), EPS);
        for (int tick = 0; tick < 12; tick++) {
            position(scene.p, 0, 5, 3);
            position(scene.p, 1, 5.3f, 3);
            scene.p.runtime.tick();
        }
        assertEquals(90, scene.p.player(1).getHp(), EPS);
    }

    @Test public void starMapContactDestroysOnlyThatStarWithoutAoe() {
        Probe p = starRoom();
        tile(p, 5.3f, 3);
        ability(p, 0, 0);
        long right = views(p, "STAR").get(0).projectileId();
        for (int tick = 0; tick < 60 && isActive(p, right); tick++) {
            position(p, 0, 5, 3);
            position(p, 1, 7, 5);
            p.runtime.tick();
        }
        assertFalse(isActive(p, right));
        assertEquals(3, views(p, "STAR").size());
        p.runtime.tick();
        assertEquals(100, p.player(0).getHp(), EPS);
        assertEquals(100, p.player(1).getHp(), EPS);
    }

    @Test public void projectileLifetimeMatchesOriginal() {
        Probe p = starRoom();
        position(p, 0, 5, 8);
        ability(p, 0, 0);
        long up = views(p, "STAR").get(2).projectileId();
        long right = views(p, "STAR").get(0).projectileId();
        for (int tick = 0; tick < 400; tick++) {
            position(p, 0, 3.5f, 1);
            position(p, 1, 8, 1);
            p.runtime.tick();
        }
        assertTrue(isActive(p, up)); // Source Star không có lifetime hoặc expiry ngoài arena.
        assertTrue(isActive(p, right));
        assertTrue(byId(p, up).y() > 14);
        assertTrue(byId(p, right).x() > 8.5f);
    }

    @Test public void projectileCooldownMatchesOriginal() {
        Probe p = mixedRoom();
        position(p, 0, 5, 8);
        position(p, 1, 7, 10);
        fireBoth(p);
        for (int tick = 1; tick < 45; tick++) {
            position(p, 0, 5, 8);
            position(p, 1, 7, 10);
            sendAbility(p, 0, tick);
            sendAbility(p, 1, tick);
            p.runtime.tick();
            assertEquals(4, views(p, "STAR").size());
            assertEquals(1, views(p, "IBM").size());
        }
        assertEquals(45, p.runtime.getMatchState().getServerTick());
        position(p, 0, 5, 8);
        position(p, 1, 7, 10);
        sendAbility(p, 0, 45);
        sendAbility(p, 1, 45);
        p.runtime.tick();
        assertEquals(8, views(p, "STAR").size());
        assertEquals(1, views(p, "IBM").size());
        for (int tick = 46; tick < 180; tick++) {
            position(p, 0, 5, 8);
            position(p, 1, 7, 10);
            ability(p, 1, tick);
            assertEquals(1, views(p, "IBM").size());
        }
        position(p, 0, 5, 8);
        position(p, 1, 7, 10);
        ability(p, 1, 180);
        assertEquals(2, views(p, "IBM").size());
        assertEquals(45, ProjectileManager.STAR_COOLDOWN_TICKS);
        assertEquals(180, ProjectileManager.IBM_COOLDOWN_TICKS);
    }

    @Test public void duplicateAbilityDoesNotDoubleSpawn() {
        Probe p = starRoom();
        PlayerInput edge = input(0, 0, true, false, false, false);
        assertEquals(ACCEPTED, p.runtime.submitInput(edge));
        assertEquals(DUPLICATE_OR_STALE, p.runtime.submitInput(edge));
        p.runtime.tick();
        assertEquals(4, p.runtime.getActiveProjectileCount());
        assertEquals(DUPLICATE_OR_STALE, p.runtime.submitInput(edge));
        p.runtime.tick();
        assertEquals(4, p.runtime.getActiveProjectileCount());
    }

    @Test public void batchedEdgesCreateOneBurstNotOneBurstPerPacket() {
        Probe p = starRoom();
        for (int sequence = 0; sequence < 20; sequence++) { sendAbility(p, 0, sequence); }
        assertEquals(1, p.runtime.tick().size());
        assertEquals(4, p.runtime.getActiveProjectileCount());
    }

    @Test public void noNewInputDoesNotAutomaticallyFireAfterCooldown() {
        Probe p = starRoom();
        position(p, 0, 5, 8);
        ability(p, 0, 0);
        for (int tick = 0; tick < 100; tick++) {
            position(p, 0, 5, 8);
            position(p, 1, 7, 10);
            p.runtime.tick();
        }
        assertEquals(4, views(p, "STAR").size());
        assertTrue(views(p, "STAR").stream().allMatch(view -> view.spawnTick() == 0));
    }

    @Test public void twoProjectileTypesCanCoexist() {
        Probe p = mixedRoom();
        fireBoth(p);
        assertEquals(5, p.runtime.getActiveProjectileCount());
        p.runtime.tick();
        assertEquals(5, p.runtime.getActiveProjectileCount());
        assertEquals(1, views(p, "STAR").get(0).velocityX(), EPS);
        assertEquals(0.7f, views(p, "IBM").get(0).velocityX(), EPS);
    }

    @Test public void projectileTypeADoesNotCorruptTypeB() {
        Probe p = mixedRoom();
        fireBoth(p);
        var ibm = views(p, "IBM").get(0);
        tile(p, 5.3f, 3);
        long right = views(p, "STAR").get(0).projectileId();
        for (int tick = 0; tick < 60 && isActive(p, right); tick++) {
            position(p, 0, 5, 3);
            position(p, 1, 7, 5);
            p.runtime.tick();
        }
        assertFalse(isActive(p, right));
        assertEquals(3, views(p, "STAR").size());
        assertEquals(1, views(p, "IBM").size());
        assertEquals(ibm.projectileId(), views(p, "IBM").get(0).projectileId());
        assertEquals(id(1), views(p, "IBM").get(0).ownerPlayerId());
        assertEquals(0.7f, views(p, "IBM").get(0).velocityX(), EPS);
    }

    @Test public void twoPlayersCanUseDifferentProjectiles() {
        Probe p = mixedRoom();
        fireBoth(p);
        assertEquals(4, views(p, "STAR").size());
        assertEquals(1, views(p, "IBM").size());
        assertEquals(id(0), views(p, "STAR").get(0).ownerPlayerId());
        assertEquals(id(1), views(p, "IBM").get(0).ownerPlayerId());
    }

    @Test public void fourPlayersCanOwnIndependentProjectiles() {
        for (int count = 2; count <= 4; count++) {
            String[] characters = {"Maurice", "Viktor", "Maurice", "Viktor"};
            Probe p = create(java.util.Arrays.copyOf(characters, count));
            for (int slot = 0; slot < count; slot++) { position(p, slot, 4 + slot, 8 + slot * 2); }
            for (int slot = count - 1; slot >= 0; slot--) { sendAbility(p, slot, 0); }
            p.runtime.tick();
            int expected = 0;
            for (int slot = 0; slot < count; slot++) {
                int expectedOwned = slot % 2 == 0 ? 4 : 1;
                expected += expectedOwned;
                PlayerId owner = id(slot);
                String type = slot % 2 == 0 ? "STAR" : "IBM";
                assertEquals(expectedOwned, p.runtime.getProjectiles().stream()
                        .filter(view -> view.ownerPlayerId().equals(owner)).count());
                assertTrue(p.runtime.getProjectiles().stream().filter(view -> view.ownerPlayerId().equals(owner))
                        .allMatch(view -> view.projectileType().equals(type)));
            }
            assertEquals(expected, p.runtime.getActiveProjectileCount());
            assertEquals(expected, p.runtime.getProjectiles().stream().map(ServerProjectile.View::projectileId).distinct().count());
        }
    }

    @Test public void projectileCannotDamageOtherRoom() {
        HitScene a = hitScene();
        Probe b = mixedRoom();
        fireBoth(b);
        awaitHit(a);
        resolve(a, false);
        assertEquals(90, a.p.player(1).getHp(), EPS);
        assertEquals(100, b.player(0).getHp(), EPS);
        assertEquals(100, b.player(1).getHp(), EPS);
        assertEquals(5, b.runtime.getActiveProjectileCount());
    }

    @Test public void differentRoomsHaveIndependentManagers() {
        Probe a = mixedRoom();
        Probe b = mixedRoom();
        fireBoth(a);
        fireBoth(b); // Cùng PlayerId và tick vẫn bắn được, không dùng cooldown/static registry chung.
        assertNotEquals(a.runtime.getMatchId(), b.runtime.getMatchId());
        assertNotSame(a.world(), b.world());
        a.runtime.close();
        assertEquals(5, b.runtime.getActiveProjectileCount());
        b.runtime.tick();
        assertEquals(5, b.runtime.getActiveProjectileCount());
        assertEquals(25, b.world().getBodyCount());
    }

    @Test public void cleanupRemovesCorrectProjectile() {
        HitScene scene = hitScene();
        List<Long> originalIds = views(scene.p, "STAR").stream().map(ServerProjectile.View::projectileId).toList();
        ServerProjectile model = (ServerProjectile) scene.p.projectile(scene.rightId).getUserData();
        awaitHit(scene);
        assertFalse(model.isActive());
        for (long id : originalIds) { assertEquals(id != scene.rightId, isActive(scene.p, id)); }
    }

    @Test public void cleanupDoesNotDoubleDestroy() {
        HitScene scene = hitScene();
        awaitHit(scene);
        resolve(scene, false);
        List<ServerProjectile> models = scene.p.runtime.getProjectiles().stream()
                .map(view -> (ServerProjectile) scene.p.projectile(view.projectileId()).getUserData()).toList();
        scene.p.runtime.close();
        scene.p.runtime.close();
        assertTrue(scene.p.physics.get().isDisposed());
        assertTrue(models.stream().noneMatch(ServerProjectile::isActive));
        assertThrows(IllegalStateException.class, scene.p.runtime::tick);
    }

    @Test public void projectileDoesNotStepWorldTwice() {
        Probe p = create("Maurice", "Viktor", "Maurice", "Viktor");
        for (int slot = 0; slot < 4; slot++) { position(p, slot, 4 + slot, 8 + slot * 2); sendAbility(p, slot, 0); }
        p.runtime.tick();
        assertEquals(10, p.runtime.getActiveProjectileCount());
        assertEquals(1, p.steps.get());
        assertEquals(1, p.runtime.getMatchState().getServerTick());
        p.runtime.tick();
        assertEquals(2, p.steps.get());
        assertEquals(2, p.runtime.getMatchState().getServerTick());
    }

    @Test public void existingMeleeTestsStillPass() {
        Probe p = starRoom();
        position(p, 1, 5.19f, 3);
        assertEquals(ACCEPTED, p.runtime.submitInput(input(0, 0, true, false, true, false)));
        p.runtime.tick();
        assertEquals(90, p.player(1).getHp(), EPS);
        assertEquals(1.5f, p.body(1).getLinearVelocity().x, EPS);
        assertEquals(4, views(p, "STAR").size());
    }

    @Test public void existingStompTestsStillPass() {
        Probe p = starRoom();
        assertEquals(ACCEPTED, p.runtime.submitInput(input(0, 0, true, true, false, false)));
        p.runtime.tick();
        assertTrue(p.runtime.getStompState(id(0)).active());
        assertEquals(99.9f, p.player(0).getHp(), EPS);
        assertEquals(-5.196f, p.body(0).getLinearVelocity().y, EPS);
        assertEquals(4, views(p, "STAR").size());
    }

    @Test public void existingPhysicsTestsStillPass() {
        Probe p = mixedRoom();
        fireBoth(p);
        for (int slot = 0; slot < 2; slot++) {
            Fixture fixture = p.body(slot).getFixtureList().first();
            assertEquals(-1, fixture.getFilterData().groupIndex);
            assertEquals(7, fixture.getFilterData().maskBits);
            assertEquals(0.05f, fixture.getShape().getRadius(), EPS);
        }
        assertEquals(-0.098f, p.body(0).getLinearVelocity().y, EPS);
        assertEquals(25, p.world().getBodyCount());
    }

    @Test public void allPreviousServerTestsStillPass() {
        Probe p = mixedRoom();
        p.runtime.start();
        assertEquals(0, p.runtime.getActiveProjectileCount());
        assertEquals(20, p.world().getBodyCount());
        assertEquals(2, p.runtime.getMatchState().getPlayerCount());
        fireBoth(p);
        assertEquals(1, p.runtime.getMatchState().getServerTick());
        // Toàn bộ test Bước 1–35 còn được chạy bằng :server:test, không chỉ assertion smoke này.
    }

    @Test public void blockingDoesNotStopStarDamageOrKnockback() {
        HitScene scene = hitScene();
        awaitHit(scene);
        resolve(scene, true);
        assertTrue(scene.p.player(1).isBlocking());
        assertEquals(90, scene.p.player(1).getHp(), EPS);
        assertEquals(1.5f, scene.p.body(1).getLinearVelocity().x, EPS);
        assertEquals(1.804f, scene.p.body(1).getLinearVelocity().y, EPS);
    }

    @Test public void starCanHitOwnerBecauseOriginalHasNoOwnerImmunity() {
        Probe p = starRoom();
        ability(p, 0, 0);
        var right = views(p, "STAR").get(0);
        position(p, 0, right.x(), right.y());
        p.runtime.tick();
        assertFalse(isActive(p, right.projectileId()));
        p.runtime.tick();
        assertEquals(90, p.player(0).getHp(), EPS);
        assertEquals(100, p.player(1).getHp(), EPS);
    }

    @Test public void deadMauriceCannotSpawnBurst() {
        Probe p = starRoom();
        p.player(0).applyDamage(100);
        ability(p, 0, 0);
        assertEquals(0, p.runtime.getActiveProjectileCount());
        assertEquals(20, p.world().getBodyCount());
    }

    @Test public void fractionalHpUsesExistingDamageApiAndClampsAtZero() {
        HitScene scene = hitScene();
        scene.p.player(1).applyDamage(99.75f);
        awaitHit(scene);
        resolve(scene, false);
        assertEquals(0, scene.p.player(1).getHp(), 0);
        assertTrue(scene.p.player(1).isDead());
    }

    @Test public void closeWithBothTypesClearsAllBodyReferences() {
        Probe p = mixedRoom();
        fireBoth(p);
        List<ServerProjectile> models = p.runtime.getProjectiles().stream()
                .map(view -> (ServerProjectile) p.projectile(view.projectileId()).getUserData()).toList();
        p.runtime.close();
        p.runtime.close();
        assertTrue(p.physics.get().isDisposed());
        assertTrue(models.stream().noneMatch(ServerProjectile::isActive));
        assertThrows(IllegalStateException.class, p.runtime::getProjectiles);
    }

    @Test(timeout = 15000) public void onlySimulationOwnerCanSpawnBothTypes() throws Exception {
        Probe p = mixedRoom();
        p.runtime.tick();
        InputQueueTest.runConcurrently(List.<Callable<Boolean>>of(() -> {
            sendAbility(p, 0, 0);
            sendAbility(p, 1, 0);
            assertThrows(IllegalStateException.class, p.runtime::tick);
            return true;
        }));
        assertEquals(0, p.runtime.getActiveProjectileCount());
        assertEquals(20, p.world().getBodyCount());
        p.runtime.tick();
        assertEquals(5, p.runtime.getActiveProjectileCount());
    }

    @Test public void starAndIbmCollisionDestroysBothContactingBodiesNotWholeBurst() {
        Probe p = mixedRoom();
        position(p, 1, 5.5f, 3);
        p.body(1).setLinearVelocity(-0.1f, 0);
        fireBoth(p);
        long right = views(p, "STAR").get(0).projectileId();
        long ibm = views(p, "IBM").get(0).projectileId();
        for (int tick = 0; tick < 90 && isActive(p, right); tick++) {
            position(p, 0, 5, 3);
            position(p, 1, 5.5f, 3);
            p.runtime.tick();
        }
        assertFalse(isActive(p, right));
        assertFalse(isActive(p, ibm));
        assertEquals(3, views(p, "STAR").size());
        assertTrue(views(p, "IBM").isEmpty());
        p.runtime.tick();
        assertEquals(100, p.player(0).getHp(), EPS);
        assertEquals(100, p.player(1).getHp(), EPS);
        assertEquals(23, p.world().getBodyCount());
    }

    @Test public void starKnockbackIsSlotIndependentAndMirroredInTwoToFourPlayerMatches() {
        for (int count = 2; count <= 4; count++) {
            for (int attacker = 0; attacker < count; attacker++) {
                for (int sign : new int[]{-1, 1}) {
                    String[] characters = new String[count];
                    java.util.Arrays.fill(characters, "Maurice");
                    Probe p = create(characters);
                    int target = attacker == 0 ? 1 : 0;
                    for (int slot = 0; slot < count; slot++) { position(p, slot, 7 + slot * 0.2f, 6); }
                    position(p, attacker, 5, 3);
                    position(p, target, 5 + sign * 0.3f, 3);
                    ability(p, attacker, 0);
                    long projectile = views(p, "STAR").get(sign > 0 ? 0 : 1).projectileId();
                    for (int tick = 0; tick < 90 && isActive(p, projectile); tick++) {
                        position(p, attacker, 5, 3);
                        position(p, target, 5 + sign * 0.3f, 3);
                        p.runtime.tick();
                    }
                    assertFalse(isActive(p, projectile));
                    position(p, attacker, 5, 3);
                    position(p, target, 5 + sign * 0.3f, 3);
                    p.runtime.tick();
                    assertEquals(90, p.player(target).getHp(), EPS);
                    assertEquals(sign * 1.5f, p.body(target).getLinearVelocity().x, EPS);
                    assertEquals(1.804f, p.body(target).getLinearVelocity().y, EPS);
                    assertEquals(100, p.player(attacker).getHp(), EPS);
                }
            }
        }
    }

    @Test public void starNativeContactIsDeferredWithoutDamageOrDestroyInsideStep() {
        HitScene scene = hitScene();
        AtomicInteger nativeContacts = new AtomicInteger();
        scene.p.afterStep.set(world -> {
            if (!isActive(scene.p, scene.rightId)) { return; }
            Body right = scene.p.projectile(scene.rightId);
            for (Contact contact : world.getContactList()) {
                if (contact.isTouching() && (contact.getFixtureA().getBody() == right
                        || contact.getFixtureB().getBody() == right)) {
                    nativeContacts.incrementAndGet();
                    assertFalse(world.isLocked());
                    assertEquals(24, world.getBodyCount());
                    assertEquals(100, scene.p.player(1).getHp(), EPS);
                }
            }
        });
        awaitHit(scene);
        assertTrue(nativeContacts.get() > 0);
        assertEquals(23, scene.p.world().getBodyCount());
        resolve(scene, false);
        assertEquals(90, scene.p.player(1).getHp(), EPS);
    }

    @Test public void starContactWithOriginalTmxColliderUsesExistingMapWithoutAoe() {
        Probe p = starRoom();
        Array<Body> bodies = new Array<>();
        p.world().getBodies(bodies);
        Body originalTile = null;
        for (Body body : bodies) {
            if (body.getType() == BodyDef.BodyType.StaticBody && "Tile".equals(body.getUserData())
                    && body.getPosition().x > 3.5f && body.getPosition().x < 8 && body.getPosition().y > 0.3f) {
                originalTile = body;
                break;
            }
        }
        assertNotNull(originalTile);
        position(p, 0, originalTile.getPosition().x - 0.1f, originalTile.getPosition().y);
        position(p, 1, 8, 5);
        AtomicInteger contacts = new AtomicInteger();
        p.afterStep.set(world -> {
            for (Contact contact : world.getContactList()) {
                if (!contact.isTouching()) { continue; }
                Object a = contact.getFixtureA().getBody().getUserData();
                Object b = contact.getFixtureB().getBody().getUserData();
                if ((a instanceof ServerProjectile && "Tile".equals(b))
                        || (b instanceof ServerProjectile && "Tile".equals(a))) { contacts.incrementAndGet(); }
            }
        });
        ability(p, 0, 0);
        assertTrue(contacts.get() > 0);
        assertTrue(views(p, "STAR").size() < 4);
        p.runtime.tick();
        assertEquals(100, p.player(1).getHp(), EPS);
    }

    private Probe starRoom() { return create("Maurice", "Alex"); }
    private Probe mixedRoom() { return create("Maurice", "Viktor"); }

    private Probe create(String... characters) {
        List<MatchSetup.Participant> roster = new ArrayList<>();
        for (int slot = 0; slot < characters.length; slot++) {
            roster.add(new MatchSetup.Participant(id(slot), slot, characters[slot]));
        }
        Collections.reverse(roster);
        String matchId = "multi-projectile-" + runtimes.size();
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
        for (int slot = 0; slot < characters.length; slot++) { position(p, slot, 5 + slot * 2, 3 + slot * 2); }
        return p;
    }

    private HitScene hitScene() {
        Probe p = starRoom();
        ability(p, 0, 0);
        long right = views(p, "STAR").get(0).projectileId();
        position(p, 1, 5.3f, 3);
        return new HitScene(p, right);
    }

    private static void awaitHit(HitScene scene) {
        for (int tick = 0; tick < 90 && isActive(scene.p, scene.rightId); tick++) {
            position(scene.p, 0, 5, 3);
            position(scene.p, 1, 5.3f, 3);
            scene.p.runtime.tick();
        }
        assertFalse("Không đạt native contact trong giới hạn manual ticks", isActive(scene.p, scene.rightId));
    }

    private static void resolve(HitScene scene, boolean block) {
        position(scene.p, 0, 5, 3);
        position(scene.p, 1, 5.3f, 3);
        if (block) { assertEquals(ACCEPTED, scene.p.runtime.submitInput(input(1, 0, false, false, false, true))); }
        scene.p.runtime.tick();
    }

    private static void tile(Probe p, float x, float y) {
        BodyDef definition = new BodyDef();
        definition.type = BodyDef.BodyType.StaticBody;
        definition.position.set(x, y);
        Body body = p.world().createBody(definition);
        body.setUserData("Tile");
        PolygonShape shape = new PolygonShape();
        try {
            shape.setAsBox(0.03f, 0.3f);
            FixtureDef fixture = new FixtureDef();
            fixture.shape = shape;
            fixture.filter.categoryBits = 2;
            fixture.filter.maskBits = 1 | 3;
            body.createFixture(fixture);
        } finally { shape.dispose(); }
    }

    private static List<ServerProjectile.View> views(Probe p, String type) {
        return p.runtime.getProjectiles().stream().filter(view -> view.projectileType().equals(type)).toList();
    }
    private static boolean isActive(Probe p, long id) {
        return p.runtime.getProjectiles().stream().anyMatch(view -> view.projectileId() == id);
    }
    private static ServerProjectile.View byId(Probe p, long id) {
        return p.runtime.getProjectiles().stream().filter(view -> view.projectileId() == id).findFirst().orElseThrow();
    }
    private static void position(Probe p, int slot, float x, float y) {
        p.body(slot).setTransform(x, y, 0);
        p.body(slot).setLinearVelocity(0, 0);
    }
    private static void fireBoth(Probe p) { sendAbility(p, 1, 0); sendAbility(p, 0, 0); p.runtime.tick(); }
    private static void ability(Probe p, int slot, long sequence) { sendAbility(p, slot, sequence); p.runtime.tick(); }
    private static void sendAbility(Probe p, int slot, long sequence) {
        assertEquals(ACCEPTED, p.runtime.submitInput(input(slot, sequence, true, false, false, false)));
    }
    private static PlayerInput input(int slot, long sequence, boolean ability, boolean stomp, boolean melee, boolean block) {
        return new PlayerInput(id(slot), sequence, Long.MAX_VALUE, 0, false, stomp, melee, block, ability);
    }
    private static PlayerId id(int slot) { return new PlayerId("arbitrary-multi-projectile-player-" + slot); }
    private record HitScene(Probe p, long rightId) { }
    private record Probe(MatchRuntime runtime, AtomicReference<HeadlessPhysicsWorld> physics, AtomicInteger steps,
                         AtomicReference<Consumer<World>> afterStep) {
        World world() { return physics.get().getWorld(); }
        ServerPlayer player(int slot) { return runtime.getMatchState().getPlayer(id(slot)); }
        Body body(int slot) {
            Array<Body> bodies = new Array<>();
            world().getBodies(bodies);
            for (Body body : bodies) { if (id(slot).equals(body.getUserData())) { return body; } }
            throw new AssertionError("Thiếu player " + slot);
        }
        Body projectile(long id) {
            Array<Body> bodies = new Array<>();
            world().getBodies(bodies);
            for (Body body : bodies) {
                if (body.getUserData() instanceof ServerProjectile projectile && projectile.getProjectileId() == id) { return body; }
            }
            throw new AssertionError("Thiếu projectile " + id);
        }
    }
}
