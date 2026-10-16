package com.smashprofs.tests.network;

import com.smashprofs.common.model.GameSnapshot;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerState;
import com.smashprofs.game.network.client.SnapshotBuffer;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class SnapshotBufferTest {

    private SnapshotBuffer buffer;

    @Before
    public void setUp() {
        buffer = new SnapshotBuffer(5);
    }

    private GameSnapshot createDummySnapshot(long tick) {
        PlayerState p1 = new PlayerState(new PlayerId("p1"), "Alex", (float) tick, 10.0f, 1f, 0f, 100, true, false, false, true);
        return new GameSnapshot("room-1", tick, "PLAYING", 50000L - tick * 33, List.of(p1));
    }

    @Test
    public void testPushAndRetrieveLatest() {
        buffer.pushSnapshot(createDummySnapshot(10L));
        buffer.pushSnapshot(createDummySnapshot(11L));
        buffer.pushSnapshot(createDummySnapshot(12L));

        assertEquals(3, buffer.size());
        assertEquals(12L, (long) buffer.getLatestServerTick());
        assertEquals(10L, (long) buffer.getOldestServerTick());
        assertEquals(12L, buffer.getLatestSnapshot().serverTick());
    }

    @Test
    public void testBoundedCapacityEvictsOldest() {
        int capacity = 3;
        SnapshotBuffer smallBuffer = new SnapshotBuffer(capacity);

        for (long tick = 1; tick <= 5; tick++) {
            smallBuffer.pushSnapshot(createDummySnapshot(tick));
        }

        assertEquals(capacity, smallBuffer.size());
        assertEquals(3L, (long) smallBuffer.getOldestServerTick());
        assertEquals(5L, (long) smallBuffer.getLatestServerTick());
    }

    @Test
    public void testOutOfOrderArrivalReordering() {
        // Arrival order: 30, then 10, then 20
        buffer.pushSnapshot(createDummySnapshot(30L));
        buffer.pushSnapshot(createDummySnapshot(10L));
        buffer.pushSnapshot(createDummySnapshot(20L));

        assertEquals(3, buffer.size());
        assertEquals(10L, (long) buffer.getOldestServerTick());
        assertEquals(30L, (long) buffer.getLatestServerTick());

        SnapshotBuffer.SnapshotPair pair = buffer.getSurroundingSnapshots(15.0f);
        assertNotNull(pair);
        assertEquals(10L, pair.from().serverTick());
        assertEquals(20L, pair.to().serverTick());
        assertEquals(0.5f, pair.alpha(), 0.0001f);
    }

    @Test
    public void testGetSurroundingSnapshotsAlphaCalculations() {
        buffer.pushSnapshot(createDummySnapshot(100L));
        buffer.pushSnapshot(createDummySnapshot(110L));

        // Exactly halfway between tick 100 and 110
        SnapshotBuffer.SnapshotPair pMid = buffer.getSurroundingSnapshots(105.0f);
        assertNotNull(pMid);
        assertEquals(100L, pMid.from().serverTick());
        assertEquals(110L, pMid.to().serverTick());
        assertEquals(0.5f, pMid.alpha(), 0.0001f);

        // Quarter progress
        SnapshotBuffer.SnapshotPair pQuarter = buffer.getSurroundingSnapshots(102.5f);
        assertEquals(0.25f, pQuarter.alpha(), 0.0001f);

        // Three quarter progress
        SnapshotBuffer.SnapshotPair pThreeQuarter = buffer.getSurroundingSnapshots(107.5f);
        assertEquals(0.75f, pThreeQuarter.alpha(), 0.0001f);
    }

    @Test
    public void testUnderflowAndOverflowHoldPolicies() {
        buffer.pushSnapshot(createDummySnapshot(100L));
        buffer.pushSnapshot(createDummySnapshot(110L));

        // Underflow: renderTick is 80 (older than oldest snapshot 100) -> hold at oldest
        SnapshotBuffer.SnapshotPair pUnderflow = buffer.getSurroundingSnapshots(80.0f);
        assertNotNull(pUnderflow);
        assertEquals(100L, pUnderflow.from().serverTick());
        assertEquals(100L, pUnderflow.to().serverTick());
        assertEquals(0.0f, pUnderflow.alpha(), 0.0001f);

        // Overflow: renderTick is 130 (newer than latest snapshot 110) -> hold at latest
        SnapshotBuffer.SnapshotPair pOverflow = buffer.getSurroundingSnapshots(130.0f);
        assertNotNull(pOverflow);
        assertEquals(110L, pOverflow.from().serverTick());
        assertEquals(110L, pOverflow.to().serverTick());
        assertEquals(1.0f, pOverflow.alpha(), 0.0001f);
    }

    @Test
    public void testMultithreadedProducerConsumerConcurrency() throws Exception {
        int totalSnapshots = 200;
        CountDownLatch finishLatch = new CountDownLatch(2);
        AtomicBoolean errorOccurred = new AtomicBoolean(false);

        // Producer: simulates Network Thread pushing snapshots
        Thread producerThread = new Thread(() -> {
            try {
                for (int i = 1; i <= totalSnapshots; i++) {
                    buffer.pushSnapshot(createDummySnapshot(i));
                    Thread.sleep(1);
                }
            } catch (Exception e) {
                errorOccurred.set(true);
            } finally {
                finishLatch.countDown();
            }
        }, "NetworkThread-Simulator");

        // Consumer: simulates Render Thread querying interpolation pairs
        Thread consumerThread = new Thread(() -> {
            try {
                for (float r = 1.0f; r <= totalSnapshots; r += 0.5f) {
                    SnapshotBuffer.SnapshotPair pair = buffer.getSurroundingSnapshots(r);
                    if (pair != null) {
                        assertNotNull(pair.from());
                        assertNotNull(pair.to());
                        assertTrue(pair.alpha() >= 0.0f && pair.alpha() <= 1.0f);
                    }
                    Thread.sleep(1);
                }
            } catch (Exception e) {
                errorOccurred.set(true);
            } finally {
                finishLatch.countDown();
            }
        }, "RenderThread-Simulator");

        producerThread.start();
        consumerThread.start();

        assertTrue("Concurrent simulation must complete within 5s", finishLatch.await(5, TimeUnit.SECONDS));
        assertFalse("No exceptions should occur during concurrent produce/consume", errorOccurred.get());
        assertTrue(buffer.size() > 0);
    }
}
