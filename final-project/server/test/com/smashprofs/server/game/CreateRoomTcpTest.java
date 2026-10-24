package com.smashprofs.server.game;

import com.smashprofs.common.protocol.*;
import com.smashprofs.server.network.GameServer;
import com.smashprofs.server.room.RoomPhase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import static org.junit.Assert.*;

/** CREATE_ROOM/ROOM_CREATED qua TCP v1 thật; không thêm scheduler, codec hoặc helper production. */
public class CreateRoomTcpTest {
    private final ManualScheduledExecutor executor = new ManualScheduledExecutor();
    private final List<TcpTestClient> clients = new ArrayList<>();
    private GameServer server;
    private TcpTestClient a, b;
    @Before public void start() throws Exception {
        var clock = new AtomicLong(10_000_000_000L);
        server = new GameServer(() -> new FixedTickScheduler(executor, Logger.getLogger("create-room-test")), clock::get);
        server.start(); a = client(true); b = client(true);
    }
    @After public void stop() throws Exception {
        try { server.stop(); } finally { for (var client : clients) { client.close(); } }
    }
    private TcpTestClient client(boolean connect) throws Exception {
        var client = new TcpTestClient(server.getBoundPort()); clients.add(client); if (connect) { client.connect(); } return client;
    }
    private ProtocolMessage create(TcpTestClient client, String id) throws Exception {
        return client.request(MessageType.CREATE_ROOM, ProtocolV1.object().put("roomId", id));
    }
    private void error(ProtocolMessage message, String code) {
        assertEquals(MessageType.ERROR, message.type()); assertEquals(code, message.payload().get("code").textValue());
        assertEquals(Set.of("code", "message"), fields(message.payload())); assertFalse(message.payload().get("message").textValue().contains("Exception"));
    }
    private Set<String> fields(com.fasterxml.jackson.databind.node.ObjectNode payload) {
        var result = new HashSet<String>(); payload.fieldNames().forEachRemaining(result::add); return result;
    }
    @Test public void connectedClientCanCreateRoomWithCorrelatedRoomCreated() throws Exception {
        var message = create(a, "Arena_A-09"); assertEquals(MessageType.ROOM_CREATED, message.type()); assertEquals(1, message.version());
        assertNotNull(message.requestId()); assertEquals("request-1", message.requestId());
        assertEquals(Set.of("roomId"), fields(message.payload())); assertEquals("Arena_A-09", message.payload().get("roomId").textValue());
        var room = server.getRoomManager().getRoom("Arena_A-09"); assertNotNull(room); assertEquals(1, server.getRoomManager().getRoomCount());
        assertEquals("Arena_A-09", room.getRoomId());
    }
    @Test public void creatorAndSecondClientExplicitlyJoinCreatedRoom() throws Exception {
        create(a, "arena"); var room = server.getRoomManager().getRoom("arena"); assertEquals(0, room.getPlayerCount());
        assertTrue(server.getConnectionManager().getSessions().stream().allMatch(s -> s.getRoomId() == null));
        for (var client : List.of(a, b)) {
            assertEquals(MessageType.ROOM_STATE, client.request(MessageType.JOIN_ROOM, ProtocolV1.object().put("roomId", "arena")).type());
        }
        assertEquals(2, room.getPlayerCount()); assertNotEquals(room.getPlayerSlot(a.playerId()).slotIndex(), room.getPlayerSlot(b.playerId()).slotIndex());
        assertTrue(server.getConnectionManager().getSessions().stream().allMatch(s -> "arena".equals(s.getRoomId())));
        assertFalse(room.isPlayerReady(a.playerId())); assertFalse(room.isPlayerReady(b.playerId()));
    }
    @Test public void invalidPayloadsAndCapacityFieldsDoNotLeakRooms() throws Exception {
        for (var payload : List.of(ProtocolV1.object(), ProtocolV1.object().putNull("roomId"), ProtocolV1.object().put("roomId", 42),
                ProtocolV1.object().put("roomId", "r").put("capacity", 5), ProtocolV1.object().put("roomId", "r").put("maxPlayers", 0))) {
            error(a.request(MessageType.CREATE_ROOM, payload), "INVALID_PAYLOAD");
        }
        for (String id : new String[]{"", " ", " r", "r ", "a/b", "é", "r".repeat(65)}) { error(create(a, id), "INVALID_PAYLOAD"); }
        error(a.request(MessageType.CREATE_ROOM, ProtocolV1.object().put("roomId", "r").put("playerId", b.playerId().value())), "UNAUTHORIZED_PLAYER");
        assertEquals(0, server.getRoomManager().getRoomCount()); assertEquals(MessageType.ROOM_CREATED, create(a, "valid").type());
    }
    @Test public void sessionWithoutConnectCannotCreateRoom() throws Exception {
        var unconnected = client(false); error(create(unconnected, "r"), "INVALID_SESSION");
        assertEquals(0, server.getRoomManager().getRoomCount()); unconnected.connect(); assertEquals(MessageType.ROOM_CREATED, create(unconnected, "r").type());
    }
    @Test public void duplicateCreationPreservesOriginalRoomAndMembership() throws Exception {
        create(a, "r"); a.request(MessageType.JOIN_ROOM, ProtocolV1.object().put("roomId", "r"));
        var original = server.getRoomManager().getRoom("r"); error(create(b, "r"), "INVALID_ROOM_STATE");
        error(create(a, "r"), "INVALID_ROOM_STATE"); assertSame(original, server.getRoomManager().getRoom("r"));
        assertEquals(1, original.getPlayerCount()); assertTrue(original.containsPlayer(a.playerId())); assertEquals(1, server.getRoomManager().getRoomCount());
    }
    @Test public void createDoesNotStartMatchOrAffectOtherRoomBinding() throws Exception {
        create(a, "first"); a.request(MessageType.JOIN_ROOM, ProtocolV1.object().put("roomId", "first"));
        var first = server.getRoomManager().getRoom("first"); create(a, "second");
        for (int i = 0; i < 10; i++) { executor.runCycle(); }
        var second = server.getRoomManager().getRoom("second"); assertEquals(RoomPhase.WAITING, second.getPhase());
        assertNull(second.getMatchRuntime()); assertEquals(0, second.getPlayerCount()); assertSame(first, server.getRoomManager().getRoom("first"));
        assertEquals(1, first.getPlayerCount()); assertTrue(server.getConnectionManager().getSessions().stream()
                .filter(s -> a.playerId().equals(s.getPlayerId())).allMatch(s -> "first".equals(s.getRoomId())));
        error(a.request(MessageType.JOIN_ROOM, ProtocolV1.object().put("roomId", "second")), "INVALID_ROOM_STATE");
        a.assertQuiet(); b.assertQuiet();
    }
    @Test public void createdRoomRetainsFourPlayerLimit() throws Exception {
        create(a, "r"); var c = client(true); var d = client(true); var fifth = client(true);
        for (var client : List.of(a, b, c, d)) {
            assertEquals(MessageType.ROOM_STATE, client.request(MessageType.JOIN_ROOM, ProtocolV1.object().put("roomId", "r")).type());
        }
        error(fifth.request(MessageType.JOIN_ROOM, ProtocolV1.object().put("roomId", "r")), "ROOM_FULL");
        assertEquals(4, server.getRoomManager().getRoom("r").getPlayerCount());
        assertEquals(4, server.getRoomManager().getRoom("r").getPlayerSlots().stream().map(s -> s.slotIndex()).distinct().count());
    }
    private List<ProtocolMessage> concurrentCreate(String first, String second) throws Exception {
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var fa = pool.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return create(a, first); });
            var fb = pool.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return create(b, second); });
            start.countDown(); return List.of(fa.get(5, TimeUnit.SECONDS), fb.get(5, TimeUnit.SECONDS));
        } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    @Test(timeout=15000) public void concurrentClientsCreateSeparateUniqueRoomIds() throws Exception {
        var results = concurrentCreate("one", "two"); for (var result : results) { assertEquals(MessageType.ROOM_CREATED, result.type()); }
        assertNotEquals(results.get(0).payload().get("roomId"), results.get(1).payload().get("roomId"));
        assertEquals(2, server.getRoomManager().getRoomCount()); assertNotSame(server.getRoomManager().getRoom("one"), server.getRoomManager().getRoom("two"));
    }
    @Test(timeout=15000) public void concurrentDuplicateIdHasOneSuccessAndOneRejection() throws Exception {
        var results = concurrentCreate("same", "same"); assertEquals(1, results.stream().filter(m -> m.type() == MessageType.ROOM_CREATED).count());
        assertEquals(1, results.stream().filter(m -> m.type() == MessageType.ERROR).count());
        error(results.stream().filter(m -> m.type() == MessageType.ERROR).findFirst().orElseThrow(), "INVALID_ROOM_STATE");
        assertEquals(1, server.getRoomManager().getRoomCount());
    }
    @Test public void closedRegistryCreationFailureDoesNotLeakRoomOrCrashReader() throws Exception {
        server.getRoomManager().shutdown(); error(create(a, "r"), "INTERNAL_ERROR");
        assertEquals(0, server.getRoomManager().getRoomCount()); assertTrue(server.isRunning()); a.barrier();
    }
    @Test public void invalidRequestIdsAreRejectedBeforeRoomCreation() throws Exception {
        for (String requestId : new String[]{"", ",\"requestId\":null", ",\"requestId\":\"" + "r".repeat(65) + "\""}) {
            // Negative fixture cố tình vi phạm envelope; codec chuẩn không cho encode các message này.
            String json = "{\"version\":1,\"type\":\"CREATE_ROOM\"" + requestId + ",\"payload\":{\"roomId\":\"r\"}}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            try (var socket = new Socket("127.0.0.1", server.getBoundPort())) {
                socket.setSoTimeout(5000); socket.getOutputStream().write(ByteBuffer.allocate(body.length + 4).putInt(body.length).put(body).array());
                var response = new JsonFrameCodec().readNext(socket.getInputStream()); error(response, "INVALID_PAYLOAD"); assertNull(response.requestId());
            }
        }
        assertEquals(0, server.getRoomManager().getRoomCount());
    }
    @Test public void disconnectAfterCreateLeavesOnlyFullyRegisteredRoomUntilExplicitCleanup() throws Exception {
        create(a, "r"); var room = server.getRoomManager().getRoom("r"); a.close();
        assertSame(room, server.getRoomManager().getRoom("r")); assertEquals(0, room.getPlayerCount()); assertNull(room.getMatchRuntime());
        assertSame(room, server.getRoomManager().removeRoom("r")); assertTrue(room.isClosed()); assertEquals(0, server.getRoomManager().getRoomCount());
        assertEquals(MessageType.ROOM_CREATED, create(b, "r").type());
    }
}
