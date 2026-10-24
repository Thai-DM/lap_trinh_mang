package com.smashprofs.tests.network;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.protocol.*;
import com.smashprofs.game.network.client.ConnectionListener;
import com.smashprofs.game.network.client.NetworkClient;
import com.smashprofs.server.network.GameServer;
import com.smashprofs.server.network.HeartbeatConfiguration;
import org.junit.After;
import org.junit.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Real TCP peers; GameServer tests use the production scheduler and maintenance worker. */
public class NetworkClientProtocolV1Test {
    private GameServer server;
    private ServerSocket peerListener;
    private ExecutorService peerExecutor;
    private final List<NetworkClient> clients = new ArrayList<>();

    @After public void cleanup() throws Exception {
        try {
            for (var client : clients) { client.disconnectAndWait(3000); }
        } finally {
            try { if (server != null) { server.stop(); } }
            finally {
                if (peerListener != null) { peerListener.close(); }
                if (peerExecutor != null) {
                    peerExecutor.shutdownNow();
                    assertTrue("Peer worker must terminate", peerExecutor.awaitTermination(3, TimeUnit.SECONDS));
                }
            }
        }
    }

    private void startServer(boolean fastHeartbeat) {
        server = fastHeartbeat
                ? new GameServer("127.0.0.1", 0, 4, 20,
                    new HeartbeatConfiguration(Duration.ofMillis(100), Duration.ofMillis(600)))
                : new GameServer("127.0.0.1", 0, 4);
        server.start();
    }

    private NetworkClient track(NetworkClient client) {
        clients.add(client);
        return client;
    }

    private Probe connect(int pingCount) throws Exception {
        var probe = new Probe(pingCount);
        probe.client = track(new NetworkClient("127.0.0.1", server.getBoundPort(), probe));
        probe.client.connect();
        assertTrue("CONNECT_ACK must complete handshake", probe.connected.await(3, TimeUnit.SECONDS));
        return probe;
    }

    private void startPeer() throws Exception {
        peerListener = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        peerListener.setSoTimeout(3000);
        peerExecutor = Executors.newSingleThreadExecutor();
    }

    @Test(timeout=15000) public void realServerAssignsDistinctIdsAndRepeatedConnectKeepsIdentity() throws Exception {
        startServer(false);
        var first = connect(0);
        var second = connect(0);
        var ack = first.next(MessageType.CONNECT_ACK);
        assertEquals(1, ack.version());
        assertNotNull(ack.requestId());
        assertEquals(UUID.fromString(ack.requestId()).toString(), ack.requestId());
        assertEquals(first.client.getPlayerId().value(), ack.payload().get("playerId").textValue());
        assertEquals(UUID.fromString(first.client.getPlayerId().value()).toString(), first.client.getPlayerId().value());
        assertEquals(first.client.getPlayerId(), first.identityAtConnected);
        assertNotEquals(first.client.getPlayerId(), second.client.getPlayerId());
        assertEquals(2, server.getConnectionManager().size());
        assertTrue(server.getConnectionManager().getSessions().stream()
                .anyMatch(s -> first.client.getPlayerId().equals(s.getPlayerId())));
        assertTrue("Callback must run on Reader", first.readerThread.getName().startsWith("NetworkClient-Reader"));

        assertTrue(first.client.sendMessage(new ProtocolMessage(MessageType.CONNECT, "repeat-connect", ProtocolV1.object())));
        var repeated = first.next(MessageType.CONNECT_ACK);
        assertEquals("repeat-connect", repeated.requestId());
        assertEquals(first.client.getPlayerId().value(), repeated.payload().get("playerId").textValue());
        assertEquals("One onConnected per handshake", 1, first.connectedCount.get());
    }

    @Test(timeout=15000) public void realServerErrorPreservesPayloadCorrelationAndLegacyListenerView() throws Exception {
        startServer(false);
        var probe = connect(0);
        probe.next(MessageType.CONNECT_ACK);
        PlayerId identity = probe.client.getPlayerId();
        assertTrue(probe.client.sendMessage(new ProtocolMessage(MessageType.PING, "invalid-ping",
                ProtocolV1.object().put("sendTimeMs", 123))));
        var error = probe.next(MessageType.ERROR);
        assertEquals(1, error.version());
        assertEquals("invalid-ping", error.requestId());
        assertEquals("INVALID_PAYLOAD", error.payload().get("code").textValue());
        assertTrue(error.payload().get("message").isTextual());
        NetworkPacket legacyError;
        do {
            legacyError = probe.legacyPackets.poll(3, TimeUnit.SECONDS);
            assertNotNull("Legacy callback must receive ERROR", legacyError);
        } while (legacyError.getType() != MessageType.ERROR);
        assertNotNull(legacyError.getPayloadJson());
        assertTrue(legacyError.getPayloadJson().contains("INVALID_PAYLOAD"));
        assertTrue(probe.client.isConnected());
        assertEquals(identity, probe.client.getPlayerId());
        assertTrue(probe.client.sendMessage(new ProtocolMessage(MessageType.PING, "valid-ping", ProtocolV1.object())));
        var pong = probe.next(MessageType.PONG);
        assertEquals("valid-ping", pong.requestId());
        assertTrue(pong.payload().isEmpty());
    }

    @Test(timeout=15000) public void existingSendPacketApiUsesV1WithRealServer() throws Exception {
        startServer(false);
        var probe = connect(0);
        probe.next(MessageType.CONNECT_ACK);
        assertTrue(probe.client.sendPacket(new NetworkPacket(MessageType.PING, "ignored-client-sender", null)));
        var pong = probe.next(MessageType.PONG);
        assertNotNull(pong.requestId());
        assertTrue(pong.payload().isEmpty());
        assertFalse("Array payload cannot become a v1 object", probe.client.sendPacket(
                new NetworkPacket(MessageType.PING, "ignored", "[]")));
        assertTrue(probe.client.isConnected());
    }

    @Test(timeout=15000) public void realServerKeepsIdleClientAliveAcrossMultipleHeartbeatDeadlines() throws Exception {
        startServer(true);
        var probe = connect(12);
        probe.next(MessageType.CONNECT_ACK);
        // No application packets or gameplay inputs while twelve production maintenance probes run.
        assertTrue("Automatic PONG must sustain idle connection", probe.pings.await(6, TimeUnit.SECONDS));
        assertTrue("Observation must extend beyond the first unanswered-probe deadline",
                probe.lastPingNanos - probe.firstPingNanos > TimeUnit.MILLISECONDS.toNanos(600));
        assertTrue(probe.client.isConnected());
        assertEquals(1, server.getConnectionManager().size());
        assertTrue(probe.pingIds.size() >= 12);
        assertEquals(0, probe.errorCount.get());
        assertEquals(0, probe.disconnectedCount.get());
        // A correlated response also proves the reader is still processing valid frames.
        assertTrue(probe.client.sendMessage(new ProtocolMessage(MessageType.PING, "after-idle", ProtocolV1.object())));
        assertEquals("after-idle", probe.next(MessageType.PONG).requestId());
        assertEquals(0, probe.errorCount.get());
    }

    @Test(timeout=15000) public void disconnectClearsIdentityAndStopsReaderWithOneNotification() throws Exception {
        startServer(false);
        var probe = connect(0);
        assertNotNull(probe.client.getPlayerId());
        probe.client.disconnectAndWait(3000);
        assertTrue(probe.disconnected.await(3, TimeUnit.SECONDS));
        assertFalse(probe.client.isConnected());
        assertNull(probe.client.getPlayerId());
        assertNull(probe.identityAtDisconnected);
        assertEquals(1, probe.disconnectedCount.get());
        assertFalse("Reader thread must terminate", probe.readerThread.isAlive());
    }

    @Test(timeout=15000) public void socketPeerObservesExactV1ConnectAndAutomaticPongWithoutListener() throws Exception {
        startPeer();
        var exchange = peerExecutor.submit(() -> {
            try (Socket socket = peerListener.accept()) {
                socket.setSoTimeout(3000);
                var codec = new JsonFrameCodec();
                var connect = codec.readNext(socket.getInputStream());
                assertNotNull(connect);
                assertEquals(1, connect.version());
                assertEquals(MessageType.CONNECT, connect.type());
                assertEquals(UUID.fromString(connect.requestId()).toString(), connect.requestId());
                assertTrue(connect.payload().isEmpty());
                socket.getOutputStream().write(codec.encode(new ProtocolMessage(MessageType.CONNECT_ACK,
                        connect.requestId(), ProtocolV1.object().put("playerId", UUID.randomUUID().toString()))));
                socket.getOutputStream().write(codec.encode(new ProtocolMessage(MessageType.PING,
                        "server-probe-\u00e9", ProtocolV1.object())));
                socket.getOutputStream().flush();
                return codec.readNext(socket.getInputStream());
            }
        });
        var client = track(new NetworkClient("127.0.0.1", peerListener.getLocalPort(), null));
        client.connect();
        var pong = exchange.get(5, TimeUnit.SECONDS);
        assertNotNull(pong);
        assertEquals(1, pong.version());
        assertEquals(MessageType.PONG, pong.type());
        assertEquals("server-probe-\u00e9", pong.requestId());
        assertTrue(pong.payload().isEmpty());
    }

    @Test(timeout=15000) public void wrongConnectAckCorrelationNeverPublishesIdentity() throws Exception {
        startPeer();
        var peer = peerExecutor.submit(() -> {
            try (Socket socket = peerListener.accept()) {
                socket.setSoTimeout(3000);
                var codec = new JsonFrameCodec();
                assertEquals(MessageType.CONNECT, codec.readNext(socket.getInputStream()).type());
                socket.getOutputStream().write(codec.encode(new ProtocolMessage(MessageType.CONNECT_ACK,
                        "different-request", ProtocolV1.object().put("playerId", UUID.randomUUID().toString()))));
                socket.getOutputStream().flush();
                assertEquals("Client must close invalid handshake", -1, socket.getInputStream().read());
            }
            return null;
        });
        var probe = new Probe(0);
        probe.client = track(new NetworkClient("127.0.0.1", peerListener.getLocalPort(), probe));
        probe.client.connect();
        assertTrue(probe.disconnected.await(5, TimeUnit.SECONDS));
        peer.get(5, TimeUnit.SECONDS);
        assertEquals(0, probe.connectedCount.get());
        assertNull(probe.client.getPlayerId());
        assertTrue(probe.disconnectReason.contains("Invalid CONNECT_ACK"));
    }

    @Test(timeout=15000) public void connectErrorIsDeliveredBeforeDisconnect() throws Exception {
        startPeer();
        var peer = peerExecutor.submit(() -> {
            try (Socket socket = peerListener.accept()) {
                socket.setSoTimeout(3000);
                var codec = new JsonFrameCodec();
                var connect = codec.readNext(socket.getInputStream());
                socket.getOutputStream().write(codec.encode(ProtocolV1.error(connect.requestId(),
                        "INTERNAL_ERROR", "Request could not be processed")));
                socket.getOutputStream().flush();
                assertEquals(-1, socket.getInputStream().read());
                return connect.requestId();
            }
        });
        var probe = new Probe(0);
        probe.client = track(new NetworkClient("127.0.0.1", peerListener.getLocalPort(), probe));
        probe.client.connect();
        var error = probe.next(MessageType.ERROR);
        assertEquals(peer.get(5, TimeUnit.SECONDS), error.requestId());
        assertEquals("INTERNAL_ERROR", error.payload().get("code").textValue());
        assertTrue(probe.disconnected.await(3, TimeUnit.SECONDS));
        assertTrue(probe.disconnectReason.contains("CONNECT rejected: INTERNAL_ERROR"));
        assertEquals(0, probe.connectedCount.get());
        assertNull(probe.client.getPlayerId());
    }

    private static final class Probe implements ConnectionListener {
        NetworkClient client;
        final BlockingQueue<ProtocolMessage> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<NetworkPacket> legacyPackets = new LinkedBlockingQueue<>();
        final CountDownLatch connected = new CountDownLatch(1), disconnected = new CountDownLatch(1), pings;
        final AtomicInteger connectedCount = new AtomicInteger(), disconnectedCount = new AtomicInteger(), errorCount = new AtomicInteger();
        final Set<String> pingIds = ConcurrentHashMap.newKeySet();
        volatile PlayerId identityAtConnected, identityAtDisconnected;
        volatile Thread readerThread;
        volatile String disconnectReason;
        volatile long firstPingNanos, lastPingNanos;
        Probe(int count) { pings = new CountDownLatch(count); }
        @Override public void onConnected() {
            readerThread = Thread.currentThread();
            identityAtConnected = client.getPlayerId();
            connectedCount.incrementAndGet();
            connected.countDown();
        }
        @Override public void onPacketReceived(NetworkPacket packet) { legacyPackets.offer(packet); }
        @Override public void onProtocolMessageReceived(ProtocolMessage message) {
            ConnectionListener.super.onProtocolMessageReceived(message);
            messages.offer(message);
            if (message.type() == MessageType.ERROR) { errorCount.incrementAndGet(); }
            if (message.type() == MessageType.PING) {
                if (firstPingNanos == 0) { firstPingNanos = System.nanoTime(); }
                lastPingNanos = System.nanoTime();
                pingIds.add(message.requestId());
                pings.countDown();
            }
        }
        @Override public void onDisconnected(String reason) {
            readerThread = Thread.currentThread();
            identityAtDisconnected = client.getPlayerId();
            disconnectReason = reason;
            disconnectedCount.incrementAndGet();
            disconnected.countDown();
        }
        ProtocolMessage next(MessageType type) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (true) {
                long remaining = deadline - System.nanoTime();
                assertTrue("Timed out awaiting " + type, remaining > 0);
                var message = messages.poll(remaining, TimeUnit.NANOSECONDS);
                assertNotNull("Missing " + type, message);
                if (message.type() == type) { return message; }
                assertNotEquals("Unexpected ERROR: " + message, MessageType.ERROR, message.type());
            }
        }
    }
}
