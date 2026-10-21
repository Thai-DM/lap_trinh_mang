package com.smashprofs.tests.network;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import com.smashprofs.game.network.client.OutboundInputQueue;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class OutboundInputQueueTest {

    private PlayerId playerId;
    private OutboundInputQueue queue;

    @Before
    public void setUp() {
        playerId = new PlayerId("player-test-01");
        queue = new OutboundInputQueue(playerId, 10);
    }

    @Test
    public void testContinuousStateOverwritesWithLatest() {
        // Sub-frame 1: moving left, no block
        queue.recordFrameInput(-1.0f, false, false, false, false, false);
        // Sub-frame 2: redirected moving right, holding block
        queue.recordFrameInput(0.85f, false, false, false, true, false);

        PlayerInput input = queue.pollNextInputToSend(100L);

        assertNotNull("Input must be produced from accumulated frames", input);
        assertEquals(playerId, input.playerId());
        assertEquals(100L, input.clientTick());
        assertEquals(0.85f, input.moveAxis(), 0.0001f);
        assertTrue("Block held must take latest true state", input.blockHeld());
        assertFalse(input.jumpPressed());
        assertFalse(input.meleePressed());
    }

    @Test
    public void testDiscreteEventsCarryForwardAcrossSubFrames() {
        // Sub-frame 1: tapped Jump
        queue.recordFrameInput(0.0f, true, false, false, false, false);
        // Sub-frame 2: released Jump, tapped Melee
        queue.recordFrameInput(0.0f, false, false, true, false, false);
        // Sub-frame 3: released Melee, tapped Ability
        queue.recordFrameInput(0.5f, false, false, false, false, true);

        PlayerInput input = queue.pollNextInputToSend(101L);

        assertNotNull(input);
        assertTrue("Jump must be carried forward via OR-logic", input.jumpPressed());
        assertTrue("Melee must be carried forward via OR-logic", input.meleePressed());
        assertTrue("Ability must be carried forward via OR-logic", input.abilityPressed());
        assertFalse("Stomp was never pressed and must remain false", input.stompPressed());

        // Subsequent frame with no button presses should reset discrete flags
        queue.recordFrameInput(0.5f, false, false, false, false, false);
        PlayerInput nextInput = queue.pollNextInputToSend(102L);

        assertNotNull(nextInput);
        assertFalse("Jump must reset after flush", nextInput.jumpPressed());
        assertFalse("Melee must reset after flush", nextInput.meleePressed());
        assertFalse("Ability must reset after flush", nextInput.abilityPressed());
        assertEquals("Continuous moveAxis persists", 0.5f, nextInput.moveAxis(), 0.0001f);
    }

    @Test
    public void testSequenceNumbersStrictlyMonotonic() {
        for (int i = 1; i <= 5; i++) {
            queue.recordFrameInput(0.1f * i, false, false, false, false, false);
            PlayerInput input = queue.pollNextInputToSend(i * 10L);
            assertNotNull(input);
            assertEquals("Sequence number must be strictly monotonic starting at 1", i, input.sequence());
            assertEquals(i * 10L, input.clientTick());
        }
    }

    @Test
    public void testBoundedCapacityDropsOldest() {
        int smallCapacity = 3;
        OutboundInputQueue smallQueue = new OutboundInputQueue(playerId, smallCapacity);

        // Enqueue 5 discrete inputs
        for (int i = 1; i <= 5; i++) {
            smallQueue.recordFrameInput((float) i, false, false, false, false, false);
            smallQueue.flushAccumulatedInput(i * 10L);
        }

        // Queue size should be bounded at smallCapacity (3)
        assertEquals(smallCapacity, smallQueue.size());

        // The polled inputs should be the newest 3: sequences 3, 4, 5 (1 and 2 dropped)
        PlayerInput p1 = smallQueue.pollNextInputToSend(0L);
        PlayerInput p2 = smallQueue.pollNextInputToSend(0L);
        PlayerInput p3 = smallQueue.pollNextInputToSend(0L);

        assertNotNull(p1);
        assertNotNull(p2);
        assertNotNull(p3);
        assertEquals(3L, p1.sequence());
        assertEquals(4L, p2.sequence());
        assertEquals(5L, p3.sequence());
        assertTrue(smallQueue.isEmpty());
    }

    @Test
    public void testMultithreadedConcurrencyProducerConsumer() throws Exception {
        int totalInputs = 300;
        CountDownLatch finishLatch = new CountDownLatch(2);
        List<Long> receivedSequences = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean errorOccurred = new AtomicBoolean(false);

        // Producer: simulates 60 FPS Render Thread
        Thread producerThread = new Thread(() -> {
            try {
                for (int i = 1; i <= totalInputs; i++) {
                    queue.recordFrameInput(0.5f, (i % 2 == 0), false, (i % 3 == 0), false, false);
                    queue.flushAccumulatedInput(i * 16L);
                    Thread.sleep(1);
                }
            } catch (Exception e) {
                errorOccurred.set(true);
            } finally {
                finishLatch.countDown();
            }
        }, "RenderThread-Simulator");

        // Consumer: simulates Network Writer Thread polling periodically
        Thread consumerThread = new Thread(() -> {
            try {
                int consumed = 0;
                while (consumed < totalInputs) {
                    PlayerInput input = queue.pollNextInputToSend(0L);
                    if (input != null) {
                        receivedSequences.add(input.sequence());
                        consumed++;
                    } else {
                        Thread.sleep(1);
                    }
                }
            } catch (Exception e) {
                errorOccurred.set(true);
            } finally {
                finishLatch.countDown();
            }
        }, "NetworkWriter-Simulator");

        producerThread.start();
        consumerThread.start();

        assertTrue("Threads must complete within 5s", finishLatch.await(5, TimeUnit.SECONDS));
        assertFalse("No exceptions should occur during concurrent produce/consume", errorOccurred.get());
        assertEquals(totalInputs, receivedSequences.size());

        // Verify sequence ordering remains strictly increasing
        for (int i = 1; i < receivedSequences.size(); i++) {
            assertTrue("Sequences must be monotonic", receivedSequences.get(i) > receivedSequences.get(i - 1));
        }
    }
}
