package com.smashprofs.server.game;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.smashprofs.server.game.InputQueue.OfferResult.ACCEPTED;
import static com.smashprofs.server.game.InputQueue.OfferResult.DUPLICATE_OR_STALE;
import static com.smashprofs.server.game.InputQueue.OfferResult.INVALID_INPUT;
import static com.smashprofs.server.game.InputQueue.OfferResult.QUEUE_FULL;
import static com.smashprofs.server.game.InputQueue.OfferResult.UNKNOWN_PLAYER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MatchRuntimeTickTest {
    private final List<MatchRuntime> createdRuntimes = new ArrayList<>();

    @After
    public void closeNativeResources() {
        createdRuntimes.forEach(MatchRuntime::close);
    }

    private MatchRuntime createRuntime(MatchSetup setup) {
        MatchRuntime runtime = new MatchRuntime(setup);
        createdRuntimes.add(runtime);
        return runtime;
    }
    private static final PlayerId FIRST = new PlayerId("player-0");
    private static final PlayerId SECOND = new PlayerId("player-1");

    @Test
    public void runtimeCreatesInputQueueOnStart() {
        MatchRuntime runtime = startedRuntime();

        assertEquals(ACCEPTED, runtime.submitInput(input(FIRST, 0)));
        assertEquals(List.of(input(FIRST, 0)), runtime.tick());
    }

    @Test
    public void runtimeDoesNotRecreateQueueOnRepeatedStart() {
        MatchRuntime runtime = startedRuntime();
        runtime.submitInput(input(FIRST, 10));

        runtime.start();

        assertEquals(DUPLICATE_OR_STALE, runtime.submitInput(input(FIRST, 10)));
        assertEquals(List.of(input(FIRST, 10)), runtime.tick());
        runtime.start();
        assertEquals(DUPLICATE_OR_STALE, runtime.submitInput(input(FIRST, 10)));
        assertTrue(runtime.tick().isEmpty());
    }

    @Test
    public void inputBeforeStartIsRejected() {
        MatchRuntime runtime = createRuntime(setup("match-1", "room-1"));

        assertThrows(IllegalStateException.class, () -> runtime.submitInput(input(FIRST, 0)));
        assertFalse(runtime.isStarted());
        runtime.start();
        assertTrue(runtime.tick().isEmpty());
    }

    @Test
    public void validInputCanBeSubmitted() {
        MatchRuntime runtime = startedRuntime();

        assertEquals(ACCEPTED, runtime.submitInput(input(FIRST, 0)));
        assertEquals(0L, runtime.getMatchState().getServerTick());
    }

    @Test
    public void unknownPlayerInputIsRejected() {
        MatchRuntime runtime = startedRuntime();

        assertEquals(UNKNOWN_PLAYER, runtime.submitInput(input(new PlayerId("unknown"), 0)));
        assertTrue(runtime.tick().isEmpty());
    }

    @Test
    public void duplicateInputSequenceIsRejected() {
        MatchRuntime runtime = startedRuntime();

        assertEquals(ACCEPTED, runtime.submitInput(input(FIRST, 10)));
        assertEquals(DUPLICATE_OR_STALE, runtime.submitInput(input(FIRST, 10)));
        assertEquals(DUPLICATE_OR_STALE, runtime.submitInput(input(FIRST, 9)));
        assertEquals(List.of(input(FIRST, 10)), runtime.tick());
    }

    @Test
    public void tickBeforeStartIsRejected() {
        MatchRuntime runtime = createRuntime(setup("match-1", "room-1"));

        assertThrows(IllegalStateException.class, runtime::tick);
        assertFalse(runtime.isStarted());
        runtime.start();
        assertEquals(0L, runtime.getMatchState().getServerTick());
    }

    @Test
    public void firstTickIncreasesServerTickToOne() {
        MatchRuntime runtime = startedRuntime();

        runtime.tick();

        assertEquals(1L, runtime.getMatchState().getServerTick());
    }

    @Test
    public void multipleTicksIncreaseServerTickCorrectly() {
        MatchRuntime runtime = startedRuntime();

        for (long expected = 1; expected <= 3; expected++) {
            runtime.tick();
            assertEquals(expected, runtime.getMatchState().getServerTick());
        }
    }

    @Test
    public void tickDrainsSubmittedInputs() {
        MatchRuntime runtime = startedRuntime();
        runtime.submitInput(input(SECOND, 0));
        runtime.submitInput(input(FIRST, 0));

        assertEquals(List.of(input(FIRST, 0), input(SECOND, 0)), runtime.tick());
        assertEquals(1L, runtime.getMatchState().getServerTick());
    }

    @Test
    public void inputIsNotConsumedTwice() {
        MatchRuntime runtime = startedRuntime();
        runtime.submitInput(input(FIRST, 0));
        assertEquals(List.of(input(FIRST, 0)), runtime.tick());

        assertTrue(runtime.tick().isEmpty());
        assertEquals(2L, runtime.getMatchState().getServerTick());
        assertEquals(DUPLICATE_OR_STALE, runtime.submitInput(input(FIRST, 0)));
    }

    @Test
    public void tickWithoutInputStillAdvances() {
        MatchRuntime runtime = startedRuntime();

        assertTrue(runtime.tick().isEmpty());
        assertTrue(runtime.tick().isEmpty());
        assertEquals(2L, runtime.getMatchState().getServerTick());
    }

    @Test
    public void movementOnlyInputDoesNotChangeHP() {
        MatchRuntime runtime = startedRuntime();
        runtime.submitInput(input(FIRST, 0)); // Stomp có self-cost 0.1 HP từ Bước 34, không thuộc assertion movement.
        runtime.tick();

        for (PlayerId id : runtime.getMatchState().getPlayerIds()) {
            assertEquals(100.0f, runtime.getMatchState().getPlayer(id).getHp(), 0.0f);
            assertFalse(runtime.getMatchState().getPlayer(id).isDead());
        }
    }

    @Test
    public void inputDoesNotChangePlayerPosition() {
        MatchRuntime runtime = startedRuntime();
        ServerPlayer player = runtime.getMatchState().getPlayer(FIRST);
        runtime.submitInput(allActions(FIRST, 0));
        runtime.tick();

        // Model chưa có position: kiểm tra không thêm tọa độ/getter giả ở bước này.
        for (String field : List.of("x", "y", "position", "velocityX", "velocityY")) {
            assertThrows(NoSuchFieldException.class, () -> ServerPlayer.class.getDeclaredField(field));
        }
        assertThrows(NoSuchMethodException.class, () -> ServerPlayer.class.getMethod("getX"));
        assertThrows(NoSuchMethodException.class, () -> ServerPlayer.class.getMethod("getY"));
        assertSame(player, runtime.getMatchState().getPlayer(FIRST));
        assertEquals("Alex", player.getCharacterId());
        assertEquals(0, player.getSlotIndex());
    }

    @Test
    public void twoMatchesHaveIndependentInputQueues() {
        MatchSetup setup = setup("match-1", "room-1");
        MatchRuntime first = createRuntime(setup);
        MatchRuntime second = createRuntime(setup);
        first.start();
        second.start();
        first.submitInput(input(FIRST, 10));

        assertTrue(second.tick().isEmpty());
        assertEquals(ACCEPTED, second.submitInput(input(FIRST, 0)));
        assertEquals(List.of(input(FIRST, 0)), second.tick());
        assertEquals(List.of(input(FIRST, 10)), first.tick());
    }

    @Test
    public void tickingOneMatchDoesNotTickAnother() {
        MatchRuntime first = startedRuntime();
        MatchRuntime second = createRuntime(setup("match-2", "room-2"));
        second.start();

        first.tick();
        first.tick();

        assertEquals(2L, first.getMatchState().getServerTick());
        assertEquals(0L, second.getMatchState().getServerTick());
        second.tick();
        assertEquals(2L, first.getMatchState().getServerTick());
        assertEquals(1L, second.getMatchState().getServerTick());
    }

    @Test(timeout = 15000)
    public void concurrentSubmissionRemainsSafe() throws Exception {
        MatchRuntime runtime = startedRuntime();
        List<Callable<InputQueue.OfferResult>> submissions = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            submissions.add(() -> runtime.submitInput(input(FIRST, 0)));
        }

        List<InputQueue.OfferResult> results = InputQueueTest.runConcurrently(submissions);

        assertEquals(1L, results.stream().filter(result -> result == ACCEPTED).count());
        assertEquals(15L, results.stream().filter(result -> result == DUPLICATE_OR_STALE).count());
        assertEquals(0L, runtime.getMatchState().getServerTick());
        assertEquals(List.of(input(FIRST, 0)), runtime.tick());
    }

    @Test
    public void existingTestsStillPass() {
        MatchRuntime runtime = startedRuntime();
        MatchState state = runtime.getMatchState();
        runtime.tick();
        runtime.start();

        assertSame(state, runtime.getMatchState());
        assertEquals(1L, state.getServerTick());
        assertEquals("match-1", runtime.getMatchId());
        assertEquals("room-1", runtime.getRoomId());
        assertEquals(2, runtime.getParticipants().size());
        assertTrue(runtime.isStarted());
    }

    @Test
    public void runtimeDelegatesInvalidInputValidation() {
        MatchRuntime runtime = startedRuntime();

        assertEquals(INVALID_INPUT, runtime.submitInput(null));
        assertEquals(INVALID_INPUT, runtime.submitInput(input(FIRST, -1)));
        assertEquals(INVALID_INPUT, runtime.submitInput(new PlayerInput(FIRST, 0, 0, Float.NaN,
                false, false, false, false, false)));
        assertTrue(runtime.tick().isEmpty());
    }

    @Test
    public void runtimeDelegatesCapacityPolicyAndRetainsWatermark() {
        MatchRuntime runtime = startedRuntime();
        for (int sequence = 0; sequence < 32; sequence++) {
            assertEquals(ACCEPTED, runtime.submitInput(input(FIRST, sequence)));
        }
        assertEquals(QUEUE_FULL, runtime.submitInput(input(FIRST, 32)));
        assertEquals(List.of(input(FIRST, 31)), runtime.tick());

        assertEquals(ACCEPTED, runtime.submitInput(input(FIRST, 32)));
        assertEquals(List.of(input(FIRST, 32)), runtime.tick());
    }

    @Test
    public void tickReusesAggregationWithoutReplayingEdges() {
        MatchRuntime runtime = startedRuntime();
        runtime.submitInput(allActions(FIRST, 0));
        PlayerInput latest = input(FIRST, 1);
        runtime.submitInput(latest);

        PlayerInput merged = runtime.tick().get(0);

        assertEquals(latest.sequence(), merged.sequence());
        assertEquals(latest.clientTick(), merged.clientTick());
        assertEquals(latest.moveAxis(), merged.moveAxis(), 0.0f);
        assertFalse(merged.blockHeld());
        assertTrue(merged.jumpPressed());
        assertTrue(merged.stompPressed());
        assertTrue(merged.meleePressed());
        assertTrue(merged.abilityPressed());
        assertTrue(runtime.tick().isEmpty());
        runtime.submitInput(input(FIRST, 2));
        assertEquals(List.of(input(FIRST, 2)), runtime.tick());
    }

    @Test
    public void tickResultIsImmutableAndDetached() {
        MatchRuntime runtime = startedRuntime();
        runtime.submitInput(input(FIRST, 0));
        List<PlayerInput> result = runtime.tick();

        assertThrows(UnsupportedOperationException.class, result::clear);
        runtime.submitInput(input(FIRST, 1));
        runtime.tick();
        assertEquals(List.of(input(FIRST, 0)), result);
    }

    @Test(timeout = 15000)
    public void otherThreadCannotTickOrDrainSimulationOwnersQueue() throws Exception {
        MatchRuntime runtime = startedRuntime();
        runtime.tick();
        runtime.submitInput(input(FIRST, 0));

        InputQueueTest.runConcurrently(List.<Callable<Boolean>>of(() -> {
            assertThrows(IllegalStateException.class, runtime::tick);
            return true;
        }));

        assertEquals(1L, runtime.getMatchState().getServerTick());
        assertEquals(List.of(input(FIRST, 0)), runtime.tick());
        assertEquals(2L, runtime.getMatchState().getServerTick());
    }

    @Test(timeout = 15000)
    public void simulationOwnerIsFirstTickThreadNotStartThread() throws Exception {
        MatchRuntime runtime = startedRuntime();

        List<List<PlayerInput>> results = InputQueueTest.runConcurrently(List.of(runtime::tick));

        assertTrue(results.get(0).isEmpty());
        assertEquals(1L, runtime.getMatchState().getServerTick());
        assertThrows(IllegalStateException.class, runtime::tick);
        assertEquals(1L, runtime.getMatchState().getServerTick());
    }

    @Test(timeout = 15000)
    public void rejectedTickBeforeStartDoesNotBindSimulationOwner() throws Exception {
        MatchRuntime runtime = createRuntime(setup("match-1", "room-1"));
        assertThrows(IllegalStateException.class, runtime::tick);
        runtime.start();

        InputQueueTest.runConcurrently(List.of(runtime::tick));

        assertEquals(1L, runtime.getMatchState().getServerTick());
    }

    @Test(timeout = 15000)
    public void producerAndSimulationOwnerCanRunConcurrently() throws Exception {
        MatchRuntime runtime = startedRuntime();
        CountDownLatch producerDone = new CountDownLatch(1);
        Callable<List<PlayerInput>> producer = () -> {
            List<PlayerInput> accepted = new ArrayList<>();
            try {
                for (int sequence = 0; sequence < 500; sequence++) {
                    // Test concurrency/aggregation, không chạy player khỏi arena rồi chịu fall penalty.
                    // Stomp aggregation được test riêng; tránh self-cost/landing damage trong test concurrency queue.
                    PlayerInput input = new PlayerInput(FIRST, sequence, sequence, 0.0f,
                            true, false, true, true, true);
                    if (runtime.submitInput(input) == ACCEPTED) {
                        accepted.add(input);
                    }
                }
                return accepted;
            } finally {
                producerDone.countDown();
            }
        };
        Callable<List<PlayerInput>> simulation = () -> {
            List<PlayerInput> consumed = new ArrayList<>();
            for (int index = 0; index < 500; index++) {
                consumed.addAll(runtime.tick());
            }
            assertTrue(producerDone.await(5, TimeUnit.SECONDS));
            consumed.addAll(runtime.tick());
            return consumed;
        };

        List<List<PlayerInput>> results = InputQueueTest.runConcurrently(List.of(producer, simulation));
        List<PlayerInput> accepted = results.get(0);
        List<PlayerInput> consumed = results.get(1);
        long previous = -1;
        for (PlayerInput input : consumed) {
            assertTrue(input.sequence() > previous);
            assertTrue(input.jumpPressed());
            previous = input.sequence();
        }
        assertEquals(accepted.get(accepted.size() - 1).sequence(), previous);
        assertEquals(501L, runtime.getMatchState().getServerTick());
        assertEquals(100.0f, runtime.getMatchState().getPlayer(FIRST).getHp(), 0.0f);
    }

    @Test
    public void stateTickMutationIsNotAPublicSetter() {
        assertThrows(NoSuchMethodException.class, () -> MatchState.class.getMethod("advanceTick"));
    }

    private MatchRuntime startedRuntime() {
        MatchRuntime runtime = createRuntime(setup("match-1", "room-1"));
        runtime.start();
        return runtime;
    }

    private static MatchSetup setup(String matchId, String roomId) {
        return new MatchSetup(matchId, roomId, List.of(
                new MatchSetup.Participant(FIRST, 0, "Alex"),
                new MatchSetup.Participant(SECOND, 1, "Luca")));
    }

    private static PlayerInput input(PlayerId id, long sequence) {
        return new PlayerInput(id, sequence, sequence, 1.0f, false, false, false, false, false);
    }

    private static PlayerInput allActions(PlayerId id, long sequence) {
        return new PlayerInput(id, sequence, sequence, -1.0f, true, true, true, true, true);
    }
}
