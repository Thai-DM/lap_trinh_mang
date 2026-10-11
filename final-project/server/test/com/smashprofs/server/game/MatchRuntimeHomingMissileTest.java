package com.smashprofs.server.game;

import com.badlogic.gdx.math.Vector2;
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

/** Native Box2D/TMX thật, manual tick; chỉ bố trí test scene, không giả lập contact hoặc mở Desktop. */
public class MatchRuntimeHomingMissileTest {
    private static final float EPS = 0.0001f;
    private final List<MatchRuntime> runtimes = new ArrayList<>();

    @After public void closeResources() { runtimes.forEach(MatchRuntime::close); }

    @Test public void selectedAbilityBelongsToCorrectCharacter() {
        assertEquals(ProjectileType.ROCKET, ProjectileType.forCharacter("Alex"));
        Probe p = rocketRoom();
        ability(p, 0, 0);
        assertEquals("Alex", p.player(0).getCharacterId());
        assertEquals(id(0), rocket(p).ownerPlayerId());
        assertEquals(id(1), p.model(rocket(p).projectileId()).getTargetPlayerId());
    }

    @Test public void wrongCharacterCannotActivateAbility() {
        for (String character : List.of("Leo", "Maurice", "Viktor", "Luca")) {
            Probe p = create(character, "Alex");
            ability(p, 0, 0);
            assertTrue(views(p, "ROCKET").isEmpty());
            for (var view : p.runtime.getProjectiles()) {
                assertNull(p.model(view.projectileId()).getTargetPlayerId());
            }
        }
        assertNull(ProjectileType.forCharacter("Unknown"));
    }

    @Test public void validAbilityCanBeActivated() {
        Probe p = rocketRoom();
        int originalBodyCount = p.world().getBodyCount();
        ability(p, 0, 0);
        assertEquals(1, views(p, "ROCKET").size());
        assertEquals(originalBodyCount + 1, p.world().getBodyCount());
        assertEquals(p.runtime.getMatchId(), rocket(p).matchId());
        assertEquals(0, rocket(p).spawnTick());
        assertEquals(100, p.player(0).getHp(), EPS);
    }

    @Test public void invalidActivationIsRejected() {
        Probe p = rocketRoom();
        assertEquals(UNKNOWN_PLAYER, p.runtime.submitInput(input(9, 0, true, false, false, false)));
        assertEquals(INVALID_INPUT, p.runtime.submitInput(new PlayerInput(id(0), 0, 0,
                Float.NaN, false, false, false, false, true)));
        p.player(0).applyDamage(100);
        ability(p, 0, 0);
        assertTrue(views(p, "ROCKET").isEmpty());
        p.runtime.close();
        assertThrows(IllegalStateException.class, () -> sendAbility(p, 0, 1));
        assertThrows(IllegalStateException.class, p.runtime::tick);
    }

    @Test public void noLivingOpponentMeansNoRocket() {
        Probe p = create("Alex", "Luca", "Leo", "Viktor");
        int bodyCount = p.world().getBodyCount();
        for (int role = 1; role < 4; role++) { p.player(role).applyDamage(100); }
        ability(p, 0, 0);
        assertEquals(0, p.runtime.getActiveProjectileCount());
        assertEquals(bodyCount, p.world().getBodyCount());
    }

    @Test public void activationIsAllowedOnGroundInAirAndWhileBlocking() {
        Probe air = rocketRoom();
        assertFalse(air.runtime.getJumpState(id(0)).grounded());
        assertEquals(ACCEPTED, air.runtime.submitInput(input(0, 0, true, false, false, true)));
        air.runtime.tick();
        assertEquals(1, views(air, "ROCKET").size());
        assertTrue(air.player(0).isBlocking());
        Probe ground = landed();
        assertTrue(ground.runtime.getJumpState(id(0)).grounded());
        ability(ground, 0, 0);
        assertEquals(1, views(ground, "ROCKET").size());
    }

    @Test public void abilityCooldownMatchesOriginal() {
        assertEquals(180, ProjectileManager.ROCKET_COOLDOWN_TICKS);
        Probe p = rocketRoom();
        ability(p, 0, 0);
        while (p.runtime.getMatchState().getServerTick() < 179) { keepPlayersInAir(p); p.runtime.tick(); }
        keepPlayersInAir(p);
        ability(p, 0, 1); // Tick 179 vẫn bị chặn.
        assertEquals(1, views(p, "ROCKET").size());
        keepPlayersInAir(p);
        ability(p, 0, 2); // Tick 180 được phép; request bị chặn không gia hạn cooldown.
        assertEquals(2, views(p, "ROCKET").size());
        assertEquals(List.of(0L, 180L), views(p, "ROCKET").stream().map(ServerProjectile.View::spawnTick).toList());
    }

    @Test public void packetSpamDoesNotExtendCooldown() {
        Probe p = rocketRoom();
        ability(p, 0, 0);
        for (int tick = 1; tick < 180; tick++) {
            keepPlayersInAir(p);
            ability(p, 0, tick);
            assertEquals(1, views(p, "ROCKET").size());
        }
        keepPlayersInAir(p);
        ability(p, 0, 180);
        assertEquals(2, views(p, "ROCKET").size());
    }

    @Test public void rocketOwnersHaveSeparateCooldownDeadlines() {
        Probe p = create("Alex", "Alex");
        ability(p, 0, 0); // Deadline 180.
        ability(p, 1, 0); // Deadline 181.
        while (p.runtime.getMatchState().getServerTick() < 180) { keepPlayersInAir(p); p.runtime.tick(); }
        keepPlayersInAir(p);
        sendAbility(p, 0, 1);
        sendAbility(p, 1, 1);
        p.runtime.tick();
        assertEquals(1, views(p, "ROCKET").stream().filter(v -> v.spawnTick() == 180).count());
        assertEquals(id(0), views(p, "ROCKET").stream().filter(v -> v.spawnTick() == 180)
                .findFirst().orElseThrow().ownerPlayerId());
        keepPlayersInAir(p);
        ability(p, 1, 2);
        assertEquals(id(1), views(p, "ROCKET").stream().filter(v -> v.spawnTick() == 181)
                .findFirst().orElseThrow().ownerPlayerId());
    }

    @Test public void duplicateInputDoesNotActivateTwice() {
        Probe p = rocketRoom();
        sendAbility(p, 0, 0);
        assertEquals(DUPLICATE_OR_STALE, p.runtime.submitInput(input(0, 0, true, false, false, false)));
        p.runtime.tick();
        assertEquals(1, views(p, "ROCKET").size());
        while (p.runtime.getMatchState().getServerTick() < 180) { keepPlayersInAir(p); p.runtime.tick(); }
        assertEquals(DUPLICATE_OR_STALE, p.runtime.submitInput(input(0, 0, true, false, false, false)));
        keepPlayersInAir(p);
        p.runtime.tick();
        assertEquals(1, views(p, "ROCKET").size());
    }

    @Test public void batchEdgesActivateOnlyOnceAndCooldownExpiryDoesNotAutoFire() {
        Probe p = rocketRoom();
        for (int sequence = 0; sequence < 8; sequence++) { sendAbility(p, 0, sequence); }
        assertEquals(1, p.runtime.tick().size());
        assertEquals(1, views(p, "ROCKET").size());
        while (p.runtime.getMatchState().getServerTick() <= 180) { keepPlayersInAir(p); p.runtime.tick(); }
        assertEquals(1, views(p, "ROCKET").size());
    }

    @Test public void abilityEffectMatchesOriginalMechanic() {
        Probe p = rocketRoom();
        position(p, 1, 7, 4);
        ability(p, 0, 0);
        long projectileId = rocket(p).projectileId();
        Vector2 history = new Vector2();
        for (int tick = 0; tick < 10; tick++) {
            position(p, 1, tick % 2 == 0 ? 7 : 6, tick % 2 == 0 ? 4 : 5);
            var target = p.body(1).getPosition().cpy();
            var before = p.projectile(projectileId).getPosition().cpy();
            history.lerp(target.sub(before), 0.05f).nor().scl(0.5f);
            p.runtime.tick();
            assertEquals(history.x, p.projectile(projectileId).getLinearVelocity().x, EPS);
            assertEquals(history.y, p.projectile(projectileId).getLinearVelocity().y, EPS);
            assertEquals(before.x + history.x / 60, p.projectile(projectileId).getPosition().x, EPS);
            assertEquals(before.y + history.y / 60, p.projectile(projectileId).getPosition().y, EPS);
            assertEquals(0.5f, p.projectile(projectileId).getLinearVelocity().len(), EPS);
        }
    }

    @Test public void homingUsesScaledVectorAsHistoryRatherThanRawDirection() {
        Probe p = rocketRoom();
        position(p, 1, 7, 3);
        ability(p, 0, 0);
        p.runtime.tick();
        Vector2 previous = p.projectile(rocket(p).projectileId()).getLinearVelocity().cpy();
        assertEquals(0.5f, previous.len(), EPS);
        position(p, 1, 5.5f, 5);
        Vector2 delta = p.body(1).getPosition().cpy().sub(p.projectile(rocket(p).projectileId()).getPosition());
        Vector2 expected = previous.cpy().lerp(delta, 0.05f).nor().scl(0.5f);
        Vector2 incorrect = previous.cpy().nor().lerp(delta, 0.05f).nor().scl(0.5f);
        assertTrue(expected.dst(incorrect) > 0.01f);
        p.runtime.tick();
        assertEquals(expected.x, rocket(p).velocityX(), EPS);
        assertEquals(expected.y, rocket(p).velocityY(), EPS);
    }

    @Test public void abilityPhysicsMatchesOriginal() {
        Probe p = rocketRoom();
        ability(p, 0, 0);
        var view = rocket(p);
        Body body = p.projectile(view.projectileId());
        Fixture fixture = body.getFixtureList().first();
        assertEquals(BodyDef.BodyType.DynamicBody, body.getType());
        assertFalse(body.isFixedRotation());
        assertFalse(body.isBullet());
        assertEquals(0, body.getAngularVelocity(), EPS);
        assertEquals(Shape.Type.Circle, fixture.getShape().getType());
        assertEquals(0.03f, fixture.getShape().getRadius(), EPS);
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
        assertEquals(0, view.velocityY(), EPS); // Spawn tick chưa update homing như source.
    }

    @Test public void rocketDoesNotReceiveMineGravityOrIbmVelocity() {
        Probe p = rocketRoom();
        position(p, 1, 7, 3);
        ability(p, 0, 0);
        for (int tick = 0; tick < 10; tick++) {
            position(p, 1, 7, rocket(p).y());
            p.runtime.tick();
            assertEquals(0.5f, rocket(p).velocityX(), EPS);
            assertEquals(0, rocket(p).velocityY(), EPS);
            assertEquals(3, rocket(p).y(), EPS);
        }
    }

    @Test public void spawnUsesFacingButHomingUsesTargetPosition() {
        Probe p = rocketRoom();
        p.body(0).setLinearVelocity(-0.3f, 0);
        position(p, 1, 7, 5);
        ability(p, 0, 0);
        assertFalse(p.player(0).isFacingRight());
        assertEquals(4.85f, rocket(p).x(), EPS);
        p.runtime.tick();
        assertTrue(rocket(p).velocityX() > 0); // Target bên phải dù owner nhìn trái.
    }

    @Test public void nearestLivingOpponentIsChosenAtSpawn() {
        Probe p = create("Alex", "Luca", "Luca", "Luca");
        position(p, 1, 7, 3);
        position(p, 2, 6, 3);
        position(p, 3, 5.3f, 3);
        p.player(3).applyDamage(100); // Gần nhất nhưng đã chết, không được chọn khi spawn.
        ability(p, 0, 0);
        assertEquals(id(2), p.model(rocket(p).projectileId()).getTargetPlayerId());
    }

    @Test public void targetTieUsesPlayerIdInsteadOfSlotOrRosterOrder() {
        for (int[] slots : List.of(new int[]{0, 1, 2, 3}, new int[]{2, 3, 0, 1})) {
            Probe p = create(slots, "Alex", "Luca", "Luca", "Luca");
            position(p, 1, 4, 3);
            position(p, 2, 6, 3);
            position(p, 3, 5, 5);
            ability(p, 0, 0);
            assertEquals(id(1), p.model(rocket(p).projectileId()).getTargetPlayerId());
        }
    }

    @Test public void targetIsLockedEvenWhenAnotherOpponentBecomesCloser() {
        Probe p = create("Alex", "Luca", "Luca");
        position(p, 1, 7, 3);
        position(p, 2, 7, 6);
        ability(p, 0, 0);
        long projectileId = rocket(p).projectileId();
        position(p, 2, 5.2f, 4);
        p.runtime.tick();
        assertEquals(id(1), p.model(projectileId).getTargetPlayerId());
        assertTrue(rocket(p).velocityX() > 0.49f);
        assertTrue(Math.abs(rocket(p).velocityY()) < 0.01f);
    }

    @Test public void targetDeathDoesNotRetargetOrDestroyRocket() {
        Probe p = create("Alex", "Luca", "Luca");
        position(p, 1, 7, 3);
        position(p, 2, 7, 6);
        ability(p, 0, 0);
        long projectileId = rocket(p).projectileId();
        p.player(1).applyDamage(100);
        position(p, 1, 7, 4); // Body target vẫn tồn tại; chỉ bố trí trong test để chứng minh đang bám body đó.
        position(p, 2, 5.3f, 5);
        for (int tick = 0; tick < 10; tick++) { p.runtime.tick(); }
        assertTrue(p.model(projectileId).isActive());
        assertEquals(id(1), p.model(projectileId).getTargetPlayerId());
        assertTrue(rocket(p).velocityX() > 0);
        assertTrue(rocket(p).velocityY() > 0);
        assertEquals(0, p.player(1).getHp(), 0);
        assertEquals(100, p.player(2).getHp(), EPS);
    }

    @Test public void separateRocketsCanLockDifferentTargetsForSameOwner() {
        Probe p = create("Alex", "Luca", "Luca");
        position(p, 1, 7, 4);
        position(p, 2, 7, 6);
        ability(p, 0, 0);
        long first = rocket(p).projectileId();
        while (p.runtime.getMatchState().getServerTick() < 180) {
            keepPlayersInAir(p);
            p.projectile(first).setTransform(5, 20, 0); // Tách bài kiểm tra cooldown/ownership khỏi contact.
            p.runtime.tick();
        }
        keepPlayersInAir(p);
        position(p, 1, 7, 7);
        position(p, 2, 6, 4);
        ability(p, 0, 1);
        assertEquals(2, views(p, "ROCKET").size());
        assertEquals(id(1), p.model(first).getTargetPlayerId());
        assertEquals(id(2), p.model(views(p, "ROCKET").get(1).projectileId()).getTargetPlayerId());
    }

    @Test public void abilityDamageMatchesOriginal() {
        Probe p = contactScene(2, 0, 1, false, new int[]{0, 1});
        assertEquals(100, p.player(1).getHp(), EPS);
        resolve(p, 0, 1, false, false);
        assertEquals(90, p.player(1).getHp(), EPS);
        assertEquals(10, ProjectileManager.ROCKET_DAMAGE, 0);
        assertEquals(100, p.player(0).getHp(), EPS);
    }

    @Test public void abilityHitDetectionIsCorrect() {
        Probe p = rocketRoom();
        ability(p, 0, 0);
        position(p, 1, 5.4f, 3); // Ngoài tổng radius 0.03 + 0.05.
        p.runtime.tick();
        assertEquals(1, views(p, "ROCKET").size());
        assertEquals(100, p.player(1).getHp(), EPS);
        awaitRocketContact(p, 1);
        resolve(p, 0, 1, false, false);
        assertEquals(90, p.player(1).getHp(), EPS);
        for (int tick = 0; tick < 10; tick++) { p.runtime.tick(); }
        assertEquals(90, p.player(1).getHp(), EPS);
    }

    @Test public void nativeHomingFlightReachesAndDamagesStationaryOpponent() {
        Probe p = rocketRoom();
        position(p, 1, 5.45f, 3);
        ability(p, 0, 0);
        long projectileId = rocket(p).projectileId();
        float initialX = rocket(p).x();
        for (int tick = 0; tick < 90 && active(p, projectileId); tick++) {
            position(p, 0, 5, 3);
            position(p, 1, 5.45f, 3);
            p.runtime.tick(); // Rocket tự bay; không teleport projectile hoặc target vào projectile.
            if (active(p, projectileId)) { assertTrue(rocket(p).x() > initialX); }
        }
        assertFalse(active(p, projectileId));
        assertEquals(100, p.player(1).getHp(), EPS);
        resolve(p, 0, 1, false, false);
        assertEquals(90, p.player(1).getHp(), EPS);
    }

    @Test public void rocketCanHitAnotherPlayerInsteadOfItsGuidanceTarget() {
        Probe p = create("Alex", "Luca", "Luca");
        position(p, 1, 7, 3);
        position(p, 2, 7, 6);
        ability(p, 0, 0);
        ServerProjectile model = p.model(rocket(p).projectileId());
        assertEquals(id(1), model.getTargetPlayerId());
        awaitRocketContact(p, 2);
        resolve(p, 0, 2, false, false);
        assertEquals(90, p.player(2).getHp(), EPS);
        assertEquals(100, p.player(1).getHp(), EPS);
        assertFalse(model.isActive());
    }

    @Test public void sourceDoesNotGiveRocketOwnerImmunity() {
        Probe p = rocketRoom();
        ability(p, 0, 0);
        awaitRocketContact(p, 0);
        p.runtime.tick();
        assertEquals(90, p.player(0).getHp(), EPS);
        assertEquals(100, p.player(1).getHp(), EPS);
    }

    @Test public void deadTargetContactEndsRocketWithoutApplyingDamageOrKnockback() {
        Probe p = create("Alex", "Luca", "Luca"); // Trận tiếp tục sau khi target chết.
        ability(p, 0, 0);
        p.player(1).applyDamage(100);
        awaitRocketContact(p, 1);
        position(p, 1, 5.3f, 3);
        p.runtime.tick();
        assertEquals(0, p.player(1).getHp(), 0);
        assertEquals(0, p.body(1).getLinearVelocity().x, EPS);
        assertEquals(-0.098f, p.body(1).getLinearVelocity().y, EPS);
    }

    @Test public void abilityEndsAccordingToOriginalRules() {
        Probe p = rocketRoom();
        ability(p, 0, 0);
        long projectileId = rocket(p).projectileId();
        tile(p, 5.3f, 3);
        for (int tick = 0; tick < 60 && active(p, projectileId); tick++) {
            position(p, 0, 5, 3);
            position(p, 1, 7, 3);
            p.runtime.tick();
        }
        assertFalse(active(p, projectileId));
        p.runtime.tick();
        assertEquals(100, p.player(0).getHp(), EPS);
        assertEquals(100, p.player(1).getHp(), EPS); // Explosion VFX không phải AOE damage.
    }

    @Test public void rocketContactWithOriginalTmxTileUsesDeferredCleanup() {
        Probe p = rocketRoom();
        Array<Body> bodies = new Array<>();
        p.world().getBodies(bodies);
        Body originalTile = null;
        for (Body body : bodies) {
            if ("Tile".equals(body.getUserData()) && body.getPosition().x > 3.5f
                    && body.getPosition().x < 8 && body.getPosition().y > 0.3f) {
                originalTile = body;
                break;
            }
        }
        assertNotNull(originalTile);
        position(p, 0, originalTile.getPosition().x - 0.15f, originalTile.getPosition().y);
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
        for (int tick = 0; tick < 8 && !views(p, "ROCKET").isEmpty(); tick++) { p.runtime.tick(); }
        assertTrue(contacts.get() > 0);
        assertEquals(0, views(p, "ROCKET").size());
        p.runtime.tick();
        assertEquals(100, p.player(1).getHp(), EPS);
    }

    @Test public void rocketAndIbmContactDestroysOnlyTheContactingProjectiles() {
        Probe p = create("Alex", "Viktor", "Leo");
        sendAbility(p, 0, 0);
        sendAbility(p, 1, 0);
        p.runtime.tick();
        long rocketId = rocket(p).projectileId();
        long ibmId = views(p, "IBM").get(0).projectileId();
        for (int tick = 0; tick < 8 && active(p, rocketId); tick++) {
            var current = rocket(p);
            p.projectile(ibmId).setTransform(current.x(), current.y(), 0);
            p.runtime.tick();
        }
        assertFalse(active(p, rocketId));
        assertFalse(active(p, ibmId));
        p.runtime.tick();
        for (int role = 0; role < 3; role++) { assertEquals(100, p.player(role).getHp(), EPS); }
    }

    @Test public void rocketHasNoInventedLifetimeAndMayKeepFollowingDeadTarget() {
        Probe p = create("Alex", "Luca", "Luca");
        position(p, 0, 5, 20);
        position(p, 1, 7, 20);
        ability(p, 0, 0);
        long projectileId = rocket(p).projectileId();
        p.player(1).applyDamage(100);
        for (int tick = 0; tick < 400; tick++) {
            var current = rocket(p);
            position(p, 0, 5, 3);
            position(p, 1, 7, current.y() + 2); // Target body luôn đủ xa; không giả contact.
            p.runtime.tick();
        }
        assertTrue(active(p, projectileId));
        assertEquals(id(1), p.model(projectileId).getTargetPlayerId());
        assertTrue(rocket(p).y() > 20);
    }

    @Test public void abilityCleanupIsSafe() {
        Probe p = rocketRoom();
        ability(p, 0, 0);
        ServerProjectile model = p.model(rocket(p).projectileId());
        int bodyCount = p.world().getBodyCount();
        AtomicInteger contacts = new AtomicInteger();
        p.afterStep.set(world -> {
            for (Contact contact : world.getContactList()) {
                if (contact.isTouching() && (contact.getFixtureA().getBody().getUserData() == model
                        || contact.getFixtureB().getBody().getUserData() == model)) {
                    contacts.incrementAndGet();
                    assertTrue(model.isActive());
                    assertEquals(bodyCount, world.getBodyCount()); // Chưa destroy trong step/callback.
                    assertEquals(100, p.player(1).getHp(), EPS);
                }
            }
        });
        awaitRocketContact(p, 1);
        assertTrue(contacts.get() > 0);
        assertFalse(model.isActive());
        assertEquals(bodyCount - 1, p.world().getBodyCount());
        p.afterStep.set(world -> { });
        resolve(p, 0, 1, false, false);
        assertEquals(90, p.player(1).getHp(), EPS);
    }

    @Test public void closeReleasesRocketAndRejectsFurtherExecution() {
        Probe p = rocketRoom();
        ability(p, 0, 0);
        ServerProjectile model = p.model(rocket(p).projectileId());
        p.runtime.close();
        p.runtime.close();
        assertFalse(model.isActive());
        assertTrue(p.physics.get().isDisposed());
        assertThrows(IllegalStateException.class, p.runtime::tick);
        assertThrows(IllegalStateException.class, p.runtime::getProjectiles);
    }

    @Test public void lethalRocketDamageUsesExistingFloatHpClamp() {
        Probe p = contactScene(2, 0, 1, false, new int[]{0, 1});
        p.player(1).applyDamage(99.75f);
        resolve(p, 0, 1, false, false);
        assertEquals(0, p.player(1).getHp(), 0);
        assertTrue(p.player(1).isDead());
    }

    @Test public void ownerDeathDoesNotEraseExistingRocket() {
        Probe p = create("Alex", "Luca", "Luca");
        ability(p, 0, 0);
        p.player(0).applyDamage(100);
        p.runtime.tick();
        assertEquals(1, views(p, "ROCKET").size());
        awaitRocketContact(p, 1);
        resolve(p, 0, 1, false, false);
        assertEquals(90, p.player(1).getHp(), EPS);
    }

    @Test public void abilityCannotAffectOtherRoom() {
        Probe a = contactScene(2, 0, 1, false, new int[]{0, 1});
        Probe b = rocketRoom();
        position(b, 1, 5.3f, 3); // Cùng PlayerId/vị trí nhưng thuộc World/match khác.
        resolve(a, 0, 1, false, false);
        b.runtime.tick();
        assertEquals(90, a.player(1).getHp(), EPS);
        assertEquals(100, b.player(1).getHp(), EPS);
        assertEquals(0, b.runtime.getActiveProjectileCount());
    }

    @Test public void twoRoomsKeepIndependentTargetsCooldownsAndNativeResources() {
        Probe a = rocketRoom();
        Probe b = rocketRoom();
        position(a, 1, 7, 4);
        position(b, 1, 4, 5);
        ability(a, 0, 0);
        ability(b, 0, 0);
        ServerProjectile bModel = b.model(rocket(b).projectileId());
        assertNotSame(a.world(), b.world());
        assertNotEquals(rocket(a).matchId(), rocket(b).matchId());
        a.runtime.close();
        b.runtime.tick();
        assertTrue(bModel.isActive());
        assertTrue(rocket(b).velocityX() < 0);
        assertFalse(b.physics.get().isDisposed());
    }

    @Test public void abilityDoesNotDependOnPlayerSlot() {
        for (int count = 2; count <= 4; count++) {
            for (int attackerSlot = 0; attackerSlot < count; attackerSlot++) {
                int[] slots = new int[count];
                for (int role = 0; role < count; role++) { slots[role] = (attackerSlot + role) % count; }
                for (boolean left : new boolean[]{false, true}) {
                    Probe p = contactScene(count, 0, 1, left, slots);
                    resolve(p, 0, 1, left, false);
                    assertEquals(90, p.player(1).getHp(), EPS);
                    assertEquals(left ? -1.5f : 1.5f, p.body(1).getLinearVelocity().x, EPS);
                    assertEquals(1.804f, p.body(1).getLinearVelocity().y, EPS);
                    assertEquals(100, p.player(0).getHp(), EPS);
                }
            }
        }
    }

    @Test public void swappingAttackerAndTargetPreservesDamageAndKnockback() {
        for (int count = 2; count <= 4; count++) {
            int[] slots = new int[count];
            for (int role = 0; role < count; role++) { slots[role] = role; }
            Probe p = contactScene(count, 1, 0, false, slots);
            resolve(p, 1, 0, false, false);
            assertEquals(90, p.player(0).getHp(), EPS);
            assertEquals(1.5f, p.body(0).getLinearVelocity().x, EPS);
            assertEquals(1.804f, p.body(0).getLinearVelocity().y, EPS);
        }
    }

    @Test public void twoPlayersUseAbilitiesIndependently() {
        Probe p = create("Alex", "Alex");
        ability(p, 0, 0);
        ability(p, 1, 0);
        assertEquals(2, views(p, "ROCKET").size());
        var rockets = views(p, "ROCKET");
        assertEquals(id(1), p.model(rockets.get(0).projectileId()).getTargetPlayerId());
        assertEquals(id(0), p.model(rockets.get(1).projectileId()).getTargetPlayerId());
        assertNotSame(p.model(rockets.get(0).projectileId()), p.model(rockets.get(1).projectileId()));
        p.runtime.tick();
        assertTrue(views(p, "ROCKET").get(0).velocityY() > 0);
        assertTrue(views(p, "ROCKET").get(1).velocityY() < 0);
    }

    @Test public void fourPlayersHaveIndependentAbilityState() {
        for (int count = 2; count <= 4; count++) {
            Probe p = create(Collections.nCopies(count, "Alex").toArray(String[]::new));
            for (int role = count - 1; role >= 0; role--) { sendAbility(p, role, 0); }
            p.runtime.tick();
            assertEquals(count, views(p, "ROCKET").size());
            for (var view : views(p, "ROCKET")) {
                assertNotEquals(view.ownerPlayerId(), p.model(view.projectileId()).getTargetPlayerId());
                assertEquals(0, view.spawnTick());
            }
            for (int role = 0; role < count; role++) { sendAbility(p, role, 1); }
            p.runtime.tick();
            assertEquals(count, views(p, "ROCKET").size());
            for (var view : views(p, "ROCKET")) {
                assertEquals(0.5f, p.projectile(view.projectileId()).getLinearVelocity().len(), EPS);
            }
        }
    }

    @Test public void oldProjectileAbilitiesStillWork() {
        Probe p = create("Alex", "Leo", "Maurice", "Viktor");
        for (int role = 0; role < 4; role++) { sendAbility(p, role, 0); }
        p.runtime.tick();
        assertEquals(7, p.runtime.getActiveProjectileCount());
        p.runtime.tick();
        assertEquals(1, views(p, "ROCKET").size());
        assertEquals(1, views(p, "MINE").size());
        assertEquals(-0.098f, views(p, "MINE").get(0).velocityY(), EPS);
        assertEquals(1, views(p, "IBM").size());
        assertEquals(0.7f, views(p, "IBM").get(0).velocityX(), EPS);
        assertEquals(4, views(p, "STAR").size());
        float[][] vectors = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int i = 0; i < 4; i++) {
            assertEquals(vectors[i][0], views(p, "STAR").get(i).velocityX(), EPS);
            assertEquals(vectors[i][1], views(p, "STAR").get(i).velocityY(), EPS);
        }
    }

    @Test public void meleeAndStompStillWork() {
        Probe melee = rocketRoom();
        position(melee, 1, 5.15f, 3);
        assertEquals(ACCEPTED, melee.runtime.submitInput(input(0, 0, false, false, true, false)));
        melee.runtime.tick();
        assertEquals(90, melee.player(1).getHp(), EPS);
        assertEquals(0.004f, melee.body(1).getLinearVelocity().y, EPS);
        Probe stomp = landed();
        float floor = stomp.body(0).getPosition().y;
        position(stomp, 0, 4.5f, floor + 0.3f);
        position(stomp, 1, 4.6f, floor);
        stomp.runtime.tick();
        assertEquals(ACCEPTED, stomp.runtime.submitInput(input(0, 0, false, true, false, false)));
        stomp.runtime.tick();
        assertEquals(99.9f, stomp.player(0).getHp(), EPS);
        for (int tick = 0; tick < 120 && !stomp.runtime.getStompState(id(0)).pendingLanding(); tick++) { stomp.runtime.tick(); }
        assertTrue(stomp.runtime.getStompState(id(0)).pendingLanding());
        stomp.runtime.tick();
        assertEquals(90, stomp.player(1).getHp(), EPS);
    }

    @Test public void blockingAndKnockbackStillWork() {
        Probe melee = rocketRoom();
        position(melee, 1, 5.15f, 3);
        assertEquals(ACCEPTED, melee.runtime.submitInput(input(1, 0, false, false, false, true)));
        assertEquals(ACCEPTED, melee.runtime.submitInput(input(0, 0, false, false, true, false)));
        melee.runtime.tick();
        assertTrue(melee.player(1).isBlocking());
        assertEquals(100, melee.player(1).getHp(), EPS);
        Probe hit = contactScene(2, 0, 1, false, new int[]{0, 1});
        resolve(hit, 0, 1, false, true);
        assertTrue(hit.player(1).isBlocking());
        assertEquals(90, hit.player(1).getHp(), EPS); // Source projectile không check block.
        assertEquals(1.5f, hit.body(1).getLinearVelocity().x, EPS);
        assertEquals(1.804f, hit.body(1).getLinearVelocity().y, EPS);
    }

    @Test public void movementAndJumpStillWork() {
        Probe moving = rocketRoom();
        assertEquals(ACCEPTED, moving.runtime.submitInput(new PlayerInput(id(0), 0, 0,
                1, false, false, false, false, true)));
        moving.runtime.tick();
        assertEquals(0.055f, moving.body(0).getLinearVelocity().x, EPS);
        assertEquals(-0.098f, moving.body(0).getLinearVelocity().y, EPS);
        Probe jump = landed();
        float initialVelocity = jump.body(0).getLinearVelocity().y;
        assertEquals(ACCEPTED, jump.runtime.submitInput(new PlayerInput(id(0), 0, 0,
                0, true, false, false, false, true)));
        jump.runtime.tick();
        assertEquals(initialVelocity * 0.2f + 2.2f - 0.098f, jump.body(0).getLinearVelocity().y, EPS);
        assertEquals(1, jump.runtime.getJumpState(id(0)).jumpsUsed());
        assertEquals(1, views(jump, "ROCKET").size());
        float beforeDoubleJump = jump.body(0).getLinearVelocity().y;
        assertEquals(ACCEPTED, jump.runtime.submitInput(new PlayerInput(id(0), 1, 0,
                0, true, false, false, false, false)));
        jump.runtime.tick();
        assertEquals(beforeDoubleJump * 0.2f + 2.2f - 0.098f, jump.body(0).getLinearVelocity().y, EPS);
        assertEquals(2, jump.runtime.getJumpState(id(0)).jumpsUsed());
    }

    @Test public void worldStepsExactlyOncePerTick() {
        Probe p = create("Alex", "Leo", "Maurice", "Viktor");
        for (int role = 3; role >= 0; role--) { sendAbility(p, role, 0); }
        p.runtime.tick();
        for (int tick = 1; tick < 10; tick++) { keepPlayersInAir(p); p.runtime.tick(); }
        assertEquals(10, p.steps.get());
        assertEquals(10, p.runtime.getMatchState().getServerTick());
    }

    @Test(timeout = 15000) public void networkThreadCannotExecuteHomingOrStepWorld() throws Exception {
        Probe p = create("Alex", "Alex");
        p.runtime.tick();
        var offers = InputQueueTest.runConcurrently(List.<Callable<InputQueue.OfferResult>>of(
                () -> p.runtime.submitInput(input(0, 0, true, false, false, false)),
                () -> p.runtime.submitInput(input(1, 0, true, false, false, false))));
        assertEquals(List.of(ACCEPTED, ACCEPTED), offers);
        InputQueueTest.runConcurrently(List.<Callable<Boolean>>of(() -> {
            assertThrows(IllegalStateException.class, p.runtime::tick);
            return true;
        }));
        assertEquals(0, p.runtime.getActiveProjectileCount());
        assertEquals(1, p.steps.get());
        p.runtime.tick();
        assertEquals(2, views(p, "ROCKET").size());
    }

    private Probe rocketRoom() { return create("Alex", "Luca"); }

    private Probe create(String... characters) {
        int[] slots = new int[characters.length];
        for (int role = 0; role < characters.length; role++) { slots[role] = role; }
        return create(slots, characters);
    }

    private Probe create(int[] slots, String... characters) {
        List<MatchSetup.Participant> roster = new ArrayList<>();
        for (int role = 0; role < characters.length; role++) {
            roster.add(new MatchSetup.Participant(id(role), slots[role], characters[role]));
        }
        Collections.reverse(roster);
        String matchId = "rocket-test-" + runtimes.size();
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

    private Probe landed() {
        Probe p = rocketRoom();
        position(p, 0, 4.5f, 0.85f);
        position(p, 1, 7.25f, 0.5f);
        for (int tick = 0; tick < 180 && !p.runtime.getJumpState(id(0)).grounded(); tick++) { p.runtime.tick(); }
        assertTrue(p.runtime.getJumpState(id(0)).grounded());
        return p;
    }

    private Probe contactScene(int count, int attacker, int target, boolean left, int[] slots) {
        String[] characters = Collections.nCopies(count, "Luca").toArray(String[]::new);
        characters[attacker] = "Alex";
        Probe p = create(slots, characters);
        for (int role = 0; role < count; role++) { position(p, role, 7, 7 + role * 2); }
        position(p, attacker, 5, 3);
        position(p, target, left ? 4 : 7, 3);
        if (left) { p.body(attacker).setLinearVelocity(-0.3f, 0); }
        ability(p, attacker, 0);
        var view = rocket(p);
        assertEquals(left ? 4.85f : 5.15f, view.x(), EPS);
        assertEquals(id(target), p.model(view.projectileId()).getTargetPlayerId());
        awaitRocketContact(p, target);
        return p;
    }

    private static void awaitRocketContact(Probe p, int target) {
        // Native broadphase có thể cần step kế tiếp sau setTransform; không giả callback hoặc sleep.
        for (int tick = 0; tick < 8 && !views(p, "ROCKET").isEmpty(); tick++) {
            var view = rocket(p);
            position(p, target, view.x(), view.y());
            p.runtime.tick();
        }
        assertEquals("Không đạt native contact trong 8 ticks", 0, views(p, "ROCKET").size());
    }

    private static void resolve(Probe p, int attacker, int target, boolean left, boolean block) {
        position(p, attacker, 5, 3);
        position(p, target, left ? 4.7f : 5.3f, 3);
        if (block) { assertEquals(ACCEPTED, p.runtime.submitInput(input(target, 0, false, false, false, true))); }
        p.runtime.tick();
    }

    private static void keepPlayersInAir(Probe p) {
        for (int role = 0; role < p.runtime.getParticipants().size(); role++) { position(p, role, 5 + role * 0.8f, 3 + role * 2); }
    }
    private static void position(Probe p, int role, float x, float y) {
        p.body(role).setTransform(x, y, 0);
        p.body(role).setLinearVelocity(0, 0);
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
    private static ServerProjectile.View rocket(Probe p) { return views(p, "ROCKET").get(0); }
    private static boolean active(Probe p, long projectileId) {
        return p.runtime.getProjectiles().stream().anyMatch(view -> view.projectileId() == projectileId);
    }
    private static void ability(Probe p, int role, long sequence) { sendAbility(p, role, sequence); p.runtime.tick(); }
    private static void sendAbility(Probe p, int role, long sequence) {
        assertEquals(ACCEPTED, p.runtime.submitInput(input(role, sequence, true, false, false, false)));
    }
    private static PlayerInput input(int role, long sequence, boolean ability, boolean stomp, boolean melee, boolean block) {
        return new PlayerInput(id(role), sequence, Long.MAX_VALUE, 0, false, stomp, melee, block, ability);
    }
    private static PlayerId id(int role) { return new PlayerId("rocket-player-" + role); }
    private record Probe(MatchRuntime runtime, AtomicReference<HeadlessPhysicsWorld> physics, AtomicInteger steps,
                         AtomicReference<Consumer<World>> afterStep) {
        World world() { return physics.get().getWorld(); }
        ServerPlayer player(int role) { return runtime.getMatchState().getPlayer(id(role)); }
        Body body(int role) {
            Array<Body> bodies = new Array<>();
            world().getBodies(bodies);
            for (Body body : bodies) { if (id(role).equals(body.getUserData())) { return body; } }
            throw new AssertionError("Thiếu player body: " + role);
        }
        Body projectile(long projectileId) {
            Array<Body> bodies = new Array<>();
            world().getBodies(bodies);
            for (Body body : bodies) {
                if (body.getUserData() instanceof ServerProjectile projectile
                        && projectile.getProjectileId() == projectileId) { return body; }
            }
            throw new AssertionError("Thiếu projectile: " + projectileId);
        }
        ServerProjectile model(long projectileId) { return (ServerProjectile) projectile(projectileId).getUserData(); }
    }
}
