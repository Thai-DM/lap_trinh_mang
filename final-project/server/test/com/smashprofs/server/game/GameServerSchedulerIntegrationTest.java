package com.smashprofs.server.game;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import com.smashprofs.server.network.GameServer;
import com.smashprofs.server.room.GameRoom;
import com.smashprofs.server.room.RoomManager;
import com.smashprofs.server.room.RoomPhase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.Assert.*;

/** Tái sử dụng executor thủ công Bước 19; clock chỉ tiến khi test yêu cầu. */
public class GameServerSchedulerIntegrationTest {
    private GameServer server;
    private AtomicLong clock;
    private List<ManualScheduledExecutor> executors;
    private List<FixedTickScheduler> schedulers;

    @Before
    public void setUp() {
        clock = new AtomicLong(TimeUnit.SECONDS.toNanos(10));
        executors = new ArrayList<>();
        schedulers = new ArrayList<>();
        server = new GameServer(() -> {
            ManualScheduledExecutor executor = new ManualScheduledExecutor();
            FixedTickScheduler scheduler = new FixedTickScheduler(executor, Logger.getAnonymousLogger());
            executors.add(executor);
            schedulers.add(scheduler);
            return scheduler;
        }, clock::get);
    }

    @After
    public void tearDown() {
        server.stop();
    }

    @Test
    public void gameServerStartsScheduler() {
        assertFalse(server.isRunning());
        assertFalse(scheduler().isRunning());
        server.start();
        assertTrue(server.isRunning());
        assertTrue(scheduler().isRunning());
        assertEquals(1, executor().getScheduleCount());
    }

    @Test
    public void gameServerStopsScheduler() {
        server.start();
        server.stop();
        assertFalse(server.isRunning());
        assertFalse(scheduler().isRunning());
        assertTrue(executor().isTaskCancelled());
        assertTrue(executor().isTerminated());
    }

    @Test
    public void startingGameServerTwiceIsSafe() {
        server.start();
        server.start();
        assertTrue(server.isRunning());
        assertEquals(1, executors.size());
        assertEquals(1, executor().getScheduleCount());
    }

    @Test
    public void stoppingGameServerTwiceIsSafe() {
        server.start();
        server.stop();
        server.stop();
        assertFalse(server.isRunning());
        assertTrue(executor().isTerminated());
    }

    @Test
    public void successfulMatchStartRegistersRuntime() {
        server.start();
        GameRoom room = preparedRoom(server.getRoomManager(), "room-1");
        MatchRuntime runtime = room.startMatch();
        assertNotNull(runtime);
        assertTrue(runtime.isStarted());
        assertEquals(RoomPhase.PLAYING, room.getPhase());
        // Đã được callback đăng ký: gọi register trực tiếp phải trả false.
        assertFalse(scheduler().register(runtime));
    }

    @Test
    public void failedMatchStartDoesNotRegisterRuntime() {
        server.start();
        GameRoom room = readyRoom(server.getRoomManager(), "room-1");
        assertNull(room.startMatch());
        assertTrue(room.startCountdown());
        assertNull(room.startMatch());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
        assertTrue(room.updateCountdown());
        assertNull(room.startMatch());
        // API startMatch tường minh vẫn từ chối thiếu setup; lifecycle tự chuẩn bị ở chu kỳ kế tiếp.
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase());
        assertNotNull(room.prepareMatch());
        MatchRuntime runtime = room.startMatch();
        executor().runCycle();
        assertEquals(1, ticks(runtime));
    }

    @Test
    public void startingMatchTwiceDoesNotRegisterTwice() {
        server.start();
        GameRoom room = preparedRoom(server.getRoomManager(), "room-1");
        MatchRuntime first = room.startMatch();
        assertSame(first, room.startMatch());
        executor().runCycle();
        assertEquals(1, ticks(first));
        // Chứng minh callback không bị gọi lại ở nhánh trả runtime đã có.
        assertTrue(scheduler().unregister(first));
        assertSame(first, room.startMatch());
        assertFalse(scheduler().unregister(first));
        executor().runCycle();
        assertEquals(1, ticks(first));
    }

    @Test
    public void registeredRuntimeReceivesTicks() {
        server.start();
        MatchRuntime runtime = preparedRoom(server.getRoomManager(), "room-1").startMatch();
        assertEquals(0, ticks(runtime));
        executor().runCycle();
        executor().runCycle();
        assertEquals(2, ticks(runtime));
    }

    @Test
    public void twoMatchesReceiveIndependentTicks() {
        server.start();
        MatchRuntime first = preparedRoom(server.getRoomManager(), "room-a").startMatch();
        MatchRuntime second = preparedRoom(server.getRoomManager(), "room-b").startMatch();
        executor().runCycle();
        assertEquals(1, ticks(first));
        assertEquals(1, ticks(second));
        assertNotSame(first, second);
        assertNotSame(first.getMatchState(), second.getMatchState());
        assertNotSame(first.getMatchState().getPlayer(player(0)), second.getMatchState().getPlayer(player(0)));
        server.getRoomManager().removeRoom("room-a");
        executor().runCycle();
        assertEquals(1, ticks(first));
        assertEquals(2, ticks(second));
    }

    @Test
    public void removingRoomUnregistersRuntime() {
        server.start();
        RoomManager manager = server.getRoomManager();
        GameRoom room = preparedRoom(manager, "room-1");
        MatchRuntime runtime = room.startMatch();
        assertSame(room, manager.removeRoom("room-1"));
        assertNull(manager.getRoom("room-1"));
        assertFalse(scheduler().unregister(runtime));
        assertTrue(room.isClosed());
        // Cleanup không phải GAME_END: phase/roster/state không bị đổi thành kết quả trận.
        assertEquals(RoomPhase.PLAYING, room.getPhase());
        assertEquals(2, room.getPlayerCount());
        assertTrue(runtime.isStarted());
    }

    @Test
    public void removedRuntimeNoLongerReceivesTicks() {
        server.start();
        RoomManager manager = server.getRoomManager();
        GameRoom room = preparedRoom(manager, "room-1");
        MatchRuntime oldRuntime = room.startMatch();
        executor().runCycle();
        manager.removeRoom("room-1");
        assertNull(room.startMatch());
        MatchRuntime replacement = preparedRoom(manager, "room-1").startMatch();
        executor().runCycle();
        executor().runCycle();
        assertEquals(1, ticks(oldRuntime));
        assertEquals(2, ticks(replacement));
        assertNotEquals(oldRuntime.getMatchId(), replacement.getMatchId());
    }

    @Test
    public void stoppingServerStopsAllMatchTicks() {
        server.start();
        RoomManager manager = server.getRoomManager();
        GameRoom roomA = preparedRoom(manager, "room-a");
        GameRoom roomB = preparedRoom(manager, "room-b");
        MatchRuntime first = roomA.startMatch();
        MatchRuntime second = roomB.startMatch();
        executor().runCycle();
        server.stop();
        executor().runCycle();
        assertEquals(1, ticks(first));
        assertEquals(1, ticks(second));
        assertEquals(0, manager.getRoomCount());
        assertTrue(roomA.isClosed());
        assertTrue(roomB.isClosed());
        assertNull(roomA.startMatch());
        assertFalse(scheduler().unregister(first));
        assertFalse(scheduler().unregister(second));
        assertThrows(IllegalStateException.class, () -> manager.getOrCreateRoom("new-room"));
    }

    @Test(timeout = 15000)
    public void shutdownDoesNotLeakSimulationThread() throws Exception {
        AtomicReference<Thread> worker = new AtomicReference<>();
        ScheduledThreadPoolExecutor realExecutor = new ScheduledThreadPoolExecutor(1, command -> {
            Thread thread = new Thread(command, "server-simulation-shutdown-test");
            thread.setDaemon(true);
            worker.set(thread);
            return thread;
        });
        FixedTickScheduler realScheduler = new FixedTickScheduler(realExecutor, Logger.getAnonymousLogger());
        GameServer realServer = new GameServer(() -> realScheduler, clock::get);
        MatchRuntime runtime;
        try {
            // Register trước start server để task đầu tiên có runtime, không chờ đủ một giây.
            runtime = preparedRoom(realServer.getRoomManager(), "real-room").startMatch();
            realServer.start();
            CountDownLatch executed = new CountDownLatch(1);
            realExecutor.execute(executed::countDown);
            assertTrue(executed.await(5, TimeUnit.SECONDS));
            assertTrue(ticks(runtime) >= 1);
            assertThrows(IllegalStateException.class, runtime::tick);
        } finally {
            realServer.stop();
        }
        assertTrue(realExecutor.isShutdown());
        assertTrue(realExecutor.isTerminated());
        assertNotNull(worker.get());
        worker.get().join(TimeUnit.SECONDS.toMillis(2));
        assertFalse(worker.get().isAlive());
        assertFalse(realServer.isRunning());
    }

    @Test
    public void existingTestsStillPass() {
        server.start();
        RoomManager manager = server.getRoomManager();
        GameRoom room = manager.getOrCreateRoom("room-1");
        assertEquals(GameRoom.JoinResult.JOINED, room.joinPlayer(player(0)));
        assertEquals(GameRoom.JoinResult.JOINED, room.joinPlayer(player(1)));
        assertTrue(room.selectCharacter(player(0), "Alex"));
        assertTrue(room.selectCharacter(player(1), "Luca"));
        assertTrue(room.setReady(player(0), true));
        assertTrue(room.setReady(player(1), true));
        assertTrue(room.startCountdown());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
        assertTrue(room.updateCountdown());
        MatchSetup setup = room.prepareMatch();
        MatchRuntime runtime = room.startMatch();
        assertEquals(setup.matchId(), runtime.getMatchId());
        assertFalse(room.leavePlayer(player(0)));
        assertFalse(room.setReady(player(0), false));
        assertFalse(room.selectCharacter(player(0), "Leo"));
        executor().runCycle();
        assertEquals(1, ticks(runtime));
        assertEquals(100.0f, runtime.getMatchState().getPlayer(player(0)).getHp(), 0.0f);
    }

    @Test
    public void serverRestartCreatesFreshSchedulerAndRegistryWithoutRevivingOldMatches() {
        server.start();
        RoomManager oldManager = server.getRoomManager();
        GameRoom oldRoom = preparedRoom(oldManager, "room-1");
        MatchRuntime oldRuntime = oldRoom.startMatch();
        ManualScheduledExecutor oldExecutor = executor();
        FixedTickScheduler oldScheduler = scheduler();
        oldExecutor.runCycle();
        server.stop();
        server.start();
        assertTrue(server.isRunning());
        assertEquals(2, executors.size());
        assertTrue(oldExecutor.isTerminated());
        assertFalse(oldScheduler.isRunning());
        assertNotSame(oldManager, server.getRoomManager());
        assertEquals(0, server.getRoomManager().getRoomCount());
        assertNull(oldRoom.startMatch());
        MatchRuntime freshRuntime = preparedRoom(server.getRoomManager(), "room-1").startMatch();
        executor().runCycle();
        oldExecutor.runCycle();
        assertEquals(1, ticks(oldRuntime));
        assertEquals(1, ticks(freshRuntime));
        assertNotEquals(oldRuntime.getMatchId(), freshRuntime.getMatchId());
    }

    @Test
    public void stopBeforeStartThenStartUsesFreshLifecycle() {
        ManualScheduledExecutor oldExecutor = executor();
        server.stop();
        server.start();
        assertTrue(oldExecutor.isTerminated());
        assertEquals(2, executors.size());
        assertEquals(1, executor().getScheduleCount());
        assertTrue(server.isRunning());
    }

    @Test
    public void countdownAndPreparationBecomeAutomatic() {
        server.start();
        GameRoom room = readyRoom(server.getRoomManager(), "room-1");
        executor().runCycle();
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase());
        assertEquals(3000, room.getCountdownRemainingMillis());
        assertNull(room.getMatchRuntime());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
        executor().runCycle();
        assertTrue(room.isCountdownComplete());
        assertEquals(RoomPhase.PLAYING, room.getPhase());
        MatchRuntime runtime = room.getMatchRuntime();
        assertNotNull(runtime);
        assertEquals(1, ticks(runtime));
    }

    @Test
    public void runtimeIsRegisteredExactlyOnce() {
        server.start();
        GameRoom room = readyRoom(server.getRoomManager(), "automatic-room");
        executor().runCycle();
        clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
        executor().runCycle();
        MatchRuntime runtime = room.getMatchRuntime();
        assertNotNull(runtime);
        assertFalse(scheduler().register(runtime));
        assertEquals(1, ticks(runtime));
        executor().runCycle();
        assertEquals(2, ticks(runtime));
        assertSame(runtime, room.getMatchRuntime());
        assertTrue(scheduler().unregister(runtime));
        executor().runCycle();
        assertFalse(scheduler().unregister(runtime));
        assertEquals(2, ticks(runtime));
    }

    @Test
    public void twoRoomsAutomaticallyProgressIndependently() {
        server.start();
        GameRoom first = readyRoom(server.getRoomManager(), "automatic-a");
        executor().runCycle();
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        GameRoom second = readyRoom(server.getRoomManager(), "automatic-b");
        executor().runCycle();
        assertEquals(2000, first.getCountdownRemainingMillis());
        assertEquals(3000, second.getCountdownRemainingMillis());
        assertTrue(first.setReady(player(0), false));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
        executor().runCycle();
        assertEquals(RoomPhase.WAITING, first.getPhase());
        assertNull(first.getMatchRuntime());
        assertEquals(RoomPhase.PLAYING, second.getPhase());
        assertEquals(1, ticks(second.getMatchRuntime()));
        assertNull(first.getLifecycleFailure());
    }

    @Test
    public void serverShutdownStopsLifecycleUpdates() {
        server.start();
        RoomManager manager = server.getRoomManager();
        GameRoom room = readyRoom(manager, "automatic-room");
        executor().runCycle();
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase());
        server.stop();
        clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
        executor().runCycle();
        manager.updateLifecycles();
        assertTrue(room.isClosed());
        assertNull(room.getMatchRuntime());
        assertFalse(room.updateLifecycle());
        assertTrue(executor().isTerminated());
    }

    @Test(timeout = 15000)
    public void concurrentLifecycleAndExplicitStartDoNotCreateTwoRuntimes() throws Exception {
        server.start();
        GameRoom room = readyRoom(server.getRoomManager(), "concurrent-room");
        executor().runCycle();
        clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
        List<Callable<Boolean>> actions = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            actions.add(room::updateLifecycle);
            actions.add(() -> room.startMatch() != null);
        }
        InputQueueTest.runConcurrently(actions);
        MatchRuntime runtime = room.getMatchRuntime();
        assertNotNull(runtime);
        assertSame(runtime, room.startMatch());
        assertFalse(scheduler().register(runtime));
        executor().runCycle();
        assertEquals(1, ticks(runtime));
        assertNull(room.getLifecycleFailure());
    }

    @Test
    public void canceledCountdownAutomaticallyRestartsOnlyAfterReadyAgain() {
        server.start();
        GameRoom room = readyRoom(server.getRoomManager(), "automatic-room");
        executor().runCycle();
        clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
        assertTrue(room.setReady(player(0), false));
        executor().runCycle();
        assertEquals(RoomPhase.WAITING, room.getPhase());
        assertNull(room.getMatchRuntime());
        assertTrue(room.setReady(player(0), true));
        executor().runCycle();
        assertEquals(3000, room.getCountdownRemainingMillis());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
        executor().runCycle();
        assertEquals(RoomPhase.PLAYING, room.getPhase());
        assertEquals(1, ticks(room.getMatchRuntime()));
    }

    @Test
    public void removingLobbyRoomPreventsStartingFromStaleReference() {
        server.start();
        RoomManager manager = server.getRoomManager();
        GameRoom room = preparedRoom(manager, "room-1");
        assertSame(room, manager.removeRoom("room-1"));
        assertTrue(room.isClosed());
        assertNull(room.startMatch());
        assertNull(room.prepareMatch());
        assertFalse(room.canStartMatch());
        assertFalse(room.setReady(player(0), true));
        assertEquals(GameRoom.JoinResult.ROOM_CLOSED, room.joinPlayer(player(2)));
    }

    @Test
    public void stoppedSchedulerRejectsRegistrationWithoutPublishingPlayingRoom() {
        server.start();
        GameRoom room = preparedRoom(server.getRoomManager(), "room-1");
        scheduler().stop();
        assertThrows(IllegalStateException.class, room::startMatch);
        assertEquals(RoomPhase.COUNTDOWN, room.getPhase());
        assertFalse(room.isClosed());
        assertNotNull(room.prepareMatch());
    }

    @Test(timeout = 15000)
    public void concurrentStartsReturnOneRegisteredRuntime() throws Exception {
        server.start();
        GameRoom room = preparedRoom(server.getRoomManager(), "room-1");
        List<Callable<MatchRuntime>> starts = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            starts.add(room::startMatch);
        }
        List<MatchRuntime> results = InputQueueTest.runConcurrently(starts);
        for (MatchRuntime runtime : results) {
            assertSame(results.get(0), runtime);
        }
        executor().runCycle();
        assertEquals(1, ticks(results.get(0)));
    }

    @Test(timeout = 15000)
    public void concurrentStartAndRemovalCannotLeaveOrphanRegistration() throws Exception {
        server.start();
        RoomManager manager = server.getRoomManager();
        for (int index = 0; index < 5; index++) {
            String id = "race-room-" + index;
            GameRoom room = preparedRoom(manager, id);
            List<Object> results = InputQueueTest.runConcurrently(List.<Callable<Object>>of(
                    room::startMatch, () -> manager.removeRoom(id)));
            assertSame(room, results.get(1));
            assertTrue(room.isClosed());
            assertNull(manager.getRoom(id));
            assertNull(room.startMatch());
            executor().runCycle();
            if (results.get(0) != null) {
                MatchRuntime runtime = (MatchRuntime) results.get(0);
                assertEquals(0, ticks(runtime));
                assertFalse(scheduler().unregister(runtime));
            }
        }
    }

    @Test(timeout = 15000)
    public void networkThreadCanSubmitInputButCannotTickScheduledRuntime() throws Exception {
        server.start();
        MatchRuntime runtime = preparedRoom(server.getRoomManager(), "room-1").startMatch();
        executor().runCycle();
        InputQueueTest.runConcurrently(List.<Callable<Boolean>>of(() -> {
            PlayerInput input = new PlayerInput(player(0), 1, 1, 0.5f,
                    false, false, false, false, false);
            assertEquals(InputQueue.OfferResult.ACCEPTED, runtime.submitInput(input));
            assertThrows(IllegalStateException.class, runtime::tick);
            return true;
        }));
        assertEquals(1, ticks(runtime));
        executor().runCycle();
        assertEquals(2, ticks(runtime));
    }

    @Test
    public void startFailureDoesNotLeaveServerRunningOrRegistryOpen() {
        executor().shutdown();
        RoomManager failedManager = server.getRoomManager();
        assertThrows(java.util.concurrent.RejectedExecutionException.class, server::start);
        assertFalse(server.isRunning());
        assertTrue(executor().isTerminated());
        assertThrows(IllegalStateException.class, () -> failedManager.getOrCreateRoom("room-1"));
        server.start();
        assertTrue(server.isRunning());
        assertEquals(2, executors.size());
    }

    private GameRoom readyRoom(RoomManager manager, String roomId) {
        GameRoom room = manager.getOrCreateRoom(roomId);
        for (int index = 0; index < 2; index++) {
            assertEquals(GameRoom.JoinResult.JOINED, room.joinPlayer(player(index)));
            assertTrue(room.selectCharacter(player(index), index == 0 ? "Alex" : "Luca"));
            assertTrue(room.setReady(player(index), true));
        }
        return room;
    }

    private GameRoom preparedRoom(RoomManager manager, String roomId) {
        GameRoom room = readyRoom(manager, roomId);
        assertTrue(room.startCountdown());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
        assertTrue(room.updateCountdown());
        assertNotNull(room.prepareMatch());
        return room;
    }

    private ManualScheduledExecutor executor() {
        return executors.get(executors.size() - 1);
    }

    private FixedTickScheduler scheduler() {
        return schedulers.get(schedulers.size() - 1);
    }

    private static PlayerId player(int index) {
        return new PlayerId("player-" + index);
    }

    private static long ticks(MatchRuntime runtime) {
        return runtime.getMatchState().getServerTick();
    }
}
