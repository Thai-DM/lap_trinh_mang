package com.smashprofs.tests.network;

import com.smashprofs.common.protocol.MessageType;
import com.smashprofs.common.protocol.NetworkPacket;
import com.smashprofs.common.protocol.PacketFraming;
import com.smashprofs.game.network.client.ConnectionListener;
import com.smashprofs.game.network.client.NetworkClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class NetworkClientLoopbackTest {

    private ServerSocket serverSocket;
    private int port;
    private ExecutorService serverExecutor;
    private NetworkClient client;

    @Before
    public void setUp() throws IOException {
        // Bind on loopback with port 0 (OS allocates ephemeral port)
        serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        port = serverSocket.getLocalPort();
        serverExecutor = Executors.newCachedThreadPool();
    }

    @After
    public void tearDown() {
        if (client != null) {
            client.disconnectAndWait(1000);
        }
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    public void testConnectAndBidirectionalPacketExchange() throws Exception {
        CountDownLatch connectedLatch = new CountDownLatch(1);
        CountDownLatch packetReceivedLatch = new CountDownLatch(1);
        AtomicReference<NetworkPacket> receivedOnClient = new AtomicReference<>();
        AtomicReference<String> connectedThread = new AtomicReference<>();

        client = NetworkClient.forMock("127.0.0.1", port, new ConnectionListener() {
            @Override
            public void onConnected() {
                connectedThread.set(Thread.currentThread().getName());
                connectedLatch.countDown();
            }

            @Override
            public void onPacketReceived(NetworkPacket packet) {
                receivedOnClient.set(packet);
                packetReceivedLatch.countDown();
            }

            @Override
            public void onDisconnected(String reason) {
            }
        });

        // Server accepts connection and exchanges packets
        Future<NetworkPacket> serverFuture = serverExecutor.submit(() -> {
            try (Socket serverClientSocket = serverSocket.accept();
                 DataInputStream in = new DataInputStream(serverClientSocket.getInputStream());
                 DataOutputStream out = new DataOutputStream(serverClientSocket.getOutputStream())) {

                // Read packet sent by client
                NetworkPacket clientPacket = PacketFraming.readPacket(in);

                // Echo back a PONG packet to client
                NetworkPacket pong = new NetworkPacket(MessageType.PONG, "server-echo", null);
                PacketFraming.writePacket(out, pong);

                return clientPacket;
            }
        });

        client.connect();

        assertTrue("Client must connect within 3s", connectedLatch.await(3, TimeUnit.SECONDS));
        assertTrue("Callback onConnected must run on Reader Thread",
                connectedThread.get() != null && connectedThread.get().startsWith("NetworkClient-Reader"));

        // Client sends PING
        NetworkPacket ping = new NetworkPacket(MessageType.PING, "client-01", null);
        boolean sent = client.sendPacket(ping);
        assertTrue("Packet must be enqueued successfully", sent);

        // Verify server received PING
        NetworkPacket serverReceived = serverFuture.get(3, TimeUnit.SECONDS);
        assertNotNull(serverReceived);
        assertEquals(MessageType.PING, serverReceived.getType());
        assertEquals("client-01", serverReceived.getSenderId());

        // Verify client received PONG back
        assertTrue("Client must receive PONG within 3s", packetReceivedLatch.await(3, TimeUnit.SECONDS));
        assertNotNull(receivedOnClient.get());
        assertEquals(MessageType.PONG, receivedOnClient.get().getType());
        assertEquals("server-echo", receivedOnClient.get().getSenderId());
    }

    @Test
    public void testMultipleSequentialPackets() throws Exception {
        int packetCount = 5;
        CountDownLatch allReceivedLatch = new CountDownLatch(packetCount);
        List<NetworkPacket> serverReceivedList = Collections.synchronizedList(new ArrayList<>());

        Future<?> serverFuture = serverExecutor.submit(() -> {
            try (Socket serverClientSocket = serverSocket.accept();
                 DataInputStream in = new DataInputStream(serverClientSocket.getInputStream())) {
                for (int i = 0; i < packetCount; i++) {
                    NetworkPacket p = PacketFraming.readPacket(in);
                    serverReceivedList.add(p);
                    allReceivedLatch.countDown();
                }
            }
            return null;
        });

        client = NetworkClient.forMock("127.0.0.1", port, new ConnectionListener() {
            @Override public void onConnected() {}
            @Override public void onPacketReceived(NetworkPacket packet) {}
            @Override public void onDisconnected(String reason) {}
        });
        client.connect();

        for (int i = 0; i < packetCount; i++) {
            NetworkPacket p = new NetworkPacket(MessageType.PLAYER_INPUT, "client-1", "seq=" + i);
            assertTrue(client.sendPacket(p));
        }

        assertTrue("Server must receive all 5 packets within 3s", allReceivedLatch.await(3, TimeUnit.SECONDS));
        assertEquals(packetCount, serverReceivedList.size());
        for (int i = 0; i < packetCount; i++) {
            assertEquals("seq=" + i, serverReceivedList.get(i).getPayloadJson());
        }
        serverFuture.get(1, TimeUnit.SECONDS);
    }

    @Test
    public void testCleanRemotePeerDisconnect() throws Exception {
        CountDownLatch disconnectedLatch = new CountDownLatch(1);
        AtomicReference<String> disconnectReason = new AtomicReference<>();
        AtomicReference<String> callbackThread = new AtomicReference<>();

        client = NetworkClient.forMock("127.0.0.1", port, new ConnectionListener() {
            @Override public void onConnected() {}
            @Override public void onPacketReceived(NetworkPacket packet) {}
            @Override
            public void onDisconnected(String reason) {
                callbackThread.set(Thread.currentThread().getName());
                disconnectReason.set(reason);
                disconnectedLatch.countDown();
            }
        });

        // Server accepts connection and immediately closes socket cleanly
        Future<?> serverFuture = serverExecutor.submit(() -> {
            try (Socket serverClientSocket = serverSocket.accept()) {
                // Do nothing, just close cleanly upon exiting try-with-resources
            }
            return null;
        });

        client.connect();
        serverFuture.get(2, TimeUnit.SECONDS);

        assertTrue("Client must trigger onDisconnected within 3s", disconnectedLatch.await(3, TimeUnit.SECONDS));
        assertNotNull(disconnectReason.get());
        assertTrue("Reason must indicate clean close by peer, got: " + disconnectReason.get(),
                disconnectReason.get().contains("closed cleanly"));
        assertTrue("Callback must run on Reader Thread",
                callbackThread.get() != null && callbackThread.get().startsWith("NetworkClient-Reader"));
    }

    @Test
    public void testClientInitiatedDisconnect() throws Exception {
        CountDownLatch disconnectedLatch = new CountDownLatch(1);
        AtomicReference<String> disconnectReason = new AtomicReference<>();
        AtomicInteger disconnectCallCount = new AtomicInteger(0);

        client = NetworkClient.forMock("127.0.0.1", port, new ConnectionListener() {
            @Override public void onConnected() {}
            @Override public void onPacketReceived(NetworkPacket packet) {}
            @Override
            public void onDisconnected(String reason) {
                disconnectCallCount.incrementAndGet();
                disconnectReason.set(reason);
                disconnectedLatch.countDown();
            }
        });

        serverExecutor.submit(() -> {
            try (Socket serverClientSocket = serverSocket.accept()) {
                // Keep server socket open until client disconnects
                while (!serverClientSocket.isClosed()) {
                    int b = serverClientSocket.getInputStream().read();
                    if (b == -1) break;
                }
            } catch (IOException ignored) {}
        });

        client.connect();
        assertTrue(client.isConnected());

        // Client initiates disconnect
        client.disconnect();

        assertTrue("onDisconnected must be called within 3s", disconnectedLatch.await(3, TimeUnit.SECONDS));
        assertFalse("Client isConnected must be false", client.isConnected());
        assertEquals("onDisconnected must be called exactly once", 1, disconnectCallCount.get());
        assertTrue("Reason must indicate client disconnect, got: " + disconnectReason.get(),
                disconnectReason.get().contains("Disconnected by client"));
    }

    @Test
    public void testWriterQueueBoundedRejection() throws Exception {
        // Small queue capacity of 3
        int capacity = 3;
        client = NetworkClient.forMock("127.0.0.1", port, 2000, capacity, new ConnectionListener() {
            @Override public void onConnected() {}
            @Override public void onPacketReceived(NetworkPacket packet) {}
            @Override public void onDisconnected(String reason) {}
        });

        // Server accepts connection and holds socket without reading to saturate TCP send buffer
        CountDownLatch serverAcceptedLatch = new CountDownLatch(1);
        CountDownLatch blockServerLatch = new CountDownLatch(1);
        serverExecutor.submit(() -> {
            try (Socket serverClientSocket = serverSocket.accept()) {
                serverAcceptedLatch.countDown();
                // Block server thread from reading any bytes
                blockServerLatch.await(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        });

        client.connect();
        assertTrue("Server must accept client connection", serverAcceptedLatch.await(3, TimeUnit.SECONDS));

        // Use 256KB payload per packet to quickly saturate OS TCP socket send buffer
        String largePayload = "X".repeat(256 * 1024);
        boolean overflowOccurred = false;
        int sentCount = 0;
        for (int i = 0; i < 50; i++) {
            NetworkPacket p = new NetworkPacket(MessageType.PING, "c1", largePayload);
            if (client.sendPacket(p)) {
                sentCount++;
            } else {
                overflowOccurred = true;
                break;
            }
        }

        assertTrue("sendPacket must reject and return false non-blockingly when queue is saturated", overflowOccurred);
        assertTrue("Must have enqueued at least capacity packets before overflow", sentCount >= capacity);

        blockServerLatch.countDown();
    }

    @Test
    public void testWriterErrorTriggersReaderDisconnectCallback() throws Exception {
        CountDownLatch disconnectedLatch = new CountDownLatch(1);
        AtomicReference<String> disconnectReason = new AtomicReference<>();
        AtomicReference<String> callbackThread = new AtomicReference<>();
        AtomicInteger disconnectCount = new AtomicInteger(0);

        client = NetworkClient.forMock("127.0.0.1", port, new ConnectionListener() {
            @Override public void onConnected() {}
            @Override public void onPacketReceived(NetworkPacket packet) {}
            @Override
            public void onDisconnected(String reason) {
                callbackThread.set(Thread.currentThread().getName());
                disconnectReason.set(reason);
                disconnectCount.incrementAndGet();
                disconnectedLatch.countDown();
            }
        });

        CountDownLatch serverConnectedLatch = new CountDownLatch(1);
        serverExecutor.submit(() -> {
            try (Socket serverClientSocket = serverSocket.accept()) {
                serverConnectedLatch.countDown();
                // Immediate RST on close
                serverClientSocket.setSoLinger(true, 0);
                serverClientSocket.close();
            } catch (IOException ignored) {}
        });

        client.connect();
        assertTrue(serverConnectedLatch.await(3, TimeUnit.SECONDS));

        // Continuously send packets to trigger writer error on closed/RST socket
        for (int i = 0; i < 20; i++) {
            client.sendPacket(new NetworkPacket(MessageType.PING, "c1", "payload" + i));
            Thread.sleep(10);
        }

        assertTrue("onDisconnected must be triggered within 3s", disconnectedLatch.await(3, TimeUnit.SECONDS));
        assertEquals("onDisconnected must be called exactly once", 1, disconnectCount.get());
        assertTrue("Callback must run on Reader Thread even if error originated from Writer, was: " + callbackThread.get(),
                callbackThread.get() != null && callbackThread.get().startsWith("NetworkClient-Reader"));
        assertNotNull(disconnectReason.get());
    }
}
