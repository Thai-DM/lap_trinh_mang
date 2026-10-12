package com.smashprofs.game.network.client;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.protocol.*;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Input/snapshot boundary for one validated GAME_START, with no simulation or rendering. */
public final class ClientMatchSession implements AutoCloseable {
    public static final int SIMULATION_HZ = 60;
    public static final int DEFAULT_SNAPSHOT_HZ = 20;
    private final NetworkClient network;
    private final GameStartRoster roster;
    private final PlayerId localId;
    private final OutboundInputQueue inputQueue;
    private final SnapshotBuffer.V1 snapshots = new SnapshotBuffer.V1(32);
    private final NetworkMetricsTracker metrics = new NetworkMetricsTracker(SIMULATION_HZ / DEFAULT_SNAPSHOT_HZ);
    private final LongSupplier nanoClock;
    private final long startedAt;
    private boolean active = true;
    private boolean finished;
    private WireMatchResult result;
    private long lastClientTick;

    ClientMatchSession(NetworkClient network, GameStartRoster roster, PlayerId localId) {
        this(network, roster, localId, System::nanoTime);
    }
    ClientMatchSession(NetworkClient network, GameStartRoster roster, PlayerId localId, LongSupplier nanoClock) {
        this.network = Objects.requireNonNull(network);
        this.roster = Objects.requireNonNull(roster);
        this.localId = Objects.requireNonNull(localId);
        this.nanoClock = Objects.requireNonNull(nanoClock);
        if (network.isMockProtocol() || !localId.equals(network.getPlayerId())) {
            throw new IllegalArgumentException("Match requires a connected production identity");
        }
        roster.localPlayer(localId);
        inputQueue = new OutboundInputQueue(localId);
        startedAt = nanoClock.getAsLong();
        metrics.onConnected();
    }

    /** Render callers sample once per frame. Enqueue/retry uses the existing input queue only. */
    public synchronized boolean recordFrameInput(float axis, boolean jump, boolean stomp,
                                                 boolean melee, boolean block, boolean ability) {
        if (!canSendInput()) { return false; }
        if (!Float.isFinite(axis) || axis < -1 || axis > 1) {
            throw new IllegalArgumentException("moveAxis must be finite and in [-1,1]");
        }
        inputQueue.recordFrameInput(axis, jump, stomp, melee, block, ability);
        return flushInput();
    }

    /** Non-blocking retry, also usable without recording another frame. */
    public synchronized boolean flushInput() {
        if (!canSendInput()) { return false; }
        // Metadata: elapsed local match time in a 60 Hz domain, independent of FPS/server authority.
        long elapsed = Math.max(0, nanoClock.getAsLong() - startedAt);
        long tick = (elapsed / 1_000_000_000L) * SIMULATION_HZ
                + (elapsed % 1_000_000_000L) * SIMULATION_HZ / 1_000_000_000L;
        lastClientTick = Math.max(lastClientTick, tick);
        return inputQueue.trySendAccumulatedInput(lastClientTick, input -> network.sendMessage(
                new ProtocolMessage(MessageType.PLAYER_INPUT, null, ProtocolV1.inputPayload(input))));
    }

    private boolean canSendInput() {
        if (!active) { return false; }
        if (!localId.equals(network.getPlayerId())) { close(); return false; }
        return !finished;
    }

    /** Reader thread parses immutable values; UI reads the synchronized buffer. */
    synchronized boolean acceptState(ProtocolMessage message) {
        if (!active || !localId.equals(network.getPlayerId())) { close(); return false; }
        if (result != null) { return false; }
        var state = WireGameSnapshot.parse(message.payload());
        state.validateRoster(roster);
        GameStartRoster.require(!finished || state.phase().equals("FINISHED"), "PLAYING snapshot after FINISHED");
        if (!snapshots.push(state)) { return false; }
        metrics.onSnapshotReceived(state.serverTick());
        if (state.phase().equals("FINISHED")) { finished = true; inputQueue.clear(); }
        return true;
    }

    /** Accept once, after the ordered final GAME_STATE; no ranking wait or UI callback. */
    synchronized boolean acceptEnd(ProtocolMessage message) {
        if (!active || !localId.equals(network.getPlayerId())) { return false; }
        var incoming = WireMatchResult.parse(message.payload());
        incoming.validateRoster(roster);
        if (result != null) {
            GameStartRoster.require(result.equals(incoming), "Conflicting duplicate GAME_END");
            return false;
        }
        var last = snapshots.getLatestSnapshot();
        GameStartRoster.require(last != null && last.phase().equals("FINISHED")
                && last.serverTick() == incoming.finishedAtTick(), "GAME_END missing matching final snapshot");
        for (var p : incoming.participants()) {
            var state = last.player(p.playerId());
            GameStartRoster.require(Float.compare(p.finalHp(), state.hp()) == 0 && p.eliminated() == state.dead(),
                    "GAME_END final player state mismatch");
        }
        finished = true;
        inputQueue.clear();
        result = incoming;
        return true;
    }

    public GameStartRoster getRoster() { return roster; }
    public PlayerId getLocalPlayerId() { return localId; }
    public SnapshotBuffer.V1 getSnapshotBuffer() { return snapshots; }
    public NetworkMetricsTracker getMetrics() { return metrics; }
    public synchronized int getPendingInputCount() { return inputQueue.size(); }
    public synchronized boolean isActive() { return canSendInput(); }
    public synchronized WireMatchResult getResult() { return result; }
    @Override public synchronized void close() {
        active = false;
        inputQueue.clear();
        snapshots.clear();
        metrics.onDisconnected("Match session closed");
    }
}
