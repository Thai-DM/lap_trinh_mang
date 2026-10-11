package com.smashprofs.game.network.client;

import com.smashprofs.common.protocol.MessageType;
import com.smashprofs.common.protocol.NetworkPacket;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thread-safe tracker for network diagnostics and quality metrics (Phase 7):
 * - Round-Trip Time (RTT / Ping) in milliseconds.
 * - Jitter (variation in packet transit delays).
 * - Real-time Snapshot Frequency (Hz) from server broadcast.
 * - Dropped / missed server ticks.
 * - Connection health and graceful disconnection status.
 */
public class NetworkMetricsTracker {

    private static final Logger log = LogManager.getLogger(NetworkMetricsTracker.class);
    private static final Pattern SEND_TIME_PATTERN = Pattern.compile("\"sendTimeMs\"\\s*:\\s*(\\d+)");

    // Ping / RTT metrics
    private volatile long currentRttMs = 0;
    private volatile float averageRttMs = 0.0f;
    private volatile float jitterMs = 0.0f;
    private volatile long lastPingSentMs = 0;

    // Snapshot frequency metrics
    private volatile float snapshotFrequencyHz = 0.0f;
    private int snapshotCountWindow = 0;
    private long windowStartMs = 0;
    private long lastServerTick = -1;
    private volatile long droppedSnapshots = 0;

    // Connection state
    private volatile boolean connected = false;
    private volatile String disconnectReason = null;

    public NetworkMetricsTracker() {
        this.windowStartMs = -1;
    }

    /**
     * Sends a PING packet carrying the current client timestamp to measure RTT.
     *
     * @param client   Active NetworkClient.
     * @param senderId Identifier of the client.
     */
    public synchronized void sendPing(NetworkClient client, String senderId) {
        if (client == null || !client.isConnected()) {
            return;
        }
        long now = System.currentTimeMillis();
        lastPingSentMs = now;
        String payload = "{\"sendTimeMs\":" + now + "}";
        client.sendPacket(new NetworkPacket(MessageType.PING, senderId, payload));
    }

    /**
     * Parses and records a PONG packet received from the server.
     *
     * @param packet PONG network packet.
     */
    public synchronized void onPongReceived(NetworkPacket packet) {
        if (packet == null || packet.getType() != MessageType.PONG) {
            return;
        }
        long now = System.currentTimeMillis();
        long sentMs = parseSendTime(packet.getPayloadJson());
        if (sentMs > 0) {
            recordPong(sentMs, now);
        } else if (lastPingSentMs > 0) {
            recordPong(lastPingSentMs, now);
        }
    }

    /**
     * Records a measured RTT sample and updates moving averages.
     *
     * @param sentMs Timestamp when PING was sent.
     * @param nowMs  Timestamp when PONG was received.
     */
    public synchronized void recordPong(long sentMs, long nowMs) {
        long rtt = Math.max(0, nowMs - sentMs);
        currentRttMs = rtt;

        if (averageRttMs <= 0.0f) {
            averageRttMs = (float) rtt;
            jitterMs = 0.0f;
        } else {
            float diff = Math.abs(rtt - averageRttMs);
            jitterMs = (jitterMs * 0.8f) + (diff * 0.2f);
            averageRttMs = (averageRttMs * 0.8f) + ((float) rtt * 0.2f);
        }
    }

    /**
     * Records arrival of a GameSnapshot packet to compute frequency and detect tick skips.
     *
     * @param serverTick Monotonically increasing tick index from the snapshot.
     */
    public synchronized void onSnapshotReceived(long serverTick) {
        recordSnapshot(serverTick, System.currentTimeMillis());
    }

    /**
     * Records snapshot arrival with an explicit timestamp (deterministic for tests).
     *
     * @param serverTick Server tick index.
     * @param receiveMs  Arrival timestamp.
     */
    public synchronized void recordSnapshot(long serverTick, long receiveMs) {
        if (windowStartMs <= 0) {
            windowStartMs = receiveMs;
        }

        snapshotCountWindow++;

        // Detect dropped server ticks
        if (lastServerTick >= 0 && serverTick > lastServerTick + 1) {
            long gap = serverTick - lastServerTick - 1;
            droppedSnapshots += gap;
            log.warn("Missed {} server snapshots between tick {} and {}", gap, lastServerTick, serverTick);
        }
        lastServerTick = serverTick;

        // Calculate frequency when time window exceeds 1000 ms
        long elapsed = receiveMs - windowStartMs;
        if (elapsed >= 1000) {
            snapshotFrequencyHz = (snapshotCountWindow * 1000.0f) / elapsed;
            snapshotCountWindow = 0;
            windowStartMs = receiveMs;
        }
    }

    public synchronized void onConnected() {
        this.connected = true;
        this.disconnectReason = null;
    }

    public synchronized void onDisconnected(String reason) {
        this.connected = false;
        this.disconnectReason = reason;
    }

    public boolean isConnected() {
        return connected;
    }

    public String getDisconnectReason() {
        return disconnectReason;
    }

    public long getCurrentRttMs() {
        return currentRttMs;
    }

    public float getAverageRttMs() {
        return averageRttMs;
    }

    public float getJitterMs() {
        return jitterMs;
    }

    public float getSnapshotFrequencyHz() {
        return snapshotFrequencyHz;
    }

    public long getDroppedSnapshots() {
        return droppedSnapshots;
    }

    /**
     * Generates a concise diagnostic summary suitable for HUD overlay or logs.
     */
    public String getStatusSummary() {
        if (!connected) {
            return disconnectReason != null ? "[MẤT KẾT NỐI: " + disconnectReason + "]" : "[CHƯA KẾT NỐI]";
        }
        return String.format("Ping: %dms (avg %.1fms) | %.1f Hz | Drops: %d",
                currentRttMs, averageRttMs, snapshotFrequencyHz, droppedSnapshots);
    }

    private long parseSendTime(String payloadJson) {
        if (payloadJson == null) return -1;
        try {
            Matcher matcher = SEND_TIME_PATTERN.matcher(payloadJson);
            if (matcher.find()) {
                return Long.parseLong(matcher.group(1));
            }
            // Fallback: check if payload is pure number
            String trimmed = payloadJson.trim();
            if (trimmed.matches("\\d+")) {
                return Long.parseLong(trimmed);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }
}
