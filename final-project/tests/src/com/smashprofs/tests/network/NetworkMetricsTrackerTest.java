package com.smashprofs.tests.network;

import com.smashprofs.common.protocol.MessageType;
import com.smashprofs.common.protocol.NetworkPacket;
import com.smashprofs.game.network.client.NetworkMetricsTracker;
import org.junit.Test;

import static org.junit.Assert.*;

public class NetworkMetricsTrackerTest {

    @Test
    public void testRttAndJitterCalculation() {
        NetworkMetricsTracker tracker = new NetworkMetricsTracker();

        // Sample 1: RTT = 20ms
        tracker.recordPong(1000, 1020);
        assertEquals(20, tracker.getCurrentRttMs());
        assertEquals(20.0f, tracker.getAverageRttMs(), 0.001f);
        assertEquals(0.0f, tracker.getJitterMs(), 0.001f);

        // Sample 2: RTT = 40ms -> diff = |40 - 20| = 20
        // New avg = 20 * 0.8 + 40 * 0.2 = 16 + 8 = 24.0
        // New jitter = 0 * 0.8 + 20 * 0.2 = 4.0
        tracker.recordPong(2000, 2040);
        assertEquals(40, tracker.getCurrentRttMs());
        assertEquals(24.0f, tracker.getAverageRttMs(), 0.01f);
        assertEquals(4.0f, tracker.getJitterMs(), 0.01f);
    }

    @Test
    public void testOnPongReceivedFromJson() {
        NetworkMetricsTracker tracker = new NetworkMetricsTracker();

        long now = System.currentTimeMillis();
        long sent = now - 15;
        NetworkPacket packet = new NetworkPacket(MessageType.PONG, "server", "{\"sendTimeMs\":" + sent + "}");

        tracker.onPongReceived(packet);
        assertTrue("Current RTT should be around 15ms", tracker.getCurrentRttMs() >= 14 && tracker.getCurrentRttMs() <= 50);
    }

    @Test
    public void testSnapshotFrequencyCalculation() {
        NetworkMetricsTracker tracker = new NetworkMetricsTracker();

        long baseTime = 10000L;
        // Simulate 31 snapshots arriving evenly spaced over ~1000ms (31 * 33ms = 1023ms)
        for (int i = 0; i <= 31; i++) {
            tracker.recordSnapshot(i, baseTime + (i * 33));
        }

        // 31 snapshots in 1023ms -> frequency ~30.3 Hz
        assertTrue("Frequency should be roughly 30 Hz", tracker.getSnapshotFrequencyHz() >= 28.0f && tracker.getSnapshotFrequencyHz() <= 33.0f);
        assertEquals("No dropped snapshots for consecutive ticks", 0, tracker.getDroppedSnapshots());
    }

    @Test
    public void testDroppedSnapshotsDetection() {
        NetworkMetricsTracker tracker = new NetworkMetricsTracker();

        tracker.recordSnapshot(100, 1000);
        tracker.recordSnapshot(101, 1033);
        // Skip ticks 102, 103, 104 -> next tick is 105 (gap of 3 ticks)
        tracker.recordSnapshot(105, 1133);

        assertEquals("Should detect 3 missed snapshots", 3, tracker.getDroppedSnapshots());
    }

    @Test
    public void testConnectionStateAndStatusSummary() {
        NetworkMetricsTracker tracker = new NetworkMetricsTracker();
        assertFalse(tracker.isConnected());
        assertTrue(tracker.getStatusSummary().contains("CHƯA KẾT NỐI"));

        tracker.onConnected();
        assertTrue(tracker.isConnected());
        assertNull(tracker.getDisconnectReason());
        assertTrue(tracker.getStatusSummary().contains("Ping"));

        tracker.onDisconnected("Server timeout");
        assertFalse(tracker.isConnected());
        assertEquals("Server timeout", tracker.getDisconnectReason());
        assertTrue(tracker.getStatusSummary().contains("MẤT KẾT NỐI: Server timeout"));
    }
}
