package com.smashprofs.server.room;

import com.smashprofs.common.model.PlayerId;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static com.smashprofs.server.room.GameRoom.JoinResult.ALREADY_IN_ROOM;
import static com.smashprofs.server.room.GameRoom.JoinResult.JOINED;
import static com.smashprofs.server.room.GameRoom.JoinResult.ROOM_FULL;

public class GameRoomTest {

    @Test
    public void roomIdIsStored() {
        GameRoom room = new GameRoom("room-1");

        assertEquals("room-1", room.getRoomId());
    }

    @Test
    public void nullRoomIdIsRejected() {
        assertThrows(NullPointerException.class, () -> new GameRoom(null));
    }

    @Test
    public void blankRoomIdIsRejected() {
        for (String roomId : new String[]{"", " ", "\t\n"}) {
            assertThrows(IllegalArgumentException.class, () -> new GameRoom(roomId));
        }
    }

    @Test
    public void validRoomIdIsNotTrimmed() {
        GameRoom room = new GameRoom(" room-1 ");

        assertEquals(" room-1 ", room.getRoomId());
    }

    @Test
    public void emptyRoomHasZeroPlayers() {
        GameRoom room = new GameRoom("room-1");

        assertEquals(0, room.getPlayerCount());
        assertTrue(room.getPlayerSlots().isEmpty());
        assertFalse(room.isFull());
    }

    @Test
    public void firstPlayerGetsSlotZero() {
        GameRoom room = new GameRoom("room-1");
        PlayerId player = new PlayerId("player-1");

        assertEquals(JOINED, room.joinPlayer(player));

        assertEquals(new PlayerSlot(0, player), room.getPlayerSlot(player));
    }

    @Test
    public void secondPlayerGetsSlotOne() {
        GameRoom room = new GameRoom("room-1");
        room.joinPlayer(new PlayerId("player-1"));
        PlayerId second = new PlayerId("player-2");

        assertEquals(JOINED, room.joinPlayer(second));

        assertEquals(new PlayerSlot(1, second), room.getPlayerSlot(second));
    }

    @Test
    public void fourPlayersCanJoin() {
        GameRoom room = new GameRoom("room-1");

        for (int index = 0; index < 4; index++) {
            PlayerId player = new PlayerId("player-" + index);
            assertEquals(JOINED, room.joinPlayer(player));
            assertEquals(index, room.getPlayerSlot(player).slotIndex());
        }

        assertEquals(4, room.getPlayerCount());
        assertTrue(room.isFull());
    }

    @Test
    public void fifthPlayerIsRejected() {
        GameRoom room = fullRoom();
        List<PlayerSlot> original = room.getPlayerSlots();
        PlayerId fifth = new PlayerId("player-5");

        assertEquals(ROOM_FULL, room.joinPlayer(fifth));

        assertEquals(original, room.getPlayerSlots());
        assertEquals(4, room.getPlayerCount());
        assertFalse(room.containsPlayer(fifth));
        assertNull(room.getPlayerSlot(fifth));
    }

    @Test
    public void duplicatePlayerIsRejected() {
        GameRoom room = new GameRoom("room-1");
        PlayerId player = new PlayerId("player-1");
        room.joinPlayer(player);
        PlayerSlot original = room.getPlayerSlot(player);

        assertEquals(ALREADY_IN_ROOM, room.joinPlayer(new PlayerId("player-1")));

        assertEquals(1, room.getPlayerCount());
        assertSame(original, room.getPlayerSlot(player));
    }

    @Test
    public void playerCanLeaveRoom() {
        GameRoom room = new GameRoom("room-1");
        PlayerId player = new PlayerId("player-1");
        room.joinPlayer(player);

        assertTrue(room.leavePlayer(new PlayerId("player-1")));

        assertFalse(room.containsPlayer(player));
        assertNull(room.getPlayerSlot(player));
        assertEquals(0, room.getPlayerCount());
    }

    @Test
    public void leavingPlayerFreesSlot() {
        GameRoom room = fullRoom();
        PlayerId survivor = new PlayerId("player-2");
        PlayerSlot survivorSlot = room.getPlayerSlot(survivor);

        assertTrue(room.leavePlayer(new PlayerId("player-1")));

        assertEquals(3, room.getPlayerCount());
        assertFalse(room.isFull());
        assertSame(survivorSlot, room.getPlayerSlot(survivor));
        assertEquals(2, survivorSlot.slotIndex());
        assertTrue(room.getPlayerSlots().stream().noneMatch(slot -> slot.slotIndex() == 1));
    }

    @Test
    public void newPlayerReusesLowestFreeSlot() {
        GameRoom room = fullRoom();
        PlayerSlot survivor = room.getPlayerSlot(new PlayerId("player-2"));
        room.leavePlayer(new PlayerId("player-1"));
        room.leavePlayer(new PlayerId("player-3"));
        PlayerId newPlayer = new PlayerId("player-new");

        assertEquals(JOINED, room.joinPlayer(newPlayer));

        assertEquals(1, room.getPlayerSlot(newPlayer).slotIndex());
        assertSame(survivor, room.getPlayerSlot(survivor.playerId()));
        assertEquals(List.of(0, 1, 2),
                room.getPlayerSlots().stream().map(PlayerSlot::slotIndex).toList());
    }

    @Test
    public void leavingUnknownPlayerIsSafe() {
        GameRoom room = fullRoom();
        List<PlayerSlot> original = room.getPlayerSlots();

        assertFalse(room.leavePlayer(new PlayerId("unknown")));

        assertEquals(original, room.getPlayerSlots());
        assertEquals(4, room.getPlayerCount());
    }

    @Test
    public void playerCountIsCorrect() {
        GameRoom room = new GameRoom("room-1");
        PlayerId first = new PlayerId("player-1");
        PlayerId second = new PlayerId("player-2");
        room.joinPlayer(first);
        room.joinPlayer(second);
        room.joinPlayer(first);
        assertEquals(2, room.getPlayerCount());

        room.leavePlayer(first);
        assertEquals(1, room.getPlayerCount());
        room.leavePlayer(first);
        assertEquals(1, room.getPlayerCount());
        room.leavePlayer(second);
        assertEquals(0, room.getPlayerCount());
    }

    @Test
    public void containsPlayerWorks() {
        GameRoom room = new GameRoom("room-1");
        PlayerId player = new PlayerId("player-1");
        assertFalse(room.containsPlayer(player));
        room.joinPlayer(player);
        assertTrue(room.containsPlayer(new PlayerId("player-1")));
        room.leavePlayer(player);
        assertFalse(room.containsPlayer(player));
    }

    @Test
    public void returnedPlayerCollectionCannotBeModified() {
        GameRoom room = new GameRoom("room-1");
        room.joinPlayer(new PlayerId("player-1"));
        List<PlayerSlot> snapshot = room.getPlayerSlots();

        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(new PlayerSlot(1, new PlayerId("other"))));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.remove(0));
        assertEquals(1, room.getPlayerCount());
    }

    @Test(timeout = 15000)
    public void concurrentJoinsNeverExceedFourPlayers() throws Exception {
        GameRoom room = new GameRoom("room-1");

        List<GameRoom.JoinResult> results = joinConcurrently(room, distinctPlayers(12));

        assertEquals(4, results.stream().filter(result -> result == JOINED).count());
        assertEquals(8, results.stream().filter(result -> result == ROOM_FULL).count());
        assertEquals(4, room.getPlayerCount());
        assertTrue(room.isFull());
    }

    @Test(timeout = 15000)
    public void concurrentJoinsDoNotAssignDuplicateSlots() throws Exception {
        GameRoom room = new GameRoom("room-1");
        joinConcurrently(room, distinctPlayers(12));

        Set<Integer> indexes = new HashSet<>();
        Set<PlayerId> players = new HashSet<>();
        for (PlayerSlot slot : room.getPlayerSlots()) {
            assertTrue(indexes.add(slot.slotIndex()));
            assertTrue(players.add(slot.playerId()));
            assertEquals(slot, room.getPlayerSlot(slot.playerId()));
        }
        assertEquals(Set.of(0, 1, 2, 3), indexes);
        assertEquals(4, players.size());
    }

    @Test
    public void invalidPlayerIdIsRejectedWithoutChangingMembership() {
        GameRoom room = fullRoom();
        List<PlayerSlot> original = room.getPlayerSlots();
        for (PlayerId invalid : new PlayerId[]{null, new PlayerId(null)}) {
            assertThrows(NullPointerException.class, () -> room.joinPlayer(invalid));
            assertThrows(NullPointerException.class, () -> room.leavePlayer(invalid));
            assertThrows(NullPointerException.class, () -> room.containsPlayer(invalid));
            assertThrows(NullPointerException.class, () -> room.getPlayerSlot(invalid));
        }
        for (String value : new String[]{"", " ", "\t\n"}) {
            PlayerId invalid = new PlayerId(value);
            assertThrows(IllegalArgumentException.class, () -> room.joinPlayer(invalid));
            assertThrows(IllegalArgumentException.class, () -> room.leavePlayer(invalid));
            assertThrows(IllegalArgumentException.class, () -> room.containsPlayer(invalid));
            assertThrows(IllegalArgumentException.class, () -> room.getPlayerSlot(invalid));
        }
        assertEquals(original, room.getPlayerSlots());
    }

    @Test
    public void duplicatePlayerInFullRoomIsAlreadyInRoom() {
        GameRoom room = fullRoom();

        assertEquals(ALREADY_IN_ROOM, room.joinPlayer(new PlayerId("player-1")));

        assertEquals(4, room.getPlayerCount());
        assertEquals(1, room.getPlayerSlot(new PlayerId("player-1")).slotIndex());
    }

    @Test
    public void playerSnapshotIsDetachedFromLaterMembershipChanges() {
        GameRoom room = new GameRoom("room-1");
        PlayerId original = new PlayerId("player-1");
        room.joinPlayer(original);
        List<PlayerSlot> snapshot = room.getPlayerSlots();

        room.leavePlayer(original);
        room.joinPlayer(new PlayerId("replacement"));

        assertEquals(List.of(new PlayerSlot(0, original)), snapshot);
        assertEquals(new PlayerId("replacement"), room.getPlayerSlots().get(0).playerId());
    }

    @Test
    public void roomsHaveIndependentMembership() {
        GameRoom first = new GameRoom("room-1");
        GameRoom second = new GameRoom("room-2");
        PlayerId player = new PlayerId("player-1");
        first.joinPlayer(player);

        assertEquals(0, second.getPlayerCount());
        assertEquals(JOINED, second.joinPlayer(player));
        first.leavePlayer(player);
        assertTrue(second.containsPlayer(player));
        assertEquals(0, second.getPlayerSlot(player).slotIndex());
    }

    @Test(timeout = 15000)
    public void concurrentDuplicateJoinsOnlyAddOnePlayer() throws Exception {
        GameRoom room = new GameRoom("room-1");
        List<PlayerId> players = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            players.add(new PlayerId("same-player"));
        }

        List<GameRoom.JoinResult> results = joinConcurrently(room, players);

        assertEquals(1, results.stream().filter(result -> result == JOINED).count());
        assertEquals(7, results.stream().filter(result -> result == ALREADY_IN_ROOM).count());
        assertEquals(1, room.getPlayerCount());
    }

    @Test
    public void playerCanSelectCharacter() {
        GameRoom room = new GameRoom("room-1");
        PlayerId player = new PlayerId("player-1");
        room.joinPlayer(player);
        PlayerSlot originalSlot = room.getPlayerSlot(player);

        for (String characterId : List.of("Alex", "Luca", "Maurice", "Leo", "Viktor")) {
            assertTrue(room.selectCharacter(new PlayerId("player-1"), characterId));
            assertEquals(characterId, room.getSelectedCharacter(player));
        }
        assertSame(originalSlot, room.getPlayerSlot(player));
        assertEquals(1, room.getPlayerCount());
    }

    @Test
    public void unknownPlayerCannotSelectCharacter() {
        GameRoom room = new GameRoom("room-1");
        PlayerId player = new PlayerId("unknown");

        assertFalse(room.selectCharacter(player, "Alex"));

        assertNull(room.getSelectedCharacter(player));
        assertEquals(0, room.getPlayerCount());
        room.joinPlayer(player);
        assertNull(room.getSelectedCharacter(player));
    }

    @Test
    public void invalidCharacterIsRejected() {
        GameRoom room = new GameRoom("room-1");
        PlayerId player = new PlayerId("player-1");
        room.joinPlayer(player);
        room.selectCharacter(player, "Alex");

        for (String invalid : List.of("", " ", "Unknown", "alex", " Alex ", "Momo")) {
            assertThrows(IllegalArgumentException.class, () -> room.selectCharacter(player, invalid));
            assertEquals("Alex", room.getSelectedCharacter(player));
        }
        assertEquals(1, room.getPlayerCount());
    }

    @Test
    public void nullCharacterIsRejected() {
        GameRoom room = new GameRoom("room-1");
        PlayerId player = new PlayerId("player-1");
        room.joinPlayer(player);
        room.selectCharacter(player, "Leo");

        assertThrows(NullPointerException.class, () -> room.selectCharacter(player, null));

        assertEquals("Leo", room.getSelectedCharacter(player));
    }

    @Test
    public void playerCanChangeCharacter() {
        GameRoom room = new GameRoom("room-1");
        PlayerId player = new PlayerId("player-1");
        room.joinPlayer(player);
        room.selectCharacter(player, "Alex");
        PlayerSlot originalSlot = room.getPlayerSlot(player);

        assertTrue(room.selectCharacter(player, "Viktor"));

        assertEquals("Viktor", room.getSelectedCharacter(player));
        assertSame(originalSlot, room.getPlayerSlot(player));
    }

    @Test
    public void multiplePlayersCanSelectSameCharacter() {
        GameRoom room = fullRoom();

        for (PlayerSlot slot : room.getPlayerSlots()) {
            assertTrue(room.selectCharacter(slot.playerId(), "Maurice"));
            assertEquals("Maurice", room.getSelectedCharacter(slot.playerId()));
        }
        assertEquals(4, room.getPlayerCount());
    }

    @Test
    public void leavingRoomClearsCharacterSelection() {
        GameRoom room = new GameRoom("room-1");
        PlayerId player = new PlayerId("player-1");
        PlayerId survivor = new PlayerId("player-2");
        room.joinPlayer(player);
        room.joinPlayer(survivor);
        room.selectCharacter(player, "Alex");
        room.selectCharacter(survivor, "Leo");

        assertTrue(room.leavePlayer(new PlayerId("player-1")));

        assertNull(room.getSelectedCharacter(player));
        assertEquals("Leo", room.getSelectedCharacter(survivor));
        assertEquals(JOINED, room.joinPlayer(player));
        assertNull(room.getSelectedCharacter(player));
    }

    @Test
    public void reusedSlotHasNoPreviousCharacter() {
        GameRoom room = fullRoom();
        PlayerId departing = new PlayerId("player-1");
        int oldIndex = room.getPlayerSlot(departing).slotIndex();
        room.selectCharacter(departing, "Luca");
        room.leavePlayer(departing);
        PlayerId replacement = new PlayerId("replacement");

        assertEquals(JOINED, room.joinPlayer(replacement));

        assertEquals(oldIndex, room.getPlayerSlot(replacement).slotIndex());
        assertNull(room.getSelectedCharacter(replacement));
        assertFalse(room.selectCharacter(departing, "Alex"));
        assertNull(room.getSelectedCharacter(replacement));
    }

    @Test
    public void existingJoinLeaveTestsStillPass() {
        GameRoom room = fullRoom();
        PlayerId player = new PlayerId("player-1");
        room.selectCharacter(player, "Alex");
        PlayerSlot original = room.getPlayerSlot(player);

        assertEquals(ALREADY_IN_ROOM, room.joinPlayer(new PlayerId("player-1")));
        assertSame(original, room.getPlayerSlot(player));
        assertEquals("Alex", room.getSelectedCharacter(player));
        assertEquals(ROOM_FULL, room.joinPlayer(new PlayerId("fifth")));
        assertTrue(room.isFull());
        assertTrue(room.leavePlayer(player));
        assertFalse(room.isFull());
        assertFalse(room.leavePlayer(player));
        PlayerId replacement = new PlayerId("replacement");
        assertEquals(JOINED, room.joinPlayer(replacement));
        assertEquals(original.slotIndex(), room.getPlayerSlot(replacement).slotIndex());
        assertEquals(4, room.getPlayerCount());
        assertNull(room.getSelectedCharacter(replacement));
    }

    @Test
    public void joinedPlayerInitiallyHasNoSelectedCharacter() {
        GameRoom room = new GameRoom("room-1");
        PlayerId player = new PlayerId("player-1");

        room.joinPlayer(player);

        assertNull(room.getSelectedCharacter(player));
    }

    @Test
    public void roomsHaveIndependentCharacterSelections() {
        GameRoom first = new GameRoom("room-1");
        GameRoom second = new GameRoom("room-2");
        PlayerId player = new PlayerId("player-1");
        first.joinPlayer(player);
        second.joinPlayer(player);
        first.selectCharacter(player, "Alex");
        second.selectCharacter(player, "Viktor");

        first.leavePlayer(player);

        assertNull(first.getSelectedCharacter(player));
        assertEquals("Viktor", second.getSelectedCharacter(player));
    }

    @Test
    public void invalidPlayerCannotSelectOrQueryCharacter() {
        GameRoom room = new GameRoom("room-1");
        for (PlayerId invalid : new PlayerId[]{null, new PlayerId(null)}) {
            assertThrows(NullPointerException.class, () -> room.selectCharacter(invalid, "Alex"));
            assertThrows(NullPointerException.class, () -> room.getSelectedCharacter(invalid));
        }
        for (String value : List.of("", " ", "\t\n")) {
            PlayerId invalid = new PlayerId(value);
            assertThrows(IllegalArgumentException.class, () -> room.selectCharacter(invalid, "Alex"));
            assertThrows(IllegalArgumentException.class, () -> room.getSelectedCharacter(invalid));
        }
        assertEquals(0, room.getPlayerCount());
    }

    @Test
    public void newPlayerIsNotReady() {
        GameRoom room = new GameRoom("room-1");
        PlayerId player = new PlayerId("player-1");

        room.joinPlayer(player);

        assertFalse(room.isPlayerReady(player));
        assertFalse(room.canStartMatch());
    }

    @Test
    public void playerCanBecomeReadyAfterSelectingCharacter() {
        GameRoom room = new GameRoom("room-1");
        PlayerId player = new PlayerId("player-1");
        room.joinPlayer(player);
        room.selectCharacter(player, "Alex");
        PlayerSlot original = room.getPlayerSlot(player);

        assertTrue(room.setReady(new PlayerId("player-1"), true));

        assertTrue(room.isPlayerReady(player));
        assertSame(original, room.getPlayerSlot(player));
        assertEquals("Alex", room.getSelectedCharacter(player));
    }

    @Test
    public void playerWithoutCharacterCannotReady() {
        GameRoom room = new GameRoom("room-1");
        PlayerId player = new PlayerId("player-1");
        room.joinPlayer(player);

        assertFalse(room.setReady(player, true));

        assertFalse(room.isPlayerReady(player));
        assertNull(room.getSelectedCharacter(player));
        assertFalse(room.canStartMatch());
    }

    @Test
    public void unknownPlayerCannotReady() {
        GameRoom room = new GameRoom("room-1");
        PlayerId unknown = new PlayerId("unknown");

        assertFalse(room.setReady(unknown, true));
        assertFalse(room.setReady(unknown, false));
        assertFalse(room.isPlayerReady(unknown));
        assertEquals(0, room.getPlayerCount());
        room.joinPlayer(unknown);
        assertFalse(room.isPlayerReady(unknown));
    }

    @Test
    public void playerCanCancelReady() {
        GameRoom room = readyRoom(2);
        PlayerId player = new PlayerId("player-0");

        assertTrue(room.setReady(player, false));

        assertFalse(room.isPlayerReady(player));
        assertFalse(room.canStartMatch());
        assertTrue(room.isPlayerReady(new PlayerId("player-1")));
        assertEquals("Alex", room.getSelectedCharacter(player));
    }

    @Test
    public void settingReadyTwiceIsSafe() {
        GameRoom room = readyRoom(2);
        PlayerId player = new PlayerId("player-0");

        assertTrue(room.setReady(player, true));
        assertTrue(room.setReady(player, true));
        assertTrue(room.isPlayerReady(player));
        assertTrue(room.canStartMatch());
        assertTrue(room.setReady(player, false));
        assertTrue(room.setReady(player, false));
        assertFalse(room.isPlayerReady(player));
    }

    @Test
    public void oneReadyPlayerCannotStartMatch() {
        GameRoom room = readyRoom(1);

        assertTrue(room.isPlayerReady(new PlayerId("player-0")));
        assertFalse(room.canStartMatch());
    }

    @Test
    public void twoReadyPlayersCanStartMatch() {
        GameRoom room = readyRoom(2);

        assertTrue(room.canStartMatch());
        assertTrue(room.canStartMatch());
        assertEquals(2, room.getPlayerCount());
    }

    @Test
    public void oneUnreadyPlayerPreventsStart() {
        GameRoom room = readyRoom(3);
        PlayerId player = new PlayerId("player-1");
        room.setReady(player, false);

        assertFalse(room.canStartMatch());

        room.setReady(player, true);
        assertTrue(room.canStartMatch());
    }

    @Test
    public void fourReadyPlayersCanStartMatch() {
        GameRoom room = readyRoom(4);

        assertTrue(room.canStartMatch());
        assertTrue(room.isFull());
        assertEquals(4, room.getPlayerCount());
    }

    @Test
    public void changingCharacterResetsReady() {
        GameRoom room = readyRoom(2);
        PlayerId player = new PlayerId("player-0");
        PlayerSlot original = room.getPlayerSlot(player);

        assertTrue(room.selectCharacter(player, "Viktor"));

        assertFalse(room.isPlayerReady(player));
        assertFalse(room.canStartMatch());
        assertEquals("Viktor", room.getSelectedCharacter(player));
        assertSame(original, room.getPlayerSlot(player));
        assertTrue(room.isPlayerReady(new PlayerId("player-1")));
        assertTrue(room.setReady(player, true));
        assertTrue(room.canStartMatch());
    }

    @Test
    public void leavingRoomRemovesReadyState() {
        GameRoom room = readyRoom(2);
        PlayerId player = new PlayerId("player-0");

        assertTrue(room.leavePlayer(new PlayerId("player-0")));

        assertFalse(room.isPlayerReady(player));
        assertFalse(room.setReady(player, true));
        assertFalse(room.canStartMatch());
        assertTrue(room.isPlayerReady(new PlayerId("player-1")));
        room.joinPlayer(player);
        room.selectCharacter(player, "Alex");
        assertFalse(room.isPlayerReady(player));
        assertFalse(room.canStartMatch());
    }

    @Test
    public void reusedSlotStartsUnready() {
        GameRoom room = readyRoom(4);
        PlayerId departing = new PlayerId("player-1");
        int originalIndex = room.getPlayerSlot(departing).slotIndex();
        room.leavePlayer(departing);
        PlayerId replacement = new PlayerId("replacement");

        assertEquals(JOINED, room.joinPlayer(replacement));

        assertEquals(originalIndex, room.getPlayerSlot(replacement).slotIndex());
        assertFalse(room.isPlayerReady(replacement));
        assertNull(room.getSelectedCharacter(replacement));
        assertFalse(room.canStartMatch());
        room.selectCharacter(replacement, "Alex");
        assertFalse(room.isPlayerReady(replacement));
    }

    @Test
    public void newPlayerMakesRoomNotReadyUntilTheyReady() {
        GameRoom room = readyRoom(2);
        PlayerId newcomer = new PlayerId("newcomer");

        room.joinPlayer(newcomer);
        assertFalse(room.canStartMatch());
        assertFalse(room.setReady(newcomer, true));
        room.selectCharacter(newcomer, "Leo");
        assertFalse(room.canStartMatch());
        assertTrue(room.setReady(newcomer, true));
        assertTrue(room.canStartMatch());
    }

    @Test
    public void existingTestsStillPass() {
        GameRoom room = readyRoom(4);
        PlayerId player = new PlayerId("player-1");
        PlayerSlot original = room.getPlayerSlot(player);
        List<PlayerSlot> snapshot = room.getPlayerSlots();

        assertEquals(ALREADY_IN_ROOM, room.joinPlayer(new PlayerId("player-1")));
        assertSame(original, room.getPlayerSlot(player));
        assertTrue(room.isPlayerReady(player));
        assertEquals(ROOM_FULL, room.joinPlayer(new PlayerId("fifth")));
        assertTrue(room.canStartMatch());
        assertTrue(room.leavePlayer(player));
        assertEquals(3, room.getPlayerCount());
        assertFalse(room.isFull());
        assertNull(room.getSelectedCharacter(player));
        PlayerId replacement = new PlayerId("replacement");
        assertEquals(JOINED, room.joinPlayer(replacement));
        assertEquals(original.slotIndex(), room.getPlayerSlot(replacement).slotIndex());
        assertFalse(room.isPlayerReady(replacement));
        assertEquals(original, snapshot.get(1));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.remove(0));
    }

    @Test
    public void emptyRoomCannotStartMatch() {
        assertFalse(new GameRoom("room-1").canStartMatch());
    }

    @Test
    public void selectingSameCharacterPreservesReady() {
        GameRoom room = readyRoom(2);
        PlayerId player = new PlayerId("player-0");

        assertTrue(room.selectCharacter(player, new String("Alex")));

        assertTrue(room.isPlayerReady(player));
        assertTrue(room.canStartMatch());
    }

    @Test
    public void rejectedCharacterChangePreservesReady() {
        GameRoom room = readyRoom(2);
        PlayerId player = new PlayerId("player-0");

        assertThrows(IllegalArgumentException.class, () -> room.selectCharacter(player, "invalid"));
        assertThrows(NullPointerException.class, () -> room.selectCharacter(player, null));

        assertEquals("Alex", room.getSelectedCharacter(player));
        assertTrue(room.isPlayerReady(player));
        assertTrue(room.canStartMatch());
    }

    @Test
    public void leavingUnreadyPlayerRecalculatesStartEligibility() {
        GameRoom room = readyRoom(2);
        PlayerId unready = new PlayerId("unready");
        room.joinPlayer(unready);
        assertFalse(room.canStartMatch());

        room.leavePlayer(unready);

        assertTrue(room.canStartMatch());
    }

    @Test
    public void playerWithoutCharacterCanSetUnready() {
        GameRoom room = new GameRoom("room-1");
        PlayerId player = new PlayerId("player-1");
        room.joinPlayer(player);

        assertTrue(room.setReady(player, false));
        assertTrue(room.setReady(player, false));

        assertFalse(room.isPlayerReady(player));
    }

    @Test
    public void newRoomStartsWaiting() {
        GameRoom room = new GameRoom("room-1");

        assertEquals(RoomPhase.WAITING, room.getPhase());
        assertFalse(room.canStartMatch());
    }

    @Test
    public void onePlayerKeepsRoomWaiting() {
        GameRoom room = new GameRoom("room-1");
        PlayerId player = new PlayerId("player-0");

        room.joinPlayer(player);

        assertEquals(RoomPhase.WAITING, room.getPhase());
        room.selectCharacter(player, "Alex");
        assertEquals(RoomPhase.WAITING, room.getPhase());
        room.setReady(player, true);
        assertEquals(RoomPhase.WAITING, room.getPhase());
        assertFalse(room.canStartMatch());
    }

    @Test
    public void twoReadyPlayersMakeRoomReady() {
        GameRoom room = readyRoom(2);

        assertEquals(RoomPhase.READY, room.getPhase());
        assertTrue(room.canStartMatch());
    }

    @Test
    public void threeReadyPlayersMakeRoomReady() {
        GameRoom room = readyRoom(3);

        assertEquals(RoomPhase.READY, room.getPhase());
        assertTrue(room.canStartMatch());
    }

    @Test
    public void fourReadyPlayersMakeRoomReady() {
        GameRoom room = readyRoom(4);

        assertEquals(RoomPhase.READY, room.getPhase());
        assertTrue(room.canStartMatch());
    }

    @Test
    public void unreadyPlayerReturnsRoomToWaiting() {
        GameRoom room = readyRoom(2);

        assertTrue(room.setReady(new PlayerId("player-0"), false));

        assertEquals(RoomPhase.WAITING, room.getPhase());
        assertFalse(room.canStartMatch());
    }

    @Test
    public void changingCharacterReturnsRoomToWaiting() {
        GameRoom room = readyRoom(2);
        PlayerId player = new PlayerId("player-0");

        assertTrue(room.selectCharacter(player, "Luca"));

        assertEquals("Luca", room.getSelectedCharacter(player));
        assertFalse(room.isPlayerReady(player));
        assertEquals(RoomPhase.WAITING, room.getPhase());
        assertFalse(room.canStartMatch());
    }

    @Test
    public void joiningNewPlayerReturnsRoomToWaiting() {
        GameRoom room = readyRoom(2);
        PlayerId newcomer = new PlayerId("new-player");

        assertEquals(JOINED, room.joinPlayer(newcomer));

        assertNull(room.getSelectedCharacter(newcomer));
        assertFalse(room.isPlayerReady(newcomer));
        assertEquals(RoomPhase.WAITING, room.getPhase());
        assertFalse(room.canStartMatch());
    }

    @Test
    public void leavingPlayerRecalculatesPhase() {
        GameRoom room = readyRoom(4);

        assertTrue(room.leavePlayer(new PlayerId("player-3")));
        assertEquals(3, room.getPlayerCount());
        assertEquals(RoomPhase.READY, room.getPhase());
        assertTrue(room.canStartMatch());

        assertTrue(room.leavePlayer(new PlayerId("player-2")));
        assertEquals(2, room.getPlayerCount());
        assertEquals(RoomPhase.READY, room.getPhase());
        assertTrue(room.canStartMatch());
    }

    @Test
    public void lessThanTwoPlayersMeansWaiting() {
        GameRoom room = readyRoom(2);

        assertTrue(room.leavePlayer(new PlayerId("player-1")));
        assertEquals(1, room.getPlayerCount());
        assertEquals(RoomPhase.WAITING, room.getPhase());
        assertFalse(room.canStartMatch());

        assertTrue(room.leavePlayer(new PlayerId("player-0")));
        assertEquals(0, room.getPlayerCount());
        assertEquals(RoomPhase.WAITING, room.getPhase());
        assertFalse(room.canStartMatch());
    }

    @Test
    public void cannotEnterPlayingAutomatically() {
        GameRoom room = readyRoom(4);

        // Đọc trạng thái hoặc ready lặp lại không tự bắt đầu countdown hay trận đấu.
        for (PlayerSlot slot : room.getPlayerSlots()) {
            assertTrue(room.setReady(slot.playerId(), true));
            assertTrue(room.canStartMatch());
            assertEquals(RoomPhase.READY, room.getPhase());
        }
    }

    @Test
    public void getPhaseReflectsCurrentRoomState() {
        GameRoom room = new GameRoom("room-1");
        PlayerId first = new PlayerId("player-0");
        PlayerId second = new PlayerId("player-1");
        assertEquals(RoomPhase.WAITING, room.getPhase());

        for (PlayerId player : List.of(first, second)) {
            assertEquals(JOINED, room.joinPlayer(player));
            assertEquals(RoomPhase.WAITING, room.getPhase());
            assertTrue(room.selectCharacter(player, "Alex"));
            assertEquals(RoomPhase.WAITING, room.getPhase());
        }
        assertTrue(room.setReady(first, true));
        assertEquals(RoomPhase.WAITING, room.getPhase());
        assertTrue(room.setReady(second, true));
        assertEquals(RoomPhase.READY, room.getPhase());
        assertTrue(room.canStartMatch());

        assertTrue(room.setReady(first, false));
        assertEquals(RoomPhase.WAITING, room.getPhase());
        assertFalse(room.canStartMatch());
        assertTrue(room.setReady(first, true));
        assertEquals(RoomPhase.READY, room.getPhase());
        assertTrue(room.canStartMatch());
    }

    @Test
    public void existingRoomTestsStillPass() {
        GameRoom room = readyRoom(4);
        PlayerId first = new PlayerId("player-0");
        PlayerId newcomer = new PlayerId("new-player");
        List<PlayerSlot> previousSlots = room.getPlayerSlots();

        assertEquals(ALREADY_IN_ROOM, room.joinPlayer(first));
        assertEquals(ROOM_FULL, room.joinPlayer(newcomer));
        assertEquals(RoomPhase.READY, room.getPhase());
        assertEquals(previousSlots, room.getPlayerSlots());

        assertTrue(room.leavePlayer(first));
        assertNull(room.getSelectedCharacter(first));
        assertFalse(room.isPlayerReady(first));
        assertEquals(RoomPhase.READY, room.getPhase());
        assertEquals(JOINED, room.joinPlayer(newcomer));
        assertEquals(0, room.getPlayerSlot(newcomer).slotIndex());
        assertNull(room.getSelectedCharacter(newcomer));
        assertFalse(room.isPlayerReady(newcomer));
        assertEquals(RoomPhase.WAITING, room.getPhase());
        assertEquals(4, previousSlots.size());
    }

    @Test
    public void leavingUnreadyPlayerRestoresReadyPhase() {
        GameRoom room = readyRoom(2);
        PlayerId unready = new PlayerId("unready");
        assertEquals(JOINED, room.joinPlayer(unready));
        assertEquals(RoomPhase.WAITING, room.getPhase());

        assertTrue(room.leavePlayer(unready));

        assertEquals(RoomPhase.READY, room.getPhase());
        assertTrue(room.canStartMatch());
    }

    @Test
    public void selectingSameCharacterKeepsReadyPhase() {
        GameRoom room = readyRoom(2);
        PlayerId player = new PlayerId("player-0");

        assertTrue(room.selectCharacter(player, "Alex"));

        assertTrue(room.isPlayerReady(player));
        assertEquals(RoomPhase.READY, room.getPhase());
        assertTrue(room.canStartMatch());
    }

    @Test
    public void rejectedActionsDoNotChangeReadyPhase() {
        GameRoom room = readyRoom(2);
        PlayerId player = new PlayerId("player-0");
        PlayerId unknown = new PlayerId("unknown");

        assertThrows(IllegalArgumentException.class, () -> room.selectCharacter(player, "invalid"));
        assertThrows(NullPointerException.class, () -> room.selectCharacter(player, null));
        assertFalse(room.selectCharacter(unknown, "Alex"));
        assertFalse(room.setReady(unknown, false));
        assertFalse(room.leavePlayer(unknown));

        assertTrue(room.isPlayerReady(player));
        assertEquals(RoomPhase.READY, room.getPhase());
        assertTrue(room.canStartMatch());
    }

    private static GameRoom readyRoom(int playerCount) {
        GameRoom room = new GameRoom("room-1");
        for (PlayerId player : distinctPlayers(playerCount)) {
            assertEquals(JOINED, room.joinPlayer(player));
            assertTrue(room.selectCharacter(player, "Alex"));
            assertTrue(room.setReady(player, true));
        }
        return room;
    }

    private static GameRoom fullRoom() {
        GameRoom room = new GameRoom("room-1");
        for (PlayerId player : distinctPlayers(4)) {
            assertEquals(JOINED, room.joinPlayer(player));
        }
        return room;
    }

    private static List<PlayerId> distinctPlayers(int count) {
        List<PlayerId> players = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            players.add(new PlayerId("player-" + i));
        }
        return players;
    }

    /** Thread tạm chỉ phục vụ test; không dùng executor, socket hay sleep. */
    private static List<GameRoom.JoinResult> joinConcurrently(GameRoom room, List<PlayerId> players)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(players.size());
        CountDownLatch start = new CountDownLatch(1);
        List<FutureTask<GameRoom.JoinResult>> tasks = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        try {
            for (PlayerId player : players) {
                FutureTask<GameRoom.JoinResult> task = new FutureTask<>(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Hết thời gian chờ bắt đầu join");
                    }
                    return room.joinPlayer(player);
                });
                Thread thread = new Thread(task, "room-join-test-" + threads.size());
                thread.setDaemon(true);
                threads.add(thread);
                tasks.add(task);
                thread.start();
            }
            assertTrue("Các thread phải sẵn sàng trong thời gian giới hạn",
                    ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<GameRoom.JoinResult> results = new ArrayList<>();
            for (FutureTask<GameRoom.JoinResult> task : tasks) {
                results.add(task.get(5, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            for (Thread thread : threads) {
                thread.interrupt();
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            for (Thread thread : threads) {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) {
                    TimeUnit.NANOSECONDS.timedJoin(thread, remaining);
                }
                assertFalse("Thread của test phải dừng sau cleanup", thread.isAlive());
            }
        }
    }
}
