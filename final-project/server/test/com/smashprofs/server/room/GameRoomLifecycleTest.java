package com.smashprofs.server.room;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.server.game.MatchRuntime;
import com.smashprofs.server.game.MatchSetup;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.Assert.*;

/** Dùng manual clock của countdown, không chờ thời gian thực và không tạo thread. */
public class GameRoomLifecycleTest {
    private final List<GameRoom> createdRooms = new ArrayList<>();

    @After
    public void closeNativeResources() {
        for (GameRoom room : createdRooms) {
            MatchRuntime runtime = room.closeForServerShutdown();
            if (runtime != null) {
                runtime.close();
            }
        }
    }

    private GameRoom createRoom(String id, ManualClock clock) {
        return createRoom(id, clock, runtime -> { });
    }

    private GameRoom createRoom(String id, ManualClock clock, Consumer<MatchRuntime> onStarted) {
        GameRoom room = new GameRoom(id, clock, onStarted);
        createdRooms.add(room);
        return room;
    }

    private GameRoom createRoom(String id, ManualClock clock, Consumer<MatchRuntime> onStarted,
                                Function<List<MatchSetup.Participant>, MatchSetup> setupFactory) {
        GameRoom room = new GameRoom(id, clock, onStarted, setupFactory);
        createdRooms.add(room);
        return room;
    }
    private ManualClock clock;
    private GameRoom room;
    private List<MatchRuntime> registered;

    @Before
    public void setUp() {
        clock = new ManualClock();
        registered = new ArrayList<>();
        room = createRoom("room-1", clock, registered::add);
    }

    @Test
    public void waitingRoomDoesNotStartCountdown() {
        assertFalse(room.updateLifecycle());
        assertEquals(RoomPhase.WAITING, room.getPhase());
        populate(room, 1);
        clock.advanceMillis(5000);
        assertFalse(room.updateLifecycle());
        assertFalse(room.isCountingDown());
        assertNull(room.getMatchRuntime());
        assertTrue(registered.isEmpty());
    }

    @Test
    public void readyRoomAutomaticallyStartsCountdown() {
        populate(room, 2);
        assertEquals(RoomPhase.READY, room.getPhase());
        assertTrue(room.updateLifecycle());
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase());
        assertEquals(3000, room.getCountdownRemainingMillis());
        assertTrue(room.isCountingDown());
        assertNull(room.getMatchRuntime());
    }

    @Test
    public void countdownProgressesWithManualClock() {
        populate(room, 2);
        room.updateLifecycle();
        clock.advanceMillis(1000);
        assertFalse(room.updateLifecycle());
        assertEquals(2000, room.getCountdownRemainingMillis());
        clock.advanceMillis(1999);
        assertFalse(room.updateLifecycle());
        assertEquals(1, room.getCountdownRemainingMillis());
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase());
        assertNull(room.getMatchRuntime());
    }

    @Test
    public void completedCountdownAutomaticallyPreparesMatch() {
        AtomicReference<MatchSetup> prepared = new AtomicReference<>();
        AtomicInteger preparations = new AtomicInteger();
        room = createRoom("room-1", clock, registered::add, participants -> {
            preparations.incrementAndGet();
            MatchSetup setup = new MatchSetup("match-observed", "room-1", participants);
            prepared.set(setup);
            return setup;
        });
        populate(room, 2);
        completeAutomatically();
        assertEquals(1, preparations.get());
        assertNotNull(prepared.get());
        assertEquals("room-1", prepared.get().roomId());
        assertEquals(List.of(new MatchSetup.Participant(player(0), 0, "Alex"),
                new MatchSetup.Participant(player(1), 1, "Alex")), prepared.get().participants());
        assertEquals(prepared.get().matchId(), room.getMatchRuntime().getMatchId());
        room.updateLifecycle();
        assertEquals(1, preparations.get());
    }

    @Test
    public void completedCountdownAutomaticallyStartsMatch() {
        populate(room, 2);
        completeAutomatically();
        MatchRuntime runtime = room.getMatchRuntime();
        assertNotNull(runtime);
        assertTrue(runtime.isStarted());
        assertEquals("room-1", runtime.getRoomId());
        assertEquals(2, runtime.getMatchState().getPlayerCount());
        // Lifecycle không tự tick; scheduler chịu trách nhiệm tick sau bước này.
        assertEquals(0, runtime.getMatchState().getServerTick());
        assertEquals(List.of(runtime), registered);
    }

    @Test
    public void successfulStartChangesPhaseToPlaying() {
        for (int count = 2; count <= 4; count++) {
            room = createRoom("room-" + count, clock, registered::add);
            populate(room, count);
            completeAutomatically();
            assertEquals(RoomPhase.PLAYING, room.getPhase());
            assertFalse(room.isCountingDown());
            assertEquals(count, room.getMatchRuntime().getParticipants().size());
        }
    }

    @Test
    public void playingRoomDoesNotRestartCountdown() {
        populate(room, 2);
        completeAutomatically();
        MatchRuntime runtime = room.getMatchRuntime();
        clock.advanceMillis(60000);
        assertFalse(room.updateLifecycle());
        assertSame(runtime, room.getMatchRuntime());
        assertEquals(RoomPhase.PLAYING, room.getPhase());
        assertEquals(0, room.getCountdownRemainingMillis());
        assertEquals(1, registered.size());
    }

    @Test
    public void unreadyCancelsAutomaticCountdown() {
        populate(room, 2);
        room.updateLifecycle();
        clock.advanceMillis(2000);
        room.updateLifecycle();
        assertTrue(room.setReady(player(0), false));
        assertEquals(RoomPhase.WAITING, room.getPhase());
        clock.advanceMillis(5000);
        assertFalse(room.updateLifecycle());
        assertFalse(room.isCountingDown());
        assertEquals(0, room.getCountdownRemainingMillis());
        assertNull(room.getMatchRuntime());
        assertTrue(registered.isEmpty());
        assertNull(room.getLifecycleFailure());
    }

    @Test
    public void playerLeavingCancelsCountdownWhenRequired() {
        populate(room, 2);
        room.updateLifecycle();
        clock.advanceMillis(2000);
        assertTrue(room.leavePlayer(player(1)));
        assertEquals(RoomPhase.WAITING, room.getPhase());
        clock.advanceMillis(5000);
        assertFalse(room.updateLifecycle());
        assertEquals(1, room.getPlayerCount());
        assertNull(room.getMatchRuntime());
        assertTrue(registered.isEmpty());
        assertNull(room.getLifecycleFailure());
    }

    @Test
    public void newPlayerCannotJoinDuringCountdown() {
        populate(room, 2);
        room.updateLifecycle();
        assertEquals(GameRoom.JoinResult.COUNTDOWN_IN_PROGRESS, room.joinPlayer(player(2)));
        assertEquals(2, room.getPlayerCount());
        assertFalse(room.containsPlayer(player(2)));
    }

    @Test
    public void duplicateLifecycleUpdatesDoNotStartTwice() {
        populate(room, 2);
        assertTrue(room.updateLifecycle());
        clock.advanceMillis(1000);
        for (int index = 0; index < 10; index++) {
            assertFalse(room.updateLifecycle());
            assertEquals(2000, room.getCountdownRemainingMillis());
        }
        clock.advanceMillis(2000);
        assertTrue(room.updateLifecycle());
        MatchRuntime runtime = room.getMatchRuntime();
        for (int index = 0; index < 10; index++) {
            assertFalse(room.updateLifecycle());
            assertSame(runtime, room.getMatchRuntime());
        }
        assertEquals(List.of(runtime), registered);
    }

    @Test
    public void failedMatchPreparationDoesNotEnterPlaying() {
        AtomicInteger attempts = new AtomicInteger();
        room = createRoom("room-1", clock, registered::add, participants -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("test-prepare-failure");
        });
        populate(room, 2);
        room.updateLifecycle();
        clock.advanceMillis(3000);
        assertFalse(room.updateLifecycle());
        assertEquals(RoomPhase.READY, room.getPhase());
        assertNull(room.getMatchRuntime());
        assertTrue(registered.isEmpty());
        assertTrue(room.getLifecycleFailure().contains("prepareMatch"));
        assertTrue(room.getLifecycleFailure().contains("test-prepare-failure"));
        assertNoAutomaticRetry();
        assertEquals(1, attempts.get());
    }

    @Test
    public void failedMatchStartDoesNotEnterPlaying() {
        AtomicInteger attempts = new AtomicInteger();
        room = createRoom("room-1", clock, runtime -> {
            attempts.incrementAndGet();
            assertTrue(runtime.isStarted());
            throw new IllegalStateException("test-registration-failure");
        });
        populate(room, 2);
        room.updateLifecycle();
        clock.advanceMillis(3000);
        assertFalse(room.updateLifecycle());
        assertEquals(RoomPhase.READY, room.getPhase());
        assertNull(room.getMatchRuntime());
        assertNull(room.prepareMatch());
        assertTrue(registered.isEmpty());
        assertTrue(room.getLifecycleFailure().contains("startMatch"));
        assertTrue(room.getLifecycleFailure().contains("test-registration-failure"));
        assertNoAutomaticRetry();
        assertEquals(1, attempts.get());
    }

    @Test
    public void nullPreparedSetupIsReportedAndNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        room = createRoom("room-1", clock, registered::add, participants -> {
            attempts.incrementAndGet();
            return null;
        });
        populate(room, 2);
        room.updateLifecycle();
        clock.advanceMillis(3000);
        assertFalse(room.updateLifecycle());
        assertNull(room.getMatchRuntime());
        assertTrue(room.getLifecycleFailure().contains("prepareMatch"));
        assertNoAutomaticRetry();
        assertEquals(1, attempts.get());
    }

    @Test
    public void rejectedPreparedRosterIsReportedBeforeRegistration() {
        room = createRoom("room-1", clock, registered::add,
                participants -> new MatchSetup("match-test", "different-room", participants));
        populate(room, 2);
        room.updateLifecycle();
        clock.advanceMillis(3000);
        assertFalse(room.updateLifecycle());
        assertEquals(RoomPhase.READY, room.getPhase());
        assertNull(room.getMatchRuntime());
        assertTrue(registered.isEmpty());
        assertTrue(room.getLifecycleFailure().contains("startMatch"));
        assertNoAutomaticRetry();
    }

    @Test
    public void twoRoomsProgressIndependently() {
        GameRoom second = createRoom("room-2", clock);
        populate(room, 2);
        populate(second, 2);
        room.updateLifecycle();
        clock.advanceMillis(1000);
        second.updateLifecycle();
        assertTrue(room.setReady(player(0), false));
        clock.advanceMillis(3000);
        room.updateLifecycle();
        second.updateLifecycle();
        assertEquals(RoomPhase.WAITING, room.getPhase());
        assertNull(room.getMatchRuntime());
        assertEquals(RoomPhase.PLAYING, second.getPhase());
        assertEquals("room-2", second.getMatchRuntime().getRoomId());
    }

    @Test
    public void leavingStillReadyRosterStartsFreshCountdown() {
        populate(room, 4);
        room.updateLifecycle();
        clock.advanceMillis(2000);
        room.updateLifecycle();
        assertTrue(room.leavePlayer(player(3)));
        assertEquals(RoomPhase.READY, room.getPhase());
        assertTrue(room.updateLifecycle());
        assertEquals(3000, room.getCountdownRemainingMillis());
        clock.advanceMillis(1000);
        assertFalse(room.updateLifecycle());
        assertNull(room.getMatchRuntime());
        clock.advanceMillis(2000);
        assertTrue(room.updateLifecycle());
        assertEquals(3, room.getMatchRuntime().getParticipants().size());
    }

    @Test
    public void changingCharacterCancelsCountdownAndRequiresReadyAgain() {
        populate(room, 2);
        room.updateLifecycle();
        assertTrue(room.selectCharacter(player(0), "Luca"));
        assertFalse(room.updateLifecycle());
        assertEquals(RoomPhase.WAITING, room.getPhase());
        assertFalse(room.isPlayerReady(player(0)));
        assertTrue(room.setReady(player(0), true));
        completeAutomatically();
        assertEquals("Luca", room.getMatchRuntime().getParticipants().get(0).characterId());
    }

    @Test
    public void closedRoomDoesNotProgress() {
        populate(room, 2);
        room.updateLifecycle();
        room.closeForServerShutdown();
        clock.advanceMillis(3000);
        assertFalse(room.updateLifecycle());
        assertFalse(room.isCountingDown());
        assertNull(room.getMatchRuntime());
        assertTrue(registered.isEmpty());
    }

    @Test
    public void failedRoomDoesNotPreventAnotherRoomsLifecycle() {
        room = createRoom("room-1", clock, runtime -> {
            throw new IllegalStateException("test-room-error");
        });
        GameRoom healthy = createRoom("healthy", clock);
        populate(room, 2);
        populate(healthy, 2);
        room.updateLifecycle();
        healthy.updateLifecycle();
        clock.advanceMillis(3000);
        assertFalse(room.updateLifecycle());
        assertTrue(healthy.updateLifecycle());
        assertNull(room.getMatchRuntime());
        assertEquals(RoomPhase.PLAYING, healthy.getPhase());
        assertNull(healthy.getLifecycleFailure());
    }

    @Test
    public void existingTestsStillPass() {
        populate(room, 2);
        assertTrue(room.startCountdown());
        clock.advanceMillis(3000);
        assertTrue(room.updateCountdown());
        MatchSetup setup = room.prepareMatch();
        MatchRuntime runtime = room.startMatch();
        assertEquals(setup.matchId(), runtime.getMatchId());
        assertSame(runtime, room.startMatch());
        assertFalse(room.updateLifecycle());
        assertEquals(List.of(runtime), registered);
    }

    private void completeAutomatically() {
        assertTrue(room.updateLifecycle());
        clock.advanceMillis(3000);
        assertTrue(room.updateLifecycle());
    }

    private void assertNoAutomaticRetry() {
        String firstFailure = room.getLifecycleFailure();
        for (int index = 0; index < 10; index++) {
            clock.advanceMillis(5000);
            assertFalse(room.updateLifecycle());
            assertEquals(firstFailure, room.getLifecycleFailure());
        }
        assertNull(room.getMatchRuntime());
        assertFalse(room.isCountingDown());
    }

    private static void populate(GameRoom target, int count) {
        for (int index = 0; index < count; index++) {
            assertEquals(GameRoom.JoinResult.JOINED, target.joinPlayer(player(index)));
            assertTrue(target.selectCharacter(player(index), "Alex"));
            assertTrue(target.setReady(player(index), true));
        }
    }

    private static PlayerId player(int index) {
        return new PlayerId("player-" + index);
    }
}
