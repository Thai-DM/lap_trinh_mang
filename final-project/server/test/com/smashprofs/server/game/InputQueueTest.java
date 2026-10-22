package com.smashprofs.server.game;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static com.smashprofs.server.game.InputQueue.OfferResult.ACCEPTED;
import static com.smashprofs.server.game.InputQueue.OfferResult.DUPLICATE_OR_STALE;
import static com.smashprofs.server.game.InputQueue.OfferResult.INVALID_INPUT;
import static com.smashprofs.server.game.InputQueue.OfferResult.QUEUE_FULL;
import static com.smashprofs.server.game.InputQueue.OfferResult.UNKNOWN_PLAYER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class InputQueueTest {
    @Test public void inventoryEdgesSurviveCoalescingAndAreNotReplayedOnNextTick() {
        assertEquals(ACCEPTED,queue.offer(new PlayerInput(FIRST,1,0,1,false,false,false,false,false,true,false)));
        assertEquals(ACCEPTED,queue.offer(new PlayerInput(FIRST,2,0,-1,false,false,false,false,false,false,true)));
        var input=queue.drainForTick().get(0);assertTrue(input.interactPressed());assertTrue(input.dropPressed());assertEquals(-1,input.moveAxis(),0);
        assertTrue(queue.drainForTick().isEmpty());assertEquals(DUPLICATE_OR_STALE,queue.offer(input));
    }
    private static final PlayerId FIRST = new PlayerId("player-0");
    private static final PlayerId SECOND = new PlayerId("player-1");
    private final InputQueue queue = new InputQueue(setup(2));

    @Test
    public void validInputIsAccepted() {
        assertEquals(ACCEPTED, queue.offer(input(FIRST, 0, 1.0f)));
        assertEquals(1, queue.getPendingCount());
    }

    @Test
    public void nullInputIsRejected() {
        assertEquals(INVALID_INPUT, queue.offer(null));
        assertEquals(0, queue.getPendingCount());
    }

    @Test
    public void unknownPlayerIsRejected() {
        assertEquals(UNKNOWN_PLAYER, queue.offer(input(new PlayerId("unknown"), 0, 0.0f)));
        assertEquals(0, queue.getPendingCount());
    }

    @Test
    public void negativeSequenceIsRejected() {
        assertEquals(INVALID_INPUT, queue.offer(input(FIRST, -1, 0.0f)));
        assertEquals(ACCEPTED, queue.offer(input(FIRST, 0, 0.0f)));
    }

    @Test
    public void duplicateSequenceIsIgnored() {
        assertEquals(ACCEPTED, queue.offer(input(FIRST, 10, 1.0f)));
        assertEquals(DUPLICATE_OR_STALE, queue.offer(input(FIRST, 10, -1.0f)));
        assertEquals(1, queue.getPendingCount());
        assertEquals(1.0f, queue.drainForTick().get(0).moveAxis(), 0.0f);
    }

    @Test
    public void olderSequenceIsIgnored() {
        assertEquals(ACCEPTED, queue.offer(input(FIRST, 10, 0.0f)));
        assertEquals(DUPLICATE_OR_STALE, queue.offer(input(FIRST, 9, 0.0f)));
        assertEquals(ACCEPTED, queue.offer(input(FIRST, 11, 0.0f)));
        assertEquals(2, queue.getPendingCount());
    }

    @Test
    public void invalidMoveAxisIsRejected() {
        for (float axis : new float[]{-1.01f, 1.01f, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY}) {
            assertEquals(INVALID_INPUT, queue.offer(input(FIRST, 0, axis)));
        }
        assertEquals(0, queue.getPendingCount());
    }

    @Test
    public void nanMoveAxisIsRejected() {
        assertEquals(INVALID_INPUT, queue.offer(input(FIRST, 0, Float.NaN)));
        assertEquals(0, queue.getPendingCount());
    }

    @Test
    public void queueCannotGrowBeyondCapacity() {
        InputQueue bounded = new InputQueue(setup(2), 2);
        assertEquals(ACCEPTED, bounded.offer(input(FIRST, 0, 0.0f)));
        assertEquals(ACCEPTED, bounded.offer(input(FIRST, 1, 0.0f)));

        for (int sequence = 2; sequence < 100; sequence++) {
            assertEquals(QUEUE_FULL, bounded.offer(input(FIRST, sequence, 0.0f)));
            assertEquals(2, bounded.getPendingCount());
        }
    }

    @Test
    public void drainReturnsAcceptedInputs() {
        PlayerInput first = input(FIRST, 0, 1.0f);
        PlayerInput second = input(SECOND, 0, -1.0f);
        queue.offer(second);
        queue.offer(first);

        assertEquals(List.of(first, second), queue.drainForTick());
        assertEquals(0, queue.getPendingCount());
    }

    @Test
    public void drainDoesNotReturnSameInputTwice() {
        queue.offer(input(FIRST, 0, 0.0f));
        assertEquals(1, queue.drainForTick().size());

        assertTrue(queue.drainForTick().isEmpty());
        assertEquals(0, queue.getPendingCount());
    }

    @Test
    public void latestMovementIsUsed() {
        queue.offer(input(FIRST, 0, 1.0f));
        queue.offer(input(FIRST, 1, -1.0f));

        assertEquals(List.of(input(FIRST, 1, -1.0f)), queue.drainForTick());
    }

    @Test
    public void jumpPressedIsNotLostDuringAggregation() {
        queue.offer(new PlayerInput(FIRST, 0, 0, 1.0f, true, false, false, false, false));
        queue.offer(input(FIRST, 1, 1.0f));

        PlayerInput merged = queue.drainForTick().get(0);

        assertTrue(merged.jumpPressed());
        assertEquals(1L, merged.sequence());
        assertTrue(queue.drainForTick().isEmpty());
        queue.offer(input(FIRST, 2, 1.0f));
        assertFalse(queue.drainForTick().get(0).jumpPressed());
    }

    @Test
    public void playersHaveIndependentSequences() {
        assertEquals(ACCEPTED, queue.offer(input(FIRST, 100, 0.0f)));
        assertEquals(ACCEPTED, queue.offer(input(SECOND, 0, 0.0f)));
        assertEquals(DUPLICATE_OR_STALE, queue.offer(input(FIRST, 99, 0.0f)));
        assertEquals(ACCEPTED, queue.offer(input(SECOND, 1, 0.0f)));
        assertEquals(List.of(input(FIRST, 100, 0.0f), input(SECOND, 1, 0.0f)), queue.drainForTick());
    }

    @Test
    public void twoQueuesDoNotShareState() {
        MatchSetup setup = setup(2);
        InputQueue first = new InputQueue(setup);
        InputQueue second = new InputQueue(setup);
        first.offer(input(FIRST, 10, 1.0f));

        assertEquals(0, second.getPendingCount());
        assertEquals(ACCEPTED, second.offer(input(FIRST, 0, -1.0f)));
        first.clear();
        assertEquals(List.of(input(FIRST, 0, -1.0f)), second.drainForTick());
        assertTrue(first.drainForTick().isEmpty());
    }

    @Test(timeout = 15000)
    public void concurrentOffersAreThreadSafe() throws Exception {
        List<Callable<InputQueue.OfferResult>> offers = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            offers.add(() -> queue.offer(input(FIRST, 0, 0.0f)));
        }

        List<InputQueue.OfferResult> results = runConcurrently(offers);

        assertEquals(1L, results.stream().filter(result -> result == ACCEPTED).count());
        assertEquals(15L, results.stream().filter(result -> result == DUPLICATE_OR_STALE).count());
        assertEquals(1, queue.getPendingCount());
        assertEquals(List.of(input(FIRST, 0, 0.0f)), queue.drainForTick());
    }

    @Test
    public void existingTestsStillPass() {
        MatchSetup setup = setup(2);
        MatchRuntime runtime = new MatchRuntime(setup);
        runtime.start();
        MatchState state = runtime.getMatchState();
        InputQueue independent = new InputQueue(setup);

        independent.offer(input(FIRST, 0, 1.0f));
        independent.drainForTick();
        runtime.start();

        assertEquals(setup.matchId(), state.getMatchId());
        assertEquals(0L, state.getServerTick());
        assertEquals(100.0f, state.getPlayer(FIRST).getHp(), 0.0f);
        assertFalse(state.getPlayer(FIRST).isDead());
    }

    @Test
    public void nullOrInvalidPlayerIdIsRejected() {
        for (PlayerId id : new PlayerId[]{null, new PlayerId(null), new PlayerId(""), new PlayerId(" ")}) {
            assertEquals(INVALID_INPUT, queue.offer(input(id, 0, 0.0f)));
        }
        assertEquals(0, queue.getPendingCount());
    }

    @Test
    public void moveAxisEndpointsAreValid() {
        assertEquals(ACCEPTED, queue.offer(input(FIRST, 0, -1.0f)));
        assertEquals(ACCEPTED, queue.offer(input(FIRST, 1, 1.0f)));
    }

    @Test
    public void defaultCapacityIs32PerPlayer() {
        for (int sequence = 0; sequence < 32; sequence++) {
            assertEquals(ACCEPTED, queue.offer(input(FIRST, sequence, 0.0f)));
            assertEquals(ACCEPTED, queue.offer(input(SECOND, sequence, 0.0f)));
        }
        assertEquals(64, queue.getPendingCount());
        assertEquals(QUEUE_FULL, queue.offer(input(FIRST, 32, 0.0f)));
        assertEquals(QUEUE_FULL, queue.offer(input(SECOND, 32, 0.0f)));
    }

    @Test
    public void capacityIsIndependentForEachPlayer() {
        InputQueue bounded = new InputQueue(setup(2), 1);
        assertEquals(ACCEPTED, bounded.offer(input(FIRST, 0, 0.0f)));
        assertEquals(QUEUE_FULL, bounded.offer(input(FIRST, 1, 0.0f)));
        assertEquals(ACCEPTED, bounded.offer(input(SECOND, 0, 0.0f)));
        assertEquals(2, bounded.getPendingCount());
    }

    @Test
    public void fullQueueDoesNotAdvanceAcceptedSequence() {
        InputQueue bounded = new InputQueue(setup(2), 1);
        bounded.offer(input(FIRST, 0, 0.0f));
        assertEquals(QUEUE_FULL, bounded.offer(input(FIRST, 1, 0.0f)));
        bounded.drainForTick();

        assertEquals(ACCEPTED, bounded.offer(input(FIRST, 1, 0.0f)));
        assertEquals(List.of(input(FIRST, 1, 0.0f)), bounded.drainForTick());
    }

    @Test
    public void invalidInputDoesNotAdvanceAcceptedSequence() {
        assertEquals(INVALID_INPUT, queue.offer(input(FIRST, 100, Float.NaN)));
        assertEquals(ACCEPTED, queue.offer(input(FIRST, 0, 0.0f)));
    }

    @Test
    public void drainKeepsSequenceWatermark() {
        queue.offer(input(FIRST, 10, 0.0f));
        queue.drainForTick();

        assertEquals(DUPLICATE_OR_STALE, queue.offer(input(FIRST, 10, 0.0f)));
        assertEquals(DUPLICATE_OR_STALE, queue.offer(input(FIRST, 9, 0.0f)));
        assertEquals(ACCEPTED, queue.offer(input(FIRST, 11, 0.0f)));
    }

    @Test
    public void clearRemovesPendingInputsButKeepsWatermark() {
        queue.offer(input(FIRST, 10, 0.0f));
        queue.offer(input(SECOND, 0, 0.0f));

        queue.clear();
        queue.clear();

        assertEquals(0, queue.getPendingCount());
        assertTrue(queue.drainForTick().isEmpty());
        assertEquals(DUPLICATE_OR_STALE, queue.offer(input(FIRST, 10, 0.0f)));
        assertEquals(ACCEPTED, queue.offer(input(FIRST, 11, 0.0f)));
    }

    @Test
    public void latestBlockHeldIsUsedInsteadOfOr() {
        queue.offer(new PlayerInput(FIRST, 0, 0, 0.0f, false, false, false, true, false));
        queue.offer(input(FIRST, 1, 0.0f));
        assertFalse(queue.drainForTick().get(0).blockHeld());

        queue.offer(new PlayerInput(FIRST, 2, 0, 0.0f, false, false, false, true, false));
        assertTrue(queue.drainForTick().get(0).blockHeld());
    }

    @Test
    public void allPressedActionsAreAggregatedAndNotReplayed() {
        queue.offer(new PlayerInput(FIRST, 0, 0, 0.0f, true, false, false, false, false));
        queue.offer(new PlayerInput(FIRST, 1, 0, 0.0f, false, true, false, false, false));
        queue.offer(new PlayerInput(FIRST, 2, 0, 0.0f, false, false, true, false, false));
        queue.offer(new PlayerInput(FIRST, 3, 0, 0.0f, false, false, false, false, true));
        queue.offer(input(FIRST, 4, 0.0f));

        PlayerInput merged = queue.drainForTick().get(0);
        assertTrue(merged.jumpPressed());
        assertTrue(merged.stompPressed());
        assertTrue(merged.meleePressed());
        assertTrue(merged.abilityPressed());
        queue.offer(input(FIRST, 5, 0.0f));
        assertEquals(List.of(input(FIRST, 5, 0.0f)), queue.drainForTick());
    }

    @Test
    public void playersDoNotShareActionEdges() {
        queue.offer(new PlayerInput(FIRST, 0, 0, 0.0f, true, true, true, false, true));
        queue.offer(input(SECOND, 0, 0.0f));

        assertEquals(input(SECOND, 0, 0.0f), queue.drainForTick().get(1));
    }

    @Test
    public void latestClientTickIsMetadataNotAuthoritativeTime() {
        queue.offer(new PlayerInput(FIRST, 0, Long.MAX_VALUE, 0.0f, false, false, false, false, false));
        PlayerInput latest = new PlayerInput(FIRST, 1, Long.MIN_VALUE, 1.0f, false, false, false, false, false);

        assertEquals(ACCEPTED, queue.offer(latest));
        assertEquals(List.of(latest), queue.drainForTick());
    }

    @Test
    public void maximumSequenceDoesNotOverflow() {
        assertEquals(ACCEPTED, queue.offer(input(FIRST, Long.MAX_VALUE, 0.0f)));
        queue.drainForTick();
        assertEquals(DUPLICATE_OR_STALE, queue.offer(input(FIRST, Long.MAX_VALUE, 0.0f)));
        assertEquals(DUPLICATE_OR_STALE, queue.offer(input(FIRST, 0, 0.0f)));
    }

    @Test
    public void drainedBatchIsImmutableAndDetached() {
        queue.offer(input(FIRST, 0, 0.0f));
        List<PlayerInput> batch = queue.drainForTick();
        assertThrows(UnsupportedOperationException.class, batch::clear);
        queue.offer(input(FIRST, 1, 1.0f));
        queue.clear();
        assertEquals(List.of(input(FIRST, 0, 0.0f)), batch);
    }

    @Test
    public void invalidConstructorArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> new InputQueue(null));
        assertThrows(IllegalArgumentException.class, () -> new InputQueue(setup(2), 0));
        assertThrows(IllegalArgumentException.class, () -> new InputQueue(setup(2), -1));
    }

    @Test
    public void fourPlayersHaveIndependentBoundedBuffers() {
        MatchSetup setup = setup(4);
        InputQueue bounded = new InputQueue(setup, 1);
        for (MatchSetup.Participant participant : setup.participants()) {
            assertEquals(ACCEPTED, bounded.offer(input(participant.playerId(), 0, 0.0f)));
            assertEquals(QUEUE_FULL, bounded.offer(input(participant.playerId(), 1, 0.0f)));
        }
        assertEquals(4, bounded.getPendingCount());
        assertEquals(4, bounded.drainForTick().size());
        assertEquals(0, bounded.getPendingCount());
    }

    @Test(timeout = 15000)
    public void concurrentUniqueOffersNeverExceedCapacity() throws Exception {
        InputQueue bounded = new InputQueue(setup(2), 2);
        List<Callable<InputQueue.OfferResult>> offers = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            long sequence = index;
            offers.add(() -> bounded.offer(input(FIRST, sequence, 0.0f)));
        }

        List<InputQueue.OfferResult> results = runConcurrently(offers);
        long accepted = results.stream().filter(result -> result == ACCEPTED).count();

        assertTrue(accepted >= 1 && accepted <= 2);
        assertEquals(accepted, bounded.getPendingCount());
        assertEquals(1, bounded.drainForTick().size());
        assertEquals(0, bounded.getPendingCount());
    }

    @Test(timeout = 15000)
    public void offersAndDrainsCanRunConcurrently() throws Exception {
        InputQueue bounded = new InputQueue(setup(2), 4);
        Callable<List<PlayerInput>> producer = () -> {
            List<PlayerInput> accepted = new ArrayList<>();
            for (int sequence = 0; sequence < 500; sequence++) {
                PlayerInput input = new PlayerInput(FIRST, sequence, sequence, 0.0f,
                        true, false, false, false, false);
                if (bounded.offer(input) == ACCEPTED) {
                    accepted.add(input);
                }
                assertTrue(bounded.getPendingCount() <= 4);
            }
            return accepted;
        };
        Callable<List<PlayerInput>> consumer = () -> {
            List<PlayerInput> drained = new ArrayList<>();
            for (int index = 0; index < 500; index++) {
                drained.addAll(bounded.drainForTick());
            }
            return drained;
        };

        List<List<PlayerInput>> results = runConcurrently(List.of(producer, consumer));
        List<PlayerInput> accepted = results.get(0);
        List<PlayerInput> drained = new ArrayList<>(results.get(1));
        drained.addAll(bounded.drainForTick());
        long previous = -1;
        for (PlayerInput input : drained) {
            assertTrue(input.sequence() > previous);
            assertTrue(input.jumpPressed());
            previous = input.sequence();
        }
        assertEquals(accepted.get(accepted.size() - 1).sequence(), previous);
        assertEquals(0, bounded.getPendingCount());
    }

    private static PlayerInput input(PlayerId id, long sequence, float axis) {
        return new PlayerInput(id, sequence, sequence, axis, false, false, false, false, false);
    }

    private static MatchSetup setup(int count) {
        List<MatchSetup.Participant> participants = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            participants.add(new MatchSetup.Participant(new PlayerId("player-" + index), index, "Alex"));
        }
        return new MatchSetup("match-1", "room-1", participants);
    }

    /** Thread tạm chỉ phục vụ test concurrency; mọi chờ đợi có giới hạn, không dùng executor/sleep. */
    static <T> List<T> runConcurrently(List<Callable<T>> actions) throws Exception {
        CountDownLatch ready = new CountDownLatch(actions.size());
        CountDownLatch start = new CountDownLatch(1);
        List<FutureTask<T>> tasks = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        try {
            for (Callable<T> action : actions) {
                FutureTask<T> task = new FutureTask<>(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Hết thời gian chờ bắt đầu test input");
                    }
                    return action.call();
                });
                Thread thread = new Thread(task, "input-queue-test-" + threads.size());
                thread.setDaemon(true);
                tasks.add(task);
                threads.add(thread);
                thread.start();
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            List<T> results = new ArrayList<>();
            for (FutureTask<T> task : tasks) {
                results.add(task.get(Math.max(1L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            }
            return results;
        } finally {
            start.countDown();
            for (Thread thread : threads) {
                thread.interrupt();
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            for (Thread thread : threads) {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) {
                    TimeUnit.NANOSECONDS.timedJoin(thread, remaining);
                }
                assertFalse("Thread của test phải dừng sau cleanup", thread.isAlive());
            }
        }
    }
}
