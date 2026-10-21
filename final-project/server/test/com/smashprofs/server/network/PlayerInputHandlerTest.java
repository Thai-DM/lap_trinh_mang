package com.smashprofs.server.network;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import com.smashprofs.common.protocol.MessageType;
import com.smashprofs.server.game.FixedTickScheduler;
import com.smashprofs.server.game.MatchRuntime;
import com.smashprofs.server.room.GameRoom;
import com.smashprofs.server.room.RoomManager;
import com.smashprofs.server.room.RoomPhase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.smashprofs.server.network.PlayerInputHandler.RoutingResult.*;
import static org.junit.Assert.*;

/** Session thật với channel giả; clock thủ công, scheduler không start nên không tạo simulation thread. */
public class PlayerInputHandlerTest {
    private static final PlayerId ALICE = new PlayerId("alice");
    private static final PlayerId BOB = new PlayerId("bob");
    private static final PlayerId CHARLIE = new PlayerId("charlie");
    private static final PlayerId DANA = new PlayerId("dana");
    private ConnectionManager connections;
    private RoomManager rooms;
    private FixedTickScheduler scheduler;
    private AtomicLong clock;
    private FakeSessionChannel channel;
    private ClientSession session;
    private PlayerInputHandler handler;
    private GameRoom room;
    private MatchRuntime runtime;

    @Before
    public void setUp() {
        connections = new ConnectionManager();
        scheduler = new FixedTickScheduler();
        clock = new AtomicLong(TimeUnit.SECONDS.toNanos(10));
        rooms = new RoomManager(scheduler, clock::get);
        room = playingRoom("room-a", ALICE, BOB);
        runtime = room.getMatchRuntime();
        channel = new FakeSessionChannel();
        session = registeredSession("session-a", ALICE, "room-a", channel);
        handler = new PlayerInputHandler(connections, rooms);
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
    public void validSessionInputIsAccepted() {
        PlayerInput input = input(ALICE, 0, 1.0f);
        assertEquals(ACCEPTED, handler.handle(session, input));
        assertEquals(0, ticks(runtime));
        assertEquals(List.of(input), runtime.tick());
    }

    @Test
    public void nullSessionIsRejected() {
        assertEquals(INVALID_SESSION, handler.handle(null, input(ALICE, 0, 0.0f)));
        assertRejectedWithoutEnqueue();
    }

    @Test
    public void disconnectedSessionIsRejected() {
        session.markDisconnected();
        assertTrue(channel.isOpen());
        assertEquals(INVALID_SESSION, handler.handle(session, input(ALICE, 0, 0.0f)));
        assertRejectedWithoutEnqueue();
    }

    @Test
    public void closedChannelIsRejectedEvenIfSessionConnectedFlagIsTrue() {
        channel.close();
        assertTrue(session.isConnected());
        assertEquals(INVALID_SESSION, handler.handle(session, input(ALICE, 0, 0.0f)));
        assertRejectedWithoutEnqueue();
    }

    @Test
    public void sessionWithoutPlayerIsRejected() {
        ClientSession unbound = new ClientSession("unbound", new FakeSessionChannel());
        unbound.joinRoom("room-a");
        connections.register(unbound);
        assertEquals(PLAYER_NOT_BOUND, handler.handle(unbound, input(ALICE, 0, 0.0f)));
        assertRejectedWithoutEnqueue();
    }

    @Test
    public void invalidBoundPlayerIdIsRejected() {
        for (PlayerId id : List.of(new PlayerId(null), new PlayerId(""), new PlayerId(" \t"))) {
            session.bindPlayer(id);
            assertEquals(PLAYER_NOT_BOUND, handler.handle(session, input(id, 0, 0.0f)));
        }
        assertRejectedWithoutEnqueue();
    }

    @Test
    public void sessionWithoutRoomIsRejected() {
        session.leaveRoom();
        assertEquals(ROOM_NOT_BOUND, handler.handle(session, input(ALICE, 0, 0.0f)));
        assertRejectedWithoutEnqueue();
    }

    @Test
    public void blankRoomIdIsRejected() {
        for (String id : List.of("", " \t")) {
            session.joinRoom(id);
            assertEquals(ROOM_NOT_BOUND, handler.handle(session, input(ALICE, 0, 0.0f)));
        }
        assertRejectedWithoutEnqueue();
    }

    @Test
    public void unknownRoomIsRejected() {
        session.joinRoom("missing");
        assertEquals(ROOM_NOT_FOUND, handler.handle(session, input(ALICE, 0, 0.0f)));
        assertRejectedWithoutEnqueue();
    }

    @Test
    public void inputDoesNotCreateUnknownRoom() {
        int count = rooms.getRoomCount();
        session.joinRoom("missing");
        assertEquals(ROOM_NOT_FOUND, handler.handle(session, input(ALICE, 0, 0.0f)));
        assertEquals(count, rooms.getRoomCount());
        assertFalse(rooms.containsRoom("missing"));
        assertRejectedWithoutEnqueue();
    }

    @Test
    public void waitingRoomRejectsInput() {
        GameRoom waiting = rooms.getOrCreateRoom("waiting");
        waiting.joinPlayer(ALICE);
        session.joinRoom("waiting");
        assertEquals(ROOM_NOT_PLAYING, handler.handle(session, input(ALICE, 0, 0.0f)));
        assertEquals(RoomPhase.WAITING, waiting.getPhase());
        assertNull(waiting.getMatchRuntime());
        assertRejectedWithoutEnqueue();
    }

    @Test
    public void countdownRoomRejectsInput() {
        GameRoom countdown = readyRoom("countdown", ALICE, BOB);
        assertTrue(countdown.updateLifecycle());
        session.joinRoom("countdown");
        assertEquals(ROOM_NOT_PLAYING, handler.handle(session, input(ALICE, 0, 0.0f)));
        assertEquals(RoomPhase.COUNTDOWN, countdown.getPhase());
        assertNull(countdown.getMatchRuntime());
        assertRejectedWithoutEnqueue();
    }

    @Test
    public void readyRoomRejectsInput() {
        GameRoom ready = readyRoom("ready", ALICE, BOB);
        session.joinRoom("ready");
        assertEquals(ROOM_NOT_PLAYING, handler.handle(session, input(ALICE, 0, 0.0f)));
        assertEquals(RoomPhase.READY, ready.getPhase());
        assertRejectedWithoutEnqueue();
    }

    @Test
    public void playingRoomAcceptsInput() {
        assertEquals(RoomPhase.PLAYING, room.getPhase());
        assertTrue(runtime.isStarted());
        PlayerInput input = input(ALICE, 0, -1.0f);
        assertEquals(ACCEPTED, handler.handle(session, input));
        assertEquals(List.of(input), runtime.tick());
    }

    @Test
    public void playerNotInMatchIsRejected() {
        session.bindPlayer(CHARLIE);
        assertEquals(PLAYER_NOT_IN_MATCH, handler.handle(session, input(CHARLIE, 0, 0.0f)));
        assertRejectedWithoutEnqueue();
    }

    @Test
    public void spoofedPlayerIdIsRejected() {
        // Bob thuộc cùng trận, nhưng session Alice không được gửi thay Bob.
        assertEquals(INVALID_INPUT, handler.handle(session, input(BOB, 0, 0.0f)));
        assertRejectedWithoutEnqueue();
    }

    @Test
    public void inputRoutesToCorrectMatch() {
        GameRoom second = playingRoom("room-b", CHARLIE, DANA);
        ClientSession other = registeredSession("session-b", CHARLIE, "room-b", new FakeSessionChannel());
        PlayerInput firstInput = input(ALICE, 0, 1.0f);
        PlayerInput secondInput = input(CHARLIE, 0, -1.0f);
        assertEquals(ACCEPTED, handler.handle(session, firstInput));
        assertEquals(ACCEPTED, handler.handle(other, secondInput));
        assertEquals(0, ticks(runtime));
        assertEquals(0, ticks(second.getMatchRuntime()));
        assertEquals(List.of(firstInput), runtime.tick());
        assertEquals(List.of(secondInput), second.getMatchRuntime().tick());
    }

    @Test
    public void inputDoesNotReachOtherRoom() {
        MatchRuntime other = playingRoom("room-b", CHARLIE, DANA).getMatchRuntime();
        PlayerInput input = input(ALICE, 0, 1.0f);
        assertEquals(ACCEPTED, handler.handle(session, input));
        assertEquals(INVALID_INPUT, handler.handle(session, input(CHARLIE, 1, 0.0f)));
        assertEquals(0, ticks(other));
        assertEquals(List.of(input), runtime.tick());
        assertTrue(other.tick().isEmpty());
    }

    @Test
    public void sessionAssignedToDifferentRoomMustBelongToItsRoster() {
        MatchRuntime other = playingRoom("room-b", CHARLIE, DANA).getMatchRuntime();
        session.joinRoom("room-b");
        assertEquals(PLAYER_NOT_IN_MATCH, handler.handle(session, input(ALICE, 0, 0.0f)));
        assertTrue(other.tick().isEmpty());
        assertRejectedWithoutEnqueue();
    }

    @Test
    public void samePlayerIdInTwoRoomsStillUsesSessionRoom() {
        MatchRuntime other = playingRoom("room-b", ALICE, BOB).getMatchRuntime();
        ClientSession second = registeredSession("session-b", ALICE, "room-b", new FakeSessionChannel());
        PlayerInput firstInput = input(ALICE, 0, 1.0f);
        PlayerInput secondInput = input(ALICE, 0, -1.0f);
        assertEquals(ACCEPTED, handler.handle(session, firstInput));
        assertEquals(ACCEPTED, handler.handle(second, secondInput));
        assertEquals(List.of(firstInput), runtime.tick());
        assertEquals(List.of(secondInput), other.tick());
    }

    @Test
    public void duplicateSequenceIsRejected() {
        PlayerInput input = input(ALICE, 10, 0.5f);
        assertEquals(ACCEPTED, handler.handle(session, input));
        assertEquals(DUPLICATE_OR_STALE, handler.handle(session, input));
        assertEquals(DUPLICATE_OR_STALE, handler.handle(session, input(ALICE, 9, -1.0f)));
        assertEquals(List.of(input), runtime.tick());
    }

    @Test
    public void queueFullResultIsPreserved() {
        for (int sequence = 0; sequence < 32; sequence++) {
            assertEquals(ACCEPTED, handler.handle(session, input(ALICE, sequence, 1.0f)));
        }
        assertEquals(QUEUE_FULL, handler.handle(session, input(ALICE, 32, 0.0f)));
        assertEquals(List.of(input(ALICE, 31, 1.0f)), runtime.tick());
        // Bị full không được làm mất sequence 32; sau drain input đó vẫn hợp lệ.
        assertEquals(ACCEPTED, handler.handle(session, input(ALICE, 32, 0.0f)));
    }

    @Test
    public void acceptedInputDoesNotTickRuntime() {
        assertEquals(ACCEPTED, handler.handle(session, input(ALICE, 0, 1.0f)));
        assertEquals(0, ticks(runtime));
        assertEquals(100.0f, runtime.getMatchState().getPlayer(ALICE).getHp(), 0.0f);
        assertFalse(runtime.getMatchState().getPlayer(ALICE).isDead());
        assertEquals(RoomPhase.PLAYING, room.getPhase());
    }

    @Test
    public void invalidPayloadValuesReuseInputQueueValidation() {
        assertEquals(INVALID_INPUT, handler.handle(session, null));
        assertEquals(INVALID_INPUT, handler.handle(session, input(null, 0, 0.0f)));
        assertEquals(INVALID_INPUT, handler.handle(session, input(ALICE, -1, 0.0f)));
        for (float axis : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -1.01f, 1.01f}) {
            assertEquals(INVALID_INPUT, handler.handle(session, input(ALICE, 0, axis)));
        }
        assertEquals(ACCEPTED, handler.handle(session, input(ALICE, 0, 0.0f)));
        assertEquals(List.of(input(ALICE, 0, 0.0f)), runtime.tick());
    }

    @Test
    public void unregisteredSessionIsRejected() {
        connections.remove(session.getSessionId());
        assertTrue(session.isOpen());
        assertEquals(INVALID_SESSION, handler.handle(session, input(ALICE, 0, 0.0f)));
        assertRejectedWithoutEnqueue();
    }

    @Test
    public void differentSessionObjectWithSameIdIsRejected() {
        ClientSession impostor = new ClientSession(session.getSessionId(), new FakeSessionChannel());
        impostor.bindPlayer(ALICE);
        impostor.joinRoom("room-a");
        assertEquals(INVALID_SESSION, handler.handle(impostor, input(ALICE, 0, 0.0f)));
        assertRejectedWithoutEnqueue();
    }

    @Test
    public void replacingRegisteredSessionRejectsOldObject() {
        connections.remove(session.getSessionId());
        ClientSession replacement = registeredSession(session.getSessionId(), ALICE, "room-a",
                new FakeSessionChannel());
        assertEquals(INVALID_SESSION, handler.handle(session, input(ALICE, 0, 0.0f)));
        assertEquals(ACCEPTED, handler.handle(replacement, input(ALICE, 0, 0.0f)));
        assertEquals(List.of(input(ALICE, 0, 0.0f)), runtime.tick());
    }

    @Test
    public void blankSessionIdIsRejectedWithoutThrowing() {
        ClientSession invalid = new ClientSession(" \t", new FakeSessionChannel());
        invalid.bindPlayer(ALICE);
        invalid.joinRoom("room-a");
        assertEquals(INVALID_SESSION, handler.handle(invalid, input(ALICE, 0, 0.0f)));
        assertRejectedWithoutEnqueue();
    }

    @Test
    public void removedRoomRejectsInputEvenIfSessionStillReferencesIt() {
        rooms.removeRoom("room-a");
        assertEquals(ROOM_NOT_FOUND, handler.handle(session, input(ALICE, 0, 0.0f)));
        assertTrue(room.isClosed());
        assertRejectedWithoutEnqueue();
    }

    @Test
    public void idsAreComparedByValueWithoutChangingInputDto() {
        PlayerId supplied = new PlayerId("alice");
        PlayerInput input = input(supplied, 0, 1.0f);
        assertNotSame(ALICE, supplied);
        assertEquals(ACCEPTED, handler.handle(session, input));
        assertSame(supplied, input.playerId());
        assertEquals(List.of(input), runtime.tick());
    }

    @Test(timeout = 15000)
    public void concurrentRoutingIsSafe() throws Exception {
        List<Callable<PlayerInputHandler.RoutingResult>> actions = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            actions.add(() -> handler.handle(session, input(ALICE, 0, 1.0f)));
        }
        List<PlayerInputHandler.RoutingResult> results = runConcurrently(actions);
        assertEquals(1, results.stream().filter(result -> result == ACCEPTED).count());
        assertEquals(15, results.stream().filter(result -> result == DUPLICATE_OR_STALE).count());
        assertEquals(0, ticks(runtime));
        assertEquals(List.of(input(ALICE, 0, 1.0f)), runtime.tick());
    }

    @Test(timeout = 15000)
    public void concurrentRoutingAcrossRoomsKeepsQueuesIndependent() throws Exception {
        MatchRuntime other = playingRoom("room-b", CHARLIE, DANA).getMatchRuntime();
        ClientSession second = registeredSession("session-b", CHARLIE, "room-b", new FakeSessionChannel());
        List<PlayerInputHandler.RoutingResult> results = runConcurrently(List.of(
                () -> handler.handle(session, input(ALICE, 0, 1.0f)),
                () -> handler.handle(second, input(CHARLIE, 0, -1.0f))));
        assertEquals(List.of(ACCEPTED, ACCEPTED), results);
        assertEquals(List.of(input(ALICE, 0, 1.0f)), runtime.tick());
        assertEquals(List.of(input(CHARLIE, 0, -1.0f)), other.tick());
    }

    @Test(timeout = 15000)
    public void concurrentSessionLeaveCannotRedirectInputToAnotherRoom() throws Exception {
        MatchRuntime other = playingRoom("room-b", CHARLIE, DANA).getMatchRuntime();
        List<Object> results = runConcurrently(List.<Callable<Object>>of(
                () -> handler.handle(session, input(ALICE, 0, 1.0f)),
                () -> { session.leaveRoom(); return true; }));
        assertTrue(results.get(0) == ACCEPTED || results.get(0) == ROOM_NOT_BOUND);
        assertNull(session.getRoomId());
        assertEquals(0, ticks(runtime));
        List<PlayerInput> expected = results.get(0) == ACCEPTED ? List.of(input(ALICE, 0, 1.0f)) : List.of();
        assertEquals(expected, runtime.tick());
        assertTrue(other.tick().isEmpty());
    }

    @Test(timeout = 15000)
    public void concurrentRoomRemovalAndRoutingAreSafe() throws Exception {
        List<Object> results = runConcurrently(List.<Callable<Object>>of(
                () -> handler.handle(session, input(ALICE, 0, 1.0f)),
                () -> rooms.removeRoom("room-a")));
        assertTrue(results.get(0) == ACCEPTED || results.get(0) == ROOM_NOT_FOUND);
        assertSame(room, results.get(1));
        assertTrue(room.isClosed());
        assertFalse(rooms.containsRoom("room-a"));
        assertEquals(0, ticks(runtime));
        assertEquals(ROOM_NOT_FOUND, handler.handle(session, input(ALICE, 1, 0.0f)));
        // Remove giờ dispose World: không tick runtime đã đóng để quan sát queue lịch sử.
        assertTrue(runtime.isClosed());
        assertThrows(IllegalStateException.class, runtime::tick);
        assertEquals(0, ticks(runtime));
    }

    @Test(timeout = 15000)
    public void sessionRoomCannotChangeBetweenValidationAndSubmission() throws Exception {
        MatchRuntime other = playingRoom("room-b", CHARLIE, DANA).getMatchRuntime();
        FutureTask<PlayerInputHandler.RoutingResult> routing = new FutureTask<>(
                () -> handler.handle(session, input(ALICE, 0, 1.0f)));
        FutureTask<Boolean> changing = new FutureTask<>(() -> {
            session.joinRoom("room-b");
            return true;
        });
        Thread routeThread = new Thread(routing, "input-route-test");
        Thread changeThread = new Thread(changing, "input-room-change-test");
        routeThread.setDaemon(true);
        changeThread.setDaemon(true);
        try {
            synchronized (runtime) {
                routeThread.start();
                awaitBlocked(routeThread);
                changeThread.start();
                awaitBlocked(changeThread);
                assertEquals("room-a", session.getRoomId());
            }
            assertEquals(ACCEPTED, routing.get(5, TimeUnit.SECONDS));
            assertTrue(changing.get(5, TimeUnit.SECONDS));
        } finally {
            routeThread.interrupt();
            changeThread.interrupt();
            routeThread.join(TimeUnit.SECONDS.toMillis(2));
            changeThread.join(TimeUnit.SECONDS.toMillis(2));
        }
        assertFalse(routeThread.isAlive());
        assertFalse(changeThread.isAlive());
        assertEquals("room-b", session.getRoomId());
        assertEquals(List.of(input(ALICE, 0, 1.0f)), runtime.tick());
        assertTrue(other.tick().isEmpty());
    }

    @Test
    public void dispatcherRoutesPlayerInputWithoutCallingGenericHandler() {
        List<MessageType> forwarded = new ArrayList<>();
        MessageDispatcher dispatcher = new MessageDispatcher((source, type, payload) -> forwarded.add(type), handler);
        PlayerInput input = input(ALICE, 0, 0.5f);
        assertTrue(dispatcher.dispatch(session, MessageType.PLAYER_INPUT, input));
        assertTrue(forwarded.isEmpty());
        assertEquals(0, ticks(runtime));
        assertEquals(List.of(input), runtime.tick());
    }

    @Test
    public void dispatcherRejectsSpoofedInputWithoutFallback() {
        List<MessageType> forwarded = new ArrayList<>();
        MessageDispatcher dispatcher = new MessageDispatcher((source, type, payload) -> forwarded.add(type), handler);
        assertFalse(dispatcher.dispatch(session, MessageType.PLAYER_INPUT, input(BOB, 0, 0.0f)));
        assertTrue(forwarded.isEmpty());
        assertRejectedWithoutEnqueue();
    }

    @Test
    public void dispatcherRejectsWrongPlayerInputPayloadType() {
        MessageDispatcher dispatcher = new MessageDispatcher((source, type, payload) -> fail("Không gọi fallback"), handler);
        assertFalse(dispatcher.dispatch(session, MessageType.PLAYER_INPUT, null));
        assertFalse(dispatcher.dispatch(session, MessageType.PLAYER_INPUT, new Object()));
        assertFalse(dispatcher.dispatch(session, MessageType.PLAYER_INPUT,
                Map.of("playerId", "bob", "roomId", "room-b")));
        assertRejectedWithoutEnqueue();
    }

    @Test
    public void dispatcherPreservesJoinRoomAndPlayerReadyBoundaries() {
        List<MessageType> forwarded = new ArrayList<>();
        List<Object> payloads = new ArrayList<>();
        MessageDispatcher dispatcher = new MessageDispatcher((source, type, payload) -> {
            assertSame(session, source);
            forwarded.add(type);
            payloads.add(payload);
        }, handler);
        Object joinPayload = new Object();
        assertTrue(dispatcher.dispatch(session, MessageType.JOIN_ROOM, joinPayload));
        assertTrue(dispatcher.dispatch(session, MessageType.PLAYER_READY, null));
        assertEquals(List.of(MessageType.JOIN_ROOM, MessageType.PLAYER_READY), forwarded);
        assertSame(joinPayload, payloads.get(0));
        assertNull(payloads.get(1));
        assertRejectedWithoutEnqueue();
    }

    @Test
    public void dispatcherReturnsFalseWhenQueueRejectsDuplicate() {
        MessageDispatcher dispatcher = new MessageDispatcher((source, type, payload) -> fail("Không gọi fallback"), handler);
        PlayerInput input = input(ALICE, 0, 0.5f);
        assertTrue(dispatcher.dispatch(session, MessageType.PLAYER_INPUT, input));
        assertFalse(dispatcher.dispatch(session, MessageType.PLAYER_INPUT, input));
        assertEquals(List.of(input), runtime.tick());
    }

    @Test
    public void existingTestsStillPass() {
        List<MessageType> forwarded = new ArrayList<>();
        Object payload = new Object();
        MessageDispatcher legacy = new MessageDispatcher((source, type, original) -> {
            assertSame(session, source);
            assertSame(payload, original);
            forwarded.add(type);
        });
        assertTrue(legacy.dispatch(session, MessageType.PLAYER_INPUT, payload));
        assertEquals(List.of(MessageType.PLAYER_INPUT), forwarded);
        assertEquals(0, ticks(runtime));
        assertEquals(GameRoom.JoinResult.ALREADY_IN_ROOM, room.joinPlayer(ALICE));
        assertFalse(room.leavePlayer(ALICE));
        assertFalse(room.setReady(ALICE, false));
    }

    @Test
    public void nullDependenciesAreRejected() {
        assertThrows(NullPointerException.class, () -> new PlayerInputHandler(null, rooms));
        assertThrows(NullPointerException.class, () -> new PlayerInputHandler(connections, null));
    }

    private GameRoom readyRoom(String id, PlayerId first, PlayerId second) {
        GameRoom created = rooms.getOrCreateRoom(id);
        for (PlayerId player : List.of(first, second)) {
            assertEquals(GameRoom.JoinResult.JOINED, created.joinPlayer(player));
            assertTrue(created.selectCharacter(player, "Alex"));
            assertTrue(created.setReady(player, true));
        }
        return created;
    }

    private GameRoom playingRoom(String id, PlayerId first, PlayerId second) {
        GameRoom created = readyRoom(id, first, second);
        assertTrue(created.updateLifecycle());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
        assertTrue(created.updateLifecycle());
        assertEquals(RoomPhase.PLAYING, created.getPhase());
        return created;
    }

    private ClientSession registeredSession(String id, PlayerId player, String roomId, FakeSessionChannel fakeChannel) {
        ClientSession created = new ClientSession(id, fakeChannel);
        created.bindPlayer(player);
        created.joinRoom(roomId);
        connections.register(created);
        return created;
    }

    private void assertRejectedWithoutEnqueue() {
        assertEquals(0, ticks(runtime));
        assertEquals(100.0f, runtime.getMatchState().getPlayer(ALICE).getHp(), 0.0f);
        // Chỉ harness gọi tick thủ công để quan sát queue; handler không được tick.
        if (runtime.isClosed()) {
            assertThrows(IllegalStateException.class, runtime::tick);
            assertEquals(0, ticks(runtime));
        } else {
            assertTrue(runtime.tick().isEmpty());
        }
    }

    private static long ticks(MatchRuntime match) {
        return match.getMatchState().getServerTick();
    }

    private static PlayerInput input(PlayerId id, long sequence, float axis) {
        return new PlayerInput(id, sequence, sequence, axis, false, false, false, false, false);
    }

    private static void awaitBlocked(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.isAlive() && thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(Thread.State.BLOCKED, thread.getState());
    }

    /** Mẫu test concurrency như các bước trước: latch/future có thời hạn và cleanup tất cả thread. */
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
                        throw new IllegalStateException("Hết thời gian chờ routing test");
                    }
                    return action.call();
                });
                Thread thread = new Thread(task, "player-input-test-" + threads.size());
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

    /** Không cho routing gửi response; chỉ mô phỏng trạng thái channel. */
    private static final class FakeSessionChannel implements SessionChannel {
        private volatile boolean open = true;

        @Override
        public void send(Object message) {
            fail("Routing không được gửi message qua channel");
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
