package com.smashprofs.server.game;

import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.World;
import com.badlogic.gdx.utils.Array;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import com.smashprofs.server.game.AuthoritativeGameSnapshot.PlayerSnapshot;
import com.smashprofs.server.game.physics.HeadlessPhysicsWorld;
import com.smashprofs.server.game.physics.PhysicsWorldFactory;
import com.smashprofs.server.game.projectile.ServerProjectile;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Native Box2D/TMX và manual tick; reader concurrency chỉ đọc snapshot, không JNI. */
public class MatchRuntimeSnapshotTest {
    private static final float EPS = 0.00001f;
    private final List<Probe> probes = new ArrayList<>();

    @After public void closeResources() { probes.forEach(probe -> probe.runtime.close()); }

    @Test public void snapshotContainsCorrectServerTick() {
        Probe p = create("Alex", "Luca");
        assertEquals(0, p.runtime.getMatchState().getServerTick());
        for (long tick = 1; tick <= 3; tick++) {
            p.runtime.tick();
            assertEquals(tick, p.snapshot().serverTick());
            assertEquals(p.runtime.getMatchState().getServerTick(), p.snapshot().serverTick());
        }
    }

    @Test public void snapshotContainsTwoPlayers() { assertPlayerCount(2); }
    @Test public void snapshotContainsThreePlayers() { assertPlayerCount(3); }
    @Test public void snapshotContainsFourPlayers() { assertPlayerCount(4); }

    private void assertPlayerCount(int count) {
        String[] roster = {"Alex", "Leo", "Maurice", "Viktor"};
        Probe p = create(java.util.Arrays.copyOf(roster, count));
        p.runtime.tick();
        assertEquals(count, p.snapshot().players().size());
        assertEquals(count, p.snapshot().players().stream().map(PlayerSnapshot::playerId).distinct().count());
    }

    @Test public void snapshotUsesCorrectPlayerIds() {
        Probe p = create("Alex", "Leo", "Luca", "Maurice");
        p.runtime.tick();
        assertEquals(Set.of(id(0), id(1), id(2), id(3)),
                Set.copyOf(p.snapshot().players().stream().map(PlayerSnapshot::playerId).toList()));
    }

    @Test public void snapshotUsesCorrectSlotIndices() {
        Probe p = create(new int[]{3, 0, 2}, "Viktor", "Luca", "Alex");
        p.runtime.tick();
        assertEquals(3, p.playerSnapshot(0).slotIndex());
        assertEquals(0, p.playerSnapshot(1).slotIndex());
        assertEquals(2, p.playerSnapshot(2).slotIndex());
    }

    @Test public void snapshotUsesCorrectCharacterIds() {
        for (String character : List.of("Alex", "Leo", "Luca", "Maurice", "Viktor")) {
            Probe p = create(character, "Luca");
            p.runtime.tick();
            assertEquals(character, p.playerSnapshot(0).characterId());
            assertEquals(p.player(0).getCharacterId(), p.playerSnapshot(0).characterId());
        }
    }

    @Test public void snapshotUsesAuthoritativeBox2DPosition() {
        Probe p = create("Alex", "Leo", "Luca", "Maurice");
        for (int role = 0; role < 4; role++) { p.place(role, 4 + role, 3 + role); }
        p.runtime.tick();
        for (int role = 0; role < 4; role++) {
            assertEquals(p.body(role).getPosition().x, p.playerSnapshot(role).x(), 0);
            assertEquals(p.body(role).getPosition().y, p.playerSnapshot(role).y(), 0);
        }
    }

    @Test public void snapshotUsesAuthoritativeVelocity() {
        Probe p = create("Alex", "Leo", "Luca", "Maurice");
        for (int role = 0; role < 4; role++) {
            p.body(role).setLinearVelocity(0.1f * (role + 1), 0.5f * (role + 1));
        }
        p.runtime.tick();
        for (int role = 0; role < 4; role++) {
            assertEquals(p.body(role).getLinearVelocity().x, p.playerSnapshot(role).velocityX(), 0);
            assertEquals(p.body(role).getLinearVelocity().y, p.playerSnapshot(role).velocityY(), 0);
        }
    }

    @Test public void snapshotUsesAuthoritativeHP() {
        Probe p = create("Viktor", "Luca");
        p.send(0, 0, false, true, false, false, false);
        p.runtime.tick();
        assertEquals(99.9f, p.playerSnapshot(0).hp(), EPS);
        assertEquals(p.player(0).getHp(), p.playerSnapshot(0).hp(), 0);
        assertEquals(100, p.playerSnapshot(1).hp(), 0);
    }

    @Test public void snapshotReflectsMeleeDamage() {
        Probe p = meleeScene();
        p.send(0, 0, false, false, true, false, false);
        p.runtime.tick();
        assertEquals(90, p.playerSnapshot(1).hp(), 0);
        assertEquals(1.5f, p.playerSnapshot(1).velocityX(), EPS);
        assertEquals(p.body(1).getPosition().x, p.playerSnapshot(1).x(), 0);
    }

    @Test public void snapshotReflectsFallPenalty() {
        Probe p = create("Alex", "Luca");
        p.place(0, 3.1f, -0.1f);
        p.runtime.tick();
        assertEquals(90, p.playerSnapshot(0).hp(), 0);
        assertEquals(2.6f, p.playerSnapshot(0).velocityY(), EPS);
        assertEquals(p.body(0).getPosition().y, p.playerSnapshot(0).y(), 0);
        p.runtime.tick();
        assertEquals(90, p.playerSnapshot(0).hp(), 0);
    }

    @Test public void snapshotReflectsMovementAfterTick() {
        Probe p = create("Viktor", "Luca");
        float x = p.body(0).getPosition().x;
        p.send(0, 1, false, false, false, false, false);
        p.runtime.tick();
        assertEquals(0.055f, p.playerSnapshot(0).velocityX(), EPS);
        assertEquals(x + 0.055f / 60, p.playerSnapshot(0).x(), EPS);
        assertTrue(p.playerSnapshot(0).x() > x);
    }

    @Test public void snapshotReflectsBlockingState() {
        Probe p = meleeScene();
        p.send(1, 0, false, false, false, true, false);
        p.send(0, 0, false, false, true, false, false);
        p.runtime.tick();
        assertTrue(p.playerSnapshot(1).blocking());
        assertEquals(100, p.playerSnapshot(1).hp(), 0);
        p.send(1, 0, false, false, false, false, false);
        p.runtime.tick();
        assertFalse(p.playerSnapshot(1).blocking());
    }

    @Test public void snapshotContainsActiveProjectiles() {
        Probe p = burstScene();
        AuthoritativeGameSnapshot snapshot = p.snapshot();
        assertEquals(7, snapshot.projectiles().size());
        assertEquals(p.runtime.getProjectiles(), snapshot.projectiles());
        assertEquals(Set.of("IBM", "STAR", "MINE", "ROCKET"),
                Set.copyOf(snapshot.projectiles().stream().map(ServerProjectile.View::projectileType).toList()));
        assertEquals(7, snapshot.projectiles().stream().map(ServerProjectile.View::projectileId).distinct().count());
        for (var projectile : snapshot.projectiles()) {
            assertEquals(p.runtime.getMatchId(), projectile.matchId());
            assertTrue(snapshot.players().stream().anyMatch(player -> player.playerId().equals(projectile.ownerPlayerId())));
            assertEquals(0, projectile.spawnTick());
        }
        p.runtime.tick();
        assertEquals(p.runtime.getProjectiles(), p.snapshot().projectiles());
        assertEquals(0.7f, p.snapshot().projectiles().stream().filter(view -> view.projectileType().equals("IBM"))
                .findFirst().orElseThrow().velocityX(), EPS);
    }

    @Test public void snapshotExcludesDestroyedProjectiles() {
        Probe p = create("Viktor", "Luca");
        p.send(0, 0, false, false, false, false, true);
        p.runtime.tick();
        AuthoritativeGameSnapshot beforeContact = p.snapshot();
        contactFirstProjectile(p);
        assertTrue(p.snapshot().projectiles().isEmpty());
        assertEquals(100, p.playerSnapshot(1).hp(), 0);
        assertEquals(1, beforeContact.projectiles().size());
        assertEquals(20, p.physics.getWorld().getBodyCount());
    }

    @Test public void snapshotPlayerOrderIsDeterministic() {
        // Roster đảo thứ tự, slot không liên tiếp, state dùng map: chỉ sort theo slot mới đúng.
        Probe p = create(new int[]{3, 0, 2}, "Alex", "Luca", "Maurice");
        for (int i = 0; i < 4; i++) {
            p.runtime.tick();
            assertEquals(List.of(0, 2, 3), p.snapshot().players().stream().map(PlayerSnapshot::slotIndex).toList());
            assertEquals(List.of(id(1), id(2), id(0)), p.snapshot().players().stream().map(PlayerSnapshot::playerId).toList());
        }
    }

    @Test public void snapshotCollectionsAreImmutable() {
        Probe p = burstScene();
        assertThrows(UnsupportedOperationException.class, () -> p.snapshot().players().clear());
        assertThrows(UnsupportedOperationException.class, () -> p.snapshot().players().set(0, p.playerSnapshot(0)));
        assertThrows(UnsupportedOperationException.class, () -> p.snapshot().projectiles().clear());
        assertThrows(UnsupportedOperationException.class, () -> p.snapshot().projectiles().remove(0));
    }

    @Test public void oldSnapshotDoesNotChangeAfterNewTick() {
        Probe p = meleeScene();
        p.place(1, 4.85f, 3); // Target bên trái: không contact IBM spawn bên phải trong fixture này.
        p.send(0, 0, false, false, false, true, true);
        p.runtime.tick();
        AuthoritativeGameSnapshot old = p.snapshot();
        List<PlayerSnapshot> oldPlayers = List.copyOf(old.players());
        List<ServerProjectile.View> oldProjectiles = List.copyOf(old.projectiles());
        p.place(0, 5, 3); p.place(1, 4.85f, 3);
        p.send(0, 0, false, false, true, false, false);
        p.runtime.tick();
        assertNotSame(old, p.snapshot());
        assertEquals(1, old.serverTick());
        assertEquals(oldPlayers, old.players());
        assertEquals(oldProjectiles, old.projectiles());
        assertEquals(100, old.players().get(1).hp(), 0);
        assertTrue(old.players().get(0).blocking());
        assertEquals(90, p.playerSnapshot(1).hp(), 0);
        assertFalse(p.playerSnapshot(0).blocking());
        assertNotEquals(old.projectiles().get(0).x(), p.snapshot().projectiles().get(0).x(), 0);
    }

    @Test public void latestSnapshotAdvancesWithServerTicks() {
        Probe p = create("Alex", "Luca");
        for (int i = 1; i <= 25; i++) {
            AuthoritativeGameSnapshot previous = p.runtime.getLatestSnapshot();
            p.runtime.tick();
            assertNotSame(previous, p.snapshot());
            assertEquals(i, p.snapshot().serverTick());
            assertEquals(i, p.steps);
        }
    }

    @Test public void otherRoomDataDoesNotLeakIntoSnapshot() {
        Probe a = create("Viktor", "Luca");
        Probe b = create("Maurice", "Luca", "Leo");
        a.place(0, 5, 3); a.place(1, 4.85f, 3);
        a.send(0, 0, false, false, true, false, true);
        a.runtime.tick();
        b.runtime.tick();
        AuthoritativeGameSnapshot originalB = b.snapshot();
        assertNotEquals(a.snapshot().roomId(), b.snapshot().roomId());
        assertNotEquals(a.snapshot().matchId(), b.snapshot().matchId());
        assertEquals(2, a.snapshot().players().size());
        assertEquals(3, b.snapshot().players().size());
        assertEquals(90, a.playerSnapshot(1).hp(), 0);
        assertEquals(100, b.playerSnapshot(1).hp(), 0);
        assertEquals(1, a.snapshot().projectiles().size());
        assertTrue(b.snapshot().projectiles().isEmpty());
        a.runtime.tick();
        assertSame(originalB, b.snapshot());
        b.send(0, 0, false, false, false, false, true);
        b.runtime.tick();
        assertEquals(4, b.snapshot().projectiles().size());
        for (var projectile : b.snapshot().projectiles()) { assertEquals(b.snapshot().matchId(), projectile.matchId()); }
        a.runtime.close();
        b.runtime.tick();
        assertEquals(3, b.snapshot().serverTick());
        assertFalse(b.physics.isDisposed());
    }

    @Test(timeout = 15000) public void concurrentReadersOnlyObserveCompleteSnapshots() throws Exception {
        Probe p = create("Viktor", "Luca", "Leo", "Maurice");
        CountDownLatch firstPublished = new CountDownLatch(1);
        CountDownLatch midTick = new CountDownLatch(1);
        CountDownLatch readersSawOld = new CountDownLatch(3);
        CountDownLatch secondPublished = new CountDownLatch(1);
        Callable<Boolean> simulation = () -> {
            try {
                p.send(0, 1, false, false, false, true, true);
                p.runtime.tick();
                firstPublished.countDown();
                p.afterStep = () -> {
                    // World đã step lần hai nhưng runtime còn giữ monitor, chưa build/publish.
                    midTick.countDown();
                    await(readersSawOld);
                };
                p.runtime.tick();
                return true;
            } finally { firstPublished.countDown(); midTick.countDown(); secondPublished.countDown(); }
        };
        List<Callable<Boolean>> tasks = new ArrayList<>();
        tasks.add(simulation);
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> {
                try {
                    await(firstPublished);
                    await(midTick);
                    AuthoritativeGameSnapshot old = p.snapshot();
                    assertEquals(1, old.serverTick());
                    for (int read = 0; read < 200; read++) {
                        // Chỉ dữ liệu cached; không gọi body()/getProjectiles()/MatchState từ reader.
                        assertSame(old, p.snapshot());
                        assertEquals(4, old.players().size());
                        assertEquals(5 + 0.055f / 60, old.players().get(0).x(), EPS);
                        assertEquals(0.055f, old.players().get(0).velocityX(), EPS);
                        assertEquals(100, old.players().get(0).hp(), 0);
                        assertTrue(old.players().get(0).blocking());
                        assertEquals(1, old.projectiles().size());
                        assertEquals(0, old.projectiles().get(0).velocityX(), 0);
                    }
                } finally { readersSawOld.countDown(); }
                await(secondPublished);
                AuthoritativeGameSnapshot next = p.snapshot();
                assertEquals(2, next.serverTick());
                assertEquals(0.11f, next.players().get(0).velocityX(), EPS);
                assertEquals(0.7f, next.projectiles().get(0).velocityX(), EPS);
                return true;
            });
        }
        assertEquals(List.of(true, true, true, true), InputQueueTest.runConcurrently(tasks));
    }

    @Test public void snapshotDoesNotMutateGameState() {
        Probe p = meleeScene();
        List<NativeValues> afterNativeStep = new ArrayList<>();
        p.afterStep = () -> { for (int role = 0; role < 2; role++) { afterNativeStep.add(p.nativeValues(role)); } };
        p.send(0, 0, false, false, true, false, false);
        p.runtime.tick();
        for (int role = 0; role < 2; role++) {
            assertEquals(afterNativeStep.get(role), p.nativeValues(role));
            var values = afterNativeStep.get(role);
            assertEquals(values.x(), p.playerSnapshot(role).x(), 0);
            assertEquals(values.y(), p.playerSnapshot(role).y(), 0);
            assertEquals(values.hp(), p.playerSnapshot(role).hp(), 0);
        }
        for (int read = 0; read < 100; read++) { p.runtime.getLatestSnapshot(); }
        assertEquals(afterNativeStep.get(0), p.nativeValues(0));
        assertEquals(afterNativeStep.get(1), p.nativeValues(1));
        assertEquals(20, p.physics.getWorld().getBodyCount());
    }

    @Test public void snapshotDoesNotStepWorldAgain() {
        Probe p = burstScene();
        assertEquals(1, p.steps);
        var before = p.nativeValues(0);
        for (int read = 0; read < 1000; read++) { assertSame(p.snapshot(), p.runtime.getLatestSnapshot()); }
        assertEquals(before, p.nativeValues(0));
        assertEquals(1, p.steps);
        assertEquals(1, p.snapshot().serverTick());
        p.runtime.tick();
        assertEquals(2, p.steps);
        assertEquals(2, p.snapshot().serverTick());
    }

    @Test public void closedRuntimeDoesNotPublishInvalidSnapshots() {
        Probe p = create("Alex", "Luca");
        p.send(0, 0, false, false, false, true, true);
        p.runtime.tick();
        AuthoritativeGameSnapshot old = p.snapshot();
        p.runtime.close();
        p.runtime.close();
        assertNull(p.runtime.getLatestSnapshot());
        assertThrows(IllegalStateException.class, p.runtime::tick);
        assertNull(p.runtime.getLatestSnapshot());
        assertTrue(p.physics.isDisposed());
        assertEquals(1, old.serverTick());
        assertTrue(old.players().get(0).blocking());
        assertEquals(1, old.projectiles().size());
        assertFalse(p.player(0).isBlocking());
    }

    @Test public void snapshotIsAbsentBeforeFirstCompletedTick() {
        Probe p = probe(new int[]{0, 1}, "Alex", "Luca");
        assertNull(p.runtime.getLatestSnapshot());
        assertThrows(IllegalStateException.class, p.runtime::tick);
        assertNull(p.runtime.getLatestSnapshot());
        p.runtime.start();
        assertNull(p.runtime.getLatestSnapshot());
        p.runtime.tick();
        assertEquals(1, p.snapshot().serverTick());
    }

    @Test public void failedStepDoesNotPublishPartialState() {
        Probe p = meleeScene();
        p.runtime.tick();
        AuthoritativeGameSnapshot old = p.snapshot();
        p.failStep = true;
        p.send(0, 0, false, false, true, false, false);
        assertThrows(IllegalStateException.class, p.runtime::tick);
        assertEquals(90, p.player(1).getHp(), 0); // State đang dở không được đóng gói dưới tick cũ.
        assertSame(old, p.snapshot());
        assertEquals(100, old.players().get(1).hp(), 0);
        assertEquals(1, old.serverTick());
        assertEquals(1, p.runtime.getMatchState().getServerTick());
    }

    @Test public void failedFirstTickDoesNotInventInitialSnapshot() {
        Probe p = create("Viktor", "Luca");
        p.failStep = true;
        assertThrows(IllegalStateException.class, p.runtime::tick);
        assertNull(p.runtime.getLatestSnapshot());
        assertEquals(0, p.runtime.getMatchState().getServerTick());
    }

    @Test public void deadPlayerSnapshotUsesItsRetainedNativeBody() {
        Probe p = meleeScene();
        p.player(1).applyDamage(90);
        p.send(0, 0, false, false, true, false, false);
        p.runtime.tick();
        assertTrue(p.playerSnapshot(1).dead());
        assertEquals(0, p.playerSnapshot(1).hp(), 0);
        assertFalse(p.playerSnapshot(1).blocking());
        assertEquals(p.body(1).getPosition().x, p.playerSnapshot(1).x(), 0);
        assertEquals(p.body(1).getLinearVelocity().x, p.playerSnapshot(1).velocityX(), 0);
        assertTrue(p.playerSnapshot(1).x() > 5);
        assertEquals(2, p.snapshot().players().size());
    }

    @Test public void snapshotReflectsGroundedAndFacingState() {
        Probe p = create("Alex", "Luca");
        p.place(0, 4.5f, 0.85f);
        for (int i = 0; i < 180 && !p.runtime.getJumpState(id(0)).grounded(); i++) { p.runtime.tick(); }
        assertTrue(p.playerSnapshot(0).grounded());
        p.body(0).setLinearVelocity(-0.3f, 0);
        p.send(0, 0, true, false, false, false, false);
        p.runtime.tick();
        assertFalse(p.playerSnapshot(0).grounded());
        assertFalse(p.playerSnapshot(0).facingRight());
        assertEquals(p.player(0).isFacingRight(), p.playerSnapshot(0).facingRight());
        assertTrue(p.playerSnapshot(0).velocityY() > 0);
    }

    @Test public void snapshotReflectsProjectileDamageOnFollowingTick() {
        Probe p = create("Viktor", "Luca");
        p.send(0, 0, false, false, false, false, true);
        p.runtime.tick();
        contactFirstProjectile(p);
        assertEquals(100, p.playerSnapshot(1).hp(), 0);
        p.place(0, 5, 3); p.place(1, 7, 3);
        p.runtime.tick();
        assertEquals(90, p.playerSnapshot(1).hp(), 0);
        assertEquals(1.804f, p.playerSnapshot(1).velocityY(), EPS);
        assertTrue(p.snapshot().projectiles().isEmpty());
    }

    @Test public void snapshotListsDefensivelyCopyCallerCollections() {
        Probe p = burstScene();
        var players = new ArrayList<>(p.snapshot().players());
        var projectiles = new ArrayList<>(p.snapshot().projectiles());
        var copy = new AuthoritativeGameSnapshot(p.snapshot().matchId(), p.snapshot().roomId(),
                p.snapshot().serverTick(), players, projectiles);
        players.clear(); projectiles.clear();
        assertEquals(4, copy.players().size());
        assertEquals(7, copy.projectiles().size());
        assertEquals(p.snapshot(), copy);
    }

    @Test public void sameTickReadsReturnSameSnapshotInstance() {
        Probe p = create("Viktor", "Luca");
        p.runtime.tick();
        AuthoritativeGameSnapshot snapshot = p.snapshot();
        p.send(0, 1, false, false, false, true, true);
        p.runtime.start();
        for (int read = 0; read < 100; read++) { assertSame(snapshot, p.snapshot()); }
        assertFalse(snapshot.players().get(0).blocking());
        assertTrue(snapshot.projectiles().isEmpty());
    }

    @Test(timeout = 15000) public void nonOwnerCanReadButCannotAdvanceOrRebuildSnapshot() throws Exception {
        Probe p = create("Alex", "Luca");
        p.runtime.tick();
        AuthoritativeGameSnapshot snapshot = p.snapshot();
        InputQueueTest.runConcurrently(List.<Callable<Boolean>>of(() -> {
            assertSame(snapshot, p.snapshot());
            assertThrows(IllegalStateException.class, p.runtime::tick);
            assertSame(snapshot, p.snapshot());
            return true;
        }));
        assertEquals(1, p.steps);
        assertEquals(1, p.snapshot().serverTick());
    }

    @Test public void snapshotModelsContainOnlyImmutableValues() {
        for (Class<?> model : List.of(PlayerSnapshot.class, ServerProjectile.View.class)) {
            assertTrue(model.isRecord());
            for (var component : model.getRecordComponents()) {
                Class<?> type = component.getType();
                assertTrue("Không được giữ mutable/native reference: " + component,
                        type.isPrimitive() || type == String.class || type == PlayerId.class);
            }
        }
        assertTrue(AuthoritativeGameSnapshot.class.isRecord());
    }

    @Test public void snapshotProjectileOrderIsDeterministic() {
        Probe p = burstScene();
        List<Long> ids = p.snapshot().projectiles().stream().map(ServerProjectile.View::projectileId).toList();
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L), ids);
        p.runtime.tick();
        assertEquals(ids, p.snapshot().projectiles().stream().map(ServerProjectile.View::projectileId).toList());
    }

    @Test public void failedInitializationDoesNotPublishSnapshot() {
        Probe p = probe(new int[]{0, 1}, "Unknown", "Luca");
        assertThrows(IllegalArgumentException.class, p.runtime::start);
        assertFalse(p.runtime.isStarted());
        assertNull(p.runtime.getLatestSnapshot());
        assertThrows(IllegalStateException.class, p.runtime::tick);
        assertNull(p.runtime.getLatestSnapshot());
    }

    @Test public void closeBeforeFirstTickKeepsSnapshotAbsent() {
        Probe p = create("Alex", "Luca");
        p.runtime.close();
        assertTrue(p.physics.isDisposed());
        assertNull(p.runtime.getLatestSnapshot());
        assertEquals(0, p.runtime.getMatchState().getServerTick());
    }

    private Probe meleeScene() {
        Probe p = create("Viktor", "Luca");
        p.place(0, 5, 3); p.place(1, 5.15f, 3);
        return p;
    }

    private Probe burstScene() {
        Probe p = create("Alex", "Leo", "Maurice", "Viktor");
        for (int role = 0; role < 4; role++) { p.send(role, 0, false, false, false, false, true); }
        p.runtime.tick();
        return p;
    }

    private void contactFirstProjectile(Probe p) {
        long projectileId = p.snapshot().projectiles().get(0).projectileId();
        for (int tick = 0; tick < 8 && !p.snapshot().projectiles().isEmpty(); tick++) {
            var view = p.snapshot().projectiles().stream().filter(projectile -> projectile.projectileId() == projectileId)
                    .findFirst().orElseThrow();
            p.place(1, view.x(), view.y());
            p.runtime.tick();
        }
        assertTrue("Native contact/cleanup phải hoàn tất trong 8 manual ticks", p.snapshot().projectiles().isEmpty());
    }

    private Probe create(String... characters) {
        int[] slots = new int[characters.length];
        for (int role = 0; role < slots.length; role++) { slots[role] = role; }
        return create(slots, characters);
    }

    private Probe create(int[] slots, String... characters) {
        Probe p = probe(slots, characters);
        p.runtime.start();
        for (int role = 0; role < characters.length; role++) { p.place(role, 5 + role * 0.8f, 3 + role * 2); }
        return p;
    }

    private Probe probe(int[] slots, String... characters) {
        List<MatchSetup.Participant> participants = new ArrayList<>();
        for (int role = 0; role < characters.length; role++) {
            participants.add(new MatchSetup.Participant(id(role), slots[role], characters[role]));
        }
        Collections.reverse(participants);
        Probe p = new Probe(new MatchSetup("snapshot-match-" + probes.size(), "snapshot-room-" + probes.size(), participants));
        probes.add(p);
        return p;
    }

    private static PlayerId id(int role) { return new PlayerId("snapshot-player-" + role); }

    private static void await(CountDownLatch latch) {
        try { assertTrue("Test latch timeout", latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private record NativeValues(float x, float y, float vx, float vy, float hp, boolean block, boolean facing) { }

    /** Seam có sẵn giữ native World chỉ trong test; production không expose Body để phục vụ assertions. */
    private static final class Probe {
        private final MatchRuntime runtime;
        private final long[] sequences;
        private HeadlessPhysicsWorld physics;
        private int steps;
        private boolean failStep;
        private Runnable afterStep = () -> { };

        private Probe(MatchSetup setup) {
            sequences = new long[setup.participants().size()];
            runtime = new MatchRuntime(setup, () -> {
                physics = new PhysicsWorldFactory().create();
                return physics;
            }, (world, dt, velocityIterations, positionIterations) -> {
                if (failStep) { throw new IllegalStateException("test-step-failure"); }
                assertEquals(1.0f / 60, dt, 0);
                assertEquals(6, velocityIterations);
                assertEquals(2, positionIterations);
                world.step(dt, velocityIterations, positionIterations);
                steps++;
                afterStep.run();
            });
        }

        private AuthoritativeGameSnapshot snapshot() {
            AuthoritativeGameSnapshot snapshot = runtime.getLatestSnapshot();
            assertNotNull("Cần một tick đã hoàn tất", snapshot);
            return snapshot;
        }

        private PlayerSnapshot playerSnapshot(int role) {
            return snapshot().players().stream().filter(player -> player.playerId().equals(id(role))).findFirst().orElseThrow();
        }

        private ServerPlayer player(int role) { return runtime.getMatchState().getPlayer(id(role)); }

        private Body body(int role) {
            Array<Body> bodies = new Array<>();
            physics.getWorld().getBodies(bodies);
            for (Body body : bodies) { if (id(role).equals(body.getUserData())) { return body; } }
            throw new AssertionError("Thiếu player Body: " + role);
        }

        private NativeValues nativeValues(int role) {
            Body body = body(role);
            return new NativeValues(body.getPosition().x, body.getPosition().y,
                    body.getLinearVelocity().x, body.getLinearVelocity().y,
                    player(role).getHp(), player(role).isBlocking(), player(role).isFacingRight());
        }

        private void place(int role, float x, float y) {
            body(role).setTransform(x, y, 0);
            body(role).setLinearVelocity(0, 0);
        }

        private void send(int role, float axis, boolean jump, boolean stomp, boolean melee, boolean block, boolean ability) {
            PlayerInput input = new PlayerInput(id(role), sequences[role]++, Long.MAX_VALUE,
                    axis, jump, stomp, melee, block, ability);
            assertEquals(InputQueue.OfferResult.ACCEPTED, runtime.submitInput(input));
        }
    }
}
