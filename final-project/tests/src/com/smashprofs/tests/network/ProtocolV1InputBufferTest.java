package com.smashprofs.tests.network;

import com.smashprofs.common.model.*;
import com.smashprofs.game.network.client.*;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class ProtocolV1InputBufferTest {
    @Test public void rejectedEnqueueRetainsEveryEdgeAndLatestHeldStateAtCapacityOne() {
        var queue = new OutboundInputQueue(new PlayerId("trusted"), 1);
        var attempts = new ArrayList<PlayerInput>();
        queue.recordFrameInput(1, true, false, false, true, false);
        assertFalse(queue.trySendAccumulatedInput(0, input -> { attempts.add(input); return false; }));
        for (int i = 1; i <= 1000; i++) {
            queue.recordFrameInput(-1, false, i == 1, i == 2, false, i == 3);
            assertFalse(queue.trySendAccumulatedInput(i, input -> { attempts.add(input); return false; }));
            assertTrue(queue.size() <= 2); // one retained input plus one accumulation window
        }
        assertTrue(queue.trySendAccumulatedInput(1001, input -> { attempts.add(input); return true; }));
        var sent = attempts.get(attempts.size() - 1);
        assertEquals(1, sent.sequence()); assertEquals(1001, sent.clientTick());
        assertEquals(-1, sent.moveAxis(), 0); assertFalse(sent.blockHeld());
        assertTrue(sent.jumpPressed()); assertTrue(sent.stompPressed()); assertTrue(sent.meleePressed()); assertTrue(sent.abilityPressed());
        assertTrue(queue.isEmpty());
        queue.recordFrameInput(0, false, false, false, true, false);
        assertTrue(queue.trySendAccumulatedInput(1002, input -> {
            assertEquals(2, input.sequence()); assertTrue(input.blockHeld());
            assertFalse(input.jumpPressed()); assertFalse(input.stompPressed()); assertFalse(input.meleePressed()); assertFalse(input.abilityPressed()); return true;
        }));
    }

    @Test public void cleanupCannotResendRejectedEdges() {
        var queue = new OutboundInputQueue(new PlayerId("trusted"));
        queue.recordFrameInput(1, true, true, true, true, true);
        assertFalse(queue.trySendAccumulatedInput(0, input -> false));
        queue.clear();
        assertTrue(queue.isEmpty());
        assertFalse(queue.trySendAccumulatedInput(1, input -> { fail("No stale input after clear"); return true; }));
    }

    @Test public void bufferRejectsDuplicatesAndStaleTicksAndKeepsActual60HzDomain() {
        var buffer = new SnapshotBuffer.V1(2);
        assertTrue(buffer.push(WireGameSnapshot.parse(WireGameSnapshotTest.payload(4, 3))));
        assertFalse(buffer.push(WireGameSnapshot.parse(WireGameSnapshotTest.payload(4, 3))));
        assertFalse(buffer.push(WireGameSnapshot.parse(WireGameSnapshotTest.payload(4, 2))));
        assertTrue(buffer.push(WireGameSnapshot.parse(WireGameSnapshotTest.payload(4, 6))));
        assertTrue(buffer.push(WireGameSnapshot.parse(WireGameSnapshotTest.payload(4, 9))));
        assertEquals(List.of(6L, 9L), buffer.getSnapshots().stream().map(WireGameSnapshot::serverTick).toList());
        assertThrows(UnsupportedOperationException.class, () -> buffer.getSnapshots().clear());
        buffer.clear(); assertTrue(buffer.isEmpty()); assertNull(buffer.getLatestSnapshot());
        assertTrue(buffer.push(WireGameSnapshot.parse(WireGameSnapshotTest.payload(4, 3))));
    }

    @Test public void metricsTreatThreeTickCadenceAsOneBroadcastAndIgnoreStaleArrivals() {
        var metrics = new NetworkMetricsTracker(3);
        for (int i = 0; i <= 20; i++) { metrics.recordSnapshot(3L + i * 3, i * 50L); }
        assertEquals(0, metrics.getDroppedSnapshots());
        assertEquals(21, metrics.getSnapshotFrequencyHz(), 0.01); // arrivals counted over first one-second window
        metrics.recordSnapshot(63, 1050); metrics.recordSnapshot(60, 1100);
        assertEquals(0, metrics.getDroppedSnapshots());
        metrics.recordSnapshot(69, 1150); assertEquals(1, metrics.getDroppedSnapshots());
        metrics.recordSnapshot(70, 1151); assertEquals(1, metrics.getDroppedSnapshots()); // final state outside cadence
    }

    @Test(timeout=5000) public void readerAndConsumerShareImmutableBoundedSnapshots() throws Exception {
        var buffer = new SnapshotBuffer.V1(8);
        var done = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread reader = new Thread(() -> {
            try { for (int i = 1; i <= 300; i++) { assertTrue(buffer.push(WireGameSnapshot.parse(WireGameSnapshotTest.payload(4, i * 3L)))); } }
            catch (Throwable t) { failure.set(t); }
            finally { done.countDown(); }
        });
        reader.start();
        do {
            var copy = buffer.getSnapshots();
            assertTrue(copy.size() <= 8);
            for (int i = 1; i < copy.size(); i++) { assertTrue(copy.get(i).serverTick() > copy.get(i - 1).serverTick()); }
        } while (!done.await(1, TimeUnit.MILLISECONDS));
        reader.join(1000); assertFalse(reader.isAlive()); assertNull(failure.get());
        assertEquals(900, buffer.getLatestSnapshot().serverTick());
    }
}
