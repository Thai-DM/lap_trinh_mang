package com.smashprofs.server.game;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.*;

/** Test chu kỳ thủ công; chỉ test giải phóng tài nguyên mới tạo executor thật. */
public class FixedTickSchedulerTest {
    private final List<MatchRuntime> createdRuntimes = new ArrayList<>();

    private MatchRuntime createRuntime(MatchSetup setup) {
        MatchRuntime runtime = new MatchRuntime(setup);
        createdRuntimes.add(runtime);
        return runtime;
    }
    private ManualScheduledExecutor executor;
    private FixedTickScheduler scheduler;
    private Logger logger;
    private List<LogRecord> failures;
    private Consumer<LogRecord> onFailure;

    @Before
    public void setUp() {
        executor = new ManualScheduledExecutor();
        failures = new ArrayList<>();
        onFailure = record -> { };
        logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                failures.add(record);
                onFailure.accept(record);
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        });
        scheduler = new FixedTickScheduler(executor, logger);
    }

    @After
    public void tearDown() {
        scheduler.stop();
        createdRuntimes.forEach(MatchRuntime::close);
    }

    @Test
    public void schedulerInitiallyStopped() {
        assertFalse(scheduler.isRunning());
        assertEquals(0, executor.getScheduleCount());
    }

    @Test
    public void schedulerStartsSuccessfully() {
        scheduler.start();
        assertTrue(scheduler.isRunning());
        assertEquals(1, executor.getScheduleCount());
    }

    @Test
    public void schedulerStopsSuccessfully() {
        MatchRuntime runtime = startedRuntime("match-1");
        scheduler.register(runtime);
        scheduler.start();
        scheduler.stop();
        executor.runCycle();
        assertFalse(scheduler.isRunning());
        assertTrue(executor.isTaskCancelled());
        assertTrue(executor.isTerminated());
        assertEquals(0, tickCount(runtime));
    }

    @Test
    public void startingTwiceDoesNotCreateDuplicateTasks() {
        MatchRuntime runtime = startedRuntime("match-1");
        scheduler.register(runtime);
        scheduler.start();
        scheduler.start();
        executor.runCycle();
        assertEquals(1, executor.getScheduleCount());
        assertEquals(1, tickCount(runtime));
    }

    @Test
    public void stoppingTwiceIsSafe() {
        scheduler.start();
        scheduler.stop();
        scheduler.stop();
        assertFalse(scheduler.isRunning());
        assertTrue(executor.isTerminated());
    }

    @Test
    public void registeredRuntimeReceivesTicks() {
        MatchRuntime runtime = startedRuntime("match-1");
        scheduler.register(runtime);
        scheduler.start();
        executor.runCycle();
        executor.runCycle();
        assertEquals(2, tickCount(runtime));
    }

    @Test
    public void unregisteredRuntimeStopsReceivingTicks() {
        MatchRuntime runtime = startedRuntime("match-1");
        scheduler.register(runtime);
        scheduler.start();
        executor.runCycle();
        assertTrue(scheduler.unregister(runtime));
        assertFalse(scheduler.unregister(runtime));
        executor.runCycle();
        assertEquals(1, tickCount(runtime));
    }

    @Test
    public void duplicateRegistrationDoesNotDoubleTick() {
        MatchRuntime runtime = startedRuntime("match-1");
        assertTrue(scheduler.register(runtime));
        assertFalse(scheduler.register(runtime));
        scheduler.start();
        executor.runCycle();
        assertEquals(1, tickCount(runtime));
    }

    @Test
    public void twoMatchesReceiveIndependentTicks() {
        MatchRuntime first = startedRuntime("match-a");
        MatchRuntime second = startedRuntime("match-b");
        scheduler.register(first);
        scheduler.register(second);
        scheduler.start();
        executor.runCycle();
        executor.runCycle();
        assertEquals(2, tickCount(first));
        assertEquals(2, tickCount(second));
        scheduler.unregister(first);
        executor.runCycle();
        assertEquals(2, tickCount(first));
        assertEquals(3, tickCount(second));
        assertNotSame(first.getMatchState(), second.getMatchState());
    }

    @Test
    public void tickingRoomADoesNotModifyRoomBState() {
        MatchRuntime first = startedRuntime("match-a");
        MatchRuntime second = startedRuntime("match-b");
        // Cùng PlayerId cũng không được chia sẻ ServerPlayer hoặc MatchState giữa hai trận.
        PlayerId id = first.getParticipants().get(0).playerId();
        assertNotSame(first.getMatchState().getPlayer(id), second.getMatchState().getPlayer(id));
        scheduler.register(first);
        scheduler.start();
        executor.runCycle();
        assertEquals(1, tickCount(first));
        assertEquals(0, tickCount(second));
        assertEquals(100.0f, second.getMatchState().getPlayer(id).getHp(), 0.0f);
        assertFalse(second.getMatchState().getPlayer(id).isDead());
    }

    @Test
    public void unstartedRuntimeIsNotTicked() {
        MatchRuntime runtime = runtime("match-1");
        scheduler.register(runtime);
        scheduler.start();
        executor.runCycle();
        assertFalse(runtime.isStarted());
        assertNull(runtime.getMatchState());
        assertTrue(failures.isEmpty());
        runtime.start();
        executor.runCycle();
        assertEquals(1, tickCount(runtime));
    }

    @Test(timeout = 15000)
    public void schedulerRunsMatchesSequentially() throws Exception {
        MatchRuntime first = startedRuntime("match-a");
        MatchRuntime middle = runtimeOwnedByAnotherThread("match-b");
        MatchRuntime last = startedRuntime("match-c");
        Thread simulationThread = Thread.currentThread();
        // Lỗi owner ở giữa làm điểm quan sát: trận trước đã tick, trận sau chưa tick.
        onFailure = record -> {
            assertSame(simulationThread, Thread.currentThread());
            assertEquals(1, tickCount(first));
            assertEquals(0, tickCount(last));
        };
        scheduler.register(first);
        scheduler.register(middle);
        scheduler.register(last);
        scheduler.start();
        executor.runCycle();
        assertEquals(1, failures.size());
        assertEquals(1, tickCount(first));
        assertEquals(1, tickCount(last));
        // Owner của cả hai trận là thread chạy chu kỳ, không có thread riêng cho từng trận.
        List<Boolean> rejected = InputQueueTest.runConcurrently(List.of(
                () -> rejectsForeignTick(first), () -> rejectsForeignTick(last)));
        assertEquals(List.of(true, true), rejected);
    }

    @Test(timeout = 15000)
    public void concurrentRegistrationIsSafe() throws Exception {
        MatchRuntime runtime = startedRuntime("match-1");
        List<Callable<Boolean>> actions = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            actions.add(() -> scheduler.register(runtime));
        }
        List<Boolean> results = InputQueueTest.runConcurrently(actions);
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        scheduler.start();
        executor.runCycle();
        assertEquals(1, tickCount(runtime));
        actions.clear();
        for (int index = 0; index < 16; index++) {
            actions.add(() -> scheduler.unregister(runtime));
        }
        results = InputQueueTest.runConcurrently(actions);
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        executor.runCycle();
        assertEquals(1, tickCount(runtime));
    }

    @Test(timeout = 15000)
    public void schedulerShutdownReleasesResources() throws Exception {
        AtomicReference<Thread> worker = new AtomicReference<>();
        ScheduledThreadPoolExecutor realExecutor = new ScheduledThreadPoolExecutor(1, command -> {
            Thread thread = new Thread(command, "fixed-tick-shutdown-test");
            // Tránh giữ JVM nếu test lỗi; vẫn kiểm tra worker thực sự kết thúc sau stop.
            thread.setDaemon(true);
            worker.set(thread);
            return thread;
        });
        FixedTickScheduler realScheduler = new FixedTickScheduler(realExecutor, logger);
        CountDownLatch executed = new CountDownLatch(1);
        try {
            realScheduler.start();
            realExecutor.execute(executed::countDown);
            assertTrue(executed.await(5, TimeUnit.SECONDS));
        } finally {
            realScheduler.stop();
        }
        assertFalse(realScheduler.isRunning());
        assertTrue(realExecutor.isShutdown());
        assertTrue(realExecutor.isTerminated());
        assertNotNull(worker.get());
        worker.get().join(TimeUnit.SECONDS.toMillis(2));
        assertFalse(worker.get().isAlive());
    }

    @Test(timeout = 15000)
    public void runtimeFailureDoesNotSilentlyKillScheduler() throws Exception {
        MatchRuntime failed = runtimeOwnedByAnotherThread("match-error");
        MatchRuntime healthy = startedRuntime("match-healthy");
        scheduler.register(failed);
        scheduler.register(healthy);
        scheduler.start();
        executor.runCycle();
        executor.runCycle();
        assertTrue(scheduler.isRunning());
        assertEquals(2, tickCount(healthy));
        assertEquals(1, tickCount(failed));
        assertEquals(1, failures.size());
        assertEquals(Level.SEVERE, failures.get(0).getLevel());
        assertTrue(failures.get(0).getMessage().contains("match-error"));
        assertTrue(failures.get(0).getThrown() instanceof IllegalStateException);
        assertFalse(scheduler.unregister(failed));
    }

    @Test
    public void previousTestsStillPass() {
        MatchRuntime runtime = startedRuntime("match-1");
        PlayerId id = runtime.getParticipants().get(0).playerId();
        PlayerInput input = new PlayerInput(id, 1, 1, 0.5f, true, false, false, false, false);
        assertEquals(InputQueue.OfferResult.ACCEPTED, runtime.submitInput(input));
        scheduler.register(runtime);
        scheduler.start();
        executor.runCycle();
        scheduler.unregister(runtime);
        // Chỉ gọi thủ công sau unregister và trên đúng owner của executor giả.
        assertTrue(runtime.tick().isEmpty());
        assertEquals(2, tickCount(runtime));
        assertEquals(InputQueue.OfferResult.DUPLICATE_OR_STALE, runtime.submitInput(input));
        assertEquals(100.0f, runtime.getMatchState().getPlayer(id).getHp(), 0.0f);
    }

    @Test
    public void fixedRateTargetsSixtyTicksPerSecond() {
        scheduler.start();
        assertEquals(0, executor.getInitialDelay());
        assertEquals(TimeUnit.SECONDS.toNanos(1) / 60, executor.getPeriodNanos());
    }

    @Test
    public void restartingAfterStopIsRejected() {
        scheduler.start();
        scheduler.stop();
        assertThrows(IllegalStateException.class, scheduler::start);
        assertFalse(scheduler.isRunning());
        assertEquals(1, executor.getScheduleCount());
    }

    @Test
    public void stoppingBeforeStartReleasesExecutorAndPreventsStart() {
        scheduler.stop();
        assertTrue(executor.isTerminated());
        assertThrows(IllegalStateException.class, scheduler::start);
        assertEquals(0, executor.getScheduleCount());
    }

    @Test
    public void registrationAfterStopIsRejected() {
        scheduler.stop();
        assertThrows(IllegalStateException.class, () -> scheduler.register(startedRuntime("match-1")));
    }

    @Test
    public void nullRuntimeIsRejected() {
        assertThrows(NullPointerException.class, () -> scheduler.register(null));
        assertThrows(NullPointerException.class, () -> scheduler.unregister(null));
    }

    @Test
    public void schedulingFailureDoesNotLeaveSchedulerRunning() {
        executor.shutdown();
        assertThrows(java.util.concurrent.RejectedExecutionException.class, scheduler::start);
        assertFalse(scheduler.isRunning());
        assertTrue(executor.isTerminated());
        assertThrows(IllegalStateException.class, scheduler::start);
    }

    @Test(timeout = 15000)
    public void unregisterDuringCycleSkipsEntryFromOldSnapshot() throws Exception {
        MatchRuntime failed = runtimeOwnedByAnotherThread("match-error");
        MatchRuntime removed = startedRuntime("match-removed");
        scheduler.register(failed);
        scheduler.register(removed);
        onFailure = record -> assertTrue(scheduler.unregister(removed));
        scheduler.start();
        executor.runCycle();
        assertEquals(0, tickCount(removed));
        assertEquals(1, failures.size());
    }

    @Test(timeout = 15000)
    public void registryIsNotLockedWhileTickIsInProgress() throws Exception {
        MatchRuntime runtime = startedRuntime("match-blocked");
        MatchRuntime other = startedRuntime("match-other");
        scheduler.register(runtime);
        scheduler.start();
        CountDownLatch monitorHeld = new CountDownLatch(1);
        CountDownLatch releaseMonitor = new CountDownLatch(1);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        // Giữ monitor runtime để tick phải chờ; registration vẫn phải hoàn tất độc lập.
        Thread monitorThread = new Thread(() -> {
            synchronized (runtime) {
                monitorHeld.countDown();
                try {
                    if (!releaseMonitor.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Hết thời gian chờ test registry");
                    }
                } catch (Throwable failure) {
                    workerFailure.set(failure);
                }
            }
        }, "fixed-tick-monitor-test");
        monitorThread.setDaemon(true);
        Thread cycle = new Thread(executor::runCycle, "fixed-tick-cycle-test");
        cycle.setDaemon(true);
        try {
            monitorThread.start();
            assertTrue(monitorHeld.await(5, TimeUnit.SECONDS));
            cycle.start();
            // BLOCKED chứng minh chu kỳ đã chạm tick, không phụ thuộc tốc độ scheduling.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (cycle.getState() != Thread.State.BLOCKED && cycle.isAlive()
                    && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertEquals(Thread.State.BLOCKED, cycle.getState());
            assertEquals(List.of(true), InputQueueTest.runConcurrently(
                    List.of(() -> scheduler.register(other))));
        } finally {
            releaseMonitor.countDown();
            monitorThread.join(TimeUnit.SECONDS.toMillis(2));
            cycle.join(TimeUnit.SECONDS.toMillis(2));
        }
        assertFalse(monitorThread.isAlive());
        assertFalse(cycle.isAlive());
        assertNull(workerFailure.get());
        assertEquals(1, tickCount(runtime));
        assertEquals(0, tickCount(other));
    }

    @Test(timeout = 15000)
    public void lifecycleRunsBeforeRuntimeSnapshotWithoutHoldingRegistryLock() {
        MatchRuntime runtime = startedRuntime("match-lifecycle");
        List<Long> observedTicks = new ArrayList<>();
        scheduler.setLifecycleUpdate(() -> {
            observedTicks.add(tickCount(runtime));
            try {
                // Thread khác register được ngay: callback không được giữ khóa registry của scheduler.
                List<Boolean> results = InputQueueTest.runConcurrently(
                        List.of(() -> scheduler.register(runtime)));
                assertEquals(List.of(observedTicks.size() == 1), results);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        scheduler.start();
        executor.runCycle();
        assertEquals(1, tickCount(runtime));
        executor.runCycle();
        assertEquals(2, tickCount(runtime));
        assertEquals(List.of(0L, 1L), observedTicks);
        assertTrue(failures.isEmpty());
    }

    @Test
    public void lifecycleCallbackFailureIsReportedOnceAndDoesNotStopMatchTicks() {
        MatchRuntime runtime = startedRuntime("match-healthy");
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        scheduler.register(runtime);
        scheduler.setLifecycleUpdate(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("test-lifecycle-boundary");
        });
        scheduler.start();
        executor.runCycle();
        executor.runCycle();
        assertEquals(1, calls.get());
        assertEquals(1, failures.size());
        assertEquals(Level.SEVERE, failures.get(0).getLevel());
        assertTrue(failures.get(0).getThrown().getMessage().contains("test-lifecycle-boundary"));
        assertEquals(2, tickCount(runtime));
        assertTrue(scheduler.isRunning());
    }

    @Test
    public void lifecycleCanOnlyBeConfiguredOnceBeforeStart() {
        assertThrows(NullPointerException.class, () -> scheduler.setLifecycleUpdate(null));
        scheduler.setLifecycleUpdate(() -> { });
        assertThrows(IllegalStateException.class, () -> scheduler.setLifecycleUpdate(() -> { }));
        scheduler.start();
        assertThrows(IllegalStateException.class, () -> scheduler.setLifecycleUpdate(() -> { }));
        scheduler.stop();
        assertThrows(IllegalStateException.class, () -> scheduler.setLifecycleUpdate(() -> { }));
    }

    @Test
    public void stopFromLifecycleSkipsRemainingRuntimeTicks() {
        MatchRuntime runtime = startedRuntime("match-1");
        scheduler.register(runtime);
        scheduler.setLifecycleUpdate(scheduler::stop);
        scheduler.start();
        executor.runCycle();
        assertFalse(scheduler.isRunning());
        assertTrue(executor.isTerminated());
        assertEquals(0, tickCount(runtime));
        executor.runCycle();
        assertEquals(0, tickCount(runtime));
    }

    private MatchRuntime runtime(String matchId) {
        return createRuntime(new MatchSetup(matchId, "room-" + matchId, List.of(
                new MatchSetup.Participant(new PlayerId("player-0"), 0, "Alex"),
                new MatchSetup.Participant(new PlayerId("player-1"), 1, "Luca"))));
    }

    private MatchRuntime startedRuntime(String matchId) {
        MatchRuntime runtime = runtime(matchId);
        runtime.start();
        return runtime;
    }

    private static long tickCount(MatchRuntime runtime) {
        return runtime.getMatchState().getServerTick();
    }

    private MatchRuntime runtimeOwnedByAnotherThread(String matchId) throws Exception {
        MatchRuntime runtime = startedRuntime(matchId);
        InputQueueTest.runConcurrently(List.of(runtime::tick));
        return runtime;
    }

    private static boolean rejectsForeignTick(MatchRuntime runtime) {
        try {
            runtime.tick();
            return false;
        } catch (IllegalStateException expected) {
            return true;
        }
    }
}
