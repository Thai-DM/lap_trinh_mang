package com.smashprofs.server.network;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import com.smashprofs.common.protocol.MessageType;
import com.smashprofs.server.game.FixedTickScheduler;
import com.smashprofs.server.game.MatchRuntime;
import com.smashprofs.server.room.GameRoom;
import com.smashprofs.server.room.PlayerSlot;
import com.smashprofs.server.room.RoomManager;
import com.smashprofs.server.room.RoomPhase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.smashprofs.server.network.JoinRoomHandler.Status.*;
import static org.junit.Assert.*;

/** Channel giả, manual clock; scheduler không start nên không tạo simulation thread. */
public class JoinRoomHandlerTest {
    private static final PlayerId ALICE = new PlayerId("alice");
    private static final PlayerId BOB = new PlayerId("bob");
    private static final PlayerId CHARLIE = new PlayerId("charlie");
    private ConnectionManager connections;
    private RoomManager rooms;
    private FixedTickScheduler scheduler;
    private AtomicLong clock;
    private FakeSessionChannel channel;
    private ClientSession session;
    private JoinRoomHandler handler;

    @Before
    public void setUp() {
        connections = new ConnectionManager();
        scheduler = new FixedTickScheduler();
        clock = new AtomicLong(TimeUnit.SECONDS.toNanos(10));
        rooms = new RoomManager(scheduler, clock::get);
        channel = new FakeSessionChannel();
        session = registeredSession("session-a", ALICE, channel);
        handler = new JoinRoomHandler(connections, rooms);
    }

    @After
    public void tearDown() {
        try {
            rooms.shutdown();
        } finally {
            scheduler.stop();
        }
    }

    @Test
    public void validSessionCanJoinNewRoom() {
        JoinRoomHandler.JoinResult result = handler.handle(session, "ROOM_001");
        assertEquals(JOINED, result.status());
        assertTrue(result.isSuccess());
        assertEquals("ROOM_001", result.roomId());
        assertEquals(new PlayerSlot(0, ALICE), result.slot());
        assertEquals(RoomPhase.WAITING, result.phase());
        assertTrue(rooms.getRoom("ROOM_001").containsPlayer(ALICE));
    }

    @Test
    public void validSessionCanJoinExistingRoom() {
        GameRoom room = rooms.getOrCreateRoom("ROOM_001");
        room.joinPlayer(BOB);
        JoinRoomHandler.JoinResult result = handler.handle(session, "ROOM_001");
        assertEquals(JOINED, result.status());
        assertSame(room, rooms.getRoom("ROOM_001"));
        assertEquals(2, room.getPlayerCount());
        assertEquals(new PlayerSlot(1, ALICE), result.slot());
    }

    @Test
    public void joiningUnknownRoomCreatesRoom() {
        assertFalse(rooms.containsRoom("ROOM_001"));
        assertEquals(JOINED, handler.handle(session, "ROOM_001").status());
        assertTrue(rooms.containsRoom("ROOM_001"));
        assertEquals(1, rooms.getRoomCount());
    }

    @Test
    public void joinReturnsCorrectSlot() {
        GameRoom room = rooms.getOrCreateRoom("ROOM_001");
        room.joinPlayer(BOB);
        room.joinPlayer(CHARLIE);
        room.leavePlayer(BOB);
        JoinRoomHandler.JoinResult result = handler.handle(session, "ROOM_001");
        assertEquals(JOINED, result.status());
        assertEquals(0, result.slot().slotIndex());
        assertSame(room.getPlayerSlot(ALICE), result.slot());
        assertEquals(1, room.getPlayerSlot(CHARLIE).slotIndex());
    }

    @Test
    public void nullSessionIsRejected() {
        assertEquals(INVALID_SESSION, handler.handle(null, "ROOM_001").status());
        assertEquals(0, rooms.getRoomCount());
    }

    @Test
    public void sessionWithoutPlayerIsRejected() {
        ClientSession unbound = new ClientSession("unbound", new FakeSessionChannel());
        connections.register(unbound);
        assertEquals(INVALID_SESSION, handler.handle(unbound, "ROOM_001").status());
        assertNull(unbound.getRoomId());
        assertEquals(0, rooms.getRoomCount());
    }

    @Test
    public void invalidRoomIdIsRejected() {
        String[] invalid = {null, "", " ", "\t\n", " ROOM_001", "ROOM_001 ", "room/name", "room\\name",
                "room..name", "room:name", "room@name", "phòng", "room\u0000id", "a".repeat(65)};
        for (String id : invalid) {
            JoinRoomHandler.JoinResult result = handler.handle(session, id);
            assertEquals(INVALID_ROOM_ID, result.status());
            assertFalse(result.isSuccess());
            assertNull(result.slot());
        }
        assertEquals(0, rooms.getRoomCount());
        assertNull(session.getRoomId());
    }

    @Test
    public void validRoomIdLengthBoundariesAndExactCaseArePreserved() {
        String id = "R" + "a".repeat(61) + "_-";
        assertEquals(64, id.length());
        assertEquals(JOINED, handler.handle(session, id).status());
        assertEquals(id, session.getRoomId());
        assertTrue(rooms.containsRoom(id));
        ClientSession other = registeredSession("other", BOB, new FakeSessionChannel());
        assertEquals(JOINED, handler.handle(other, "r").status());
        assertFalse(rooms.containsRoom("R"));
    }

    @Test
    public void fullRoomRejectsPlayer() {
        GameRoom room = rooms.getOrCreateRoom("ROOM_001");
        for (int index = 0; index < 4; index++) {
            room.joinPlayer(new PlayerId("filled-" + index));
        }
        List<PlayerSlot> before = room.getPlayerSlots();
        JoinRoomHandler.JoinResult result = handler.handle(session, "ROOM_001");
        assertEquals(ROOM_FULL, result.status());
        assertEquals(before, room.getPlayerSlots());
        assertNull(result.slot());
        assertNull(session.getRoomId());
    }

    @Test
    public void countdownRoomRejectsJoin() {
        GameRoom room = readyRoom("ROOM_001", BOB, CHARLIE);
        room.updateLifecycle();
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase());
        assertEquals(ROOM_NOT_JOINABLE, handler.handle(session, "ROOM_001").status());
        assertEquals(2, room.getPlayerCount());
        assertFalse(room.containsPlayer(ALICE));
        assertNull(session.getRoomId());
    }

    @Test
    public void playingRoomRejectsJoin() {
        GameRoom room = playingRoom("ROOM_001", BOB, CHARLIE);
        MatchRuntime runtime = room.getMatchRuntime();
        assertEquals(ROOM_NOT_JOINABLE, handler.handle(session, "ROOM_001").status());
        assertSame(runtime, room.getMatchRuntime());
        assertEquals(2, room.getPlayerCount());
        assertNull(session.getRoomId());
        assertEquals(0, runtime.getMatchState().getServerTick());
    }

    @Test
    public void finishedPhaseIsNotJoinable() {
        // FINISHED chưa có GAME_END API: kiểm tra chính sách phase, không thêm transition giả.
        for (RoomPhase phase : RoomPhase.values()) {
            assertEquals(phase == RoomPhase.WAITING || phase == RoomPhase.READY,
                    JoinRoomHandler.isJoinablePhase(phase));
        }
        assertFalse(JoinRoomHandler.isJoinablePhase(null));
    }

    @Test
    public void duplicateJoinIsIdempotent() {
        JoinRoomHandler.JoinResult first = handler.handle(session, "ROOM_001");
        GameRoom room = rooms.getRoom("ROOM_001");
        room.selectCharacter(ALICE, "Leo");
        room.setReady(ALICE, true);
        JoinRoomHandler.JoinResult second = handler.handle(session, "ROOM_001");
        assertEquals(JOINED, first.status());
        assertEquals(ALREADY_IN_ROOM, second.status());
        assertSame(first.slot(), second.slot());
        assertEquals(1, room.getPlayerCount());
        assertEquals("Leo", room.getSelectedCharacter(ALICE));
        assertTrue(room.isPlayerReady(ALICE));
    }

    @Test
    public void duplicateJoinInReadyRoomPreservesPhaseAndReady() {
        GameRoom room = readyRoom("ROOM_001", ALICE, BOB);
        JoinRoomHandler.JoinResult result = handler.handle(session, "ROOM_001");
        assertEquals(ALREADY_IN_ROOM, result.status());
        assertEquals(RoomPhase.READY, result.phase());
        assertEquals(RoomPhase.READY, room.getPhase());
        assertTrue(room.isPlayerReady(ALICE));
        assertTrue(room.isPlayerReady(BOB));
        assertEquals("ROOM_001", session.getRoomId());
        assertEquals(2, room.getPlayerCount());
    }

    @Test
    public void joiningReadyRoomKeepsExistingPhaseRules() {
        GameRoom room = readyRoom("ROOM_001", BOB, CHARLIE);
        JoinRoomHandler.JoinResult result = handler.handle(session, "ROOM_001");
        assertEquals(JOINED, result.status());
        assertEquals(RoomPhase.WAITING, result.phase());
        assertTrue(room.isPlayerReady(BOB));
        assertTrue(room.isPlayerReady(CHARLIE));
        assertFalse(room.isPlayerReady(ALICE));
        assertEquals(3, room.getPlayerCount());
    }

    @Test
    public void sessionRoomIdUpdatedAfterSuccess() {
        assertNull(session.getRoomId());
        JoinRoomHandler verifying = new JoinRoomHandler(connections, rooms, (source, id) -> {
            assertTrue(Thread.holdsLock(source));
            GameRoom room = rooms.getRoom(id);
            assertTrue(Thread.holdsLock(room));
            assertTrue(room.containsPlayer(ALICE));
            assertNotNull(room.getPlayerSlot(ALICE));
            assertNull(source.getRoomId());
            source.joinRoom(id);
        });
        assertEquals(JOINED, verifying.handle(session, "ROOM_001").status());
        assertEquals("ROOM_001", session.getRoomId());
    }

    @Test
    public void failedJoinDoesNotBindSession() {
        GameRoom room = readyRoom("ROOM_001", BOB, CHARLIE);
        room.updateLifecycle();
        assertEquals(ROOM_NOT_JOINABLE, handler.handle(session, "ROOM_001").status());
        assertNull(session.getRoomId());
        assertNull(room.getPlayerSlot(ALICE));
    }

    @Test
    public void sessionCannotJoinSecondRoom() {
        assertEquals(JOINED, handler.handle(session, "ROOM_A").status());
        PlayerSlot slot = rooms.getRoom("ROOM_A").getPlayerSlot(ALICE);
        assertEquals(ALREADY_IN_ANOTHER_ROOM, handler.handle(session, "ROOM_B").status());
        assertEquals("ROOM_A", session.getRoomId());
        assertSame(slot, rooms.getRoom("ROOM_A").getPlayerSlot(ALICE));
        assertFalse(rooms.containsRoom("ROOM_B"));
    }

    @Test(timeout = 15000)
    public void concurrentJoinsRespectCapacity() throws Exception {
        List<ClientSession> clients = new ArrayList<>();
        List<Callable<JoinRoomHandler.JoinResult>> actions = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            ClientSession client = registeredSession("client-" + index, new PlayerId("player-" + index),
                    new FakeSessionChannel());
            clients.add(client);
            actions.add(() -> handler.handle(client, "ROOM_001"));
        }
        List<JoinRoomHandler.JoinResult> results = runConcurrently(actions);
        assertEquals(4, results.stream().filter(result -> result.status() == JOINED).count());
        assertEquals(8, results.stream().filter(result -> result.status() == ROOM_FULL).count());
        GameRoom room = rooms.getRoom("ROOM_001");
        assertEquals(4, room.getPlayerCount());
        assertEquals(1, rooms.getRoomCount());
        assertEquals(4, new HashSet<>(room.getPlayerSlots().stream().map(PlayerSlot::slotIndex).toList()).size());
        for (int index = 0; index < clients.size(); index++) {
            assertEquals(results.get(index).isSuccess() ? "ROOM_001" : null, clients.get(index).getRoomId());
        }
    }

    @Test(timeout = 15000)
    public void concurrentJoinsFromSameSessionAreSafe() throws Exception {
        List<Callable<JoinRoomHandler.JoinResult>> actions = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            actions.add(() -> handler.handle(session, "ROOM_001"));
        }
        List<JoinRoomHandler.JoinResult> results = runConcurrently(actions);
        assertEquals(1, results.stream().filter(result -> result.status() == JOINED).count());
        assertEquals(15, results.stream().filter(result -> result.status() == ALREADY_IN_ROOM).count());
        PlayerSlot slot = rooms.getRoom("ROOM_001").getPlayerSlot(ALICE);
        for (JoinRoomHandler.JoinResult result : results) {
            assertSame(slot, result.slot());
        }
        assertEquals(1, rooms.getRoom("ROOM_001").getPlayerCount());
        assertEquals("ROOM_001", session.getRoomId());
    }

    @Test(timeout = 15000)
    public void concurrentRequestsForDifferentRoomsBindOnlyOneRoom() throws Exception {
        List<JoinRoomHandler.JoinResult> results = runConcurrently(List.of(
                () -> handler.handle(session, "ROOM_A"), () -> handler.handle(session, "ROOM_B")));
        assertEquals(1, results.stream().filter(result -> result.status() == JOINED).count());
        assertEquals(1, results.stream().filter(result -> result.status() == ALREADY_IN_ANOTHER_ROOM).count());
        assertEquals(1, rooms.getRoomCount());
        assertEquals(1, rooms.getRoom(session.getRoomId()).getPlayerCount());
        assertTrue(rooms.getRoom(session.getRoomId()).containsPlayer(ALICE));
        assertFalse(rooms.containsRoom(session.getRoomId().equals("ROOM_A") ? "ROOM_B" : "ROOM_A"));
    }

    @Test
    public void joiningRoomADoesNotModifyRoomB() {
        GameRoom second = rooms.getOrCreateRoom("ROOM_B");
        second.joinPlayer(BOB);
        List<PlayerSlot> before = second.getPlayerSlots();
        assertEquals(JOINED, handler.handle(session, "ROOM_A").status());
        assertEquals(before, second.getPlayerSlots());
        assertEquals(0, rooms.getRoom("ROOM_A").getPlayerSlot(ALICE).slotIndex());
        assertEquals(0, second.getPlayerSlot(BOB).slotIndex());
        assertFalse(second.containsPlayer(ALICE));
    }

    @Test
    public void playerInputRoutingStillWorks() {
        PlayerInputHandler inputs = new PlayerInputHandler(connections, rooms);
        MessageDispatcher dispatcher = new MessageDispatcher((source, type, payload) -> fail("Không gọi fallback"), inputs, handler);
        assertTrue(dispatcher.dispatch(session, MessageType.JOIN_ROOM, "ROOM_001"));
        ClientSession bob = registeredSession("session-b", BOB, new FakeSessionChannel());
        assertTrue(dispatcher.dispatch(bob, MessageType.JOIN_ROOM, "ROOM_001"));
        GameRoom room = rooms.getRoom("ROOM_001");
        PlayerInput input = new PlayerInput(ALICE, 0, 0, 1.0f, false, false, false, false, false);
        assertFalse(dispatcher.dispatch(session, MessageType.PLAYER_INPUT, input));
        for (PlayerId player : List.of(ALICE, BOB)) {
            room.selectCharacter(player, "Alex");
            room.setReady(player, true);
        }
        rooms.updateLifecycles();
        clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
        rooms.updateLifecycles();
        MatchRuntime runtime = room.getMatchRuntime();
        assertTrue(dispatcher.dispatch(session, MessageType.PLAYER_INPUT, input));
        assertEquals(0, runtime.getMatchState().getServerTick());
        assertEquals(List.of(input), runtime.tick());
    }

    @Test
    public void existingTestsStillPass() {
        List<MessageType> forwarded = new ArrayList<>();
        Object payload = new Object();
        MessageDispatcher legacy = new MessageDispatcher((source, type, original) -> {
            assertSame(payload, original);
            forwarded.add(type);
        });
        assertTrue(legacy.dispatch(session, MessageType.JOIN_ROOM, payload));
        assertTrue(legacy.dispatch(session, MessageType.PLAYER_INPUT, payload));
        MessageDispatcher inputOnly = new MessageDispatcher((source, type, original) -> forwarded.add(type),
                new PlayerInputHandler(connections, rooms));
        assertTrue(inputOnly.dispatch(session, MessageType.JOIN_ROOM, payload));
        assertEquals(List.of(MessageType.JOIN_ROOM, MessageType.PLAYER_INPUT, MessageType.JOIN_ROOM), forwarded);
        assertNull(session.getRoomId());
        assertEquals(0, rooms.getRoomCount());
    }

    @Test
    public void dispatcherUsesJoinHandlerAndKeepsReadyBoundary() {
        List<MessageType> forwarded = new ArrayList<>();
        MessageDispatcher dispatcher = new MessageDispatcher((source, type, payload) -> forwarded.add(type), null, handler);
        assertTrue(dispatcher.dispatch(session, MessageType.JOIN_ROOM, "ROOM_001"));
        assertTrue(dispatcher.dispatch(session, MessageType.JOIN_ROOM, "ROOM_001"));
        assertTrue(forwarded.isEmpty());
        assertTrue(dispatcher.dispatch(session, MessageType.PLAYER_READY, null));
        assertEquals(List.of(MessageType.PLAYER_READY), forwarded);
        assertEquals(1, rooms.getRoom("ROOM_001").getPlayerCount());
    }

    @Test
    public void dispatcherRejectsWrongJoinPayloadWithoutTrustingClientPlayerId() {
        MessageDispatcher dispatcher = new MessageDispatcher((source, type, payload) -> fail("Không gọi fallback"), null, handler);
        assertFalse(dispatcher.dispatch(session, MessageType.JOIN_ROOM, null));
        assertFalse(dispatcher.dispatch(session, MessageType.JOIN_ROOM, new Object()));
        assertFalse(dispatcher.dispatch(session, MessageType.JOIN_ROOM,
                Map.of("roomId", "ROOM_001", "playerId", "bob")));
        assertEquals(0, rooms.getRoomCount());
        assertNull(session.getRoomId());
    }

    @Test
    public void disconnectedOrClosedSessionIsRejected() {
        session.markDisconnected();
        assertEquals(INVALID_SESSION, handler.handle(session, "ROOM_001").status());
        ClientSession other = registeredSession("other", BOB, new FakeSessionChannel());
        other.close();
        assertEquals(INVALID_SESSION, handler.handle(other, "ROOM_001").status());
        assertEquals(0, rooms.getRoomCount());
    }

    @Test
    public void closedChannelWithoutDisconnectedFlagIsRejected() {
        channel.close();
        assertTrue(session.isConnected());
        assertEquals(INVALID_SESSION, handler.handle(session, "ROOM_001").status());
        assertEquals(0, rooms.getRoomCount());
    }

    @Test
    public void unregisteredOrImpostorSessionIsRejected() {
        ClientSession impostor = new ClientSession(session.getSessionId(), new FakeSessionChannel());
        impostor.bindPlayer(ALICE);
        assertEquals(INVALID_SESSION, handler.handle(impostor, "ROOM_001").status());
        connections.remove(session.getSessionId());
        assertEquals(INVALID_SESSION, handler.handle(session, "ROOM_001").status());
        assertEquals(0, rooms.getRoomCount());
    }

    @Test
    public void invalidBoundPlayerAndBlankSessionIdAreRejected() {
        for (PlayerId id : List.of(new PlayerId(null), new PlayerId(""), new PlayerId(" \t"))) {
            session.bindPlayer(id);
            assertEquals(INVALID_SESSION, handler.handle(session, "ROOM_001").status());
        }
        ClientSession invalid = new ClientSession(" \t", new FakeSessionChannel());
        invalid.bindPlayer(ALICE);
        assertEquals(INVALID_SESSION, handler.handle(invalid, "ROOM_001").status());
        assertEquals(0, rooms.getRoomCount());
    }

    @Test
    public void bindFailureRollsBackNewMembershipAndMetadata() {
        JoinRoomHandler failing = new JoinRoomHandler(connections, rooms, (source, id) -> {
            source.joinRoom(id);
            throw new IllegalStateException("test-bind-failure");
        });
        JoinRoomHandler.JoinResult result = failing.handle(session, "ROOM_001");
        assertEquals(JOIN_FAILED, result.status());
        assertFalse(result.isSuccess());
        assertNull(result.slot());
        assertNull(session.getRoomId());
        assertEquals(0, rooms.getRoom("ROOM_001").getPlayerCount());
        assertEquals(JOINED, handler.handle(session, "ROOM_001").status());
        assertEquals(0, rooms.getRoom("ROOM_001").getPlayerSlot(ALICE).slotIndex());
    }

    @Test
    public void bindFailureOnDuplicateDoesNotRemoveExistingPlayerOrReady() {
        handler.handle(session, "ROOM_001");
        GameRoom room = rooms.getRoom("ROOM_001");
        room.selectCharacter(ALICE, "Leo");
        room.setReady(ALICE, true);
        PlayerSlot slot = room.getPlayerSlot(ALICE);
        JoinRoomHandler failing = new JoinRoomHandler(connections, rooms, (source, id) -> {
            source.leaveRoom();
            throw new IllegalStateException("test-duplicate-bind-failure");
        });
        assertEquals(JOIN_FAILED, failing.handle(session, "ROOM_001").status());
        assertEquals("ROOM_001", session.getRoomId());
        assertSame(slot, room.getPlayerSlot(ALICE));
        assertEquals("Leo", room.getSelectedCharacter(ALICE));
        assertTrue(room.isPlayerReady(ALICE));
    }

    @Test
    public void binderThatDoesNotBindIsRejectedAndRolledBack() {
        JoinRoomHandler failing = new JoinRoomHandler(connections, rooms, (source, id) -> { });
        assertEquals(JOIN_FAILED, failing.handle(session, "ROOM_001").status());
        assertNull(session.getRoomId());
        assertEquals(0, rooms.getRoom("ROOM_001").getPlayerCount());
    }

    @Test
    public void bindRollbackRestoresReadyRoomPhase() {
        GameRoom room = readyRoom("ROOM_001", BOB, CHARLIE);
        JoinRoomHandler failing = new JoinRoomHandler(connections, rooms, (source, id) -> {
            throw new IllegalStateException("test-ready-room-bind-failure");
        });
        assertEquals(JOIN_FAILED, failing.handle(session, "ROOM_001").status());
        assertEquals(RoomPhase.READY, room.getPhase());
        assertEquals(2, room.getPlayerCount());
        assertTrue(room.isPlayerReady(BOB));
        assertTrue(room.isPlayerReady(CHARLIE));
        assertNull(session.getRoomId());
    }

    @Test
    public void alreadyJoinedPlayerIsRejectedAfterCountdownStarts() {
        GameRoom room = readyRoom("ROOM_001", ALICE, BOB);
        assertEquals(ALREADY_IN_ROOM, handler.handle(session, "ROOM_001").status());
        room.updateLifecycle();
        PlayerSlot slot = room.getPlayerSlot(ALICE);
        assertEquals(ROOM_NOT_JOINABLE, handler.handle(session, "ROOM_001").status());
        assertSame(slot, room.getPlayerSlot(ALICE));
        assertTrue(room.isPlayerReady(ALICE));
        assertEquals("ROOM_001", session.getRoomId());
    }

    @Test
    public void shutdownRegistryReturnsJoinFailedWithoutBinding() {
        rooms.shutdown();
        assertEquals(JOIN_FAILED, handler.handle(session, "ROOM_001").status());
        assertNull(session.getRoomId());
        assertEquals(0, rooms.getRoomCount());
    }

    @Test
    public void nullDependenciesAreRejected() {
        assertThrows(NullPointerException.class, () -> new JoinRoomHandler(null, rooms));
        assertThrows(NullPointerException.class, () -> new JoinRoomHandler(connections, null));
        assertThrows(NullPointerException.class, () -> new JoinRoomHandler(connections, rooms, null));
    }

    private ClientSession registeredSession(String id, PlayerId player, FakeSessionChannel fakeChannel) {
        ClientSession created = new ClientSession(id, fakeChannel);
        created.bindPlayer(player);
        connections.register(created);
        return created;
    }

    private GameRoom readyRoom(String id, PlayerId first, PlayerId second) {
        GameRoom created = rooms.getOrCreateRoom(id);
        for (PlayerId player : List.of(first, second)) {
            created.joinPlayer(player);
            created.selectCharacter(player, "Alex");
            created.setReady(player, true);
        }
        return created;
    }

    private GameRoom playingRoom(String id, PlayerId first, PlayerId second) {
        GameRoom created = readyRoom(id, first, second);
        created.updateLifecycle();
        clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
        created.updateLifecycle();
        assertEquals(RoomPhase.PLAYING, created.getPhase());
        return created;
    }

    /** Như các test concurrency trước: chờ có thời hạn, cleanup thread; không socket/sleep. */
    private static <T> List<T> runConcurrently(List<Callable<T>> actions) throws Exception {
        CountDownLatch ready = new CountDownLatch(actions.size());
        CountDownLatch start = new CountDownLatch(1);
        List<FutureTask<T>> tasks = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        try {
            for (Callable<T> action : actions) {
                FutureTask<T> task = new FutureTask<>(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Hết thời gian chờ JOIN_ROOM test");
                    }
                    return action.call();
                });
                Thread thread = new Thread(task, "join-room-test-" + threads.size());
                thread.setDaemon(true);
                tasks.add(task);
                threads.add(thread);
                thread.start();
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            List<T> results = new ArrayList<>();
            for (FutureTask<T> task : tasks) {
                results.add(task.get(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
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
                assertFalse(thread.isAlive());
            }
        }
    }

    private static final class FakeSessionChannel implements SessionChannel {
        private volatile boolean open = true;

        @Override
        public void send(Object message) {
            fail("JOIN_ROOM không được gửi packet");
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public boolean isOpen() {
            return open;
        }
    }
}
