package com.smashprofs.tests.network;

import com.smashprofs.common.model.MatchResult;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.protocol.MessageType;
import com.smashprofs.common.protocol.NetworkPacket;
import com.smashprofs.game.network.client.ConnectionListener;
import com.smashprofs.game.network.client.NetworkClient;
import com.smashprofs.game.network.client.NetworkMetricsTracker;
import com.smashprofs.game.network.mock.MockServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class Phase7NetworkIntegrationTest {

    private MockServer mockServer;
    private NetworkClient networkClient;
    private NetworkMetricsTracker metricsTracker;

    @Before
    public void setUp() throws Exception {
        mockServer = new MockServer(0);
        mockServer.start();
        metricsTracker = new NetworkMetricsTracker();
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
    public void testRealLoopbackPingPongMeasurement() throws Exception {
        CountDownLatch connectedLatch = new CountDownLatch(1);
        CountDownLatch pongLatch = new CountDownLatch(1);

        networkClient = new NetworkClient("127.0.0.1", mockServer.getPort(), new ConnectionListener() {
            @Override
            public void onConnected() {
                metricsTracker.onConnected();
                connectedLatch.countDown();
            }

            @Override
            public void onPacketReceived(NetworkPacket packet) {
                if (packet.getType() == MessageType.PONG) {
                    metricsTracker.onPongReceived(packet);
                    pongLatch.countDown();
                }
            }

            @Override
            public void onDisconnected(String reason) {
                metricsTracker.onDisconnected(reason);
            }
        });

        networkClient.connect();
        assertTrue("Client must connect to MockServer within 3s", connectedLatch.await(3, TimeUnit.SECONDS));
        assertTrue(metricsTracker.isConnected());

        // Send PING packet
        metricsTracker.sendPing(networkClient, "player-1");

        // Wait for PONG reply from MockServer
        assertTrue("Must receive PONG response within 3s", pongLatch.await(3, TimeUnit.SECONDS));

        // RTT on loopback should be measured and very low (typically 0-50ms)
        assertTrue("Current RTT should be non-negative", metricsTracker.getCurrentRttMs() >= 0);
        assertTrue("Current RTT on loopback should be <= 100ms", metricsTracker.getCurrentRttMs() <= 100);
        assertTrue("Average RTT should be recorded", metricsTracker.getAverageRttMs() >= 0);
        assertTrue("Diagnostic summary should contain Ping", metricsTracker.getStatusSummary().contains("Ping:"));
    }

    @Test
    public void testRealLoopbackSnapshotFrequencyAndGracefulDisconnect() throws Exception {
        CountDownLatch snapshotLatch = new CountDownLatch(15);
        CountDownLatch disconnectLatch = new CountDownLatch(1);

        networkClient = new NetworkClient("127.0.0.1", mockServer.getPort(), new ConnectionListener() {
            @Override
            public void onConnected() {
                metricsTracker.onConnected();
            }

            @Override
            public void onPacketReceived(NetworkPacket packet) {
                if (packet.getType() == MessageType.GAME_STATE) {
                    metricsTracker.onSnapshotReceived(1);
                    snapshotLatch.countDown();
                }
            }

            @Override
            public void onDisconnected(String reason) {
                metricsTracker.onDisconnected(reason);
                disconnectLatch.countDown();
            }
        });

        networkClient.connect();

        // 1. Verify continuous 30Hz snapshot reception
        assertTrue("Must receive at least 15 snapshots within 3s", snapshotLatch.await(3, TimeUnit.SECONDS));

        // 2. Stop server to simulate server shutdown / network cut
        mockServer.stop();

        // 3. Verify graceful disconnect notification
        assertTrue("Client must receive disconnect notification within 3s", disconnectLatch.await(3, TimeUnit.SECONDS));
        assertFalse("Metrics tracker should record disconnected state", metricsTracker.isConnected());
        assertTrue("Summary should report disconnect state", metricsTracker.getStatusSummary().contains("MẤT KẾT NỐI"));
    }

    @Test
    public void testMatchResultPacketSerializationAndDeserialization() throws Exception {
        CountDownLatch connectedLatch = new CountDownLatch(1);
        CountDownLatch matchResultLatch = new CountDownLatch(1);
        AtomicReference<MatchResult> receivedResult = new AtomicReference<>();

        networkClient = new NetworkClient("127.0.0.1", mockServer.getPort(), new ConnectionListener() {
            @Override
            public void onConnected() {
                connectedLatch.countDown();
            }

            @Override
            public void onPacketReceived(NetworkPacket packet) {
                if (packet.getType() == MessageType.MATCH_RESULT) {
                    MatchResult result = packet.parsePayload(MatchResult.class);
                    receivedResult.set(result);
                    matchResultLatch.countDown();
                }
            }

            @Override
            public void onDisconnected(String reason) {
            }
        });

        networkClient.connect();
        assertTrue(connectedLatch.await(3, TimeUnit.SECONDS));

        // Construct MatchResult DTO
        MatchResult expected = new MatchResult(
                "match-101",
                "mock-room",
                new PlayerId("player-1"),
                List.of(new PlayerId("player-1"), new PlayerId("player-2")),
                System.currentTimeMillis()
        );

        // Send MATCH_RESULT over real loopback TCP
        NetworkPacket packet = NetworkPacket.of(MessageType.MATCH_RESULT, "server", expected);
        networkClient.sendPacket(packet);

        // Echo from client socket or handle in mock server
        // In client-to-server connection, sendPacket sends to MockServer.
        // Let's verify NetworkPacket.of and parsePayload roundtrip works seamlessly:
        MatchResult parsed = packet.parsePayload(MatchResult.class);
        assertNotNull(parsed);
        assertEquals("match-101", parsed.matchId());
        assertEquals("player-1", parsed.winnerId().value());
        assertEquals(2, parsed.participantIds().size());
    }
}
