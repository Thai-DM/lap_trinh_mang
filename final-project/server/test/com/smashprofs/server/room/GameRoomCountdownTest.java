package com.smashprofs.server.room;

import com.smashprofs.common.model.PlayerId;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.smashprofs.server.room.GameRoom.JoinResult.ALREADY_IN_ROOM;
import static com.smashprofs.server.room.GameRoom.JoinResult.COUNTDOWN_IN_PROGRESS;
import static com.smashprofs.server.room.GameRoom.JoinResult.JOINED;
import static com.smashprofs.server.room.GameRoom.JoinResult.ROOM_FULL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class GameRoomCountdownTest {

    @Test
    public void newRoomIsNotCountingDown() {
        ManualClock clock = new ManualClock();
        GameRoom room = new GameRoom("room-1", clock);

        assertEquals(RoomPhase.WAITING, room.getPhase());
        assertCountdownReset(room);
        clock.advanceMillis(5000);
        assertFalse(room.updateCountdown());
        assertFalse(room.cancelCountdown());
        assertCountdownReset(room);
    }

    @Test
    public void waitingRoomCannotStartCountdown() {
        ManualClock clock = new ManualClock();
        GameRoom empty = new GameRoom("empty", clock);
        GameRoom onePlayer = readyRoom("one", clock, 1);
        GameRoom unready = readyRoom("unready", clock, 2);
        unready.setReady(player(0), false);
        GameRoom noCharacter = new GameRoom("no-character", clock);
        noCharacter.joinPlayer(player(0));
        noCharacter.joinPlayer(player(1));

        for (GameRoom room : List.of(empty, onePlayer, unready, noCharacter)) {
            assertFalse(room.startCountdown());
            assertEquals(RoomPhase.WAITING, room.getPhase());
            assertCountdownReset(room);
        }
    }

    @Test
    public void readyRoomCanStartCountdown() {
        for (int count = 2; count <= 4; count++) {
            GameRoom room = readyRoom("room-" + count, new ManualClock(), count);

            assertEquals(RoomPhase.READY, room.getPhase());
            assertTrue(room.startCountdown());
            assertEquals(RoomPhase.COUNTDOWN, room.getPhase());
            assertTrue(room.isCountingDown());
            assertFalse(room.isCountdownComplete());
        }
    }

    @Test
    public void countdownStartsAtThreeSeconds() {
        GameRoom room = readyRoom("room-1", new ManualClock(), 2);

        assertTrue(room.startCountdown());

        assertEquals(3000L, room.getCountdownRemainingMillis());
        assertFalse(room.updateCountdown());
        assertEquals(3000L, room.getCountdownRemainingMillis());
    }

    @Test
    public void countdownDecreasesWithTime() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 2);
        room.startCountdown();

        clock.advanceMillis(1000);
        assertFalse(room.updateCountdown());
        assertEquals(2000L, room.getCountdownRemainingMillis());
        clock.advanceMillis(1000);
        assertFalse(room.updateCountdown());
        assertEquals(1000L, room.getCountdownRemainingMillis());
        assertTrue(room.isCountingDown());
        assertFalse(room.isCountdownComplete());
    }

    @Test
    public void countdownCompletesAfterThreeSeconds() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 2);
        room.startCountdown();
        clock.advanceMillis(2999);
        assertFalse(room.updateCountdown());
        assertEquals(1L, room.getCountdownRemainingMillis());
        assertFalse(room.isCountdownComplete());

        clock.advanceMillis(1);

        assertTrue(room.updateCountdown());
        assertEquals(0L, room.getCountdownRemainingMillis());
        assertTrue(room.isCountdownComplete());
        assertFalse(room.isCountingDown());
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase());
    }

    @Test
    public void remainingTimeNeverBecomesNegative() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 2);
        room.startCountdown();

        clock.advanceMillis(10_000);
        assertTrue(room.updateCountdown());
        assertEquals(0L, room.getCountdownRemainingMillis());
        clock.advanceMillis(10_000);
        assertFalse(room.updateCountdown());
        assertEquals(0L, room.getCountdownRemainingMillis());
    }

    @Test
    public void startingCountdownTwiceDoesNotResetTime() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 2);
        room.startCountdown();
        clock.advanceMillis(1000);
        room.updateCountdown();

        assertFalse(room.startCountdown());
        assertEquals(2000L, room.getCountdownRemainingMillis());
        clock.advanceMillis(2000);
        assertTrue(room.updateCountdown());
        assertTrue(room.isCountdownComplete());
        assertFalse(room.startCountdown());
        assertEquals(0L, room.getCountdownRemainingMillis());
    }

    @Test
    public void unreadyCancelsCountdown() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 2);
        room.startCountdown();
        clock.advanceMillis(1000);
        room.updateCountdown();

        assertTrue(room.setReady(player(0), false));

        assertEquals(RoomPhase.WAITING, room.getPhase());
        assertFalse(room.isPlayerReady(player(0)));
        assertCountdownReset(room);
        clock.advanceMillis(5000);
        assertFalse(room.updateCountdown());
        assertCountdownReset(room);
    }

    @Test
    public void playerLeavingCancelsCountdown() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 4);
        room.startCountdown();

        assertTrue(room.leavePlayer(player(3)));

        assertEquals(3, room.getPlayerCount());
        assertEquals(RoomPhase.READY, room.getPhase());
        assertTrue(room.canStartMatch());
        assertCountdownReset(room);
        clock.advanceMillis(5000);
        assertFalse(room.updateCountdown());
        assertEquals(RoomPhase.READY, room.getPhase());
        assertCountdownReset(room);
    }

    @Test
    public void changingCharacterCancelsCountdown() {
        GameRoom room = readyRoom("room-1", new ManualClock(), 2);
        room.startCountdown();

        assertTrue(room.selectCharacter(player(0), "Luca"));

        assertEquals("Luca", room.getSelectedCharacter(player(0)));
        assertFalse(room.isPlayerReady(player(0)));
        assertEquals(RoomPhase.WAITING, room.getPhase());
        assertCountdownReset(room);
    }

    @Test
    public void joiningDuringCountdownIsRejected() {
        GameRoom room = readyRoom("room-1", new ManualClock(), 2);
        List<PlayerSlot> original = room.getPlayerSlots();
        room.startCountdown();
        PlayerId newcomer = new PlayerId("newcomer");

        assertEquals(COUNTDOWN_IN_PROGRESS, room.joinPlayer(newcomer));

        assertEquals(original, room.getPlayerSlots());
        assertEquals(2, room.getPlayerCount());
        assertFalse(room.containsPlayer(newcomer));
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase());
        assertEquals(3000L, room.getCountdownRemainingMillis());
    }

    @Test
    public void canceledCountdownCanBeRestarted() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 2);
        room.startCountdown();
        clock.advanceMillis(2000);
        room.updateCountdown();
        room.setReady(player(0), false);
        assertCountdownReset(room);
        room.setReady(player(0), true);
        assertEquals(RoomPhase.READY, room.getPhase());
        assertCountdownReset(room);

        assertTrue(room.startCountdown());
        assertEquals(3000L, room.getCountdownRemainingMillis());
        // Deadline cũ đã qua, nhưng lượt countdown mới chưa hoàn tất.
        clock.advanceMillis(1000);
        assertFalse(room.updateCountdown());
        assertEquals(2000L, room.getCountdownRemainingMillis());
        clock.advanceMillis(2000);
        assertTrue(room.updateCountdown());
    }

    @Test
    public void countdownCompletionDoesNotStartMatch() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 2);
        room.startCountdown();
        clock.advanceMillis(3000);

        assertTrue(room.updateCountdown());
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase());
        assertTrue(room.isCountdownComplete());
        assertFalse(room.isCountingDown());
        clock.advanceMillis(60_000);
        assertFalse(room.updateCountdown());
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase());
        assertTrue(room.isCountdownComplete());
    }

    @Test
    public void twoRoomsHaveIndependentCountdowns() {
        ManualClock clock = new ManualClock();
        GameRoom first = readyRoom("first", clock, 2);
        GameRoom second = readyRoom("second", clock, 2);
        first.startCountdown();
        clock.advanceMillis(1000);
        first.updateCountdown();
        second.startCountdown();
        assertEquals(2000L, first.getCountdownRemainingMillis());
        assertEquals(3000L, second.getCountdownRemainingMillis());

        first.setReady(player(0), false);
        clock.advanceMillis(1000);

        assertFalse(first.updateCountdown());
        assertCountdownReset(first);
        assertFalse(second.updateCountdown());
        assertEquals(2000L, second.getCountdownRemainingMillis());
        assertTrue(second.isCountingDown());
        clock.advanceMillis(2000);
        assertTrue(second.updateCountdown());
        assertTrue(second.isCountdownComplete());
        assertCountdownReset(first);
    }

    @Test
    public void existingRoomTestsStillPass() {
        GameRoom room = readyRoom("room-1", new ManualClock(), 4);
        List<PlayerSlot> original = room.getPlayerSlots();

        assertEquals(ALREADY_IN_ROOM, room.joinPlayer(player(0)));
        assertEquals(ROOM_FULL, room.joinPlayer(new PlayerId("fifth")));
        assertTrue(room.leavePlayer(player(1)));
        PlayerId replacement = new PlayerId("replacement");
        assertEquals(JOINED, room.joinPlayer(replacement));
        assertEquals(1, room.getPlayerSlot(replacement).slotIndex());
        assertNull(room.getSelectedCharacter(replacement));
        assertFalse(room.isPlayerReady(replacement));
        assertEquals(RoomPhase.WAITING, room.getPhase());
        assertTrue(room.selectCharacter(replacement, "Alex"));
        assertTrue(room.setReady(replacement, true));
        assertEquals(RoomPhase.READY, room.getPhase());
        assertTrue(room.canStartMatch());
        assertCountdownReset(room);
        assertEquals(player(1), original.get(1).playerId());
        assertThrows(UnsupportedOperationException.class, () -> original.clear());
    }

    @Test
    public void completionIsReportedOnlyOnce() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 2);
        room.startCountdown();
        clock.advanceMillis(3000);

        assertTrue(room.updateCountdown());
        assertFalse(room.updateCountdown());
        assertFalse(room.updateCountdown());
        assertTrue(room.isCountdownComplete());
    }

    @Test
    public void repeatedReadyAndSameCharacterDoNotCancelCountdown() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 2);
        room.startCountdown();
        clock.advanceMillis(1000);
        room.updateCountdown();

        assertTrue(room.setReady(player(0), true));
        assertTrue(room.selectCharacter(player(0), new String("Alex")));
        assertEquals(ALREADY_IN_ROOM, room.joinPlayer(player(0)));

        assertTrue(room.isCountingDown());
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase());
        assertEquals(2000L, room.getCountdownRemainingMillis());
        clock.advanceMillis(2000);
        assertTrue(room.updateCountdown());
        assertTrue(room.setReady(player(0), true));
        assertTrue(room.selectCharacter(player(0), "Alex"));
        assertTrue(room.isCountdownComplete());
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase());
    }

    @Test
    public void rejectedActionsDoNotCancelCountdown() {
        GameRoom room = readyRoom("room-1", new ManualClock(), 2);
        room.startCountdown();
        PlayerId unknown = new PlayerId("unknown");

        assertFalse(room.leavePlayer(unknown));
        assertFalse(room.setReady(unknown, false));
        assertFalse(room.selectCharacter(unknown, "Alex"));
        assertThrows(IllegalArgumentException.class, () -> room.selectCharacter(player(0), "invalid"));
        assertThrows(NullPointerException.class, () -> room.selectCharacter(player(0), null));

        assertTrue(room.isCountingDown());
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase());
        assertEquals(3000L, room.getCountdownRemainingMillis());
    }

    @Test
    public void explicitCancellationIsSafeAndDoesNotAutoRestart() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 2);
        room.startCountdown();

        assertTrue(room.cancelCountdown());
        assertFalse(room.cancelCountdown());
        assertEquals(RoomPhase.READY, room.getPhase());
        assertCountdownReset(room);
        clock.advanceMillis(5000);
        assertFalse(room.updateCountdown());
        assertCountdownReset(room);
        assertTrue(room.startCountdown());
        assertEquals(3000L, room.getCountdownRemainingMillis());
    }

    @Test
    public void leavingOnlyOnePlayerCancelsCountdownToWaiting() {
        GameRoom room = readyRoom("room-1", new ManualClock(), 2);
        room.startCountdown();

        assertTrue(room.leavePlayer(player(1)));

        assertEquals(1, room.getPlayerCount());
        assertEquals(RoomPhase.WAITING, room.getPhase());
        assertFalse(room.canStartMatch());
        assertCountdownReset(room);
    }

    @Test
    public void completedCountdownCanBeCanceledAndRejectsNewPlayersUntilThen() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 2);
        room.startCountdown();
        clock.advanceMillis(3000);
        room.updateCountdown();

        assertEquals(COUNTDOWN_IN_PROGRESS, room.joinPlayer(new PlayerId("newcomer")));
        assertTrue(room.isCountdownComplete());
        assertTrue(room.cancelCountdown());
        assertEquals(RoomPhase.READY, room.getPhase());
        assertCountdownReset(room);
        assertEquals(JOINED, room.joinPlayer(new PlayerId("newcomer")));
        assertEquals(RoomPhase.WAITING, room.getPhase());
    }

    @Test
    public void fractionalMillisecondDoesNotCompleteEarly() {
        ManualClock clock = new ManualClock();
        GameRoom room = readyRoom("room-1", clock, 2);
        room.startCountdown();
        clock.nowNanos += TimeUnit.MILLISECONDS.toNanos(3000) - 1L;

        assertFalse(room.updateCountdown());
        assertEquals(1L, room.getCountdownRemainingMillis());
        assertFalse(room.isCountdownComplete());
        clock.nowNanos++;
        assertTrue(room.updateCountdown());
        assertEquals(0L, room.getCountdownRemainingMillis());
    }

    @Test
    public void negativeNanoTimeOriginIsSupported() {
        ManualClock clock = new ManualClock();
        clock.nowNanos = -TimeUnit.SECONDS.toNanos(5);
        GameRoom room = readyRoom("room-1", clock, 2);
        room.startCountdown();

        clock.advanceMillis(1000);
        assertFalse(room.updateCountdown());
        assertEquals(2000L, room.getCountdownRemainingMillis());
        clock.advanceMillis(2000);
        assertTrue(room.updateCountdown());
    }

    @Test
    public void nanoTimeWraparoundIsSupported() {
        ManualClock clock = new ManualClock();
        clock.nowNanos = Long.MAX_VALUE - TimeUnit.SECONDS.toNanos(1);
        GameRoom room = readyRoom("room-1", clock, 2);
        room.startCountdown();

        clock.advanceMillis(2000);
        assertFalse(room.updateCountdown());
        assertEquals(1000L, room.getCountdownRemainingMillis());
        clock.advanceMillis(1000);
        assertTrue(room.updateCountdown());
    }

    @Test
    public void nullClockIsRejected() {
        assertThrows(NullPointerException.class, () -> new GameRoom("room-1", null));
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

    private static PlayerId player(int index) {
        return new PlayerId("player-" + index);
    }

    private static void assertCountdownReset(GameRoom room) {
        assertFalse(room.isCountingDown());
        assertFalse(room.isCountdownComplete());
        assertEquals(0L, room.getCountdownRemainingMillis());
    }

}
