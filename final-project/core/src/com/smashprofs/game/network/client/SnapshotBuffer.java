package com.smashprofs.game.network.client;

import com.smashprofs.common.model.GameSnapshot;

import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Thread-safe tick-ordered buffer for incoming server game snapshots.
 * <p>
 * Key architectural features:
 * 1. Decouples the asynchronous Network Thread (producer) from the 60 FPS Render Thread (consumer).
 * 2. Orders snapshots by strictly increasing server tick, tolerating network jitter / out-of-order delivery.
 * 3. Enforces bounded capacity by evicting stale snapshots to bound memory and GC pressure.
 * 4. Provides getSurroundingSnapshots(float renderTick) which computes the two surrounding snapshots
 *    and exact interpolation factor alpha for the StateInterpolator.
 * 5. Handles underflow (pre-buffering/lag) and overflow (server freeze/packet drop) with hold-policies.
 * </p>
 */
public class SnapshotBuffer {

    public static final int DEFAULT_CAPACITY = 32;

    private final int capacity;
    private final NavigableMap<Long, GameSnapshot> snapshots = new TreeMap<>();

    public SnapshotBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public SnapshotBuffer(int capacity) {
        this.capacity = Math.max(2, capacity);
    }

    /**
     * Immutable pair of surrounding snapshots and fractional interpolation progress alpha in [0.0, 1.0].
     */
    public record SnapshotPair(GameSnapshot from, GameSnapshot to, float alpha) {
        public boolean isIdentical() {
            return from.serverTick() == to.serverTick();
        }
    }

    /**
     * Producer method invoked on the Network Thread when a GAME_STATE packet arrives.
     * Inserts the snapshot ordered by serverTick and evicts the oldest entry if capacity is exceeded.
     *
     * @param snapshot The incoming GameSnapshot.
     * @return true if accepted and buffered, false if rejected (null or too stale).
     */
    public synchronized boolean push(GameSnapshot snapshot) {
        return pushSnapshot(snapshot);
    }

    public synchronized boolean pushSnapshot(GameSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }

        long tick = snapshot.serverTick();

        // Reject snapshot if it's older than our oldest retained entry when buffer is already full
        if (snapshots.size() >= capacity && tick <= snapshots.firstKey()) {
            return false;
        }

        snapshots.put(tick, snapshot);

        // Bounded capacity: evict oldest
        while (snapshots.size() > capacity) {
            snapshots.pollFirstEntry();
        }

        return true;
    }

    /**
     * Consumer method invoked by the StateInterpolator / Render Thread.
     * Finds the two surrounding snapshots S0 (tick <= renderTick) and S1 (tick >= renderTick),
     * and calculates alpha = (renderTick - tick0) / (tick1 - tick0).
     *
     * @param renderTick Continuous simulation tick (can be fractional, e.g. 54.3f).
     * @return SnapshotPair for interpolation, or null if buffer is empty.
     */
    public synchronized SnapshotPair getSurroundingSnapshots(float renderTick) {
        if (snapshots.isEmpty()) {
            return null;
        }

        if (snapshots.size() == 1) {
            GameSnapshot single = snapshots.firstEntry().getValue();
            return new SnapshotPair(single, single, 1.0f);
        }

        long firstTick = snapshots.firstKey();
        long lastTick = snapshots.lastKey();

        // Underflow: renderTick is earlier than our oldest available snapshot -> Hold at oldest
        if (renderTick <= firstTick) {
            GameSnapshot oldest = snapshots.firstEntry().getValue();
            return new SnapshotPair(oldest, oldest, 0.0f);
        }

        // Starvation / Overflow: renderTick has caught up or surpassed latest snapshot -> Hold at latest
        if (renderTick >= lastTick) {
            GameSnapshot latest = snapshots.lastEntry().getValue();
            return new SnapshotPair(latest, latest, 1.0f);
        }

        long floorKey = (long) Math.floor(renderTick);
        Long t0 = snapshots.floorKey(floorKey);
        if (t0 == null) {
            t0 = firstTick;
        }

        Long t1 = snapshots.higherKey(t0);
        if (t1 == null) {
            GameSnapshot latest = snapshots.get(t0);
            return new SnapshotPair(latest, latest, 1.0f);
        }

        GameSnapshot s0 = snapshots.get(t0);
        GameSnapshot s1 = snapshots.get(t1);

        float alpha = (float) (renderTick - t0) / (float) (t1 - t0);
        alpha = Math.max(0.0f, Math.min(1.0f, alpha));

        return new SnapshotPair(s0, s1, alpha);
    }

    /**
     * Retrieves the most recent snapshot received from the server.
     */
    public synchronized GameSnapshot getLatestSnapshot() {
        return snapshots.isEmpty() ? null : snapshots.lastEntry().getValue();
    }

    /**
     * Retrieves the oldest snapshot currently retained in the buffer.
     */
    public synchronized GameSnapshot getOldestSnapshot() {
        return snapshots.isEmpty() ? null : snapshots.firstEntry().getValue();
    }

    public synchronized int size() {
        return snapshots.size();
    }

    public synchronized boolean isEmpty() {
        return snapshots.isEmpty();
    }

    public synchronized void clear() {
        snapshots.clear();
    }

    public synchronized Long getLatestServerTick() {
        return snapshots.isEmpty() ? null : snapshots.lastKey();
    }

    public synchronized Long getOldestServerTick() {
        return snapshots.isEmpty() ? null : snapshots.firstKey();
    }

    public int getCapacity() {
        return capacity;
    }
}
