package com.smashprofs.server.room;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.server.game.MatchSetup;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static com.smashprofs.server.room.GameRoom.JoinResult.ALREADY_IN_ROOM;
import static com.smashprofs.server.room.GameRoom.JoinResult.JOINED;
import static com.smashprofs.server.room.GameRoom.JoinResult.ROOM_FULL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class GameRoomMatchSetupTest {

    @Test
    public void cannotPrepareBeforeCountdown() {
        ManualClock clock = new ManualClock();
        GameRoom waiting = new GameRoom("waiting", clock);
        GameRoom ready = readyRoom("ready", clock, 2);

        assertNull(waiting.prepareMatch());
        assertEquals(RoomPhase.WAITING, waiting.getPhase());
        assertNull(ready.prepareMatch());
        assertEquals(RoomPhase.READY, ready.getPhase());
    }

    @Test
    public void cannotPrepareWhileCountdownRunning() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 2);
        assertTrue(room.startCountdown());
        assertNull(room.prepareMatch());
        clock.advanceMillis(2999);
        assertFalse(room.updateCountdown());

        assertNull(room.prepareMatch());
        assertTrue(room.isCountingDown());
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase());
    }

    @Test
    public void matchCanBePreparedAfterCountdown() {
        for (int count = 2; count <= 4; count++) {
            ManualClock clock = new ManualClock();
            GameRoom room = readyRoom("room-" + count, clock, count);
            completeCountdown(room, clock);

            MatchSetup setup = room.prepareMatch();

            assertNotNull(setup);
            assertEquals(count, setup.participants().size());
            assertEquals(RoomPhase.COUNTDOWN, room.getPhase());
            assertTrue(room.isCountdownComplete());
            assertFalse(room.isCountingDown());
        }
    }

    @Test
    public void preparedMatchHasCorrectRoomId() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom(" room-1 ", clock, 2);
        completeCountdown(room, clock);

        assertEquals(room.getRoomId(), room.prepareMatch().roomId());
        assertEquals(" room-1 ", room.prepareMatch().roomId());
    }

    @Test
    public void preparedMatchHasUniqueMatchId() {
        ManualClock clock = new ManualClock();
        GameRoom first = readyRoom("first", clock, 2);
        GameRoom second = readyRoom("second", clock, 2);
        completeCountdown(first, clock);
        completeCountdown(second, clock);

        MatchSetup firstSetup = first.prepareMatch();
        MatchSetup secondSetup = second.prepareMatch();

        assertNotEquals(firstSetup.matchId(), secondSetup.matchId());
        assertEquals(4, UUID.fromString(firstSetup.matchId()).version());
        assertEquals(4, UUID.fromString(secondSetup.matchId()).version());
        assertSame(firstSetup, first.prepareMatch());
        assertSame(secondSetup, second.prepareMatch());
    }

    @Test
    public void preparedMatchContainsCorrectPlayers() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 4);
        completeCountdown(room, clock);

        MatchSetup setup = room.prepareMatch();

        assertEquals(List.of(player(0), player(1), player(2), player(3)),
                setup.participants().stream().map(MatchSetup.Participant::playerId).toList());
    }

    @Test
    public void preparedMatchPreservesSlotAndCharacter() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 4);
        room.leavePlayer(player(1));
        room.selectCharacter(player(2), "Maurice");
        room.selectCharacter(player(3), "Viktor");
        room.setReady(player(2), true);
        room.setReady(player(3), true);
        completeCountdown(room, clock);

        assertEquals(List.of(
                new MatchSetup.Participant(player(0), 0, "Alex"),
                new MatchSetup.Participant(player(2), 2, "Maurice"),
                new MatchSetup.Participant(player(3), 3, "Viktor")), room.prepareMatch().participants());
    }

    @Test
    public void preparingTwiceReturnsSameMatch() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 2);
        completeCountdown(room, clock);

        MatchSetup first = room.prepareMatch();
        MatchSetup second = room.prepareMatch();

        assertNotNull(first);
        assertSame(first, second);
        assertEquals(first.matchId(), second.matchId());
        clock.advanceMillis(5000);
        assertFalse(room.updateCountdown());
        assertSame(first, room.prepareMatch());
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase());
    }

    @Test
    public void canceledCountdownCannotPrepareMatch() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 2);
        room.startCountdown();
        clock.advanceMillis(2000);
        room.updateCountdown();

        assertTrue(room.cancelCountdown());
        clock.advanceMillis(5000);
        assertFalse(room.updateCountdown());

        assertNull(room.prepareMatch());
        assertEquals(RoomPhase.READY, room.getPhase());
        assertFalse(room.isCountdownComplete());
    }

    @Test
    public void matchSetupIsImmutable() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 2);
        completeCountdown(room, clock);
        MatchSetup setup = room.prepareMatch();
        List<MatchSetup.Participant> original = setup.participants();

        assertThrows(UnsupportedOperationException.class, () -> original.remove(0));
        assertThrows(UnsupportedOperationException.class, () -> original.clear());
        room.selectCharacter(player(0), "Luca");
        room.leavePlayer(player(1));

        assertEquals(List.of(
                new MatchSetup.Participant(player(0), 0, "Alex"),
                new MatchSetup.Participant(player(1), 1, "Alex")), setup.participants());
        assertEquals("Luca", room.getSelectedCharacter(player(0)));
        assertNull(room.prepareMatch());
    }

    @Test
    public void previousTestsStillPass() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 4);
        PlayerSlot oldSlot = room.getPlayerSlot(player(1));
        assertEquals(ALREADY_IN_ROOM, room.joinPlayer(player(0)));
        assertEquals(ROOM_FULL, room.joinPlayer(new PlayerId("fifth")));
        assertTrue(room.leavePlayer(player(1)));
        PlayerId replacement = new PlayerId("replacement");
        assertEquals(JOINED, room.joinPlayer(replacement));
        assertEquals(oldSlot.slotIndex(), room.getPlayerSlot(replacement).slotIndex());
        assertNull(room.getSelectedCharacter(replacement));
        assertFalse(room.isPlayerReady(replacement));
        assertFalse(room.canStartMatch());
        assertTrue(room.selectCharacter(replacement, "Leo"));
        assertTrue(room.setReady(replacement, true));
        assertTrue(room.canStartMatch());
        assertEquals(RoomPhase.READY, room.getPhase());
        assertNull(room.prepareMatch());

        completeCountdown(room, clock);

        assertEquals(4, room.prepareMatch().participants().size());
        assertEquals(new MatchSetup.Participant(replacement, 1, "Leo"),
                room.prepareMatch().participants().get(1));
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase());
    }

    @Test
    public void elapsedTimeRequiresCountdownUpdateBeforePreparation() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 2);
        room.startCountdown();
        clock.advanceMillis(3000);

        assertNull(room.prepareMatch());
        assertFalse(room.isCountdownComplete());
        assertTrue(room.updateCountdown());
        assertNotNull(room.prepareMatch());
    }

    @Test
    public void cancelingCompletedCountdownDiscardsCachedSetup() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 2);
        completeCountdown(room, clock);
        MatchSetup oldSetup = room.prepareMatch();

        assertTrue(room.cancelCountdown());
        assertNull(room.prepareMatch());
        completeCountdown(room, clock);
        MatchSetup newSetup = room.prepareMatch();

        assertNotSame(oldSetup, newSetup);
        assertNotEquals(oldSetup.matchId(), newSetup.matchId());
        assertEquals(oldSetup.participants(), newSetup.participants());
        assertSame(newSetup, room.prepareMatch());
    }

    @Test
    public void characterChangeRequiresNewCountdownAndFreshSetup() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 2);
        completeCountdown(room, clock);
        MatchSetup oldSetup = room.prepareMatch();

        assertTrue(room.selectCharacter(player(0), "Luca"));
        assertNull(room.prepareMatch());
        assertEquals(RoomPhase.WAITING, room.getPhase());
        assertFalse(room.startCountdown());
        assertTrue(room.setReady(player(0), true));
        assertNull(room.prepareMatch());
        completeCountdown(room, clock);
        MatchSetup newSetup = room.prepareMatch();

        assertNotEquals(oldSetup.matchId(), newSetup.matchId());
        assertEquals("Alex", oldSetup.participants().get(0).characterId());
        assertEquals("Luca", newSetup.participants().get(0).characterId());
    }

    @Test
    public void leavingPlayerDiscardsSetupAndSnapshotsNewRosterAfterNewCountdown() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 3);
        completeCountdown(room, clock);
        MatchSetup oldSetup = room.prepareMatch();

        assertTrue(room.leavePlayer(player(1)));
        assertNull(room.prepareMatch());
        assertEquals(RoomPhase.READY, room.getPhase());
        completeCountdown(room, clock);
        MatchSetup newSetup = room.prepareMatch();

        assertEquals(3, oldSetup.participants().size());
        assertEquals(List.of(
                new MatchSetup.Participant(player(0), 0, "Alex"),
                new MatchSetup.Participant(player(2), 2, "Alex")), newSetup.participants());
        assertNotEquals(oldSetup.matchId(), newSetup.matchId());
    }

    @Test
    public void unreadyAfterPreparationDiscardsCachedSetup() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 2);
        completeCountdown(room, clock);
        MatchSetup oldSetup = room.prepareMatch();

        assertTrue(room.setReady(player(0), false));
        assertNull(room.prepareMatch());
        assertTrue(room.setReady(player(0), true));
        assertNull(room.prepareMatch());
        completeCountdown(room, clock);
        MatchSetup newSetup = room.prepareMatch();

        assertNotEquals(oldSetup.matchId(), newSetup.matchId());
        assertSame(newSetup, room.prepareMatch());
    }

    @Test
    public void noOpAndRejectedActionsPreserveCachedSetup() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 2);
        completeCountdown(room, clock);
        MatchSetup setup = room.prepareMatch();
        PlayerId unknown = new PlayerId("unknown");

        assertTrue(room.selectCharacter(player(0), new String("Alex")));
        assertTrue(room.setReady(player(0), true));
        assertEquals(ALREADY_IN_ROOM, room.joinPlayer(player(0)));
        assertFalse(room.leavePlayer(unknown));
        assertFalse(room.setReady(unknown, false));
        assertThrows(IllegalArgumentException.class, () -> room.selectCharacter(player(0), "invalid"));

        assertSame(setup, room.prepareMatch());
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase());
    }

    private static GameRoom readyRoom(String roomId, ManualClock clock, int count) {
        GameRoom room = new GameRoom(roomId, clock);
        for (int index = 0; index < count; index++) {
            assertEquals(JOINED, room.joinPlayer(player(index)));
            assertTrue(room.selectCharacter(player(index), "Alex"));
            assertTrue(room.setReady(player(index), true));
        }
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
