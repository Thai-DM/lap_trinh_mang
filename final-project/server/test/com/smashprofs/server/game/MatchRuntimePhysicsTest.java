package com.smashprofs.server.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef;
import com.badlogic.gdx.physics.box2d.World;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import com.smashprofs.server.game.physics.HeadlessPhysicsWorld;
import com.smashprofs.server.game.physics.PhysicsWorldFactory;
import com.smashprofs.server.network.GameServer;
import com.smashprofs.server.room.GameRoom;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.Assert.*;

/** Native Box2D thật; body chỉ xuất hiện trong test, không trong production. */
public class MatchRuntimePhysicsTest {
    private final List<MatchRuntime> runtimes = new ArrayList<>();
    private final List<FixedTickScheduler> schedulers = new ArrayList<>();
    private final List<GameServer> servers = new ArrayList<>();

    @After
    public void tearDown() {
        servers.forEach(GameServer::stop);
        schedulers.forEach(FixedTickScheduler::stop);
        runtimes.forEach(MatchRuntime::close);
    }

    @Test
    public void runtimeCreatesPhysicsWorldOnStart() {
        Probe probe = probe("one");
        assertEquals(0, probe.creations.get());
        assertNull(probe.world.get());
        probe.runtime.start();
        assertTrue(probe.runtime.isStarted());
        assertEquals(1, probe.creations.get());
        assertEquals(20, probe.world.get().getWorld().getBodyCount()); // 18 arena + 2 player.
        assertEquals(0, probe.runtime.getMatchState().getServerTick());
    }

    @Test
    public void runtimeOwnsExactlyOnePhysicsWorld() {
        Probe probe = startedProbe("one");
        HeadlessPhysicsWorld world = probe.world.get();
        probe.runtime.tick();
        probe.runtime.tick();
        assertSame(world, probe.world.get());
        assertEquals(1, probe.creations.get());
        assertFalse(world.isDisposed());
    }

    @Test
    public void startingRuntimeTwiceDoesNotRecreateWorld() {
        Probe probe = startedProbe("one");
        HeadlessPhysicsWorld world = probe.world.get();
        probe.runtime.start();
        assertSame(world, probe.world.get());
        assertEquals(1, probe.creations.get());
        assertEquals(0, probe.steps.get());
    }

    @Test
    public void unstartedRuntimeCannotStepWorld() {
        Probe probe = probe("one");
        assertThrows(IllegalStateException.class, probe.runtime::tick);
        assertEquals(0, probe.creations.get());
        assertEquals(0, probe.steps.get());
        assertNull(probe.runtime.getMatchState());
    }

    @Test
    public void eachTickStepsWorldExactlyOnce() {
        Probe probe = startedProbe("one");
        for (int tick = 1; tick <= 10; tick++) {
            probe.runtime.tick();
            assertEquals(tick, probe.steps.get());
        }
    }

    @Test
    public void eachSuccessfulTickIncrementsServerTick() {
        Probe probe = startedProbe("one");
        for (int tick = 1; tick <= 10; tick++) {
            probe.runtime.tick();
            assertEquals(tick, probe.runtime.getMatchState().getServerTick());
            assertEquals(tick, probe.steps.get());
        }
    }

    @Test
    public void tickingWithoutInputStillStepsWorld() {
        Probe probe = startedProbe("one");
        assertTrue(probe.runtime.tick().isEmpty());
        assertEquals(1, probe.steps.get());
        assertEquals(1, probe.runtime.getMatchState().getServerTick());
    }

    @Test
    public void twoRuntimesHaveIndependentWorlds() {
        Probe first = startedProbe("first");
        Probe second = startedProbe("second");
        assertNotSame(first.world.get(), second.world.get());
        assertNotSame(first.world.get().getWorld(), second.world.get().getWorld());
        first.world.get().getWorld().setGravity(new com.badlogic.gdx.math.Vector2(0, -2));
        assertEquals(0.0f, second.world.get().getWorld().getGravity().y, 0.0f);
    }

    @Test
    public void tickingRoomADoesNotStepRoomB() {
        Probe first = startedProbe("first");
        Probe second = startedProbe("second");
        Body firstBody = movingBody(first.world.get().getWorld());
        Body secondBody = movingBody(second.world.get().getWorld());
        first.runtime.tick();
        assertEquals(1.0f, firstBody.getPosition().x, 0.00001f);
        assertEquals(0.0f, secondBody.getPosition().x, 0.0f);
        assertEquals(1, first.steps.get());
        assertEquals(0, second.steps.get());
        assertEquals(1, first.runtime.getMatchState().getServerTick());
        assertEquals(0, second.runtime.getMatchState().getServerTick());
    }

    @Test
    public void closingRuntimeDisposesWorld() {
        Probe probe = startedProbe("one");
        probe.runtime.close();
        assertTrue(probe.runtime.isClosed());
        assertTrue(probe.runtime.isPhysicsDisposed());
        assertTrue(probe.world.get().isDisposed());
        assertThrows(IllegalStateException.class, probe.world.get()::getWorld);
    }

    @Test
    public void closingTwiceIsSafe() {
        Probe probe = startedProbe("one");
        probe.runtime.close();
        probe.runtime.close();
        assertTrue(probe.world.get().isDisposed());
    }

    @Test
    public void cannotTickAfterClose() {
        Probe probe = startedProbe("one");
        probe.runtime.tick();
        probe.runtime.close();
        assertThrows(IllegalStateException.class, probe.runtime::tick);
        assertEquals(1, probe.steps.get());
        assertEquals(1, probe.runtime.getMatchState().getServerTick());
    }

    @Test
    public void gameServerShutdownDisposesPhysicsWorlds() {
        ServerFixture fixture = serverFixture();
        fixture.server.start();
        GameRoom first = readyRoom(fixture.server, "first");
        GameRoom second = readyRoom(fixture.server, "second");
        playRooms(fixture);
        MatchRuntime firstRuntime = first.getMatchRuntime();
        MatchRuntime secondRuntime = second.getMatchRuntime();
        assertNotNull(firstRuntime);
        assertNotNull(secondRuntime);
        fixture.server.stop();
        assertTrue(firstRuntime.isPhysicsDisposed());
        assertTrue(secondRuntime.isPhysicsDisposed());
        assertTrue(firstRuntime.isClosed());
        assertTrue(secondRuntime.isClosed());
        assertTrue(fixture.executor.get().isTerminated());
        long tick = firstRuntime.getMatchState().getServerTick();
        fixture.executor.get().runCycle();
        assertEquals(tick, firstRuntime.getMatchState().getServerTick());
    }

    @Test
    public void repeatedStartDoesNotLeakWorlds() {
        Probe probe = startedProbe("one");
        for (int attempt = 0; attempt < 100; attempt++) {
            probe.runtime.start();
        }
        assertEquals(1, probe.creations.get());
        probe.runtime.close();
        assertTrue(probe.world.get().isDisposed());
        assertThrows(IllegalStateException.class, probe.runtime::start);
        assertEquals(1, probe.creations.get());
    }

    @Test
    public void existingServerTestsStillPass() {
        Probe probe = startedProbe("one");
        PlayerId player = probe.runtime.getParticipants().get(0).playerId();
        PlayerInput input = new PlayerInput(player, 1, 1, 0.5f, true, false, false, false, false);
        assertEquals(InputQueue.OfferResult.ACCEPTED, probe.runtime.submitInput(input));
        assertEquals(0, probe.steps.get());
        assertEquals(List.of(input), probe.runtime.tick());
        assertTrue(probe.runtime.tick().isEmpty());
        assertEquals(2, probe.steps.get());
        assertEquals(2, probe.runtime.getMatchState().getServerTick());
        assertEquals(InputQueue.OfferResult.DUPLICATE_OR_STALE, probe.runtime.submitInput(input));
    }

    @Test
    public void realNativeBodyAdvancesAtFixedTimeStep() {
        assertNull(Gdx.app);
        assertNull(Gdx.graphics);
        Probe probe = startedProbe("one");
        Body body = movingBody(probe.world.get().getWorld());
        for (int tick = 1; tick <= 60; tick++) {
            probe.runtime.tick();
            assertEquals((float) tick, body.getPosition().x, 0.0001f);
        }
        assertEquals(60, probe.runtime.getMatchState().getServerTick());
        assertNull(Gdx.app);
        assertNull(Gdx.graphics);
    }

    @Test
    public void failedPhysicsCreationDoesNotPublishStartedState() {
        RuntimeException failure = new IllegalStateException("test-factory-failure");
        MatchRuntime runtime = runtime("failed", () -> { throw failure; }, World::step);
        assertSame(failure, assertThrows(IllegalStateException.class, runtime::start));
        assertFalse(runtime.isStarted());
        assertNull(runtime.getMatchState());
        assertThrows(IllegalStateException.class, runtime::tick);
    }

    @Test
    public void nativeLoadingFailureBecomesLifecycleException() {
        LinkageError failure = new UnsatisfiedLinkError("test-native-loading-failure");
        MatchRuntime runtime = runtime("failed", () -> { throw failure; }, World::step);
        IllegalStateException reported = assertThrows(IllegalStateException.class, runtime::start);
        assertSame(failure, reported.getCause());
        assertTrue(reported.getMessage().contains("Box2D native"));
        assertFalse(runtime.isStarted());
        assertNull(runtime.getMatchState());
    }

    @Test
    public void invalidPhysicsWrapperIsNotPublished() {
        HeadlessPhysicsWorld disposed = new PhysicsWorldFactory().create();
        disposed.close();
        MatchRuntime runtime = runtime("failed", () -> disposed, World::step);
        assertThrows(IllegalStateException.class, runtime::start);
        assertFalse(runtime.isStarted());
        assertNull(runtime.getMatchState());
        assertTrue(disposed.isDisposed());
    }

    @Test
    public void failedStepDoesNotAdvanceServerTick() {
        AtomicReference<HeadlessPhysicsWorld> world = new AtomicReference<>();
        RuntimeException failure = new IllegalStateException("test-step-failure");
        MatchRuntime runtime = runtime("failed", () -> {
            HeadlessPhysicsWorld physics = new PhysicsWorldFactory().create();
            world.set(physics);
            return physics;
        }, (nativeWorld, timeStep, velocity, position) -> { throw failure; });
        runtime.start();
        IllegalStateException reported = assertThrows(IllegalStateException.class, runtime::tick);
        assertSame(failure, reported.getCause());
        assertTrue(reported.getMessage().contains("Box2D step"));
        assertEquals(0, runtime.getMatchState().getServerTick());
        runtime.close();
        assertTrue(world.get().isDisposed());
    }

    @Test
    public void closingBeforeStartIsSafeAndPreventsNativeCreation() {
        Probe probe = probe("one");
        probe.runtime.close();
        probe.runtime.close();
        assertThrows(IllegalStateException.class, probe.runtime::start);
        assertThrows(IllegalStateException.class, probe.runtime::tick);
        assertEquals(0, probe.creations.get());
    }

    @Test
    public void disposingRuntimeADoesNotPreventRuntimeBFromStepping() {
        Probe first = startedProbe("first");
        Probe second = startedProbe("second");
        Body body = movingBody(second.world.get().getWorld());
        first.runtime.close();
        second.runtime.tick();
        assertTrue(first.world.get().isDisposed());
        assertFalse(second.world.get().isDisposed());
        assertEquals(1.0f, body.getPosition().x, 0.00001f);
    }

    @Test
    public void removingRoomDisposesOnlyItsRuntime() {
        ServerFixture fixture = serverFixture();
        fixture.server.start();
        GameRoom first = readyRoom(fixture.server, "first");
        GameRoom second = readyRoom(fixture.server, "second");
        playRooms(fixture);
        MatchRuntime removed = first.getMatchRuntime();
        MatchRuntime remaining = second.getMatchRuntime();
        fixture.server.getRoomManager().removeRoom("first");
        assertTrue(removed.isPhysicsDisposed());
        assertFalse(remaining.isPhysicsDisposed());
        long previous = remaining.getMatchState().getServerTick();
        fixture.executor.get().runCycle();
        assertEquals(previous + 1, remaining.getMatchState().getServerTick());
    }

    @Test
    public void serverRestartClosesOldWorldsAndCreatesFreshRuntime() {
        ServerFixture fixture = serverFixture();
        MatchRuntime previous = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            fixture.server.start();
            GameRoom room = readyRoom(fixture.server, "room");
            playRooms(fixture);
            MatchRuntime current = room.getMatchRuntime();
            assertNotSame(previous, current);
            assertFalse(current.isPhysicsDisposed());
            fixture.server.stop();
            assertTrue(current.isPhysicsDisposed());
            previous = current;
        }
    }

    @Test
    public void schedulerStepsPhysicsOncePerCycle() {
        Probe probe = startedProbe("one");
        ManualScheduledExecutor executor = new ManualScheduledExecutor();
        FixedTickScheduler scheduler = scheduler(executor);
        scheduler.register(probe.runtime);
        scheduler.start();
        executor.runCycle();
        executor.runCycle();
        assertEquals(2, probe.steps.get());
        assertEquals(2, probe.runtime.getMatchState().getServerTick());
        scheduler.unregister(probe.runtime);
        probe.runtime.close();
        executor.runCycle();
        assertEquals(2, probe.steps.get());
    }

    @Test
    public void closedRuntimeCannotBeRegistered() {
        Probe probe = startedProbe("one");
        probe.runtime.close();
        FixedTickScheduler scheduler = scheduler(new ManualScheduledExecutor());
        assertThrows(IllegalStateException.class, () -> scheduler.register(probe.runtime));
    }

    @Test(timeout = 15000)
    public void closeWaitsForInFlightStep() throws Exception {
        CountDownLatch stepping = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<HeadlessPhysicsWorld> world = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        MatchRuntime runtime = runtime("one", () -> {
            HeadlessPhysicsWorld physics = new PhysicsWorldFactory().create();
            world.set(physics);
            return physics;
        }, (nativeWorld, dt, velocity, position) -> {
            stepping.countDown();
            await(release);
            nativeWorld.step(dt, velocity, position);
        });
        runtime.start();
        Thread simulation = worker(runtime::tick, failure);
        Thread closer = worker(runtime::close, failure);
        try {
            simulation.start();
            assertTrue(stepping.await(5, TimeUnit.SECONDS));
            closer.start();
            assertBlocked(closer);
            assertFalse(world.get().isDisposed());
        } finally {
            release.countDown();
            join(simulation);
            join(closer);
        }
        assertNull(failure.get());
        assertTrue(world.get().isDisposed());
        assertEquals(1, runtime.getMatchState().getServerTick());
    }

    @Test(timeout = 15000)
    public void unregisterAndCloseWaitForScheduledStep() throws Exception {
        CountDownLatch stepping = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<HeadlessPhysicsWorld> world = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        MatchRuntime runtime = runtime("one", () -> {
            HeadlessPhysicsWorld physics = new PhysicsWorldFactory().create();
            world.set(physics);
            return physics;
        }, (nativeWorld, dt, velocity, position) -> {
            stepping.countDown();
            await(release);
            nativeWorld.step(dt, velocity, position);
        });
        runtime.start();
        ManualScheduledExecutor executor = new ManualScheduledExecutor();
        FixedTickScheduler scheduler = scheduler(executor);
        scheduler.register(runtime);
        scheduler.start();
        Thread simulation = worker(executor::runCycle, failure);
        Thread closer = worker(() -> {
            assertTrue(scheduler.unregister(runtime));
            runtime.close();
        }, failure);
        try {
            simulation.start();
            assertTrue(stepping.await(5, TimeUnit.SECONDS));
            closer.start();
            assertBlocked(closer);
            assertFalse(world.get().isDisposed());
        } finally {
            release.countDown();
            join(simulation);
            join(closer);
        }
        assertNull(failure.get());
        assertTrue(world.get().isDisposed());
        executor.runCycle();
        assertEquals(1, runtime.getMatchState().getServerTick());
    }

    @Test
    public void runtimeDoesNotExposeMutableWorldPublicly() {
        for (java.lang.reflect.Method method : MatchRuntime.class.getMethods()) {
            assertNotEquals(World.class, method.getReturnType());
            assertNotEquals(HeadlessPhysicsWorld.class, method.getReturnType());
        }
    }

    private Probe probe(String id) {
        AtomicReference<HeadlessPhysicsWorld> world = new AtomicReference<>();
        AtomicInteger creations = new AtomicInteger();
        AtomicInteger steps = new AtomicInteger();
        MatchRuntime runtime = runtime(id, () -> {
            creations.incrementAndGet();
            HeadlessPhysicsWorld physics = new PhysicsWorldFactory().create();
            world.set(physics);
            return physics;
        }, (nativeWorld, dt, velocity, position) -> {
            assertSame(world.get().getWorld(), nativeWorld);
            assertEquals(MatchRuntime.PHYSICS_TIME_STEP, dt, 0.0f);
            assertEquals(6, velocity);
            assertEquals(2, position);
            steps.incrementAndGet();
            nativeWorld.step(dt, velocity, position);
        });
        return new Probe(runtime, world, creations, steps);
    }

    private Probe startedProbe(String id) {
        Probe probe = probe(id);
        probe.runtime.start();
        return probe;
    }

    private MatchRuntime runtime(String id, Supplier<HeadlessPhysicsWorld> factory, MatchRuntime.PhysicsStepper stepper) {
        MatchSetup setup = new MatchSetup(id, "room-" + id, List.of(
                new MatchSetup.Participant(new PlayerId("alice"), 0, "Alex"),
                new MatchSetup.Participant(new PlayerId("bob"), 1, "Luca")));
        MatchRuntime runtime = new MatchRuntime(setup, factory, stepper);
        runtimes.add(runtime);
        return runtime;
    }

    private FixedTickScheduler scheduler(ManualScheduledExecutor executor) {
        FixedTickScheduler scheduler = new FixedTickScheduler(executor, Logger.getAnonymousLogger());
        schedulers.add(scheduler);
        return scheduler;
    }

    private ServerFixture serverFixture() {
        AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(10));
        AtomicReference<ManualScheduledExecutor> executor = new AtomicReference<>();
        GameServer server = new GameServer(() -> {
            ManualScheduledExecutor next = new ManualScheduledExecutor();
            executor.set(next);
            return scheduler(next);
        }, clock::get);
        servers.add(server);
        return new ServerFixture(server, executor, clock);
    }

    private static GameRoom readyRoom(GameServer server, String id) {
        GameRoom room = server.getRoomManager().getOrCreateRoom(id);
        for (PlayerId player : List.of(new PlayerId("alice"), new PlayerId("bob"))) {
            room.joinPlayer(player);
            room.selectCharacter(player, "Alex");
            room.setReady(player, true);
        }
        return room;
    }

    private static void playRooms(ServerFixture fixture) {
        fixture.executor.get().runCycle();
        fixture.clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
        fixture.executor.get().runCycle();
    }

    private static Body movingBody(World world) {
        BodyDef definition = new BodyDef();
        definition.type = BodyDef.BodyType.DynamicBody;
        Body body = world.createBody(definition);
        body.setLinearVelocity(60.0f, 0.0f);
        return body;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private static Thread worker(Runnable action, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable thrown) {
                failure.compareAndSet(null, thrown);
            }
        }, "runtime-physics-test");
        thread.setDaemon(true);
        return thread;
    }

    private static void assertBlocked(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.BLOCKED && thread.isAlive() && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(Thread.State.BLOCKED, thread.getState());
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(thread.isAlive());
    }

    private record Probe(MatchRuntime runtime, AtomicReference<HeadlessPhysicsWorld> world,
                         AtomicInteger creations, AtomicInteger steps) { }

    private record ServerFixture(GameServer server, AtomicReference<ManualScheduledExecutor> executor,
                                 AtomicLong clock) { }
}
