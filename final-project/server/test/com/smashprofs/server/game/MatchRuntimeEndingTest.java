package com.smashprofs.server.game;

import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.utils.Array;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import com.smashprofs.server.game.combat.MeleeCombatController;
import com.smashprofs.server.game.physics.HeadlessPhysicsWorld;
import com.smashprofs.server.game.physics.PhysicsWorldFactory;
import com.smashprofs.server.game.projectile.ServerProjectile;
import com.smashprofs.server.network.ClientSession;
import com.smashprofs.server.network.ConnectionManager;
import com.smashprofs.server.network.PlayerInputHandler;
import com.smashprofs.server.network.SessionChannel;
import com.smashprofs.server.room.GameRoom;
import com.smashprofs.server.room.RoomManager;
import com.smashprofs.server.room.RoomPhase;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static com.smashprofs.server.game.AuthoritativeMatchResult.FinishReason.*;
import static org.junit.Assert.*;

/** Native World/TMX thật, manual tick/clock/executor; không sleep, GUI hoặc socket. */
public class MatchRuntimeEndingTest {
    private static final float EPS = 0.0001f;
    private static final long SECOND = 1_000_000_000L;
    private final List<Probe> probes = new ArrayList<>();
    private final List<FixedTickScheduler> schedulers = new ArrayList<>();
    private final List<RoomManager> managers = new ArrayList<>();

    @After public void closeResources() {
        schedulers.forEach(FixedTickScheduler::stop);
        managers.forEach(RoomManager::shutdown);
        probes.forEach(probe -> probe.runtime.close());
    }

    @Test public void playerRemainsAliveWithPositiveHP() {
        ServerPlayer player = new ServerPlayer(id(0), "Alex", 0);
        player.applyDamage(99.9f);
        assertTrue(player.getHp() > 0);
        assertFalse(player.isDead());
    }

    @Test public void playerDeathOccursAtCorrectThreshold() {
        ServerPlayer player = new ServerPlayer(id(0), "Alex", 0);
        player.applyDamage(100);
        assertEquals(0, player.getHp(), 0);
        assertTrue(player.isDead());
    }

    @Test public void playerHPDoesNotBecomeNegative() {
        ServerPlayer player = new ServerPlayer(id(0), "Alex", 0);
        player.applyDamage(1000);
        assertEquals(0, player.getHp(), 0);
        assertTrue(player.isDead());
    }

    @Test public void deathIsIdempotent() {
        ServerPlayer player = new ServerPlayer(id(0), "Alex", 0);
        player.setBlocking(true);
        for (int i = 0; i < 5; i++) { player.applyDamage(100); }
        player.setBlocking(true);
        assertTrue(player.isDead());
        assertEquals(0, player.getHp(), 0);
        assertFalse(player.isBlocking());
    }

    @Test public void eliminatedPlayerCannotMove() {
        Probe p = create("Alex", "Luca", "Leo");
        p.player(0).applyDamage(100);
        p.send(0, 1, false, false, false, true, false);
        p.runtime.tick();
        assertEquals(0, p.body(0).getLinearVelocity().x, EPS);
        assertFalse(p.player(0).isBlocking());
        assertFalse(p.runtime.isFinished());
    }

    @Test public void eliminatedPlayerCannotMelee() {
        Probe p = create("Alex", "Luca", "Leo");
        p.place(1, 5.15f, 3);
        p.player(0).applyDamage(100);
        p.send(0, 0, false, false, true, false, false);
        p.runtime.tick();
        assertEquals(100, p.player(1).getHp(), 0);
    }

    @Test public void eliminatedPlayerCannotStomp() {
        Probe p = create("Alex", "Luca", "Leo");
        p.player(0).applyDamage(100);
        p.send(0, 0, false, true, false, false, false);
        p.runtime.tick();
        assertFalse(p.runtime.getStompState(id(0)).active());
        assertEquals(-0.098f, p.body(0).getLinearVelocity().y, EPS);
        assertEquals(0, p.player(0).getHp(), 0);
    }

    @Test public void eliminatedPlayerCannotActivateAbility() {
        Probe p = create("Viktor", "Luca", "Leo");
        p.player(0).applyDamage(100);
        p.send(0, 0, false, false, false, false, true);
        p.runtime.tick();
        assertEquals(0, p.runtime.getActiveProjectileCount());
    }

    @Test public void previouslySpawnedProjectileUsesOriginalDeathPolicy() {
        Probe p = create("Alex", "Luca", "Leo");
        p.place(1, 7, 3);
        p.send(0, 0, false, false, false, false, true);
        p.runtime.tick();
        var old = p.runtime.getLatestSnapshot();
        p.player(0).applyDamage(100);
        p.runtime.tick();
        assertFalse(p.runtime.isFinished());
        assertEquals(1, p.runtime.getActiveProjectileCount());
        var rocket = p.runtime.getLatestSnapshot().projectiles().get(0);
        assertEquals(id(0), rocket.ownerPlayerId());
        assertEquals(0.5f, (float) Math.hypot(rocket.velocityX(), rocket.velocityY()), EPS);
        assertEquals(1, old.projectiles().size());
    }

    @Test public void twoPlayerMatchEndsWithOneSurvivor() {
        Probe p = lethalMelee();
        assertTrue(p.runtime.isFinished());
        assertEquals(id(0), p.runtime.getMatchResult().winnerId());
        assertEquals(LAST_PLAYER_STANDING, p.runtime.getMatchResult().finishReason());
    }

    @Test public void threePlayerMatchDoesNotEndAfterFirstElimination() { assertStillPlayingAfterOneDeath(3); }
    @Test public void fourPlayerMatchDoesNotEndAfterFirstElimination() { assertStillPlayingAfterOneDeath(4); }

    private void assertStillPlayingAfterOneDeath(int count) {
        String[] characters = new String[count];
        java.util.Arrays.fill(characters, "Luca");
        Probe p = create(characters);
        p.player(0).applyDamage(100);
        p.runtime.tick();
        assertFalse(p.runtime.isFinished());
        assertNull(p.runtime.getMatchResult());
        p.runtime.tick();
        assertEquals(2, p.steps);
    }

    @Test public void fourPlayerMatchEndsAtCorrectCondition() {
        Probe p = create("Alex", "Leo", "Maurice", "Viktor");
        for (int role = 0; role < 3; role++) { p.player(role).applyDamage(100); }
        p.runtime.tick();
        assertEquals(id(3), p.runtime.getMatchResult().winnerId());
        assertEquals(4, p.runtime.getMatchResult().participants().size());
        assertTrue(p.runtime.isFinished());
    }

    @Test public void simultaneousDeathsAreResolvedDeterministically() {
        for (int shift = 0; shift < 4; shift++) {
            int[] slots = new int[4];
            for (int role = 0; role < 4; role++) { slots[role] = (role + shift) % 4; }
            Probe p = create(slots, "Alex", "Leo", "Maurice", "Viktor");
            for (int role = 0; role < 3; role++) {
                p.player(role).applyDamage(90);
                p.place(role, 3.1f + role * 0.1f, -0.1f);
            }
            p.runtime.tick();
            assertEquals(id(3), p.runtime.getMatchResult().winnerId());
            assertEquals(1, p.runtime.getMatchResult().finishedAtTick());
            for (int role = 0; role < 3; role++) { assertTrue(p.player(role).isDead()); }
        }
    }

    @Test public void allPlayersEliminatedProducesCorrectOutcome() {
        for (int count = 2; count <= 4; count++) {
            String[] roster = new String[count];
            java.util.Arrays.fill(roster, "Luca");
            Probe p = create(roster);
            for (int role = 0; role < count; role++) {
                p.player(role).applyDamage(90);
                p.place(role, 3.1f + role * 0.1f, -0.1f);
            }
            p.runtime.tick();
            assertTrue(p.runtime.getMatchResult().isDraw());
            assertNull(p.runtime.getMatchResult().winnerId());
            assertEquals(ALL_PLAYERS_ELIMINATED, p.runtime.getMatchResult().finishReason());
        }
    }

    @Test public void matchResultHasCorrectParticipants() {
        Probe p = lethalMelee();
        var result = p.runtime.getMatchResult();
        assertEquals(p.runtime.getMatchId(), result.matchId());
        assertEquals(p.runtime.getRoomId(), result.roomId());
        assertEquals(List.of(id(0), id(1)), result.participants().stream()
                .map(AuthoritativeMatchResult.ParticipantResult::playerId).toList());
        assertEquals("Viktor", result.participants().get(0).characterId());
        assertEquals(1, result.participants().get(1).slotIndex());
        assertEquals(0, result.participants().get(1).finalHp(), 0);
        assertTrue(result.participants().get(1).eliminated());
    }

    @Test public void matchResultHasCorrectWinner() {
        Probe p = create(new int[]{2, 0, 3}, "Viktor", "Leo", "Luca");
        p.player(0).applyDamage(100);
        p.player(1).applyDamage(100);
        p.runtime.tick();
        assertEquals(id(2), p.runtime.getMatchResult().winnerId());
        assertFalse(p.runtime.getMatchResult().isDraw());
    }

    @Test public void matchResultIsImmutable() {
        Probe p = lethalMelee();
        var result = p.runtime.getMatchResult();
        assertThrows(UnsupportedOperationException.class, () -> result.participants().clear());
        var mutable = new ArrayList<>(result.participants());
        var copy = new AuthoritativeMatchResult(result.matchId(), result.roomId(), result.finishedAtTick(),
                result.winnerId(), result.finishReason(), mutable);
        mutable.clear();
        assertEquals(result, copy);
        p.runtime.close();
        assertSame(result, p.runtime.getMatchResult());
        assertEquals(100, result.participants().get(0).finalHp(), 0);
    }

    @Test public void matchResultIsCreatedOnce() {
        Probe p = lethalMelee();
        var result = p.runtime.getMatchResult();
        p.runtime.start();
        for (int i = 0; i < 4; i++) {
            assertThrows(IllegalStateException.class, p.runtime::tick);
            assertSame(result, p.runtime.getMatchResult());
        }
        assertEquals(1, p.steps);
    }

    @Test public void roomTransitionsToFinishedOnce() {
        AtomicLong clock = new AtomicLong();
        RoomManager rooms = new RoomManager(scheduler(new ManualScheduledExecutor()), clock::get);
        managers.add(rooms);
        GameRoom room = startRoom(rooms, "room-ending", clock, 2);
        room.getMatchRuntime().getMatchState().getPlayer(id(1)).applyDamage(100);
        room.getMatchRuntime().tick();
        assertTrue(room.updateLifecycle());
        assertEquals(RoomPhase.FINISHED, room.getPhase());
        assertFalse(room.updateLifecycle());
        assertNull(room.startMatch());
        assertFalse(room.startCountdown());
        assertFalse(room.setReady(id(0), true));
        assertFalse(room.selectCharacter(id(0), "Leo"));
        assertFalse(room.leavePlayer(id(0)));
        assertEquals(GameRoom.JoinResult.MATCH_ALREADY_STARTED, room.joinPlayer(new PlayerId("new-player")));
    }

    @Test public void finishedMatchRejectsGameplayInput() {
        Probe p = lethalMelee();
        assertEquals(InputQueue.OfferResult.MATCH_FINISHED, p.runtime.submitInput(
                new PlayerInput(id(0), 100, Long.MAX_VALUE, 1, true, true, true, true, true)));
        assertEquals(1, p.runtime.getMatchState().getServerTick());
        assertEquals(0, p.runtime.getActiveProjectileCount());
    }

    @Test public void finishedMatchDoesNotStepWorldAgain() {
        Probe p = lethalMelee();
        var snapshot = p.runtime.getLatestSnapshot();
        var executor = new ManualScheduledExecutor();
        var scheduler = scheduler(executor);
        assertThrows(IllegalStateException.class, () -> scheduler.register(p.runtime));
        assertThrows(IllegalStateException.class, p.runtime::tick);
        assertEquals(1, p.steps);
        assertSame(snapshot, p.runtime.getLatestSnapshot());
    }

    @Test public void finalSnapshotReflectsTerminalState() {
        Probe p = lethalMelee();
        var snapshot = p.runtime.getLatestSnapshot();
        var result = p.runtime.getMatchResult();
        assertEquals(result.finishedAtTick(), snapshot.serverTick());
        assertTrue(snapshot.players().get(1).dead());
        assertEquals(0, snapshot.players().get(1).hp(), 0);
        assertEquals(p.body(1).getPosition().x, snapshot.players().get(1).x(), 0);
        assertFalse(snapshot.players().get(1).blocking());
    }

    @Test public void latestFinalSnapshotRemainsReadable() {
        Probe p = lethalMelee();
        var snapshot = p.runtime.getLatestSnapshot();
        p.clock.set(200 * SECOND);
        for (int i = 0; i < 20; i++) { assertSame(snapshot, p.runtime.getLatestSnapshot()); }
        assertTrue(p.runtime.isFinished());
        assertFalse(p.runtime.isClosed());
        assertFalse(p.physics.isDisposed());
    }

    @Test public void twoRoomsHaveIndependentResults() {
        var executor = new ManualScheduledExecutor();
        var scheduler = scheduler(executor);
        AtomicLong clock = new AtomicLong();
        RoomManager rooms = new RoomManager(scheduler, clock::get);
        managers.add(rooms);
        GameRoom a = startRoom(rooms, "room-a", clock, 2);
        GameRoom b = startRoom(rooms, "room-b", clock, 3);
        a.getMatchRuntime().getMatchState().getPlayer(id(1)).applyDamage(100);
        b.getMatchRuntime().getMatchState().getPlayer(id(0)).applyDamage(100);
        b.getMatchRuntime().getMatchState().getPlayer(id(1)).applyDamage(100);
        scheduler.start(); executor.runCycle();
        assertEquals(RoomPhase.FINISHED, a.getPhase());
        assertEquals(RoomPhase.FINISHED, b.getPhase());
        assertEquals(id(0), a.getMatchRuntime().getMatchResult().winnerId());
        assertEquals(id(2), b.getMatchRuntime().getMatchResult().winnerId());
        assertEquals(2, a.getMatchRuntime().getMatchResult().participants().size());
        assertEquals(3, b.getMatchRuntime().getMatchResult().participants().size());
        assertNotEquals(a.getMatchRuntime().getMatchResult().matchId(), b.getMatchRuntime().getMatchResult().matchId());
    }

    @Test public void finishingOneMatchDoesNotStopOtherMatch() {
        var executor = new ManualScheduledExecutor();
        var scheduler = scheduler(executor);
        AtomicLong clock = new AtomicLong();
        RoomManager rooms = new RoomManager(scheduler, clock::get);
        managers.add(rooms);
        GameRoom a = startRoom(rooms, "room-a", clock, 2);
        GameRoom b = startRoom(rooms, "room-b", clock, 3);
        a.getMatchRuntime().getMatchState().getPlayer(id(1)).applyDamage(100);
        scheduler.start(); executor.runCycle();
        var result = a.getMatchRuntime().getMatchResult();
        for (int i = 0; i < 4; i++) { executor.runCycle(); }
        assertEquals(1, a.getMatchRuntime().getMatchState().getServerTick());
        assertEquals(5, b.getMatchRuntime().getMatchState().getServerTick());
        assertEquals(RoomPhase.FINISHED, a.getPhase());
        assertEquals(RoomPhase.PLAYING, b.getPhase());
        assertFalse(scheduler.unregister(a.getMatchRuntime())); // Đã unregister đúng một lần khi kết thúc.
        assertSame(result, a.getMatchRuntime().getMatchResult());
        rooms.removeRoom("room-a");
        executor.runCycle();
        assertEquals(6, b.getMatchRuntime().getMatchState().getServerTick());
    }

    @Test public void closingFinishedMatchReleasesResources() {
        Probe p = create("Viktor", "Luca");
        p.place(1, 4.85f, 3);
        p.player(1).applyDamage(90);
        p.send(0, 0, false, false, true, false, true);
        p.runtime.tick();
        assertTrue(p.runtime.isFinished());
        assertEquals(1, p.runtime.getActiveProjectileCount());
        ServerProjectile projectile = p.model(p.runtime.getProjectiles().get(0).projectileId());
        var snapshot = p.runtime.getLatestSnapshot();
        p.runtime.close();
        assertTrue(p.physics.isDisposed());
        assertFalse(projectile.isActive());
        assertNull(p.runtime.getLatestSnapshot());
        assertEquals(1, snapshot.projectiles().size());
        assertNotNull(p.runtime.getMatchResult());
    }

    @Test public void repeatedCloseIsSafe() {
        Probe p = lethalMelee();
        var result = p.runtime.getMatchResult();
        p.runtime.close(); p.runtime.close();
        assertSame(result, p.runtime.getMatchResult());
        assertTrue(p.physics.isDisposed());
        assertTrue(p.runtime.isFinished());
        assertTrue(p.runtime.isClosed());
    }

    @Test public void fallRecoveryDoesNotCauseFalseDeath() {
        Probe p = create("Alex", "Luca");
        Body body = p.body(0);
        p.place(0, 3.1f, -0.1f);
        p.runtime.tick();
        assertEquals(90, p.player(0).getHp(), 0);
        assertFalse(p.player(0).isDead());
        assertFalse(p.runtime.isFinished());
        assertSame(body, p.body(0));
        assertEquals(2.6f, body.getLinearVelocity().y, EPS);
    }

    @Test public void timeoutDoesNotFinishBefore120Seconds() {
        Probe p = create("Alex", "Luca");
        p.clock.set(120 * SECOND - 1);
        p.runtime.tick();
        assertFalse(p.runtime.isFinished());
        p.clock.incrementAndGet();
        p.runtime.tick();
        assertTrue(p.runtime.isFinished());
        assertEquals(TIME_LIMIT, p.runtime.getMatchResult().finishReason());
        assertEquals(2, p.runtime.getMatchResult().finishedAtTick());
    }

    @Test public void timeoutWinnerHasHighestAuthoritativeHPInTwoToFourPlayers() {
        for (int count = 2; count <= 4; count++) {
            String[] roster = new String[count]; java.util.Arrays.fill(roster, "Luca");
            Probe p = create(roster);
            for (int role = 0; role < count - 1; role++) { p.player(role).applyDamage(10 + role); }
            p.clock.set(120 * SECOND);
            p.runtime.tick();
            assertEquals(id(count - 1), p.runtime.getMatchResult().winnerId());
            assertEquals(TIME_LIMIT, p.runtime.getMatchResult().finishReason());
            assertFalse(p.runtime.getMatchResult().isDraw());
        }
    }

    @Test public void timeoutTiedHighestHPProducesDrawWithoutSlotTieBreak() {
        Probe p = create(new int[]{3, 0, 2}, "Alex", "Leo", "Luca");
        p.player(2).applyDamage(50);
        p.clock.set(120 * SECOND);
        p.runtime.tick();
        assertTrue(p.runtime.getMatchResult().isDraw());
        assertNull(p.runtime.getMatchResult().winnerId());
        assertEquals(TIME_LIMIT, p.runtime.getMatchResult().finishReason());
    }

    @Test public void finalTickDamageIsResolvedBeforeTimeoutWinnerSelection() {
        Probe p = create("Viktor", "Luca");
        p.place(1, 5.15f, 3);
        p.player(0).applyDamage(5);
        p.send(0, 0, false, false, true, false, false);
        p.clock.set(120 * SECOND);
        p.runtime.tick();
        assertEquals(id(0), p.runtime.getMatchResult().winnerId());
        assertEquals(90, p.runtime.getLatestSnapshot().players().get(1).hp(), 0);
        assertEquals(95, p.runtime.getMatchResult().participants().get(0).finalHp(), 0);
    }

    @Test public void timeoutComparisonPreservesFractionalHP() {
        Probe p = create("Alex", "Luca");
        p.player(0).applyDamage(0.1f);
        p.clock.set(120 * SECOND);
        p.runtime.tick();
        assertEquals(id(1), p.runtime.getMatchResult().winnerId());
        assertEquals(99.9f, p.runtime.getMatchResult().participants().get(0).finalHp(), EPS);
        assertFalse(p.runtime.getMatchResult().isDraw());
    }

    @Test public void eliminationOnTimeoutTickTakesPriority() {
        for (int eliminated = 1; eliminated <= 2; eliminated++) {
            Probe p = create("Alex", "Luca");
            for (int role = 0; role < eliminated; role++) {
                p.player(role).applyDamage(90);
                p.place(role, 3.1f + role * 0.1f, -0.1f);
            }
            p.clock.set(120 * SECOND);
            p.runtime.tick();
            assertEquals(eliminated == 1 ? LAST_PLAYER_STANDING : ALL_PLAYERS_ELIMINATED,
                    p.runtime.getMatchResult().finishReason());
            assertEquals(eliminated == 2, p.runtime.getMatchResult().isDraw());
            assertEquals(eliminated == 1 ? id(1) : null, p.runtime.getMatchResult().winnerId());
        }
    }

    @Test public void repeatedStartDoesNotResetServerTimer() {
        Probe p = create("Alex", "Luca");
        p.clock.set(119 * SECOND);
        p.runtime.tick();
        p.runtime.start();
        p.clock.set(120 * SECOND);
        p.runtime.tick();
        assertTrue(p.runtime.isFinished());
    }

    @Test public void clientTickDoesNotControlTimeout() {
        Probe p = create("Alex", "Luca");
        p.send(0, 0, false, false, false, false, false); // Helper gửi clientTick=Long.MAX_VALUE.
        p.runtime.tick();
        assertFalse(p.runtime.isFinished());
        assertNull(p.runtime.getMatchResult());
    }

    @Test public void roomCountdownTimeIsNotIncludedInMatchTimeout() {
        AtomicLong clock = new AtomicLong();
        var executor = new ManualScheduledExecutor();
        var scheduler = scheduler(executor);
        RoomManager rooms = new RoomManager(scheduler, clock::get); managers.add(rooms);
        GameRoom room = startRoom(rooms, "clock-room", clock, 2);
        long started = clock.get();
        scheduler.start();
        clock.set(started + 120 * SECOND - 1); executor.runCycle();
        assertEquals(RoomPhase.PLAYING, room.getPhase());
        clock.incrementAndGet(); executor.runCycle();
        assertEquals(RoomPhase.FINISHED, room.getPhase());
        assertEquals(TIME_LIMIT, room.getMatchRuntime().getMatchResult().finishReason());
    }

    @Test public void bothPendingProjectileHitsResolveBeforeDeclaringDraw() {
        Probe p = create("Viktor", "Viktor");
        p.place(1, 7, 3);
        p.body(1).setLinearVelocity(-0.2f, 0);
        p.player(0).applyDamage(90); p.player(1).applyDamage(90);
        p.send(0, 0, false, false, false, false, true);
        p.send(1, 0, false, false, false, false, true);
        p.runtime.tick();
        for (int tick = 0; tick < 8 && p.runtime.getActiveProjectileCount() != 0; tick++) {
            var projectiles = p.runtime.getProjectiles();
            assertEquals("Fixture phải tạo cả hai contact trong cùng step", 2, projectiles.size());
            p.place(1, projectiles.get(0).x(), projectiles.get(0).y());
            p.place(0, projectiles.get(1).x(), projectiles.get(1).y());
            p.runtime.tick();
        }
        assertEquals(0, p.runtime.getActiveProjectileCount());
        assertFalse(p.runtime.isFinished());
        p.runtime.tick();
        assertTrue(p.runtime.getMatchResult().isDraw());
        assertEquals(ALL_PLAYERS_ELIMINATED, p.runtime.getMatchResult().finishReason());
        assertEquals(0, p.player(0).getHp(), 0);
        assertEquals(0, p.player(1).getHp(), 0);
    }

    @Test public void finishedRoomInputHandlerRejectsWithoutForfeit() {
        AtomicLong clock = new AtomicLong();
        RoomManager rooms = new RoomManager(scheduler(new ManualScheduledExecutor()), clock::get); managers.add(rooms);
        GameRoom room = startRoom(rooms, "input-room", clock, 2);
        ClientSession session = session(room.getRoomId());
        ConnectionManager connections = new ConnectionManager(); connections.register(session);
        var handler = new PlayerInputHandler(connections, rooms);
        session.markDisconnected();
        room.getMatchRuntime().tick();
        assertFalse(room.getMatchRuntime().isFinished());
        assertFalse(room.getMatchRuntime().getMatchState().getPlayer(id(0)).isDead());
        ClientSession open = session(room.getRoomId()); connections.remove(session.getSessionId()); connections.register(open);
        room.getMatchRuntime().getMatchState().getPlayer(id(1)).applyDamage(100);
        room.getMatchRuntime().tick();
        assertEquals(PlayerInputHandler.RoutingResult.ROOM_NOT_PLAYING, handler.handle(open,
                new PlayerInput(id(0), 0, 0, 1, true, true, true, true, true)));
        assertEquals(RoomPhase.FINISHED, room.getPhase());
    }

    @Test(timeout = 15000) public void completionCallbackRunsOnceOutsideSimulationLocks() throws Exception {
        Probe p = create("Alex", "Luca");
        var executor = new ManualScheduledExecutor(); var scheduler = scheduler(executor);
        p.player(1).applyDamage(100);
        var callbacks = new AtomicLong();
        assertTrue(scheduler.register(p.runtime, finished -> {
            assertFalse(Thread.holdsLock(finished));
            assertTrue(finished.isFinished());
            assertEquals(finished.getMatchResult().finishedAtTick(), finished.getLatestSnapshot().serverTick());
            try {
                InputQueueTest.runConcurrently(List.<Callable<Boolean>>of(() -> {
                    assertFalse(scheduler.unregister(finished));
                    return true;
                }));
            } catch (Exception failure) { throw new AssertionError(failure); }
            callbacks.incrementAndGet();
        }));
        scheduler.start(); executor.runCycle(); executor.runCycle();
        assertEquals(1, callbacks.get());
        assertEquals(1, p.steps);
    }

    @Test public void closingActiveMatchDoesNotInventWinner() {
        Probe p = create("Alex", "Luca");
        p.runtime.tick();
        p.runtime.close();
        p.clock.set(200 * SECOND);
        assertThrows(IllegalStateException.class, p.runtime::tick);
        assertNull(p.runtime.getMatchResult());
        assertFalse(p.runtime.isFinished());
    }

    private Probe lethalMelee() {
        Probe p = create("Viktor", "Luca");
        p.place(1, 5.15f, 3);
        p.player(1).applyDamage(90);
        p.send(0, 0, false, false, true, false, false);
        p.runtime.tick();
        return p;
    }

    private FixedTickScheduler scheduler(ManualScheduledExecutor executor) {
        var scheduler = new FixedTickScheduler(executor, Logger.getAnonymousLogger());
        schedulers.add(scheduler); return scheduler;
    }

    private GameRoom startRoom(RoomManager rooms, String name, AtomicLong clock, int count) {
        // Mọi manager trong test nhận cùng manual clock; không chờ countdown bằng thời gian thật.
        if (!managers.contains(rooms)) { throw new AssertionError("RoomManager phải được cleanup"); }
        GameRoom room = rooms.getOrCreateRoom(name);
        for (int role = 0; role < count; role++) {
            assertEquals(GameRoom.JoinResult.JOINED, room.joinPlayer(id(role)));
            assertTrue(room.selectCharacter(id(role), "Luca"));
            assertTrue(room.setReady(id(role), true));
        }
        assertTrue(room.startCountdown());
        clock.addAndGet(3 * SECOND);
        assertTrue(room.updateCountdown());
        assertNotNull(room.prepareMatch());
        assertNotNull(room.startMatch());
        return room;
    }

    private ClientSession session(String roomId) {
        SessionChannel channel = new SessionChannel() {
            public void send(Object message) { }
            public void close() { }
            public boolean isOpen() { return true; }
        };
        ClientSession session = new ClientSession("ending-session", channel);
        session.bindPlayer(id(0)); session.joinRoom(roomId); return session;
    }

    private Probe create(String... characters) {
        int[] slots = new int[characters.length];
        for (int role = 0; role < slots.length; role++) { slots[role] = role; }
        return create(slots, characters);
    }

    private Probe create(int[] slots, String... characters) {
        var participants = new ArrayList<MatchSetup.Participant>();
        for (int role = 0; role < characters.length; role++) {
            participants.add(new MatchSetup.Participant(id(role), slots[role], characters[role]));
        }
        Collections.reverse(participants);
        Probe p = new Probe(new MatchSetup("ending-match-" + probes.size(), "ending-room-" + probes.size(), participants));
        probes.add(p); p.runtime.start();
        for (int role = 0; role < characters.length; role++) { p.place(role, 5 + role * 0.8f, 3 + role * 2); }
        return p;
    }

    private static PlayerId id(int role) { return new PlayerId("ending-player-" + role); }

    private static final class Probe {
        private final MatchRuntime runtime;
        private final AtomicLong clock = new AtomicLong();
        private HeadlessPhysicsWorld physics;
        private int steps;
        private final long[] sequences;

        private Probe(MatchSetup setup) {
            sequences = new long[setup.participants().size()];
            runtime = new MatchRuntime(setup, () -> {
                physics = new PhysicsWorldFactory().create(); return physics;
            }, (world, dt, velocity, position) -> {
                assertEquals(1.0f / 60, dt, 0); assertEquals(6, velocity); assertEquals(2, position);
                world.step(dt, velocity, position); steps++;
            }, ignored -> { }, MatchRuntime.DEFAULT_MOVEMENT_TIMEOUT_TICKS,
                    MeleeCombatController.DEFAULT_COOLDOWN_TICKS, clock::get);
        }

        private ServerPlayer player(int role) { return runtime.getMatchState().getPlayer(id(role)); }
        private Body body(int role) {
            Array<Body> bodies = new Array<>(); physics.getWorld().getBodies(bodies);
            for (Body body : bodies) { if (id(role).equals(body.getUserData())) { return body; } }
            throw new AssertionError("Thiếu player body " + role);
        }
        private ServerProjectile model(long id) {
            Array<Body> bodies = new Array<>(); physics.getWorld().getBodies(bodies);
            for (Body body : bodies) {
                if (body.getUserData() instanceof ServerProjectile projectile && projectile.getProjectileId() == id) { return projectile; }
            }
            throw new AssertionError("Thiếu projectile " + id);
        }
        private void place(int role, float x, float y) { body(role).setTransform(x, y, 0); body(role).setLinearVelocity(0, 0); }
        private void send(int role, float axis, boolean jump, boolean stomp, boolean melee, boolean block, boolean ability) {
            assertEquals(InputQueue.OfferResult.ACCEPTED, runtime.submitInput(new PlayerInput(id(role), sequences[role]++,
                    Long.MAX_VALUE, axis, jump, stomp, melee, block, ability)));
        }
    }
}
