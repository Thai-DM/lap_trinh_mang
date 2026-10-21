package com.smashprofs.server.room;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.server.game.MatchRuntime;
import com.smashprofs.server.game.MatchSetup;
import com.smashprofs.server.game.MatchState;
import com.smashprofs.server.game.ServerPlayer;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static com.smashprofs.server.room.GameRoom.JoinResult.ALREADY_IN_ROOM;
import static com.smashprofs.server.room.GameRoom.JoinResult.JOINED;
import static com.smashprofs.server.room.GameRoom.JoinResult.MATCH_ALREADY_STARTED;
import static com.smashprofs.server.room.GameRoom.JoinResult.ROOM_FULL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class GameRoomMatchRuntimeTest {
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

    private GameRoom createRoom(String roomId, ManualClock clock) {
        GameRoom room = new GameRoom(roomId, clock);
        createdRooms.add(room);
        return room;
    }

    @Test
    public void roomCannotStartBeforeCountdown() {
        ManualClock clock = new ManualClock();
        GameRoom waiting = createRoom("waiting", clock);
        GameRoom ready = readyRoom("ready", clock, 2);

        assertNull(waiting.startMatch());
        assertEquals(RoomPhase.WAITING, waiting.getPhase());
        assertNull(ready.startMatch());
        assertEquals(RoomPhase.READY, ready.getPhase());
    }

    @Test
    public void roomCannotStartDuringCountdown() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 2);
        assertTrue(room.startCountdown());
        clock.advanceMillis(2999);
        assertFalse(room.updateCountdown());

        assertNull(room.startMatch());
        assertTrue(room.isCountingDown());
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase());
    }

    @Test
    public void roomCannotStartWithoutPreparedMatch() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 2);
        completeCountdown(room, clock);

        assertNull(room.startMatch());
        assertTrue(room.isCountdownComplete());
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase());
        // startMatch không ngầm gọi prepareMatch hoặc sinh matchId.
        MatchSetup setup = room.prepareMatch();
        assertNotNull(setup);
        assertEquals(setup.matchId(), room.startMatch().getMatchId());
    }

    @Test
    public void roomCanStartAfterPreparedMatch() {
        for (int count = 2; count <= 4; count++) {
            GameRoom room = preparedRoom("room-" + count, new ManualClock(), count);
            MatchSetup setup = room.prepareMatch();

            MatchRuntime runtime = room.startMatch();

            assertNotNull(runtime);
            assertTrue(runtime.isStarted());
            assertEquals(setup.matchId(), runtime.getMatchId());
            assertEquals(room.getRoomId(), runtime.getRoomId());
            assertEquals(count, runtime.getParticipants().size());
            assertSame(setup.participants(), runtime.getParticipants());
        }
    }

    @Test
    public void successfulStartChangesPhaseToPlaying() {
        GameRoom room = preparedRoom("room-1", new ManualClock(), 2);
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase());

        MatchRuntime runtime = room.startMatch();

        assertTrue(runtime.isStarted());
        assertEquals(RoomPhase.PLAYING, room.getPhase());
        assertFalse(room.isCountingDown());
    }

    @Test
    public void startingMatchTwiceDoesNotCreateAnotherRuntime() {
        GameRoom room = preparedRoom("room-1", new ManualClock(), 2);
        MatchSetup setup = room.prepareMatch();

        MatchRuntime first = room.startMatch();
        MatchRuntime second = room.startMatch();

        assertSame(first, second);
        assertEquals(setup.matchId(), second.getMatchId());
        assertSame(setup.participants(), second.getParticipants());
        assertTrue(second.isStarted());
        assertEquals(RoomPhase.PLAYING, room.getPhase());
    }

    @Test
    public void canceledCountdownCannotStartMatch() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 2);
        room.startCountdown();
        clock.advanceMillis(1000);
        room.updateCountdown();
        assertTrue(room.cancelCountdown());
        clock.advanceMillis(5000);

        assertFalse(room.updateCountdown());
        assertNull(room.startMatch());
        assertEquals(RoomPhase.READY, room.getPhase());
    }

    @Test
    public void playerCannotJoinPlayingRoom() {
        GameRoom room = preparedRoom("room-1", new ManualClock(), 2);
        MatchRuntime runtime = room.startMatch();
        List<PlayerSlot> roster = room.getPlayerSlots();
        PlayerId newcomer = new PlayerId("newcomer");

        assertEquals(MATCH_ALREADY_STARTED, room.joinPlayer(newcomer));
        assertEquals(ALREADY_IN_ROOM, room.joinPlayer(player(0)));

        assertFalse(room.containsPlayer(newcomer));
        assertEquals(roster, room.getPlayerSlots());
        assertSame(runtime, room.startMatch());
        assertEquals(RoomPhase.PLAYING, room.getPhase());
    }

    @Test
    public void characterCannotChangeWhilePlaying() {
        GameRoom room = preparedRoom("room-1", new ManualClock(), 2);
        MatchRuntime runtime = room.startMatch();

        assertFalse(room.selectCharacter(player(0), "Luca"));
        assertFalse(room.selectCharacter(player(0), "Alex"));

        assertEquals("Alex", room.getSelectedCharacter(player(0)));
        assertTrue(room.isPlayerReady(player(0)));
        assertEquals("Alex", runtime.getParticipants().get(0).characterId());
        assertSame(runtime, room.startMatch());
        assertEquals(RoomPhase.PLAYING, room.getPhase());
    }

    @Test
    public void readyCannotChangeWhilePlaying() {
        GameRoom room = preparedRoom("room-1", new ManualClock(), 2);
        MatchRuntime runtime = room.startMatch();

        assertFalse(room.setReady(player(0), false));
        assertFalse(room.setReady(player(0), true));

        assertTrue(room.isPlayerReady(player(0)));
        assertTrue(room.isPlayerReady(player(1)));
        assertSame(runtime, room.startMatch());
        assertEquals(RoomPhase.PLAYING, room.getPhase());
    }

    @Test
    public void existingTestsStillPass() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 4);
        assertEquals(ALREADY_IN_ROOM, room.joinPlayer(player(0)));
        assertEquals(ROOM_FULL, room.joinPlayer(new PlayerId("fifth")));
        assertTrue(room.leavePlayer(player(1)));
        PlayerId replacement = new PlayerId("replacement");
        assertEquals(JOINED, room.joinPlayer(replacement));
        assertEquals(1, room.getPlayerSlot(replacement).slotIndex());
        assertNull(room.getSelectedCharacter(replacement));
        assertFalse(room.isPlayerReady(replacement));
        assertTrue(room.selectCharacter(replacement, "Leo"));
        assertTrue(room.setReady(replacement, true));
        completeCountdown(room, clock);
        MatchSetup setup = room.prepareMatch();
        assertSame(setup, room.prepareMatch());
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase());

        MatchRuntime runtime = room.startMatch();

        assertEquals(new MatchSetup.Participant(replacement, 1, "Leo"), runtime.getParticipants().get(1));
        assertEquals(setup.matchId(), runtime.getMatchId());
        assertEquals(RoomPhase.PLAYING, room.getPhase());
    }

    @Test
    public void playerCannotLeavePlayingRoom() {
        GameRoom room = preparedRoom("room-1", new ManualClock(), 2);
        MatchRuntime runtime = room.startMatch();
        List<PlayerSlot> roster = room.getPlayerSlots();

        assertFalse(room.leavePlayer(player(0)));
        assertFalse(room.leavePlayer(new PlayerId("unknown")));

        assertEquals(roster, room.getPlayerSlots());
        assertEquals(2, room.getPlayerCount());
        assertTrue(room.containsPlayer(player(0)));
        assertEquals("Alex", room.getSelectedCharacter(player(0)));
        assertTrue(room.isPlayerReady(player(0)));
        assertSame(runtime, room.startMatch());
        assertEquals(RoomPhase.PLAYING, room.getPhase());
    }

    @Test
    public void playingRoomCannotRestartCountdownOrPrepareAnotherMatch() {
        ManualClock clock = new ManualClock();
        GameRoom room = preparedRoom("room-1", clock, 2);
        MatchRuntime runtime = room.startMatch();

        assertFalse(room.startCountdown());
        assertFalse(room.cancelCountdown());
        assertNull(room.prepareMatch());
        clock.advanceMillis(60_000);
        assertFalse(room.updateCountdown());

        assertSame(runtime, room.startMatch());
        assertTrue(runtime.isStarted());
        assertEquals(RoomPhase.PLAYING, room.getPhase());
    }

    @Test
    public void rosterChangeBeforeStartInvalidatesPreparedMatch() {
        ManualClock clock = new ManualClock();
        GameRoom room = preparedRoom("room-1", clock, 3);
        MatchSetup oldSetup = room.prepareMatch();

        assertTrue(room.leavePlayer(player(1)));
        assertNull(room.startMatch());
        assertEquals(RoomPhase.READY, room.getPhase());
        completeCountdown(room, clock);
        MatchSetup newSetup = room.prepareMatch();
        MatchRuntime runtime = room.startMatch();

        assertNotEquals(oldSetup.matchId(), runtime.getMatchId());
        assertEquals(newSetup.matchId(), runtime.getMatchId());
        assertEquals(List.of(
                new MatchSetup.Participant(player(0), 0, "Alex"),
                new MatchSetup.Participant(player(2), 2, "Alex")), runtime.getParticipants());
    }

    @Test
    public void characterChangeBeforeStartRequiresNewPreparation() {
        ManualClock clock = new ManualClock();
        GameRoom room = preparedRoom("room-1", clock, 2);
        MatchSetup oldSetup = room.prepareMatch();

        assertTrue(room.selectCharacter(player(0), "Luca"));
        assertNull(room.startMatch());
        assertEquals(RoomPhase.WAITING, room.getPhase());
        assertTrue(room.setReady(player(0), true));
        completeCountdown(room, clock);
        assertNull(room.startMatch());
        MatchSetup newSetup = room.prepareMatch();
        MatchRuntime runtime = room.startMatch();

        assertEquals(newSetup.matchId(), runtime.getMatchId());
        assertNotEquals(oldSetup.matchId(), runtime.getMatchId());
        assertEquals("Luca", runtime.getParticipants().get(0).characterId());
    }

    @Test
    public void unreadyBeforeStartPreventsStart() {
        GameRoom room = preparedRoom("room-1", new ManualClock(), 2);

        assertTrue(room.setReady(player(0), false));

        assertNull(room.startMatch());
        assertEquals(RoomPhase.WAITING, room.getPhase());
        assertFalse(room.isCountdownComplete());
        assertTrue(room.setReady(player(0), true));
        assertNull(room.startMatch());
        assertEquals(RoomPhase.READY, room.getPhase());
    }

    @Test
    public void cancelingPreparedCountdownPreventsStart() {
        GameRoom room = preparedRoom("room-1", new ManualClock(), 2);

        assertTrue(room.cancelCountdown());

        assertNull(room.startMatch());
        assertEquals(RoomPhase.READY, room.getPhase());
    }

    @Test
    public void startingOneRoomDoesNotStartAnotherRoom() {
        ManualClock clock = new ManualClock();
        GameRoom first = preparedRoom("first", clock, 2);
        GameRoom second = preparedRoom("second", clock, 2);

        MatchRuntime firstRuntime = first.startMatch();

        assertEquals(RoomPhase.PLAYING, first.getPhase());
        assertEquals(RoomPhase.COUNTDOWN, second.getPhase());
        MatchRuntime secondRuntime = second.startMatch();
        assertNotSame(firstRuntime, secondRuntime);
        assertNotEquals(firstRuntime.getMatchId(), secondRuntime.getMatchId());
        assertEquals("first", firstRuntime.getRoomId());
        assertEquals("second", secondRuntime.getRoomId());
    }

    @Test
    public void startedRoomRuntimeOwnsInitializedMatchState() {
        for (int count = 2; count <= 4; count++) {
            GameRoom room = preparedRoom("room-" + count, new ManualClock(), count);

            MatchRuntime runtime = room.startMatch();
            MatchState state = runtime.getMatchState();

            assertNotNull(state);
            assertEquals(RoomPhase.PLAYING, room.getPhase());
            assertEquals(runtime.getMatchId(), state.getMatchId());
            assertEquals(room.getRoomId(), state.getRoomId());
            assertEquals(count, state.getPlayerCount());
            assertEquals(0L, state.getServerTick());
            for (PlayerSlot slot : room.getPlayerSlots()) {
                ServerPlayer player = state.getPlayer(slot.playerId());
                assertEquals(slot.slotIndex(), player.getSlotIndex());
                assertEquals(room.getSelectedCharacter(slot.playerId()), player.getCharacterId());
                assertEquals(100.0f, player.getHp(), 0.0f);
                assertFalse(player.isDead());
            }
            assertSame(state, room.startMatch().getMatchState());
        }
    }

    @Test
    public void twoRoomsOwnIndependentPlayerStatesEvenForSamePlayerIds() {
        ManualClock clock = new ManualClock();
        GameRoom first = preparedRoom("first", clock, 2);
        GameRoom second = preparedRoom("second", clock, 2);

        MatchState firstState = first.startMatch().getMatchState();
        MatchState secondState = second.startMatch().getMatchState();

        assertNotSame(firstState, secondState);
        for (PlayerSlot slot : first.getPlayerSlots()) {
            assertNotSame(firstState.getPlayer(slot.playerId()), secondState.getPlayer(slot.playerId()));
        }
        assertEquals("first", firstState.getRoomId());
        assertEquals("second", secondState.getRoomId());
    }

    private GameRoom readyRoom(String roomId, ManualClock clock, int count) {
        GameRoom room = createRoom(roomId, clock);
        for (int index = 0; index < count; index++) {
            assertEquals(JOINED, room.joinPlayer(player(index)));
            assertTrue(room.selectCharacter(player(index), "Alex"));
            assertTrue(room.setReady(player(index), true));
        }
        return room;
    }

    private GameRoom preparedRoom(String roomId, ManualClock clock, int count) {
        GameRoom room = readyRoom(roomId, clock, count);
        completeCountdown(room, clock);
        assertNotNull(room.prepareMatch());
        return room;
    }

    private static void completeCountdown(GameRoom room, ManualClock clock) {
        assertTrue(room.startCountdown());
        clock.advanceMillis(3000);
        assertTrue(room.updateCountdown());
    }

    private static PlayerId player(int index) {
        return new PlayerId("player-" + index);
    }
}
