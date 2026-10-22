package com.smashprofs.server.network;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import com.smashprofs.common.protocol.MessageType;
import com.smashprofs.server.game.FixedTickScheduler;
import com.smashprofs.server.game.MatchRuntime;
import com.smashprofs.server.game.MatchSetup;
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

import static com.smashprofs.server.network.PlayerReadyHandler.Result.*;
import static org.junit.Assert.*;

/** Channel giả và manual clock; không start scheduler, không socket hoặc sleep. */
public class PlayerReadyHandlerTest {
    private static final PlayerId ALICE = new PlayerId("alice");
    private static final PlayerId BOB = new PlayerId("bob");
    private ConnectionManager connections;
    private RoomManager rooms;
    private FixedTickScheduler scheduler;
    private AtomicLong clock;
    private FakeSessionChannel channel;
    private ClientSession session;
    private GameRoom room;
    private PlayerReadyHandler handler;

    @Before
    public void setUp() {
        connections = new ConnectionManager();
        scheduler = new FixedTickScheduler();
        clock = new AtomicLong(TimeUnit.SECONDS.toNanos(10));
        rooms = new RoomManager(scheduler, clock::get);
        channel = new FakeSessionChannel();
        session = registeredSession("session-a", ALICE, channel);
        room = rooms.getOrCreateRoom("ROOM_A");
        room.joinPlayer(ALICE);
        room.joinPlayer(BOB);
        session.joinRoom("ROOM_A");
        handler = new PlayerReadyHandler(connections, rooms);
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
    public void validPlayerCanBecomeReady() {
        room.selectCharacter(ALICE, "Alex");
        assertEquals(UPDATED, handler.handle(session, request(null, true)));
        assertTrue(room.isPlayerReady(ALICE));
        assertEquals("Alex", room.getSelectedCharacter(ALICE));
    }

    @Test
    public void validPlayerCanBecomeUnready() {
        handler.handle(session, request("Alex", true));
        assertEquals(UPDATED, handler.handle(session, request(null, false)));
        assertFalse(room.isPlayerReady(ALICE));
        assertEquals("Alex", room.getSelectedCharacter(ALICE));
    }

    @Test
    public void nullSessionIsRejected() {
        assertEquals(INVALID_SESSION, handler.handle(null, request("Alex", true)));
        assertUnchangedLobby();
    }

    @Test
    public void disconnectedSessionIsRejected() {
        session.markDisconnected();
        assertEquals(INVALID_SESSION, handler.handle(session, request("Alex", true)));
        assertUnchangedLobby();
    }

    @Test
    public void sessionWithoutPlayerIsRejected() {
        ClientSession unbound = new ClientSession("unbound", new FakeSessionChannel());
        connections.register(unbound);
        unbound.joinRoom("ROOM_A");
        assertEquals(PLAYER_NOT_BOUND, handler.handle(unbound, request("Alex", true)));
        assertUnchangedLobby();
    }

    @Test
    public void sessionWithoutRoomIsRejected() {
        session.leaveRoom();
        assertEquals(ROOM_NOT_BOUND, handler.handle(session, request("Alex", true)));
        assertUnchangedLobby();
    }

    @Test
    public void unknownRoomIsRejected() {
        session.joinRoom("UNKNOWN");
        assertEquals(ROOM_NOT_FOUND, handler.handle(session, request("Alex", true)));
        assertFalse(rooms.containsRoom("UNKNOWN"));
        assertEquals(1, rooms.getRoomCount());
        assertUnchangedLobby();
    }

    @Test
    public void playerNotInRoomIsRejected() {
        room.leavePlayer(ALICE);
        assertEquals(PLAYER_NOT_IN_ROOM, handler.handle(session, request("Alex", true)));
        assertNull(room.getSelectedCharacter(ALICE));
        assertFalse(room.isPlayerReady(ALICE));
        assertEquals(1, room.getPlayerCount());
    }

    @Test
    public void invalidCharacterIsRejected() {
        for (String character : List.of("", " ", "alex", " Alex ", "Unknown")) {
            assertEquals(INVALID_CHARACTER, handler.handle(session, request(character, true)));
            assertUnchangedLobby();
        }
    }

    @Test
    public void playerCannotReadyWithoutCharacter() {
        assertEquals(INVALID_CHARACTER, handler.handle(session, request(null, true)));
        assertUnchangedLobby();
    }

    @Test
    public void validCharacterAndReadyAreProcessed() {
        for (String character : List.of("Alex", "Luca", "Maurice", "Leo", "Viktor")) {
            assertEquals(UPDATED, handler.handle(session, request(character, true)));
            assertEquals(character, room.getSelectedCharacter(ALICE));
            assertTrue(room.isPlayerReady(ALICE));
        }
    }

    @Test
    public void duplicateReadyRequestIsIdempotent() {
        PlayerReadyHandler.ReadyRequest request = request("Alex", true);
        assertEquals(UPDATED, handler.handle(session, request));
        assertEquals(ALREADY_READY, handler.handle(session, request));
        assertEquals(ALREADY_READY, handler.handle(session, request(null, true)));
        assertEquals(2, room.getPlayerCount());
        assertTrue(room.isPlayerReady(ALICE));
        assertEquals("Alex", room.getSelectedCharacter(ALICE));
        assertNull(room.getMatchRuntime());
    }

    @Test
    public void unreadyDuringCountdownCancelsIt() {
        startCountdown();
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        room.updateCountdown();
        assertEquals(UPDATED, handler.handle(session, request(null, false)));
        assertEquals(RoomPhase.WAITING, room.getPhase());
        assertFalse(room.isPlayerReady(ALICE));
        assertEquals("Alex", room.getSelectedCharacter(ALICE));
        assertCountdownReset();
        clock.addAndGet(TimeUnit.SECONDS.toNanos(5));
        rooms.updateLifecycles();
        assertNull(room.getMatchRuntime());
    }

    @Test
    public void readyRequestDuringPlayingIsRejected() {
        startCountdown();
        clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
        rooms.updateLifecycles();
        MatchRuntime runtime = room.getMatchRuntime();
        assertEquals(RoomPhase.PLAYING, room.getPhase());
        assertEquals(INVALID_ROOM_PHASE, handler.handle(session, request("Luca", false)));
        assertEquals(INVALID_ROOM_PHASE, handler.handle(session, request(null, true)));
        assertTrue(room.isPlayerReady(ALICE));
        assertEquals("Alex", room.getSelectedCharacter(ALICE));
        assertSame(runtime, room.getMatchRuntime());
        assertEquals(0, runtime.getMatchState().getServerTick());
    }

    @Test
    public void allPlayersReadyTriggersReadyPhase() {
        ClientSession bob = bobSession();
        assertEquals(UPDATED, handler.handle(session, request("Alex", true)));
        assertEquals(RoomPhase.WAITING, room.getPhase());
        assertEquals(UPDATED, handler.handle(bob, request("Luca", true)));
        assertTrue(room.canStartMatch());
        assertEquals(RoomPhase.READY, room.getPhase());
    }

    @Test
    public void readyDoesNotDirectlyStartMatch() {
        makeReady();
        clock.addAndGet(TimeUnit.SECONDS.toNanos(60));
        assertEquals(ALREADY_READY, handler.handle(session, request("Alex", true)));
        assertEquals(RoomPhase.READY, room.getPhase());
        assertFalse(room.isCountingDown());
        assertNull(room.prepareMatch());
        assertNull(room.getMatchRuntime());
        assertEquals(0, room.getCountdownRemainingMillis());
    }

    @Test
    public void roomIdSpoofingIsRejected() {
        for (String id : List.of("ROOM_B", "", " ROOM_A", "room_a")) {
            assertEquals(INVALID_REQUEST, handler.handle(session,
                    new PlayerReadyHandler.ReadyRequest(id, "Alex", true)));
        }
        assertFalse(rooms.containsRoom("ROOM_B"));
        assertUnchangedLobby();
        assertEquals(UPDATED, handler.handle(session,
                new PlayerReadyHandler.ReadyRequest("ROOM_A", "Alex", true)));
    }

    @Test
    public void twoRoomsHaveIndependentReadyState() {
        GameRoom other = rooms.getOrCreateRoom("ROOM_B");
        other.joinPlayer(ALICE);
        other.joinPlayer(BOB);
        other.selectCharacter(ALICE, "Leo");
        handler.handle(session, request("Alex", true));
        assertFalse(other.isPlayerReady(ALICE));
        assertEquals("Leo", other.getSelectedCharacter(ALICE));
        assertEquals(RoomPhase.WAITING, other.getPhase());
        assertNull(other.getMatchRuntime());
    }

    @Test(timeout = 15000)
    public void concurrentReadyRequestsRemainConsistent() throws Exception {
        room.selectCharacter(BOB, "Alex");
        room.setReady(BOB, true);
        for (int index = 0; index < 20; index++) {
            List<PlayerReadyHandler.Result> results = runConcurrently(List.of(
                    () -> handler.handle(session, request("Alex", true)),
                    () -> handler.handle(session, request("Luca", false))));
            assertTrue(results.stream().allMatch(PlayerReadyHandler.Result::isSuccess));
            assertWholeRequestState();
        }
    }

    @Test
    public void joinRoomRoutingStillWorks() {
        ClientSession newcomer = registeredSession("new", new PlayerId("new"), new FakeSessionChannel());
        MessageDispatcher dispatcher = dispatcher();
        assertTrue(dispatcher.dispatch(newcomer, MessageType.JOIN_ROOM, "ROOM_B"));
        assertEquals("ROOM_B", newcomer.getRoomId());
        assertTrue(rooms.getRoom("ROOM_B").containsPlayer(newcomer.getPlayerId()));
        assertTrue(dispatcher.dispatch(newcomer, MessageType.PLAYER_READY,
                request("Leo", true)));
        assertTrue(rooms.getRoom("ROOM_B").isPlayerReady(newcomer.getPlayerId()));
        assertFalse(room.isPlayerReady(ALICE));
    }

    @Test
    public void playerInputRoutingStillWorks() {
        MessageDispatcher dispatcher = dispatcher();
        ClientSession bob = bobSession();
        PlayerInput input = new PlayerInput(ALICE, 0, 0, 1.0f, false, false, false, false, false);
        assertFalse(dispatcher.dispatch(session, MessageType.PLAYER_INPUT, input));
        assertTrue(dispatcher.dispatch(session, MessageType.PLAYER_READY, request("Alex", true)));
        assertTrue(dispatcher.dispatch(bob, MessageType.PLAYER_READY, request("Luca", true)));
        assertEquals(RoomPhase.READY, room.getPhase());
        rooms.updateLifecycles();
        clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
        rooms.updateLifecycles();
        MatchRuntime runtime = room.getMatchRuntime();
        assertTrue(dispatcher.dispatch(session, MessageType.PLAYER_INPUT, input));
        assertEquals(0, runtime.getMatchState().getServerTick());
        // Scheduler chưa chạy: chỉ test này tick thủ công để kiểm chứng queue, không tick trong handler.
        assertEquals(List.of(input), runtime.tick());
    }

    @Test
    public void existingTestsStillPass() {
        List<MessageType> forwarded = new ArrayList<>();
        MessageDispatcher.InboundHandler fallback = (source, type, payload) -> forwarded.add(type);
        Object payload = new Object();
        for (MessageDispatcher legacy : List.of(new MessageDispatcher(fallback),
                new MessageDispatcher(fallback, new PlayerInputHandler(connections, rooms)),
                new MessageDispatcher(fallback, null, new JoinRoomHandler(connections, rooms)))) {
            assertTrue(legacy.dispatch(session, MessageType.PLAYER_READY, payload));
        }
        assertEquals(List.of(MessageType.PLAYER_READY, MessageType.PLAYER_READY, MessageType.PLAYER_READY), forwarded);
        assertUnchangedLobby();
    }

    @Test
    public void nullRequestIsRejected() {
        assertEquals(INVALID_REQUEST, handler.handle(session, null));
        assertUnchangedLobby();
    }

    @Test
    public void closedChannelIsRejectedEvenWithConnectedFlag() {
        channel.close();
        assertTrue(session.isConnected());
        assertEquals(INVALID_SESSION, handler.handle(session, request("Alex", true)));
        assertUnchangedLobby();
    }

    @Test
    public void unregisteredOrImpostorSessionIsRejected() {
        ClientSession impostor = new ClientSession(session.getSessionId(), new FakeSessionChannel());
        impostor.bindPlayer(ALICE);
        impostor.joinRoom("ROOM_A");
        assertEquals(INVALID_SESSION, handler.handle(impostor, request("Alex", true)));
        connections.remove(session.getSessionId());
        assertEquals(INVALID_SESSION, handler.handle(session, request("Alex", true)));
        assertUnchangedLobby();
    }

    @Test
    public void invalidBoundIdentityIsRejected() {
        for (PlayerId invalid : List.of(new PlayerId(null), new PlayerId(""), new PlayerId(" \t"))) {
            session.bindPlayer(invalid);
            assertEquals(PLAYER_NOT_BOUND, handler.handle(session, request("Alex", true)));
        }
        assertUnchangedLobby();
    }

    @Test
    public void blankSessionOrRoomIdIsRejected() {
        ClientSession invalid = new ClientSession(" ", new FakeSessionChannel());
        assertEquals(INVALID_SESSION, handler.handle(invalid, request("Alex", true)));
        session.joinRoom(" \t");
        assertEquals(ROOM_NOT_BOUND, handler.handle(session, request("Alex", true)));
        assertUnchangedLobby();
    }

    @Test
    public void invalidCharacterDoesNotChangeReadyOrCountdown() {
        startCountdown();
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        room.updateCountdown();
        for (boolean ready : List.of(true, false)) {
            assertEquals(INVALID_CHARACTER, handler.handle(session, request("Unknown", ready)));
            assertTrue(room.isPlayerReady(ALICE));
            assertEquals("Alex", room.getSelectedCharacter(ALICE));
            assertEquals(RoomPhase.COUNTDOWN, room.getPhase());
            assertEquals(2000, room.getCountdownRemainingMillis());
        }
        clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
        rooms.updateLifecycles();
        assertEquals(RoomPhase.PLAYING, room.getPhase());
    }

    @Test
    public void duplicateReadyPreservesCountdownDeadlineAndPreparedMatch() {
        startCountdown();
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        room.updateCountdown();
        assertEquals(ALREADY_READY, handler.handle(session, request("Alex", true)));
        assertEquals(ALREADY_READY, handler.handle(session, request(null, true)));
        assertEquals(2000, room.getCountdownRemainingMillis());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
        room.updateCountdown();
        MatchSetup setup = room.prepareMatch();
        assertNotNull(setup);
        assertEquals(ALREADY_READY, handler.handle(session, request("Alex", true)));
        assertSame(setup, room.prepareMatch());
        assertNull(room.getMatchRuntime());
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase());
    }

    @Test
    public void changingCharacterAndUnreadyCancelsCountdown() {
        startCountdown();
        assertEquals(UPDATED, handler.handle(session, request("Luca", false)));
        assertEquals("Luca", room.getSelectedCharacter(ALICE));
        assertFalse(room.isPlayerReady(ALICE));
        assertEquals(RoomPhase.WAITING, room.getPhase());
        assertCountdownReset();
    }

    @Test
    public void changingCharacterAndReadyRequiresFreshLifecycleCountdown() {
        startCountdown();
        clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
        room.updateCountdown();
        assertEquals(UPDATED, handler.handle(session, request("Luca", true)));
        assertEquals("Luca", room.getSelectedCharacter(ALICE));
        assertTrue(room.isPlayerReady(ALICE));
        assertEquals(RoomPhase.READY, room.getPhase());
        assertCountdownReset();
        rooms.updateLifecycles();
        assertEquals(3000, room.getCountdownRemainingMillis());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        rooms.updateLifecycles();
        assertNull(room.getMatchRuntime());
        assertEquals(2000, room.getCountdownRemainingMillis());
    }

    @Test
    public void unreadyWithoutCharacterAndRepeatedUnreadyAreValid() {
        assertEquals(UNCHANGED, handler.handle(session, request(null, false)));
        assertEquals(UNCHANGED, handler.handle(session, request(null, false)));
        assertUnchangedLobby();
        assertEquals(UPDATED, handler.handle(session, request("Leo", false)));
        assertEquals("Leo", room.getSelectedCharacter(ALICE));
        assertFalse(room.isPlayerReady(ALICE));
    }

    @Test
    public void finishedPhaseIsNotUpdatable() {
        // FINISHED chưa có transition API: kiểm tra chính sách, không thêm GAME_END giả.
        for (RoomPhase phase : RoomPhase.values()) {
            assertEquals(phase == RoomPhase.WAITING || phase == RoomPhase.READY || phase == RoomPhase.COUNTDOWN,
                    PlayerReadyHandler.isUpdatablePhase(phase));
        }
        assertFalse(PlayerReadyHandler.isUpdatablePhase(null));
    }

    @Test
    public void removedRoomDoesNotReceiveReadyUpdates() {
        assertSame(room, rooms.removeRoom("ROOM_A"));
        assertEquals(ROOM_NOT_FOUND, handler.handle(session, request("Alex", true)));
        assertUnchangedLobby();
    }

    @Test
    public void dispatcherRejectsInvalidReadyPayloadAndUntrustedIdentity() {
        MessageDispatcher dispatcher = dispatcher();
        for (Object payload : List.of(new Object(), true, Map.of("playerId", "bob", "ready", true))) {
            assertFalse(dispatcher.dispatch(session, MessageType.PLAYER_READY, payload));
        }
        assertFalse(dispatcher.dispatch(session, MessageType.PLAYER_READY, null));
        assertFalse(dispatcher.dispatch(session, MessageType.PLAYER_READY, request("Unknown", true)));
        assertFalse(dispatcher.dispatch(session, MessageType.PING, request("Alex", true)));
        assertUnchangedLobby();
    }

    @Test(timeout = 15000)
    public void concurrentRequestsFromSeparateSessionsForSamePlayerAreAtomic() throws Exception {
        ClientSession other = registeredSession("same-player", ALICE, new FakeSessionChannel());
        other.joinRoom("ROOM_A");
        room.selectCharacter(BOB, "Alex");
        room.setReady(BOB, true);
        for (int index = 0; index < 20; index++) {
            List<PlayerReadyHandler.Result> results = runConcurrently(List.of(
                    () -> handler.handle(session, request("Alex", true)),
                    () -> handler.handle(other, request("Luca", false))));
            assertTrue(results.stream().allMatch(PlayerReadyHandler.Result::isSuccess));
            assertWholeRequestState();
        }
    }

    @Test(timeout = 15000)
    public void concurrentPlayersCanBothReadyWithoutStartingCountdown() throws Exception {
        ClientSession bob = bobSession();
        List<PlayerReadyHandler.Result> results = runConcurrently(List.of(
                () -> handler.handle(session, request("Alex", true)),
                () -> handler.handle(bob, request("Luca", true))));
        assertEquals(List.of(UPDATED, UPDATED), results);
        assertTrue(room.canStartMatch());
        assertEquals(RoomPhase.READY, room.getPhase());
        assertNull(room.getMatchRuntime());
        assertFalse(room.isCountingDown());
    }

    @Test
    public void roomAtomicOperationRejectsInvalidCharacterBeforeMutation() {
        startCountdown();
        assertThrows(IllegalArgumentException.class, () -> room.updatePlayerReady(ALICE, "Unknown", false));
        assertTrue(room.isPlayerReady(ALICE));
        assertEquals("Alex", room.getSelectedCharacter(ALICE));
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase());
        assertEquals(3000, room.getCountdownRemainingMillis());
        assertFalse(room.updatePlayerReady(new PlayerId("outsider"), "Alex", true));
    }

    @Test
    public void nullDependenciesAreRejected() {
        assertThrows(NullPointerException.class, () -> new PlayerReadyHandler(null, rooms));
        assertThrows(NullPointerException.class, () -> new PlayerReadyHandler(connections, null));
    }

    private static PlayerReadyHandler.ReadyRequest request(String character, boolean ready) {
        return new PlayerReadyHandler.ReadyRequest(null, character, ready);
    }

    private ClientSession registeredSession(String id, PlayerId player, FakeSessionChannel fakeChannel) {
        ClientSession created = new ClientSession(id, fakeChannel);
        created.bindPlayer(player);
        connections.register(created);
        return created;
    }

    private ClientSession bobSession() {
        ClientSession bob = registeredSession("session-b", BOB, new FakeSessionChannel());
        bob.joinRoom("ROOM_A");
        return bob;
    }

    private MessageDispatcher dispatcher() {
        return new MessageDispatcher((source, type, payload) -> fail("Không gọi fallback"),
                new PlayerInputHandler(connections, rooms), new JoinRoomHandler(connections, rooms), handler);
    }

    private void makeReady() {
        assertEquals(UPDATED, handler.handle(session, request("Alex", true)));
        room.selectCharacter(BOB, "Alex");
        room.setReady(BOB, true);
        assertEquals(RoomPhase.READY, room.getPhase());
    }

    private void startCountdown() {
        makeReady();
        rooms.updateLifecycles();
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase());
    }

    private void assertUnchangedLobby() {
        assertNull(room.getSelectedCharacter(ALICE));
        assertFalse(room.isPlayerReady(ALICE));
        assertEquals(RoomPhase.WAITING, room.getPhase());
        assertNull(room.getMatchRuntime());
    }

    private void assertCountdownReset() {
        assertEquals(0, room.getCountdownRemainingMillis());
        assertFalse(room.isCountingDown());
        assertFalse(room.isCountdownComplete());
        assertNull(room.getMatchRuntime());
    }

    private void assertWholeRequestState() {
        boolean ready = room.isPlayerReady(ALICE);
        assertEquals(ready ? "Alex" : "Luca", room.getSelectedCharacter(ALICE));
        assertEquals(ready ? RoomPhase.READY : RoomPhase.WAITING, room.getPhase());
        assertEquals(ready, room.canStartMatch());
        assertEquals(2, room.getPlayerCount());
        assertNull(room.getMatchRuntime());
    }

    /** Chờ có thời hạn và cleanup thread như các test concurrency hiện có. */
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
                        throw new IllegalStateException("Hết thời gian chờ PLAYER_READY test");
                    }
                    return action.call();
                });
                Thread thread = new Thread(task, "player-ready-test-" + threads.size());
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
            fail("PLAYER_READY không được gửi packet");
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
