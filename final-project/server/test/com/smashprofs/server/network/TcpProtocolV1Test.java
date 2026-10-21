package com.smashprofs.server.network;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.protocol.*;
import com.smashprofs.server.game.MatchRuntime;
import com.smashprofs.server.room.GameRoom;
import com.smashprofs.server.room.RoomManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Socket loopback thật. Harness không scheduler để kiểm tra chính xác input queue, không sleep. */
public class TcpProtocolV1Test {
    private final JsonFrameCodec codec = new JsonFrameCodec();
    private final List<Socket> clients = new ArrayList<>();
    private ConnectionManager connections;
    private RoomManager rooms;
    private TcpServerTransport transport;
    private BlockingQueue<ClientSession> closed;
    @Before public void setUp() {
        connections = new ConnectionManager(); rooms = new RoomManager(); closed = new LinkedBlockingQueue<>();
        transport = new TcpServerTransport("127.0.0.1", 0, 8, connections,
                task -> new Thread(task, "test-tcp-accept"), session -> { },
                new TcpIoConfiguration(JsonTcpMessageCodec::new, MessageDispatcher.protocolV1(connections, rooms),
                        ProtocolV1.MAX_FRAME_BYTES, 5000), closed::add);
        transport.bind(); transport.startAccepting();
    }
    @After public void tearDown() throws Exception {
        try { transport.close(); } finally {
            try { rooms.shutdown(); } finally { for (Socket socket : clients) { socket.close(); } }
        }
    }
    private Socket client() throws Exception {
        Socket socket = new Socket("127.0.0.1", transport.getBoundPort()); socket.setSoTimeout(5000); clients.add(socket); return socket;
    }
    private void send(Socket socket, ProtocolMessage message) throws Exception {
        socket.getOutputStream().write(codec.encode(message)); socket.getOutputStream().flush();
    }
    private ProtocolMessage read(Socket socket) throws Exception {
        ProtocolMessage message = codec.readNext(socket.getInputStream()); assertNotNull(message); return message;
    }
    private PlayerId connect(Socket socket) throws Exception {
        send(socket, new ProtocolMessage(MessageType.CONNECT, "connect", ProtocolV1.object()));
        var response = read(socket); assertEquals(MessageType.CONNECT_ACK, response.type()); assertEquals("connect", response.requestId());
        return new PlayerId(response.payload().get("playerId").textValue());
    }
    private ProtocolMessage join(Socket socket) throws Exception {
        send(socket, new ProtocolMessage(MessageType.JOIN_ROOM, "join", ProtocolV1.object().put("roomId", "arena")));
        var response = read(socket); assertEquals("join", response.requestId()); return response;
    }
    private ProtocolMessage ready(Socket socket, String character) throws Exception {
        send(socket, new ProtocolMessage(MessageType.PLAYER_READY, "ready", ProtocolV1.object().put("characterId", character).put("ready", true)));
        return read(socket);
    }
    private void error(Socket socket, String code, String requestId) throws Exception {
        var response = read(socket); assertEquals(MessageType.ERROR, response.type()); assertEquals(requestId, response.requestId());
        assertEquals(code, response.payload().get("code").textValue()); assertFalse(response.payload().has("stackTrace"));
    }
    private byte[] frame(String json) { return frame(json.getBytes(StandardCharsets.UTF_8)); }
    private byte[] frame(byte[] json) { return ByteBuffer.allocate(json.length + 4).putInt(json.length).put(json).array(); }
    @Test public void connectCreatesServerOwnedUniqueIdentityAndIsIdempotent() throws Exception {
        Socket a = client(); Socket b = client(); PlayerId first = connect(a); PlayerId second = connect(b);
        assertNotEquals(first, second); UUID.fromString(first.value()); assertEquals(first, connect(a));
        assertEquals(Set.of(first, second), new HashSet<>(connections.getSessions().stream().map(ClientSession::getPlayerId).toList()));
    }
    @Test public void defaultGameServerEnablesOfficialCodec() throws Exception {
        GameServer server = new GameServer(); server.start();
        try (Socket socket = new Socket("127.0.0.1", server.getBoundPort())) {
            socket.setSoTimeout(5000); assertNotNull(connect(socket)); assertEquals(MessageType.ROOM_STATE, join(socket).type());
        } finally { server.stop(); }
    }
    @Test public void joinRoomOverTcpBindsOwningSessionAndReturnsRoster() throws Exception {
        Socket socket = client(); PlayerId player = connect(socket); var state = join(socket);
        assertEquals(MessageType.ROOM_STATE, state.type()); assertEquals("arena", state.payload().get("roomId").textValue());
        assertEquals(player.value(), state.payload().get("players").get(0).get("playerId").textValue());
        assertTrue(rooms.getRoom("arena").containsPlayer(player)); assertEquals("arena", connections.getSessions().get(0).getRoomId());
    }
    @Test public void readyOverTcpUsesExistingSelectionAndReadyHandler() throws Exception {
        Socket socket = client(); var player = connect(socket); join(socket); var state = ready(socket, "Alex");
        assertEquals(MessageType.ROOM_STATE, state.type()); assertEquals("ready", state.requestId());
        assertTrue(rooms.getRoom("arena").isPlayerReady(player)); assertEquals("Alex", rooms.getRoom("arena").getSelectedCharacter(player));
    }
    @Test public void requestBeforeConnectIsRejectedAndConnectionRemainsUsable() throws Exception {
        Socket socket = client(); assertEquals("INVALID_SESSION", join(socket).payload().get("code").textValue()); assertNotNull(connect(socket));
    }
    @Test public void partialHeaderAndBodyWaitForCompleteFrame() throws Exception {
        Socket socket = client(); byte[] bytes = codec.encode(new ProtocolMessage(MessageType.CONNECT, "part", ProtocolV1.object()));
        socket.getOutputStream().write(bytes, 0, 2); socket.setSoTimeout(100);
        assertThrows(SocketTimeoutException.class, () -> socket.getInputStream().read());
        socket.getOutputStream().write(bytes, 2, 9);
        assertThrows(SocketTimeoutException.class, () -> socket.getInputStream().read());
        socket.getOutputStream().write(bytes, 11, bytes.length - 11); socket.setSoTimeout(5000);
        assertEquals(MessageType.CONNECT_ACK, read(socket).type());
    }
    @Test public void multipleFramesInSingleWritePreserveOrderAndCorrelation() throws Exception {
        Socket socket = client(); var bytes = new ByteArrayOutputStream();
        bytes.write(codec.encode(new ProtocolMessage(MessageType.CONNECT, "one", ProtocolV1.object())));
        bytes.write(codec.encode(new ProtocolMessage(MessageType.JOIN_ROOM, "two", ProtocolV1.object().put("roomId", "arena"))));
        socket.getOutputStream().write(bytes.toByteArray());
        assertEquals("one", read(socket).requestId()); assertEquals("two", read(socket).requestId());
    }
    @Test public void utf8UnicodeRequestIdIsPreserved() throws Exception {
        Socket socket = client(); String id = "yêu-cầu-🎮";
        send(socket, new ProtocolMessage(MessageType.CONNECT, id, ProtocolV1.object())); assertEquals(id, read(socket).requestId());
    }
    @Test public void malformedJsonReturnsErrorAndNextFrameStillWorks() throws Exception {
        Socket socket = client(); socket.getOutputStream().write(frame("{")); error(socket, "INVALID_PAYLOAD", null); assertNotNull(connect(socket));
    }
    @Test public void duplicateJsonKeysAreRejected() throws Exception {
        Socket socket = client(); socket.getOutputStream().write(frame("{\"version\":1,\"version\":1,\"type\":\"CONNECT\",\"requestId\":\"x\",\"payload\":{}}"));
        error(socket, "INVALID_PAYLOAD", null); assertNotNull(connect(socket));
    }
    @Test public void unsupportedVersionReturnsCorrelatedError() throws Exception {
        Socket socket = client(); socket.getOutputStream().write(frame("{\"version\":2,\"type\":\"CONNECT\",\"requestId\":\"v\",\"payload\":{}}"));
        error(socket, "UNSUPPORTED_VERSION", "v"); assertNotNull(connect(socket));
    }
    @Test public void unknownTypeReturnsCorrelatedError() throws Exception {
        Socket socket = client(); socket.getOutputStream().write(frame("{\"version\":1,\"type\":\"NOPE\",\"requestId\":\"u\",\"payload\":{}}"));
        error(socket, "UNKNOWN_MESSAGE_TYPE", "u"); assertNotNull(connect(socket));
    }
    @Test public void oversizedHeaderClosesOnlyOffendingConnection() throws Exception {
        Socket bad = client(); Socket good = client(); connect(bad); connect(good);
        bad.getOutputStream().write(ByteBuffer.allocate(4).putInt(ProtocolV1.MAX_PAYLOAD_BYTES + 1).array());
        assertEquals(-1, bad.getInputStream().read()); assertNotNull(closed.poll(5, TimeUnit.SECONDS)); assertNotNull(connect(good));
        assertEquals(1, connections.size());
    }
    @Test public void exactMaximumPayloadFitsTransportBudgetIncludingHeader() throws Exception {
        Socket socket = client();
        byte[] body = new byte[ProtocolV1.MAX_PAYLOAD_BYTES]; Arrays.fill(body, (byte)' ');
        byte[] json = "{\"version\":1,\"type\":\"CONNECT\",\"requestId\":\"max\",\"payload\":{}}".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(json, 0, body, 0, json.length);
        socket.getOutputStream().write(frame(body));
        assertEquals("max", read(socket).requestId()); assertNotNull(connect(socket));
    }
    @Test public void invalidSignedLengthsAreFatal() throws Exception {
        for (int length : new int[]{0, -1, Integer.MIN_VALUE}) {
            Socket socket = client(); connect(socket); socket.getOutputStream().write(ByteBuffer.allocate(4).putInt(length).array());
            assertEquals(-1, socket.getInputStream().read()); assertNotNull(closed.poll(5, TimeUnit.SECONDS));
        }
    }
    @Test public void littleEndianLengthIsNotMisinterpretedAsBigEndian() throws Exception {
        Socket socket = client(); connect(socket); socket.getOutputStream().write(new byte[]{60, 0, 0, 0});
        assertEquals(-1, socket.getInputStream().read()); assertNotNull(closed.poll(5, TimeUnit.SECONDS));
    }
    @Test public void truncatedBodyAndHeaderCloseAfterEof() throws Exception {
        for (byte[] bytes : List.of(new byte[]{0,0}, new byte[]{0,0,0,10,'{'})) {
            Socket socket = client(); connect(socket); socket.getOutputStream().write(bytes); socket.shutdownOutput();
            assertEquals(-1, socket.getInputStream().read()); assertNotNull(closed.poll(5, TimeUnit.SECONDS));
        }
    }
    @Test public void invalidUtf8ReturnsApplicationError() throws Exception {
        Socket socket = client(); socket.getOutputStream().write(frame(new byte[]{(byte)0xc3,0x28}));
        error(socket, "INVALID_PAYLOAD", null); assertNotNull(connect(socket));
    }
    @Test public void malformedApplicationPayloadDoesNotChangeRoomOrCrashReader() throws Exception {
        Socket socket = client(); PlayerId player = connect(socket); join(socket);
        send(socket, new ProtocolMessage(MessageType.PLAYER_READY, "bad", ProtocolV1.object().put("ready", "true")));
        error(socket, "INVALID_PAYLOAD", "bad"); assertFalse(rooms.getRoom("arena").isPlayerReady(player)); assertEquals(player, connect(socket));
    }
    @Test public void playerInputReachesExistingQueueWithTrustedIdentityAndDuplicateProtection() throws Exception {
        exerciseInput(false);
    }
    @Test public void clientCannotImpersonateAnotherPlayer() throws Exception { exerciseInput(true); }
    private void exerciseInput(boolean impersonate) throws Exception {
        // Dùng RoomManager với manual clock và scheduler không start; transport vẫn là Socket thật.
        transport.close(); rooms.shutdown();
        var clock = new java.util.concurrent.atomic.AtomicLong(10_000_000_000L);
        var scheduler = new com.smashprofs.server.game.FixedTickScheduler();
        rooms = new RoomManager(scheduler, clock::get);
        transport = new TcpServerTransport("127.0.0.1", 0, 8, connections,
                task -> new Thread(task, "test-tcp-accept"), session -> { },
                new TcpIoConfiguration(JsonTcpMessageCodec::new, MessageDispatcher.protocolV1(connections, rooms),
                        ProtocolV1.MAX_FRAME_BYTES, 5000), closed::add);
        transport.bind(); transport.startAccepting();
        try {
            Socket a = client(); Socket b = client(); var first = connect(a); var second = connect(b);
            join(a); join(b); ready(a, "Alex"); ready(b, "Leo"); GameRoom room = rooms.getRoom("arena");
            assertTrue(room.startCountdown()); clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
            assertTrue(room.updateCountdown());
            assertNotNull(room.prepareMatch()); MatchRuntime runtime = room.startMatch(); assertNotNull(runtime);
            var payload = ProtocolV1.object().put("sequence", 0).put("clientTick", 8).put("moveAxis", 1)
                    .put("jumpPressed", true).put("stompPressed", false).put("meleePressed", false)
                    .put("abilityPressed", false).put("blockHeld", true);
            if (impersonate) {
                send(a, new ProtocolMessage(MessageType.PLAYER_INPUT, "spoof", payload.deepCopy().put("playerId", second.value())));
                error(a, "UNAUTHORIZED_PLAYER", "spoof"); assertTrue(runtime.tick().isEmpty());
                send(a, new ProtocolMessage(MessageType.CONNECT, "fake", ProtocolV1.object().put("playerId", second.value())));
                error(a, "UNAUTHORIZED_PLAYER", "fake"); assertEquals(first, connect(a));
            }
            send(a, new ProtocolMessage(MessageType.PLAYER_INPUT, null, payload));
            assertEquals(first, connect(a)); // ACK CONNECT cùng stream chứng minh input trước đó đã enqueue.
            var batch = runtime.tick(); assertEquals(1, batch.size()); assertEquals(first, batch.get(0).playerId());
            assertTrue(batch.get(0).jumpPressed()); assertTrue(batch.get(0).blockHeld()); assertEquals(8, batch.get(0).clientTick());
            send(a, new ProtocolMessage(MessageType.PLAYER_INPUT, "dup", payload)); error(a, "DUPLICATE_INPUT", "dup");
            assertTrue(runtime.tick().isEmpty()); assertEquals(first, connect(a));
        } finally { rooms.shutdown(); scheduler.stop(); }
    }
    @Test public void twoToFourClientsCanConnectAndJoinConcurrentlyWithoutSharingIdentityOrSlots() throws Exception {
        for (int count = 2; count <= 4; count++) {
            List<Socket> sockets = new ArrayList<>(); for (int i = 0; i < count; i++) { sockets.add(client()); }
            ExecutorService workers = Executors.newFixedThreadPool(count); CountDownLatch start = new CountDownLatch(1);
            List<Future<PlayerId>> results = new ArrayList<>(); String roomId = "multi" + count;
            try {
                for (Socket socket : sockets) {
                    results.add(workers.submit(() -> {
                        assertTrue(start.await(5, TimeUnit.SECONDS)); var id = connect(socket);
                        send(socket, new ProtocolMessage(MessageType.JOIN_ROOM, "join", ProtocolV1.object().put("roomId", roomId)));
                        assertEquals(MessageType.ROOM_STATE, read(socket).type()); return id;
                    }));
                }
                start.countDown(); Set<PlayerId> identities = new HashSet<>();
                for (var result : results) { identities.add(result.get(5, TimeUnit.SECONDS)); }
                assertEquals(count, identities.size()); var room = rooms.getRoom(roomId); assertEquals(count, room.getPlayerCount());
                assertEquals(count, room.getPlayerSlots().stream().map(slot -> slot.slotIndex()).distinct().count());
            } finally {
                start.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
                for (Socket socket : sockets) { socket.close(); assertNotNull(closed.poll(5, TimeUnit.SECONDS)); }
            }
        }
    }
    @Test public void fifthPlayerGetsRoomFull() throws Exception {
        for (int i = 0; i < 5; i++) {
            Socket socket = client(); connect(socket); var response = join(socket);
            assertEquals(i < 4 ? MessageType.ROOM_STATE : MessageType.ERROR, response.type());
            if (i == 4) { assertEquals("ROOM_FULL", response.payload().get("code").textValue()); }
        }
    }
    @Test public void definedButUnimplementedRequestsReturnExplicitError() throws Exception {
        Socket socket = client(); connect(socket);
        send(socket, new ProtocolMessage(MessageType.LEAVE_ROOM, "later", ProtocolV1.object())); error(socket, "NOT_IMPLEMENTED", "later");
        send(socket, new ProtocolMessage(MessageType.PING, "probe", ProtocolV1.object()));
        var pong = read(socket); assertEquals(MessageType.PONG, pong.type()); assertEquals("probe", pong.requestId());
        send(socket, new ProtocolMessage(MessageType.PONG, "later", ProtocolV1.object())); error(socket, "INVALID_PAYLOAD", "later");
    }
    @Test public void serverPushCannotBeUsedAsClientCommand() throws Exception {
        Socket socket = client(); connect(socket); send(socket, new ProtocolMessage(MessageType.GAME_STATE, "push", ProtocolV1.object()));
        error(socket, "INVALID_PAYLOAD", "push"); assertEquals(0, rooms.getRoomCount());
    }
    @Test public void disconnectAndShutdownReleaseSessionsAndReadWorkers() throws Exception {
        Socket a = client(); Socket b = client(); connect(a); connect(b); a.close();
        assertNotNull(closed.poll(5, TimeUnit.SECONDS)); assertEquals(1, connections.size());
        transport.close(); assertEquals(-1, b.getInputStream().read()); assertEquals(0, connections.size()); assertTrue(transport.areReadersTerminated());
    }
    @Test public void concurrentWritesRemainWholeJsonFrames() throws Exception {
        Socket socket = client(); PlayerId id = connect(socket);
        ClientSession session = connections.getSessions().stream().filter(s -> id.equals(s.getPlayerId())).findFirst().orElseThrow();
        ExecutorService writers = Executors.newFixedThreadPool(4); CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) { int index = i; futures.add(writers.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                for (int n = 0; n < 10; n++) { session.send(ProtocolV1.error(index + "-" + n, "INVALID_PAYLOAD", "test")); }
                return null;
            })); }
            start.countDown(); Set<String> ids = new HashSet<>(); for (int i = 0; i < 40; i++) { ids.add(read(socket).requestId()); }
            assertEquals(40, ids.size()); for (var future : futures) { future.get(5, TimeUnit.SECONDS); }
        } finally { start.countDown(); writers.shutdownNow(); assertTrue(writers.awaitTermination(5, TimeUnit.SECONDS)); }
    }
}
