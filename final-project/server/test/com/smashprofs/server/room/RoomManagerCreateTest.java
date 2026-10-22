package com.smashprofs.server.room;

import org.junit.Test;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Kiểm tra API create-only mới cùng registry/concurrency đã có; không gameplay hoặc socket giả. */
public class RoomManagerCreateTest {
    @Test public void createRegistersCompleteEmptyRoom() {
        var manager = new RoomManager(); var room = manager.createRoom("r");
        assertSame(room, manager.getRoom("r")); assertEquals(1, manager.getRoomCount());
        assertEquals(RoomPhase.WAITING, room.getPhase()); assertEquals(0, room.getPlayerCount()); assertNull(room.getMatchRuntime());
        manager.shutdown(); assertTrue(room.isClosed());
    }
    @Test public void duplicateCreationDoesNotReplaceExistingRoom() {
        var manager = new RoomManager(); var original = manager.getOrCreateRoom("r");
        assertThrows(IllegalArgumentException.class, () -> manager.createRoom("r"));
        assertSame(original, manager.getRoom("r")); assertSame(original, manager.getOrCreateRoom("r"));
        assertEquals(1, manager.getRoomCount()); manager.shutdown();
    }
    @Test public void invalidCreationDoesNotRegisterRoom() {
        var manager = new RoomManager(); assertThrows(NullPointerException.class, () -> manager.createRoom(null));
        for (String id : new String[]{"", " ", "\t"}) { assertThrows(IllegalArgumentException.class, () -> manager.createRoom(id)); }
        assertEquals(0, manager.getRoomCount()); manager.shutdown();
    }
    @Test public void closedRegistryRejectsCreateWithoutLeak() {
        var manager = new RoomManager(); manager.shutdown();
        assertThrows(IllegalStateException.class, () -> manager.createRoom("r")); assertEquals(0, manager.getRoomCount());
    }
    @Test public void removedIdCanBeCreatedAgain() {
        var manager = new RoomManager(); var first = manager.createRoom("r"); manager.removeRoom("r");
        assertTrue(first.isClosed()); assertNotSame(first, manager.createRoom("r")); manager.shutdown();
    }
    @Test(timeout=15000) public void concurrentCreateAndGetOrCreateNeverOverwrite() throws Exception {
        var manager = new RoomManager(); var pool = Executors.newFixedThreadPool(8); var start = new CountDownLatch(1);
        var results = new ArrayList<Future<GameRoom>>();
        try {
            for (int i = 0; i < 8; i++) {
                final boolean create = i % 2 == 0;
                results.add(pool.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try { return create ? manager.createRoom("shared") : manager.getOrCreateRoom("shared"); }
                    catch (IllegalArgumentException duplicate) { return null; }
                }));
            }
            start.countDown(); for (var future : results) {
                var room = future.get(5, TimeUnit.SECONDS); if (room != null) { assertSame(manager.getRoom("shared"), room); }
            }
            assertEquals(1, manager.getRoomCount());
        } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); manager.shutdown(); }
    }
}
