package com.smashprofs.server.room;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RoomManagerTest {

    @Test
    public void managerStartsWithNoRooms() {
        RoomManager manager = new RoomManager();

        assertEquals(0, manager.getRoomCount());
        assertNull(manager.getRoom("room-1"));
        assertFalse(manager.containsRoom("room-1"));
    }

    @Test
    public void newRoomCanBeCreated() {
        RoomManager manager = new RoomManager();

        GameRoom room = manager.getOrCreateRoom("room-1");

        assertEquals("room-1", room.getRoomId());
        assertSame(room, manager.getRoom("room-1"));
        assertEquals(1, manager.getRoomCount());
    }

    @Test
    public void existingRoomIsReturnedInsteadOfDuplicated() {
        RoomManager manager = new RoomManager();
        GameRoom original = manager.getOrCreateRoom("room-1");

        GameRoom repeated = manager.getOrCreateRoom("room-1");

        assertSame(original, repeated);
        assertEquals(1, manager.getRoomCount());
    }

    @Test
    public void roomCanBeRetrievedById() {
        RoomManager manager = new RoomManager();
        GameRoom first = manager.getOrCreateRoom("room-1");
        GameRoom second = manager.getOrCreateRoom("room-2");

        assertSame(first, manager.getRoom("room-1"));
        assertSame(second, manager.getRoom("room-2"));
        assertNull(manager.getRoom("unknown"));
        assertEquals(2, manager.getRoomCount());
    }

    @Test
    public void containsRoomReturnsCorrectResult() {
        RoomManager manager = new RoomManager();
        assertFalse(manager.containsRoom("room-1"));

        manager.getOrCreateRoom("room-1");
        assertTrue(manager.containsRoom("room-1"));

        manager.removeRoom("room-1");
        assertFalse(manager.containsRoom("room-1"));
    }

    @Test
    public void roomCanBeRemoved() {
        RoomManager manager = new RoomManager();
        GameRoom first = manager.getOrCreateRoom("room-1");
        GameRoom second = manager.getOrCreateRoom("room-2");

        assertSame(first, manager.removeRoom("room-1"));

        assertNull(manager.getRoom("room-1"));
        assertNull(manager.removeRoom("room-1"));
        assertSame(second, manager.getRoom("room-2"));
        assertEquals(1, manager.getRoomCount());
    }

    @Test
    public void roomCountIsCorrect() {
        RoomManager manager = new RoomManager();
        manager.getOrCreateRoom("room-1");
        manager.getOrCreateRoom("room-2");
        manager.getOrCreateRoom("room-3");
        manager.getOrCreateRoom("room-1");
        assertEquals(3, manager.getRoomCount());

        manager.removeRoom("room-2");
        assertEquals(2, manager.getRoomCount());
    }

    @Test
    public void invalidRoomIdIsRejected() {
        RoomManager manager = new RoomManager();
        GameRoom existing = manager.getOrCreateRoom("room-1");

        assertThrows(NullPointerException.class, () -> manager.getOrCreateRoom(null));
        assertThrows(NullPointerException.class, () -> manager.getRoom(null));
        assertThrows(NullPointerException.class, () -> manager.containsRoom(null));
        assertThrows(NullPointerException.class, () -> manager.removeRoom(null));
        for (String roomId : new String[]{"", " ", "\t\n"}) {
            assertThrows(IllegalArgumentException.class, () -> manager.getOrCreateRoom(roomId));
            assertThrows(IllegalArgumentException.class, () -> manager.getRoom(roomId));
            assertThrows(IllegalArgumentException.class, () -> manager.containsRoom(roomId));
            assertThrows(IllegalArgumentException.class, () -> manager.removeRoom(roomId));
        }

        assertEquals(1, manager.getRoomCount());
        assertSame(existing, manager.getRoom("room-1"));
    }

    @Test(timeout = 15000)
    public void concurrentCreationOfSameRoomIdCreatesOnlyOneRoom() throws Exception {
        RoomManager manager = new RoomManager();
        int callers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<GameRoom>> results = new ArrayList<>();

        try {
            for (int i = 0; i < callers; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Hết thời gian chờ bắt đầu test");
                    }
                    return manager.getOrCreateRoom("shared-room");
                }));
            }
            assertTrue("Các thread phải sẵn sàng trong thời gian giới hạn",
                    ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            GameRoom registered = results.get(0).get(5, TimeUnit.SECONDS);
            for (Future<GameRoom> result : results) {
                assertSame(registered, result.get(5, TimeUnit.SECONDS));
            }
            assertEquals("shared-room", registered.getRoomId());
            assertSame(registered, manager.getRoom("shared-room"));
            assertEquals(1, manager.getRoomCount());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue("Executor của test phải dừng sau cleanup",
                    executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void removedRoomCanBeCreatedAgain() {
        RoomManager manager = new RoomManager();
        GameRoom original = manager.getOrCreateRoom("room-1");
        manager.removeRoom("room-1");

        GameRoom recreated = manager.getOrCreateRoom("room-1");

        assertNotSame(original, recreated);
        assertEquals("room-1", recreated.getRoomId());
        assertSame(recreated, manager.getRoom("room-1"));
        assertEquals(1, manager.getRoomCount());
    }

    @Test
    public void validRoomIdsAreUsedExactlyAsProvided() {
        RoomManager manager = new RoomManager();
        GameRoom plain = manager.getOrCreateRoom("room-1");
        GameRoom spaced = manager.getOrCreateRoom(" room-1 ");

        assertNotSame(plain, spaced);
        assertSame(spaced, manager.getOrCreateRoom(" room-1 "));
        assertSame(spaced, manager.getRoom(" room-1 "));
        assertTrue(manager.containsRoom(" room-1 "));
        assertSame(spaced, manager.removeRoom(" room-1 "));
        assertSame(plain, manager.getRoom("room-1"));
        assertEquals(1, manager.getRoomCount());
    }
}
