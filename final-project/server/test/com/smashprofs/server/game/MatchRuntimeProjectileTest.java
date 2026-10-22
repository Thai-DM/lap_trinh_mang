package com.smashprofs.server.game;

import com.badlogic.gdx.physics.box2d.*;
import com.badlogic.gdx.utils.Array;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import com.smashprofs.server.game.physics.HeadlessPhysicsWorld;
import com.smashprofs.server.game.physics.PhysicsWorldFactory;
import com.smashprofs.server.game.projectile.ProjectileManager;
import com.smashprofs.server.game.projectile.ServerProjectile;
import com.smashprofs.server.network.ClientSession;
import com.smashprofs.server.network.ConnectionManager;
import com.smashprofs.server.network.PlayerInputHandler;
import com.smashprofs.server.network.SessionChannel;
import com.smashprofs.server.room.RoomManager;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static com.smashprofs.server.game.InputQueue.OfferResult.*;
import static org.junit.Assert.*;

/** Manual tick trên World/TMX/native thật. Bố trí Body chỉ trong test, không dùng sleep/Desktop. */
public class MatchRuntimeProjectileTest {
    private static final float EPS = 0.0001f;
    private final List<MatchRuntime> runtimes = new ArrayList<>();

    @After public void closeResources() { runtimes.forEach(MatchRuntime::close); }

    @Test public void projectileManagerStartsEmpty() {
        Probe p = airborne(2);
        assertEquals(0, p.runtime.getActiveProjectileCount());
        assertTrue(p.runtime.getProjectiles().isEmpty());
        assertEquals(20, p.world().getBodyCount());
    }

    @Test public void validAbilityCreatesProjectile() {
        Probe p = airborne(2);
        ability(p, 0, 0);
        assertEquals(1, p.runtime.getActiveProjectileCount());
        assertEquals(21, p.world().getBodyCount());
        assertEquals("IBM", view(p).projectileType());
    }

    @Test public void invalidAbilityDoesNotCreateProjectile() {
        // Bước 38 đã hỗ trợ Rocket của Alex; chỉ Circle của Luca chưa được port.
        for (String character : List.of("Luca")) {
            Probe p = create(List.of(character, "Viktor"));
            ability(p, 0, 0);
            assertEquals(0, p.runtime.getActiveProjectileCount());
        }
        Probe p = airborne(2);
        assertEquals(UNKNOWN_PLAYER, p.runtime.submitInput(input(9, 0, 0, false, false, false, false, true)));
        assertEquals(INVALID_INPUT, p.runtime.submitInput(input(0, 0, Float.NaN, false, false, false, false, true)));
        p.runtime.tick();
        assertEquals(0, p.runtime.getActiveProjectileCount());
    }

    @Test public void deadPlayerCannotSpawnProjectile() {
        Probe p = airborne(2);
        p.player(0).applyDamage(100);
        ability(p, 0, 0);
        assertEquals(0, p.runtime.getActiveProjectileCount());
        assertEquals(0, p.player(0).getHp(), 0);
    }

    @Test public void projectileHasCorrectOwnerPlayerId() {
        Probe p = airborne(4);
        ability(p, 3, 0);
        assertEquals(id(3), view(p).ownerPlayerId());
        assertEquals(0, view(p).spawnTick());
    }

    @Test public void projectileBelongsToCorrectMatch() {
        Probe a = airborne(2);
        Probe b = airborne(2);
        ability(a, 0, 0);
        ability(b, 0, 0);
        assertEquals(a.runtime.getMatchId(), view(a).matchId());
        assertEquals(b.runtime.getMatchId(), view(b).matchId());
        assertNotEquals(view(a).matchId(), view(b).matchId());
        assertNotSame(a.projectile(), b.projectile());
    }

    @Test public void projectileUsesOriginalSpawnPosition() {
        Probe p = airborne(2);
        ability(p, 0, 0);
        assertEquals(4.65f, view(p).x(), EPS);
        assertEquals(3, view(p).y(), EPS); // Body mới chưa nhận velocity trong spawn tick.
        assertEquals(0, view(p).velocityX(), EPS);
        assertEquals(0, view(p).velocityY(), EPS);
    }

    @Test public void projectileUsesAuthoritativeFacing() {
        Probe p = airborne(2);
        p.body(0).setLinearVelocity(-0.3f, 0);
        ability(p, 0, 0);
        assertFalse(p.player(0).isFacingRight());
        assertEquals(4.35f, view(p).x(), EPS);
        p.runtime.tick();
        assertEquals(-0.7f, view(p).velocityX(), EPS);
    }

    @Test public void stationaryPlayerRetainsLastFacing() {
        Probe p = airborne(2);
        p.body(0).setLinearVelocity(-0.2f, 0);
        p.runtime.tick();
        position(p, 0, 4.5f, 3);
        ability(p, 0, 0);
        assertFalse(p.player(0).isFacingRight());
        assertEquals(4.35f, view(p).x(), EPS);
    }

    @Test public void originalFacingUpdatePrecedesWalkingImpulse() {
        Probe p = airborne(2);
        send(p, input(0, 0, -1, false, false, false, false, true));
        p.runtime.tick();
        assertTrue(p.player(0).isFacingRight()); // vx đầu tick là 0, đúng Player.managePlayerInput.
        assertEquals(4.65f, view(p).x(), EPS);
        p.runtime.tick();
        assertFalse(p.player(0).isFacingRight());
        assertEquals(0.7f, view(p).velocityX(), EPS); // Hướng projectile không đổi theo owner sau spawn.
    }

    @Test public void projectilePhysicsMatchesOriginal() {
        Probe p = airborne(2);
        ability(p, 0, 0);
        Body projectile = p.projectile();
        Fixture fixture = projectile.getFixtureList().first();
        assertEquals(BodyDef.BodyType.DynamicBody, projectile.getType());
        assertFalse(projectile.isFixedRotation());
        assertFalse(projectile.isBullet());
        assertEquals(4, projectile.getAngularVelocity(), EPS);
        assertEquals(4.0f / 60, projectile.getAngle(), EPS);
        assertEquals(Shape.Type.Circle, fixture.getShape().getType());
        assertEquals(0.03f, fixture.getShape().getRadius(), EPS);
        assertEquals(3, fixture.getFilterData().categoryBits);
        assertEquals((short) 0xffff, fixture.getFilterData().maskBits);
        assertEquals(0, fixture.getFilterData().groupIndex);
        assertFalse(fixture.isSensor());
        assertEquals(0, fixture.getDensity(), 0);
        assertEquals(0.2f, fixture.getFriction(), EPS);
        assertEquals(0, fixture.getRestitution(), 0);
        assertEquals(0, p.world().getGravity().y, 0);
        for (int slot = 0; slot < 2; slot++) {
            assertEquals(-1, p.body(slot).getFixtureList().first().getFilterData().groupIndex);
            assertEquals(7, p.body(slot).getFixtureList().first().getFilterData().maskBits);
        }
    }

    @Test public void projectileMovesAcrossTicks() {
        Probe p = airborne(2);
        ability(p, 0, 0);
        float initialX = view(p).x();
        for (int tick = 0; tick < 10; tick++) { p.runtime.tick(); }
        assertEquals(initialX + 10 * 0.7f / 60, view(p).x(), EPS);
        assertEquals(3, view(p).y(), EPS);
        assertEquals(0.7f, view(p).velocityX(), EPS);
        assertEquals(0, view(p).velocityY(), EPS);
    }

    @Test public void validProjectileHitDamagesTarget() {
        Probe p = hitScene();
        flyUntilRemoved(p, 100);
        assertEquals(100, p.player(1).getHp(), EPS);
        p.runtime.tick(); // Contact flags gốc được xử lý trong combat update kế tiếp.
        assertEquals(90, p.player(1).getHp(), EPS);
        assertEquals(100, p.player(0).getHp(), EPS);
    }

    @Test public void damageMatchesOriginalProjectile() {
        Probe p = hitScene();
        flyUntilRemoved(p, 100);
        p.runtime.tick();
        assertEquals(10, 100 - p.player(1).getHp(), EPS);
        assertEquals(10, ProjectileManager.IBM_DAMAGE, 0);
    }

    @Test public void projectileAllowsSelfHitBecauseOriginalHasNoOwnerImmunity() {
        Probe p = airborne(2);
        ability(p, 0, 0);
        ServerProjectile metadata = (ServerProjectile) p.projectile().getUserData();
        position(p, 0, view(p).x(), view(p).y());
        p.runtime.tick();
        assertEquals(0, p.runtime.getActiveProjectileCount());
        assertFalse(metadata.isActive());
        p.runtime.tick();
        assertEquals(90, p.player(0).getHp(), EPS);
        assertEquals(100, p.player(1).getHp(), EPS);
    }

    @Test public void projectileCannotDamageOtherRoom() {
        Probe a = hitScene();
        Probe b = airborne(2);
        position(b, 0, 4.7f, 3);
        position(b, 1, 4.8f, 3);
        flyUntilRemoved(a, 100);
        a.runtime.tick();
        b.runtime.tick();
        assertEquals(90, a.player(1).getHp(), EPS);
        assertEquals(100, b.player(0).getHp(), EPS);
        assertEquals(100, b.player(1).getHp(), EPS);
        assertEquals(0, b.runtime.getActiveProjectileCount());
    }

    @Test public void projectileCollisionWithMapIsHandled() {
        Probe p = airborne(2);
        tile(p, 4.85f, 3, 0.03f, 0.3f);
        ability(p, 0, 0);
        flyUntilRemoved(p, 60, 8, 4); // Target ở ngoài đường đạn: đây là map-only contact.
        p.runtime.tick();
        assertEquals(21, p.world().getBodyCount()); // 18 map + 2 players + 1 test tile.
        assertEquals(100, p.player(0).getHp(), EPS);
        assertEquals(100, p.player(1).getHp(), EPS);
    }

    @Test public void duplicateAbilityInputDoesNotSpawnTwice() {
        Probe p = airborne(2);
        PlayerInput input = input(0, 0, 0, false, false, false, false, true);
        send(p, input);
        assertEquals(DUPLICATE_OR_STALE, p.runtime.submitInput(input));
        p.runtime.tick();
        assertEquals(1, p.runtime.getActiveProjectileCount());
        assertEquals(DUPLICATE_OR_STALE, p.runtime.submitInput(input));
        p.runtime.tick();
        assertEquals(1, p.runtime.getActiveProjectileCount());
    }

    @Test public void multipleAbilityEdgesInBatchCreateOnlyOneProjectile() {
        Probe p = airborne(2);
        for (int seq = 0; seq < 20; seq++) { send(p, input(0, seq, 0, false, false, false, false, true)); }
        assertEquals(1, p.runtime.tick().size());
        assertEquals(1, p.runtime.getActiveProjectileCount());
    }

    @Test public void cooldownPreventsAbilitySpam() {
        Probe p = airborne(2);
        ability(p, 0, 0);
        long originalId = view(p).projectileId();
        for (int tick = 1; tick < 180; tick++) {
            position(p, 0, 4.5f, 3);
            position(p, 1, 8, 4);
            ability(p, 0, tick);
            assertEquals(1, p.runtime.getActiveProjectileCount());
            assertEquals(originalId, view(p).projectileId());
        }
        assertEquals(180, p.runtime.getMatchState().getServerTick());
        position(p, 0, 4.5f, 3);
        ability(p, 0, 180);
        assertEquals(2, p.runtime.getActiveProjectileCount());
        assertNotEquals(p.runtime.getProjectiles().get(0).projectileId(),
                p.runtime.getProjectiles().get(1).projectileId());
    }

    @Test public void noNewInputDoesNotAutomaticallyFireAtCooldownEnd() {
        Probe p = airborne(2);
        ability(p, 0, 0);
        for (int tick = 0; tick < 200; tick++) {
            position(p, 0, 4.5f, 3);
            position(p, 1, 8, 4);
            p.runtime.tick();
        }
        assertEquals(1, p.runtime.getActiveProjectileCount());
        assertEquals(0, view(p).spawnTick());
    }

    @Test public void projectileDoesNotDamageRepeatedly() {
        Probe p = hitScene();
        flyUntilRemoved(p, 100);
        p.runtime.tick();
        for (int tick = 0; tick < 15; tick++) {
            position(p, 0, 4.5f, 3);
            position(p, 1, 4.7f, 3);
            p.runtime.tick();
        }
        assertEquals(90, p.player(1).getHp(), EPS);
        assertEquals(0, p.runtime.getActiveProjectileCount());
    }

    @Test public void projectileHasNoInventedLifetimeOrBoundaryExpiry() {
        Probe p = airborne(2);
        position(p, 0, 4.5f, 8);
        ability(p, 0, 0);
        long id = view(p).projectileId();
        for (int tick = 0; tick < 600; tick++) { p.runtime.tick(); }
        assertEquals(1, p.runtime.getActiveProjectileCount());
        assertEquals(id, view(p).projectileId());
        assertTrue(view(p).x() > 8.5f);
        assertEquals(8, view(p).y(), EPS);
    }

    @Test public void destroyedProjectileIsRemoved() {
        Probe p = hitScene();
        flyUntilRemoved(p, 100);
        assertTrue(p.runtime.getProjectiles().isEmpty());
        assertEquals(20, p.world().getBodyCount());
    }

    @Test public void destroyedBodyIsNotUsedAgain() {
        Probe p = hitScene();
        ServerProjectile metadata = (ServerProjectile) p.projectile().getUserData();
        flyUntilRemoved(p, 100);
        assertFalse(metadata.isActive());
        for (int tick = 0; tick < 20; tick++) { p.runtime.tick(); }
        assertFalse(metadata.isActive());
        assertEquals(20, p.world().getBodyCount());
    }

    @Test public void deferredDestructionIsSafe() {
        Probe p = hitScene();
        AtomicInteger contacts = new AtomicInteger();
        p.afterStep.set(world -> {
            assertFalse(world.isLocked());
            boolean projectileContact = false;
            for (Contact contact : world.getContactList()) {
                if (contact.isTouching() && (contact.getFixtureA().getBody().getUserData() instanceof ServerProjectile
                        || contact.getFixtureB().getBody().getUserData() instanceof ServerProjectile)) {
                    projectileContact = true;
                }
            }
            if (projectileContact) {
                contacts.incrementAndGet();
                assertEquals(21, world.getBodyCount()); // Chưa destroy trong callback/step.
                assertEquals(100, p.player(1).getHp(), EPS); // Chưa damage trong callback.
            }
        });
        flyUntilRemoved(p, 100);
        assertTrue(contacts.get() > 0);
        assertEquals(20, p.world().getBodyCount());
    }

    @Test public void cleanupDoesNotDoubleDestroyBody() {
        Probe p = hitScene();
        flyUntilRemoved(p, 100);
        p.runtime.tick();
        p.runtime.close();
        p.runtime.close();
        assertTrue(p.physics.get().isDisposed());
    }

    @Test public void twoPlayersCanOwnSeparateProjectiles() {
        assertIndependentOwners(2);
    }

    @Test public void fourPlayersHaveIndependentProjectileState() {
        assertIndependentOwners(4);
    }

    @Test public void threePlayersHaveIndependentProjectileState() {
        assertIndependentOwners(3);
    }

    @Test public void twoMatchesHaveIndependentManagers() {
        Probe a = airborne(4);
        Probe b = airborne(4);
        ability(a, 0, 0);
        ability(b, 0, 0);
        a.runtime.close();
        assertEquals(1, b.runtime.getActiveProjectileCount());
        b.runtime.tick();
        assertEquals(0.7f, view(b).velocityX(), EPS);
    }

    @Test public void projectileDoesNotBreakMeleeCombat() {
        Probe p = airborne(2);
        position(p, 1, 4.4f, 3); // Melee phía trái, IBM bay phải: không lẫn native contact vào assertion lực.
        send(p, input(0, 0, 0, false, false, true, false, true));
        p.runtime.tick();
        assertEquals(90, p.player(1).getHp(), EPS); // Melee ngay tick này.
        assertEquals(-1.5f, p.body(1).getLinearVelocity().x, EPS);
        assertEquals(1, p.runtime.getActiveProjectileCount());
        p.runtime.tick();
        assertEquals(90, p.player(1).getHp(), EPS);
    }

    @Test public void meleeAndProjectileContactsApplyIndependentDamage() {
        Probe p = airborne(2);
        position(p, 1, 4.6f, 3);
        send(p, input(0, 0, 0, false, false, true, false, true));
        p.runtime.tick();
        assertEquals(90, p.player(1).getHp(), EPS);
        // Non-sensor IBM thật có thể đổi velocity qua solver; không giả định vx giữ nguyên sau contact.
        assertEquals(0, p.runtime.getActiveProjectileCount());
        p.runtime.tick();
        assertEquals(80, p.player(1).getHp(), EPS);
    }

    @Test public void projectileDoesNotBreakStomp() {
        Probe p = airborne(2);
        send(p, input(0, 0, 0, false, true, false, false, true));
        p.runtime.tick();
        assertTrue(p.runtime.getStompState(id(0)).active());
        assertEquals(99.9f, p.player(0).getHp(), EPS);
        assertEquals(-5.196f, p.body(0).getLinearVelocity().y, EPS);
        assertEquals(1, p.runtime.getActiveProjectileCount());
        assertEquals(3, view(p).y(), EPS);
    }

    @Test public void projectileDoesNotAddExtraWorldStep() {
        Probe p = airborne(4);
        for (int slot = 0; slot < 4; slot++) { send(p, input(slot, 0, 0, false, false, false, false, true)); }
        p.runtime.tick();
        assertEquals(4, p.runtime.getActiveProjectileCount());
        assertEquals(1, p.steps.get());
        assertEquals(1, p.runtime.getMatchState().getServerTick());
        p.runtime.tick();
        assertEquals(2, p.steps.get());
        assertEquals(2, p.runtime.getMatchState().getServerTick());
    }

    @Test public void runtimeCloseReleasesProjectileResources() throws Exception {
        Probe p = airborne(2);
        ability(p, 0, 0);
        ServerProjectile metadata = (ServerProjectile) p.projectile().getUserData();
        p.runtime.close();
        assertFalse(metadata.isActive());
        assertTrue(p.physics.get().isDisposed());
        assertThrows(IllegalStateException.class, p.runtime::tick);
        assertThrows(IllegalStateException.class, p.runtime::getProjectiles);
        assertThrows(IllegalStateException.class, () -> p.runtime.submitInput(
                input(0, 1, 0, false, false, false, false, true)));
        var field = MatchRuntime.class.getDeclaredField("projectileManager");
        field.setAccessible(true);
        assertNull(field.get(p.runtime));
        p.runtime.close();
    }

    @Test public void existingServerTestsStillPass() {
        Probe p = airborne(4);
        p.runtime.tick();
        assertEquals(22, p.world().getBodyCount());
        assertEquals(4, p.runtime.getMatchState().getPlayerCount());
        assertEquals(1, p.runtime.getMatchState().getServerTick());
    }

    @Test public void blockingDoesNotStopOriginalIbmDamageOrKnockback() {
        Probe p = hitScene();
        flyUntilRemoved(p, 100);
        position(p, 0, 4.5f, 3);
        position(p, 1, 4.85f, 3);
        send(p, input(1, 0, 0, false, false, false, true, false));
        p.runtime.tick();
        assertTrue(p.player(1).isBlocking());
        assertEquals(90, p.player(1).getHp(), EPS);
        assertEquals(1.5f, p.body(1).getLinearVelocity().x, EPS);
        assertEquals(1.804f, p.body(1).getLinearVelocity().y, EPS);
    }

    @Test public void projectileKnockbackIsSymmetricForEverySlotInTwoToFourPlayerMatches() {
        for (int count = 2; count <= 4; count++) {
            for (int attacker = 0; attacker < count; attacker++) {
                int target = attacker == 0 ? 1 : 0;
                Probe p = airborne(count);
                for (int slot = 0; slot < count; slot++) { position(p, slot, 7 + slot * 0.2f, 4); }
                position(p, attacker, 5, 3);
                position(p, target, 5.35f, 3);
                ability(p, attacker, 0);
                for (int tick = 0; tick < 80 && p.runtime.getActiveProjectileCount() > 0; tick++) {
                    position(p, attacker, 5, 3);
                    position(p, target, 5.35f, 3);
                    p.runtime.tick();
                }
                assertEquals(0, p.runtime.getActiveProjectileCount());
                position(p, attacker, 5, 3);
                position(p, target, 5.35f, 3);
                p.runtime.tick();
                assertEquals(90, p.player(target).getHp(), EPS);
                assertEquals(1.5f, p.body(target).getLinearVelocity().x, EPS);
                assertEquals(1.804f, p.body(target).getLinearVelocity().y, EPS);
                assertEquals(100, p.player(attacker).getHp(), EPS);
            }
        }
    }

    @Test public void leftFacingProjectileUsesMirroredKnockbackNotSlot() {
        Probe p = airborne(2);
        position(p, 0, 5, 3);
        position(p, 1, 4.65f, 3);
        p.body(0).setLinearVelocity(-0.2f, 0);
        ability(p, 0, 0);
        for (int tick = 0; tick < 80 && p.runtime.getActiveProjectileCount() > 0; tick++) {
            position(p, 0, 5, 3);
            position(p, 1, 4.65f, 3);
            p.runtime.tick();
        }
        assertEquals(0, p.runtime.getActiveProjectileCount());
        position(p, 0, 5, 3);
        position(p, 1, 4.65f, 3);
        p.runtime.tick();
        assertEquals(90, p.player(1).getHp(), EPS);
        assertEquals(-1.5f, p.body(1).getLinearVelocity().x, EPS);
        assertEquals(1.804f, p.body(1).getLinearVelocity().y, EPS);
    }

    @Test public void simultaneousPlayerContactsSelectOnePhysicalTargetWithoutAoe() {
        for (int count = 3; count <= 4; count++) {
            Probe p = airborne(count);
            for (int slot = 1; slot < count; slot++) { position(p, slot, 4.65f, 3); }
            AtomicReference<PlayerId> expectedTarget = new AtomicReference<>();
            p.afterStep.set(world -> {
                Body projectile = p.projectile();
                List<PlayerId> candidates = new ArrayList<>();
                for (Contact contact : world.getContactList()) {
                    if (!contact.isTouching()) { continue; }
                    Body a = contact.getFixtureA().getBody();
                    Body b = contact.getFixtureB().getBody();
                    if (a == projectile && b.getUserData() instanceof PlayerId id) { candidates.add(id); }
                    if (b == projectile && a.getUserData() instanceof PlayerId id) { candidates.add(id); }
                }
                assertTrue(candidates.size() > 1);
                candidates.sort(java.util.Comparator.<PlayerId>comparingDouble(id -> {
                    int slot = p.runtime.getMatchState().getPlayer(id).getSlotIndex();
                    double dx = p.body(slot).getPosition().x - projectile.getPosition().x;
                    double dy = p.body(slot).getPosition().y - projectile.getPosition().y;
                    return dx * dx + dy * dy;
                }).thenComparingInt(id -> p.runtime.getMatchState().getPlayer(id).getSlotIndex()));
                expectedTarget.set(candidates.get(0));
            });
            ability(p, 0, 0);
            assertEquals(0, p.runtime.getActiveProjectileCount());
            p.afterStep.set(world -> { });
            p.runtime.tick();
            int hits = 0;
            for (int slot = 0; slot < count; slot++) {
                if (p.player(slot).getHp() < 100) {
                    hits++;
                    assertEquals(expectedTarget.get(), id(slot));
                    assertEquals(90, p.player(slot).getHp(), EPS);
                }
            }
            assertEquals(1, hits);
        }
    }

    @Test public void projectileProjectileCollisionDestroysBothWithoutAreaDamage() {
        Probe p = airborne(2);
        position(p, 0, 4.5f, 3);
        position(p, 1, 5.5f, 3);
        p.body(1).setLinearVelocity(-0.1f, 0);
        send(p, input(0, 0, 0, false, false, false, false, true));
        send(p, input(1, 0, 0, false, false, false, false, true));
        p.runtime.tick();
        assertEquals(2, p.runtime.getActiveProjectileCount());
        for (int tick = 0; tick < 80 && p.runtime.getActiveProjectileCount() > 0; tick++) {
            position(p, 0, 4.5f, 3);
            position(p, 1, 5.5f, 3);
            p.runtime.tick();
        }
        assertEquals(0, p.runtime.getActiveProjectileCount());
        p.runtime.tick();
        assertEquals(100, p.player(0).getHp(), EPS);
        assertEquals(100, p.player(1).getHp(), EPS);
        assertEquals(20, p.world().getBodyCount());
    }

    @Test public void projectilePersistsAfterOwnerDiesAsInOriginal() {
        Probe p = airborne(3); // Owner bị loại, nhưng còn hai survivor để projectile tiếp tục update.
        ability(p, 0, 0);
        position(p, 1, 4.85f, 3);
        p.player(0).applyDamage(100);
        flyUntilRemoved(p, 100);
        p.runtime.tick();
        assertTrue(p.player(0).isDead());
        assertEquals(90, p.player(1).getHp(), EPS);
    }

    @Test public void alreadyDeadTargetDoesNotReceiveProjectileKnockback() {
        Probe p = hitScene();
        flyUntilRemoved(p, 100);
        p.player(1).applyDamage(100);
        position(p, 1, 4.85f, 3);
        p.runtime.tick();
        assertEquals(0, p.player(1).getHp(), 0);
        assertEquals(0, p.body(1).getLinearVelocity().x, EPS);
        assertEquals(-0.098f, p.body(1).getLinearVelocity().y, EPS);
    }

    @Test public void fractionalHpUsesExistingDamageApiAndClampsAtZero() {
        Probe p = hitScene();
        p.player(1).applyDamage(99.75f);
        assertEquals(0.25f, p.player(1).getHp(), 0);
        flyUntilRemoved(p, 100);
        p.runtime.tick();
        assertEquals(0, p.player(1).getHp(), 0);
        assertTrue(p.player(1).isDead());
    }

    @Test public void composedListenerPreservesLandingAndJump() {
        Probe p = airborne(2);
        ability(p, 0, 0);
        for (int tick = 0; tick < 180 && !p.runtime.getJumpState(id(0)).grounded(); tick++) { p.runtime.tick(); }
        assertTrue(p.runtime.getJumpState(id(0)).grounded());
        send(p, input(0, 1, 0, true, false, false, false, false));
        p.runtime.tick();
        assertFalse(p.runtime.getJumpState(id(0)).grounded());
        assertEquals(1, p.runtime.getJumpState(id(0)).jumpsUsed());
        assertTrue(p.body(0).getLinearVelocity().y > 2);
    }

    @Test public void returnedProjectileViewsCannotBeModifiedAndRemainSnapshots() {
        Probe p = airborne(2);
        ability(p, 0, 0);
        List<ServerProjectile.View> old = p.runtime.getProjectiles();
        assertThrows(UnsupportedOperationException.class, old::clear);
        assertThrows(UnsupportedOperationException.class, () -> old.add(old.get(0)));
        float x = old.get(0).x();
        p.runtime.tick();
        assertEquals(x, old.get(0).x(), 0);
        assertTrue(view(p).x() > x);
        for (var method : ServerProjectile.class.getMethods()) {
            assertNotEquals(Body.class, method.getReturnType());
        }
    }

    @Test(timeout = 15000) public void networkThreadOnlyEnqueuesAndCannotSpawnOrStep() throws Exception {
        Probe p = airborne(2);
        p.runtime.tick();
        InputQueueTest.runConcurrently(List.<Callable<Boolean>>of(() -> {
            send(p, input(0, 0, 0, false, false, false, false, true));
            assertThrows(IllegalStateException.class, p.runtime::tick);
            return true;
        }));
        assertEquals(0, p.runtime.getActiveProjectileCount());
        assertEquals(20, p.world().getBodyCount());
        assertEquals(1, p.steps.get());
        p.runtime.tick();
        assertEquals(1, p.runtime.getActiveProjectileCount());
    }

    @Test public void closingWithPendingHitDoesNotApplyDamageOrAccessDisposedBody() {
        Probe p = hitScene();
        flyUntilRemoved(p, 100);
        assertEquals(100, p.player(1).getHp(), EPS);
        p.runtime.close();
        p.runtime.close();
        assertEquals(100, p.player(1).getHp(), EPS);
        assertTrue(p.physics.get().isDisposed());
        assertThrows(IllegalStateException.class, p.runtime::tick);
    }

    @Test public void groundedViktorCanFireWithoutInventedAirborneGate() {
        Probe p = airborne(2);
        for (int tick = 0; tick < 180 && !p.runtime.getJumpState(id(0)).grounded(); tick++) { p.runtime.tick(); }
        assertTrue(p.runtime.getJumpState(id(0)).grounded());
        ability(p, 0, 0);
        assertEquals(1, p.runtime.getActiveProjectileCount());
        assertEquals(100, p.player(0).getHp(), EPS);
    }

    @Test public void abilityRoutingRequiresPlayingAndOnlyEnqueuesOnNetworkBoundary() {
        // Tái dùng manual executor hiện có, không start scheduler hoặc tạo thread/socket.
        FixedTickScheduler scheduler = new FixedTickScheduler(new ManualScheduledExecutor(), Logger.getAnonymousLogger());
        AtomicLong clock = new AtomicLong();
        RoomManager rooms = new RoomManager(scheduler, clock::get);
        try {
            var room = rooms.getOrCreateRoom("ability-gate");
            ConnectionManager connections = new ConnectionManager();
            ClientSession session = new ClientSession("ability-session", new SessionChannel() {
                @Override public void send(Object message) { fail("Input routing không được gửi message"); }
                @Override public void close() { }
                @Override public boolean isOpen() { return true; }
            });
            session.bindPlayer(id(0));
            session.joinRoom("ability-gate");
            connections.register(session);
            PlayerInputHandler handler = new PlayerInputHandler(connections, rooms);
            PlayerInput ability = input(0, 0, 0, false, false, false, false, true);
            assertEquals(PlayerInputHandler.RoutingResult.ROOM_NOT_PLAYING, handler.handle(session, ability));
            for (int slot = 0; slot < 2; slot++) {
                room.joinPlayer(id(slot));
                room.selectCharacter(id(slot), "Viktor");
                room.setReady(id(slot), true);
            }
            assertTrue(room.startCountdown());
            assertEquals(PlayerInputHandler.RoutingResult.ROOM_NOT_PLAYING, handler.handle(session, ability));
            clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
            room.updateCountdown();
            assertNotNull(room.prepareMatch());
            MatchRuntime runtime = room.startMatch();
            assertNotNull(runtime);
            assertEquals(PlayerInputHandler.RoutingResult.ACCEPTED, handler.handle(session, ability));
            assertEquals(0, runtime.getActiveProjectileCount());
            assertEquals(0, runtime.getMatchState().getServerTick());
            runtime.tick();
            assertEquals(1, runtime.getActiveProjectileCount());
        } finally {
            rooms.shutdown();
            scheduler.stop();
        }
    }

    @Test public void originalTmxColliderDestroysProjectileWithoutInventedExplosionDamage() {
        Probe p = airborne(2);
        Array<Body> bodies = new Array<>();
        p.world().getBodies(bodies);
        Body originalTile = null;
        for (Body body : bodies) {
            if (body.getType() == BodyDef.BodyType.StaticBody && "Tile".equals(body.getUserData())
                    && body.getPosition().x > 3.5f && body.getPosition().x < 8
                    && body.getPosition().y > 0.3f) {
                originalTile = body;
                break;
            }
        }
        assertNotNull(originalTile);
        position(p, 0, originalTile.getPosition().x - 0.15f, originalTile.getPosition().y);
        position(p, 1, 8, 3);
        ability(p, 0, 0); // Spawn trong collider TMX thật, không thêm collider hoặc sửa map.
        assertEquals(0, p.runtime.getActiveProjectileCount());
        assertEquals(20, p.world().getBodyCount());
        p.runtime.tick();
        assertEquals(100, p.player(1).getHp(), EPS);
    }

    @Test public void separateProjectileHitsHaveSeparateIdsAndDamageNotGlobalBooleanFlags() {
        Probe p = airborne(3);
        position(p, 0, 5, 3);
        position(p, 1, 5.4f, 3);
        position(p, 2, 5.2f, 3);
        p.body(1).setLinearVelocity(-0.1f, 0);
        send(p, input(0, 0, 0, false, false, false, false, true));
        send(p, input(1, 0, 0, false, false, false, false, true));
        p.runtime.tick();
        assertEquals(0, p.runtime.getActiveProjectileCount());
        assertEquals(100, p.player(2).getHp(), EPS);
        p.runtime.tick();
        assertEquals(80, p.player(2).getHp(), EPS); // Mỗi projectile hợp lệ một hit, không repeated contact.
        assertEquals(100, p.player(0).getHp(), EPS);
        assertEquals(100, p.player(1).getHp(), EPS);
    }

    private void assertIndependentOwners(int count) {
        Probe p = airborne(count);
        for (int slot = count - 1; slot >= 0; slot--) {
            send(p, input(slot, 0, 0, false, false, false, false, true));
        }
        p.runtime.tick();
        assertEquals(count, p.runtime.getActiveProjectileCount());
        for (int slot = 0; slot < count; slot++) {
            assertEquals(id(slot), p.runtime.getProjectiles().get(slot).ownerPlayerId());
            assertEquals(slot + 1L, p.runtime.getProjectiles().get(slot).projectileId());
        }
        ability(p, 0, 1);
        assertEquals(count, p.runtime.getActiveProjectileCount());
    }

    private Probe hitScene() {
        Probe p = airborne(2);
        ability(p, 0, 0);
        position(p, 1, 4.85f, 3);
        return p;
    }

    private Probe airborne(int count) {
        Probe p = create(Collections.nCopies(count, "Viktor"));
        for (int slot = 0; slot < count; slot++) { position(p, slot, 4.5f + slot * 0.6f, 3 + slot * 0.3f); }
        return p;
    }

    private Probe create(List<String> characters) {
        List<MatchSetup.Participant> roster = new ArrayList<>();
        for (int slot = 0; slot < characters.size(); slot++) {
            roster.add(new MatchSetup.Participant(id(slot), slot, characters.get(slot)));
        }
        Collections.reverse(roster);
        String matchId = "projectile-" + runtimes.size();
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
        return new Probe(runtime, physics, steps, afterStep);
    }

    private static void flyUntilRemoved(Probe p, int maxTicks) {
        flyUntilRemoved(p, maxTicks, 4.85f, 3);
    }

    private static void flyUntilRemoved(Probe p, int maxTicks, float targetX, float targetY) {
        for (int tick = 0; tick < maxTicks && p.runtime.getActiveProjectileCount() > 0; tick++) {
            // Chỉ test giữ target ở cùng độ cao để kiểm tra native contact, không mock contact result.
            position(p, 0, 4.5f, 3);
            position(p, 1, targetX, targetY);
            p.runtime.tick();
        }
        assertEquals("Projectile không contact trong giới hạn manual ticks", 0, p.runtime.getActiveProjectileCount());
    }

    private static void tile(Probe p, float x, float y, float halfWidth, float halfHeight) {
        BodyDef definition = new BodyDef();
        definition.type = BodyDef.BodyType.StaticBody;
        definition.position.set(x, y);
        Body body = p.world().createBody(definition);
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

    private static void position(Probe p, int slot, float x, float y) {
        p.body(slot).setTransform(x, y, 0);
        p.body(slot).setLinearVelocity(0, 0);
    }

    private static void ability(Probe p, int slot, long sequence) {
        send(p, input(slot, sequence, 0, false, false, false, false, true));
        p.runtime.tick();
    }

    private static void send(Probe p, PlayerInput input) { assertEquals(ACCEPTED, p.runtime.submitInput(input)); }

    private static PlayerInput input(int slot, long sequence, float axis, boolean jump, boolean stomp,
                                    boolean melee, boolean block, boolean ability) {
        return new PlayerInput(id(slot), sequence, Long.MAX_VALUE, axis, jump, stomp, melee, block, ability);
    }

    private static ServerProjectile.View view(Probe p) { return p.runtime.getProjectiles().get(0); }
    private static PlayerId id(int slot) { return new PlayerId("arbitrary-projectile-player-" + slot); }

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
        Body projectile() {
            Array<Body> bodies = new Array<>();
            world().getBodies(bodies);
            for (Body body : bodies) { if (body.getUserData() instanceof ServerProjectile) { return body; } }
            throw new AssertionError("Thiếu projectile");
        }
    }
}
