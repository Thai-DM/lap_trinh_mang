package com.smashprofs.server.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.protocol.*;
import com.smashprofs.server.network.ClientSession;
import com.smashprofs.server.network.GameServer;
import com.smashprofs.server.room.GameRoom;
import com.smashprofs.server.room.RoomPhase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import static org.junit.Assert.*;

/** Trận hai người qua TCP thật; manual scheduler chỉ điều khiển thời điểm chạy production cycle. */
public class TwoClientTcpMatchEndToEndTest {
    private static final double EPS = 0.00001;
    private final ManualScheduledExecutor executor = new ManualScheduledExecutor();
    private final AtomicLong clock = new AtomicLong(10_000_000_000L);
    private final List<TcpTestClient> clients = Collections.synchronizedList(new ArrayList<>());
    private GameServer server;
    private TcpTestClient a, b;
    private GameRoom room;

    @Before public void start() throws Exception {
        server = new GameServer(() -> new FixedTickScheduler(executor, Logger.getLogger("two-client-e2e")), clock::get);
        server.start(); assertTrue(server.isRunning()); assertTrue(server.getBoundPort() > 0);
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        Callable<TcpTestClient> connect = () -> {
            assertTrue(start.await(5, TimeUnit.SECONDS)); var client = client(); client.connect(); return client;
        };
        try {
            var first = pool.submit(connect); var second = pool.submit(connect); start.countDown();
            a = first.get(5, TimeUnit.SECONDS); b = second.get(5, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
        var created = a.request(MessageType.CREATE_ROOM, ProtocolV1.object().put("roomId", "arena"));
        assertEquals(MessageType.ROOM_CREATED, created.type());
        room = server.getRoomManager().getRoom(created.payload().get("roomId").textValue());
        assertNotNull(room); // Fixture không còn tạo room nội bộ; E2E dùng CREATE_ROOM thật.
    }
    @After public void cleanup() throws Exception {
        try { if (server != null) { server.stop(); } }
        finally { for (var client : clients) { client.close(); } }
    }
    private TcpTestClient client() throws Exception { var client = new TcpTestClient(server.getBoundPort()); clients.add(client); return client; }
    private void join(TcpTestClient client, String id) throws Exception {
        var response = client.request(MessageType.JOIN_ROOM, ProtocolV1.object().put("roomId", id));
        assertEquals(MessageType.ROOM_STATE, response.type()); assertEquals(id, response.payload().get("roomId").textValue());
    }
    private void ready(TcpTestClient client, String character) throws Exception {
        assertEquals(MessageType.ROOM_STATE, client.request(MessageType.PLAYER_READY,
                ProtocolV1.object().put("characterId", character).put("ready", true)).type());
    }
    private void joinPair() throws Exception { join(a, "arena"); join(b, "arena"); }
    private void startMatch() throws Exception {
        joinPair(); ready(a, "Alex"); ready(b, "Leo"); executor.runCycle();
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase()); assertEquals(3000, room.getCountdownRemainingMillis());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(3)); executor.runCycle();
        assertEquals(RoomPhase.PLAYING, room.getPhase()); assertNotNull(room.getMatchRuntime());
        assertEquals(1, runtime().getMatchState().getServerTick());
        for (var client : List.of(a, b)) { assertEquals(runtime().getMatchId(), client.awaitType(MessageType.GAME_START).payload().get("matchId").textValue()); }
    }
    private MatchRuntime runtime() { return room.getMatchRuntime(); }
    private void cycles(int count) {
        for (int i = 0; i < count; i++) {
            var before = server.getRoomManager().getRooms().stream().map(GameRoom::getMatchRuntime).filter(Objects::nonNull)
                    .filter(r -> !r.isFinished()).collect(java.util.stream.Collectors.toMap(r -> r, r -> r.getMatchState().getServerTick()));
            executor.runCycle(); before.forEach((runtime, tick) -> assertEquals(tick + 1, runtime.getMatchState().getServerTick()));
        }
    }
    /** Quan sát queue private đã tồn tại, chỉ đọc; không submit/drain hoặc thêm API production. */
    private int pendingInputs() throws Exception {
        var field = MatchRuntime.class.getDeclaredField("inputQueue"); field.setAccessible(true);
        return ((InputQueue) field.get(runtime())).getPendingCount();
    }
    /** Chỉ test owner đọc Body thật khi scheduler manual đang đứng yên; không step/mutate JNI. */
    private Map<PlayerId, List<Float>> nativeMotion() throws Exception {
        var field = MatchRuntime.class.getDeclaredField("physicsWorld"); field.setAccessible(true);
        var physics = (com.smashprofs.server.game.physics.HeadlessPhysicsWorld) field.get(runtime());
        var bodies = new com.badlogic.gdx.utils.Array<com.badlogic.gdx.physics.box2d.Body>();
        physics.getWorld().getBodies(bodies); var result = new HashMap<PlayerId, List<Float>>();
        for (var body : bodies) {
            if (body.getUserData() instanceof PlayerId id) {
                result.put(id, List.of(body.getPosition().x, body.getPosition().y,
                        body.getLinearVelocity().x, body.getLinearVelocity().y));
            }
        }
        return Map.copyOf(result);
    }
    private ClientSession session(TcpTestClient client) {
        return server.getConnectionManager().getSessions().stream().filter(s -> client.playerId().equals(s.getPlayerId())).findFirst().orElseThrow();
    }
    private JsonNode player(ProtocolMessage state, PlayerId id) {
        for (var player : state.payload().get("players")) { if (id.value().equals(player.get("playerId").textValue())) { return player; } }
        throw new AssertionError("Thiếu PlayerId trong wire snapshot: " + id);
    }
    private ProtocolMessage sameState() throws Exception {
        var first = a.awaitType(MessageType.GAME_STATE); var second = b.awaitType(MessageType.GAME_STATE); assertEquals(first, second);
        var snapshot = runtime().getLatestSnapshot(); var payload = first.payload();
        assertEquals(1, first.version()); assertNull(first.requestId()); assertEquals(snapshot.matchId(), payload.get("matchId").textValue());
        assertEquals("arena", payload.get("roomId").textValue()); assertEquals(snapshot.serverTick(), payload.get("serverTick").longValue());
        assertEquals(2, payload.get("players").size());
        for (var p : snapshot.players()) {
            var wire = player(first, p.playerId()); assertEquals(p.slotIndex(), wire.get("slotIndex").intValue());
            assertEquals(p.characterId(), wire.get("characterId").textValue()); assertEquals(p.x(), wire.get("x").doubleValue(), EPS);
            assertEquals(p.y(), wire.get("y").doubleValue(), EPS); assertEquals(p.velocityX(), wire.get("velocityX").doubleValue(), EPS);
            assertEquals(p.hp(), wire.get("hp").doubleValue(), EPS);
        }
        for (var entry : nativeMotion().entrySet()) {
            var wire = player(first, entry.getKey()); var motion = entry.getValue();
            assertEquals(motion.get(0), wire.get("x").doubleValue(), EPS); assertEquals(motion.get(1), wire.get("y").doubleValue(), EPS);
            assertEquals(motion.get(2), wire.get("velocityX").doubleValue(), EPS); assertEquals(motion.get(3), wire.get("velocityY").doubleValue(), EPS);
        }
        return first;
    }
    private void prepareAirborneB() throws Exception {
        if (runtime().getJumpState(b.playerId()).grounded()) {
            b.inputAndBarrier(0, 0, true, false, false); cycles(1);
        }
        assertFalse(runtime().getJumpState(b.playerId()).grounded());
    }
    private void consumeCadenceStateIfPresent() throws Exception {
        if (!runtime().isFinished() && runtime().getMatchState().getServerTick() % 3 == 0) { sameState(); }
    }
    private void assertTerminal(AuthoritativeMatchResult result) throws Exception {
        assertNotNull(result); assertEquals(RoomPhase.FINISHED, room.getPhase()); var finalState = sameState();
        assertEquals("FINISHED", finalState.payload().get("phase").textValue());
        var first = a.awaitType(MessageType.GAME_END); var second = b.awaitType(MessageType.GAME_END); assertEquals(first, second);
        var p = first.payload(); assertEquals(1, first.version()); assertNull(first.requestId());
        assertEquals(result.matchId(), p.get("matchId").textValue()); assertEquals(result.roomId(), p.get("roomId").textValue());
        assertEquals(result.finishedAtTick(), p.get("finishedAtTick").longValue()); assertEquals(result.finishReason().name(), p.get("finishReason").textValue());
        if (result.winnerId() == null) { assertTrue(p.get("winnerId").isNull()); }
        else { assertEquals(result.winnerId().value(), p.get("winnerId").textValue()); }
        assertEquals(2, p.get("participants").size());
        for (var participant : result.participants()) {
            var wire = p.get("participants").get(participant.slotIndex()); assertEquals(participant.playerId().value(), wire.get("playerId").textValue());
            assertEquals(participant.slotIndex(), wire.get("slotIndex").intValue()); assertEquals(participant.characterId(), wire.get("characterId").textValue());
            assertEquals(participant.finalHp(), wire.get("finalHp").doubleValue(), EPS); assertEquals(participant.eliminated(), wire.get("eliminated").booleanValue());
        }
        for (var client : List.of(a, b)) {
            var history = client.received(); int index = history.indexOf(first); assertTrue(index > 0);
            assertEquals(MessageType.GAME_STATE, history.get(index - 1).type());
            assertEquals("FINISHED", history.get(index - 1).payload().get("phase").textValue());
            assertEquals(1, history.stream().filter(m -> m.type() == MessageType.GAME_END).count());
        }
        var snapshot = runtime().getLatestSnapshot(); cycles(9); assertSame(result, runtime().getMatchResult());
        assertSame(snapshot, runtime().getLatestSnapshot()); a.assertQuiet(); b.assertQuiet();
    }

    @Test(timeout=20000) public void twoClientsConnectWithUniquePlayerIdsAndJoinSameRoom() throws Exception {
        assertNotEquals(a.playerId(), b.playerId()); UUID.fromString(a.playerId().value()); UUID.fromString(b.playerId().value());
        assertEquals(2, server.getConnectionManager().size()); assertNotEquals(session(a).getSessionId(), session(b).getSessionId());
        joinPair(); assertEquals(2, room.getPlayerCount()); assertEquals(Set.of(a.playerId(), b.playerId()),
                new HashSet<>(room.getPlayerSlots().stream().map(s -> s.playerId()).toList()));
        assertNotEquals(room.getPlayerSlot(a.playerId()).slotIndex(), room.getPlayerSlot(b.playerId()).slotIndex());
        assertEquals("arena", session(a).getRoomId()); assertEquals("arena", session(b).getRoomId());
    }
    @Test(timeout=20000) public void twoClientsReadyAndStartExactlyOneMatchAfterFullCountdown() throws Exception {
        joinPair(); ready(a, "Alex"); executor.runCycle(); assertEquals(RoomPhase.WAITING, room.getPhase()); assertNull(runtime());
        ready(b, "Leo"); assertEquals("Alex", room.getSelectedCharacter(a.playerId())); assertEquals("Leo", room.getSelectedCharacter(b.playerId()));
        assertTrue(room.isPlayerReady(a.playerId())); assertTrue(room.isPlayerReady(b.playerId())); executor.runCycle();
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase()); clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(2999)); executor.runCycle();
        assertEquals(1, room.getCountdownRemainingMillis()); assertNull(runtime());
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1)); executor.runCycle(); var match = runtime();
        assertEquals(RoomPhase.PLAYING, room.getPhase()); assertEquals(1, executor.getScheduleCount()); cycles(2); sameState();
        assertSame(match, runtime()); assertEquals(2, match.getParticipants().size());
        var start = a.awaitType(MessageType.GAME_START); assertEquals(match.getMatchId(), start.payload().get("matchId").textValue());
        b.awaitType(MessageType.GAME_START);
        assertEquals(1, a.received().stream().filter(m -> m.type() == MessageType.GAME_START).count());
    }
    @Test(timeout=20000) public void completeMatchThroughTcpMovementStompFractionalHpAndProductionTimeout() throws Exception {
        startMatch(); prepareAirborneB(); consumeCadenceStateIfPresent(); var initial = runtime().getLatestSnapshot();
        var initialA = initial.players().stream().filter(p -> p.playerId().equals(a.playerId())).findFirst().orElseThrow();
        var initialB = initial.players().stream().filter(p -> p.playerId().equals(b.playerId())).findFirst().orElseThrow();
        var bodiesBeforeInput = nativeMotion();
        a.inputAndBarrier(1, 1, false, false, false); b.inputAndBarrier(1, -1, false, true, false);
        assertEquals(2, pendingInputs()); assertSame(initial, runtime().getLatestSnapshot()); // read worker chưa tick/step.
        assertEquals(bodiesBeforeInput, nativeMotion()); // Không có World.step phụ trên đường TCP/dispatch.
        cycles(1); assertEquals(0, pendingInputs());
        while (runtime().getMatchState().getServerTick() % 3 != 0) { cycles(1); }
        var state = sameState(); var saved = runtime().getLatestSnapshot();
        assertTrue(player(state, a.playerId()).get("x").doubleValue() > initialA.x());
        assertTrue(player(state, b.playerId()).get("x").doubleValue() < initialB.x());
        assertEquals(100f - 0.1f, player(state, b.playerId()).get("hp").doubleValue(), EPS);
        assertTrue(player(state, b.playerId()).get("hp").isFloatingPointNumber());
        assertThrows(UnsupportedOperationException.class, () -> saved.players().clear());
        cycles(3); var next = sameState(); assertTrue(next.payload().get("serverTick").longValue() > state.payload().get("serverTick").longValue());
        assertEquals(saved.serverTick(), state.payload().get("serverTick").longValue());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(120)); cycles(1); var result = runtime().getMatchResult();
        assertEquals(a.playerId(), result.winnerId()); assertEquals(AuthoritativeMatchResult.FinishReason.TIME_LIMIT, result.finishReason());
        assertTerminal(result);
    }
    @Test(timeout=20000) public void duplicateAndImpersonatedInputsCannotControlOtherPlayer() throws Exception {
        startMatch(); var before = runtime().getLatestSnapshot(); a.inputAndBarrier(1, 1, false, false, false);
        var duplicate = a.request(MessageType.PLAYER_INPUT, a.input(1, -1, false, false, false));
        assertEquals(MessageType.ERROR, duplicate.type()); assertEquals("DUPLICATE_INPUT", duplicate.payload().get("code").textValue());
        var spoof = a.request(MessageType.PLAYER_INPUT, a.input(2, -1, false, false, false).put("playerId", b.playerId().value()));
        assertEquals(MessageType.ERROR, spoof.type()); assertEquals("UNAUTHORIZED_PLAYER", spoof.payload().get("code").textValue());
        assertEquals(1, pendingInputs()); assertSame(before, runtime().getLatestSnapshot()); cycles(2);
        var state = sameState(); assertTrue(player(state, a.playerId()).get("velocityX").doubleValue() > 0);
        assertEquals(0, player(state, b.playerId()).get("velocityX").doubleValue(), EPS);
        assertEquals(0, pendingInputs()); assertFalse(runtime().isFinished());
    }
    @Test(timeout=20000) public void lowHpServerFixtureFinishesThroughRealTcpStompAndProductionDeathEvaluator() throws Exception {
        startMatch(); prepareAirborneB(); consumeCadenceStateIfPresent();
        // Fixture chỉ giảm HP khi player còn sống; death/result vẫn phải do input stomp + production tick tạo.
        runtime().getMatchState().getPlayer(b.playerId()).applyDamage(99.95f);
        assertFalse(runtime().getMatchState().getPlayer(b.playerId()).isDead()); assertNull(runtime().getMatchResult());
        b.inputAndBarrier(1, 0, false, true, false); assertEquals(1, pendingInputs()); cycles(1);
        var result = runtime().getMatchResult(); assertNotNull(result); assertEquals(a.playerId(), result.winnerId());
        assertEquals(AuthoritativeMatchResult.FinishReason.LAST_PLAYER_STANDING, result.finishReason());
        assertTrue(runtime().getMatchState().getPlayer(b.playerId()).isDead()); assertTerminal(result);
    }
    @Test(timeout=20000) public void unjoinedAndOtherRoomClientsReceiveNoForeignStateDamageOrEnd() throws Exception {
        var outsider = client(); outsider.connect(); var c = client(); c.connect(); var d = client(); d.connect();
        var otherRoom = server.getRoomManager().getOrCreateRoom("other"); join(c, "other"); join(d, "other"); ready(c, "Viktor"); ready(d, "Maurice");
        startMatch(); prepareAirborneB(); consumeCadenceStateIfPresent();
        c.awaitType(MessageType.GAME_START); d.awaitType(MessageType.GAME_START);
        runtime().getMatchState().getPlayer(b.playerId()).applyDamage(99.95f); b.inputAndBarrier(1, 0, false, true, false); cycles(1);
        assertTerminal(runtime().getMatchResult());
        // assertTerminal chạy thêm cycle: phòng B vẫn PLAYING, có snapshots của chính nó (latest-wins).
        assertEquals(RoomPhase.PLAYING, otherRoom.getPhase()); assertNull(otherRoom.getMatchRuntime().getMatchResult());
        for (var client : List.of(c, d)) {
            var state = client.awaitType(MessageType.GAME_STATE); assertEquals("other", state.payload().get("roomId").textValue());
            for (var p : state.payload().get("players")) { assertTrue(Set.of(c.playerId().value(), d.playerId().value()).contains(p.get("playerId").textValue())); assertEquals(100, p.get("hp").doubleValue(), EPS); }
            client.barrier(); assertFalse(client.received().stream().anyMatch(m -> m.type() == MessageType.GAME_END
                    || (m.type() == MessageType.GAME_STATE && !"other".equals(m.payload().get("roomId").textValue()))));
        }
        outsider.assertQuiet();
    }
    @Test(timeout=20000) public void closingClientDoesNotCrashMatchAndShutdownReleasesSocketsAndWorkers() throws Exception {
        startMatch(); var sessions = server.getConnectionManager().getSessions();
        var workers = Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().startsWith("smashprofs-tcp-")).toList();
        assertFalse(workers.isEmpty()); a.close(); b.inputAndBarrier(1, -1, false, false, false); cycles(2);
        assertEquals(MessageType.GAME_STATE, b.awaitType(MessageType.GAME_STATE).type()); assertTrue(server.isRunning());
        assertNull(server.getAcceptFailure()); assertEquals(2, room.getPlayerCount()); var match = runtime(); server.stop();
        assertFalse(server.isRunning()); assertEquals(-1, server.getBoundPort()); assertEquals(0, server.getConnectionManager().size());
        assertEquals(0, server.getRoomManager().getRoomCount()); assertTrue(executor.isTerminated()); assertTrue(match.isClosed());
        assertNull(match.getMatchResult()); assertTrue(sessions.stream().noneMatch(ClientSession::isConnected)); b.assertEof();
        for (var worker : workers) {
            worker.join(5000); // Pool đã terminated; chờ hữu hạn wrapper Thread thoát finally, không sleep.
            assertFalse("Worker còn sống: " + worker.getName(), worker.isAlive());
        }
    }
    @Test(timeout=20000) public void createRoomDoesNotImplyMembershipOrStart() throws Exception {
        var response = a.request(MessageType.CREATE_ROOM, ProtocolV1.object().put("roomId", "new-room"));
        assertEquals(MessageType.ROOM_CREATED, response.type()); var created = server.getRoomManager().getRoom("new-room");
        assertNotNull(created); assertEquals(0, created.getPlayerCount()); assertEquals(RoomPhase.WAITING, created.getPhase());
        assertNull(session(a).getRoomId()); assertNull(created.getMatchRuntime());
    }
}
