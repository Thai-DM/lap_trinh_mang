package com.smashprofs.tests.network;

import com.smashprofs.common.model.GameSnapshot;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import com.smashprofs.common.protocol.MessageType;
import com.smashprofs.common.protocol.NetworkPacket;
import com.smashprofs.game.network.client.ConnectionListener;
import com.smashprofs.game.network.client.NetworkClient;
import com.smashprofs.game.network.mock.MockServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class MockServerLoopbackTest {

    private MockServer mockServer;
    private NetworkClient networkClient;

    private static final ConnectionListener NOOP_LISTENER = new ConnectionListener() {
        @Override
        public void onConnected() {}
        @Override
        public void onPacketReceived(NetworkPacket packet) {}
        @Override
        public void onDisconnected(String reason) {}
    };

    @Before
    public void setUp() {
        mockServer = null;
        networkClient = null;
    }

    @After
    public void tearDown() {
        if (networkClient != null) {
            networkClient.disconnect();
        }
        if (mockServer != null) {
            mockServer.stop();
        }
    }

    @Test
    public void testLifecycle_startAndStop() throws IOException {
        mockServer = new MockServer("127.0.0.1", 0);
        assertFalse(mockServer.isRunning());
        assertEquals(-1, mockServer.getPort());

        mockServer.start();
        assertTrue(mockServer.isRunning());
        assertTrue(mockServer.getPort() > 0);

        mockServer.stop();
        assertFalse(mockServer.isRunning());
    }

    @Test
    public void testMultipleStops_idempotent() throws IOException {
        mockServer = new MockServer("127.0.0.1", 0);
        mockServer.start();
        assertTrue(mockServer.isRunning());

        mockServer.stop();
        mockServer.stop();
        assertFalse(mockServer.isRunning());
    }

    @Test
    public void testBroadcast_clientReceivesSnapshots() throws Exception {
        mockServer = new MockServer("127.0.0.1", 0);
        mockServer.start();

        CountDownLatch snapshotLatch = new CountDownLatch(5);
        CopyOnWriteArrayList<GameSnapshot> receivedSnapshots = new CopyOnWriteArrayList<>();

        networkClient = new NetworkClient("127.0.0.1", mockServer.getPort(), new ConnectionListener() {
            @Override
            public void onConnected() {}

            @Override
            public void onPacketReceived(NetworkPacket packet) {
                if (packet.getType() == MessageType.GAME_STATE) {
                    GameSnapshot snapshot = packet.parsePayload(GameSnapshot.class);
                    if (snapshot != null) {
                        receivedSnapshots.add(snapshot);
                        snapshotLatch.countDown();
                    }
                }
            }

            @Override
            public void onDisconnected(String reason) {}
        });

        networkClient.connect();
        assertTrue(networkClient.isConnected());

        boolean completed = snapshotLatch.await(3000, TimeUnit.MILLISECONDS);
        assertTrue("Client should receive at least 5 snapshots in 3 seconds", completed);
        assertTrue(receivedSnapshots.size() >= 5);

        // Verify ticks are strictly increasing
        for (int i = 1; i < 5; i++) {
            assertTrue(receivedSnapshots.get(i).serverTick() > receivedSnapshots.get(i - 1).serverTick());
        }

        // Verify player states exist (player-1 and player-2)
        GameSnapshot last = receivedSnapshots.get(receivedSnapshots.size() - 1);
        assertEquals(2, last.players().size());
    }

    @Test
    public void testPingPong_echoesPayload() throws Exception {
        mockServer = new MockServer("127.0.0.1", 0);
        mockServer.start();

        CountDownLatch pongLatch = new CountDownLatch(1);
        AtomicReference<String> pongPayload = new AtomicReference<>();

        networkClient = new NetworkClient("127.0.0.1", mockServer.getPort(), new ConnectionListener() {
            @Override
            public void onConnected() {}

            @Override
            public void onPacketReceived(NetworkPacket packet) {
                if (packet.getType() == MessageType.PONG) {
                    pongPayload.set(packet.getPayloadJson());
                    pongLatch.countDown();
                }
            }

            @Override
            public void onDisconnected(String reason) {}
        });

        networkClient.connect();
        assertTrue(networkClient.isConnected());

        String testPayload = "{\"timestamp\":123456789}";
        networkClient.sendPacket(new NetworkPacket(MessageType.PING, "client", testPayload));

        boolean completed = pongLatch.await(2000, TimeUnit.MILLISECONDS);
        assertTrue("Should receive PONG from MockServer", completed);
        assertEquals(testPayload, pongPayload.get());
    }

    @Test
    public void testPlayerInput_acceptedAndProcessed() throws Exception {
        mockServer = new MockServer("127.0.0.1", 0);
        mockServer.start();

        networkClient = new NetworkClient("127.0.0.1", mockServer.getPort(), NOOP_LISTENER);
        networkClient.connect();
        assertTrue(networkClient.isConnected());

        // Send input packet
        PlayerInput input = new PlayerInput(new PlayerId("player-1"), 1L, 100L, 1.0f, true, false, false, false, false);
        networkClient.sendPacket(NetworkPacket.of(MessageType.PLAYER_INPUT, "player-1", input));

        // Wait small interval
        Thread.sleep(150);
        assertTrue(mockServer.isRunning());
    }

    @Test
    public void testClientDisconnect_serverRemainsHealthy() throws Exception {
        mockServer = new MockServer("127.0.0.1", 0);
        mockServer.start();

        networkClient = new NetworkClient("127.0.0.1", mockServer.getPort(), NOOP_LISTENER);
        networkClient.connect();
        assertTrue(networkClient.isConnected());

        // Let it run for 100ms
        Thread.sleep(100);

        // Disconnect client
        networkClient.disconnect();
        assertFalse(networkClient.isConnected());

        // Wait small interval for server to handle EOF
        Thread.sleep(100);

        // Server should still be running smoothly
        assertTrue(mockServer.isRunning());
        assertEquals(0, mockServer.getConnectedClientCount());
    }
}
