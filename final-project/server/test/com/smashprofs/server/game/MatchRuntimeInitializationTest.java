package com.smashprofs.server.game;

import com.badlogic.gdx.physics.box2d.Body;
import com.badlogic.gdx.physics.box2d.BodyDef;
import com.badlogic.gdx.physics.box2d.World;
import com.badlogic.gdx.utils.Array;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.server.game.physics.HeadlessPhysicsWorld;
import com.smashprofs.server.game.physics.PhysicsWorldFactory;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.Assert.*;

/** Initialization dùng native thật; không thêm Body getter vào runtime để phục vụ test. */
public class MatchRuntimeInitializationTest {
    private final List<MatchRuntime> runtimes = new ArrayList<>();

    @After
    public void tearDown() {
        runtimes.forEach(MatchRuntime::close);
    }

    @Test
    public void runtimeLoadsOriginalArenaOnStart() {
        Probe probe = create(2, physics -> { });
        probe.runtime.start();
        int statics = 0;
        for (Body body : bodies(probe.physics.get().getWorld())) {
            if (body.getType() == BodyDef.BodyType.StaticBody) {
                statics++;
                assertEquals("Tile", body.getUserData());
            }
        }
        assertEquals(18, statics);
    }

    @Test
    public void arenaLoadedExactlyOnce() {
        Probe probe = create(4, physics -> { });
        probe.runtime.start();
        List<Body> original = bodies(probe.physics.get().getWorld());
        probe.runtime.start();
        assertEquals(original, bodies(probe.physics.get().getWorld()));
        assertEquals(1, probe.creations.get());
    }

    @Test
    public void runtimeCreatesBodiesForTwoPlayers() { assertRoster(2); }

    @Test
    public void runtimeCreatesBodiesForThreePlayers() { assertRoster(3); }

    @Test
    public void runtimeCreatesBodiesForFourPlayers() { assertRoster(4); }

    @Test
    public void repeatedStartDoesNotDuplicateBodies() {
        Probe probe = create(4, physics -> { });
        probe.runtime.start();
        for (int attempt = 0; attempt < 20; attempt++) {
            probe.runtime.start();
        }
        assertEquals(22, probe.physics.get().getWorld().getBodyCount());
        assertEquals(22, probe.physics.get().getWorld().getFixtureCount());
        assertEquals(1, probe.creations.get());
        assertEquals(0, probe.runtime.getMatchState().getServerTick());
    }

    @Test
    public void failedInitializationCleansResources() {
        RuntimeException failure = new IllegalStateException("test-before-publish-failure");
        Probe probe = create(4, physics -> {
            assertEquals(22, physics.getWorld().getBodyCount());
            throw failure;
        });
        assertSame(failure, assertThrows(IllegalStateException.class, probe.runtime::start));
        assertTrue(probe.physics.get().isDisposed());
        assertThrows(IllegalStateException.class, probe.physics.get()::getWorld);
        assertFalse(probe.runtime.isStarted());
        assertNull(probe.runtime.getMatchState());
        assertThrows(IllegalStateException.class, probe.runtime::tick);
    }

    @Test
    public void runtimeCloseReleasesPhysicsResources() throws Exception {
        Probe probe = create(3, physics -> { });
        probe.runtime.start();
        probe.runtime.close();
        probe.runtime.close();
        assertTrue(probe.physics.get().isDisposed());
        for (String name : List.of("playerBodies", "arenaBodies")) {
            var field = MatchRuntime.class.getDeclaredField(name);
            field.setAccessible(true);
            assertNull(field.get(probe.runtime));
        }
        assertThrows(IllegalStateException.class, probe.runtime::tick);
    }

    @Test
    public void existingServerTestsStillPass() {
        assertRoster(4);
    }

    @Test(timeout = 15000)
    public void schedulerCannotTickPartialInitialization() throws Exception {
        CountDownLatch initializing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Probe probe = create(4, physics -> {
            initializing.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
        });
        ManualScheduledExecutor executor = new ManualScheduledExecutor();
        FixedTickScheduler scheduler = new FixedTickScheduler(executor, Logger.getAnonymousLogger());
        FutureTask<Void> start = new FutureTask<>(() -> { probe.runtime.start(); return null; });
        Thread worker = new Thread(start, "runtime-initialization-test");
        worker.setDaemon(true);
        try {
            scheduler.register(probe.runtime);
            scheduler.start();
            worker.start();
            assertTrue(initializing.await(5, TimeUnit.SECONDS));
            assertFalse(probe.runtime.isStarted());
            assertNull(probe.runtime.getMatchState());
            executor.runCycle();
            assertFalse(probe.runtime.isStarted());
            release.countDown();
            start.get(5, TimeUnit.SECONDS);
            executor.runCycle();
            assertEquals(1, probe.runtime.getMatchState().getServerTick());
            assertEquals(22, probe.physics.get().getWorld().getBodyCount());
        } finally {
            release.countDown();
            worker.join(TimeUnit.SECONDS.toMillis(5));
            scheduler.stop();
            assertFalse(worker.isAlive());
        }
    }

    private void assertRoster(int count) {
        Probe probe = create(count, physics -> { });
        probe.runtime.start();
        assertEquals(18 + count, probe.physics.get().getWorld().getBodyCount());
        int dynamic = 0;
        for (Body body : bodies(probe.physics.get().getWorld())) {
            if (body.getType() == BodyDef.BodyType.DynamicBody) {
                dynamic++;
                PlayerId playerId = (PlayerId) body.getUserData();
                ServerPlayer player = probe.runtime.getMatchState().getPlayer(playerId);
                assertNotNull(player);
                assertEquals(100.0f, player.getHp(), 0.0f);
                assertFalse(player.isDead());
                var participant = probe.runtime.getParticipants().get(player.getSlotIndex());
                assertEquals(participant.characterId(), player.getCharacterId());
                assertEquals(participant.playerId(), playerId);
            }
        }
        assertEquals(count, dynamic);
        assertEquals(0, probe.runtime.getMatchState().getServerTick());
    }

    private Probe create(int count, Consumer<HeadlessPhysicsWorld> check) {
        List<MatchSetup.Participant> participants = new ArrayList<>();
        for (int slot = 0; slot < count; slot++) {
            participants.add(new MatchSetup.Participant(new PlayerId("player-" + slot), slot, "Alex"));
        }
        MatchSetup setup = new MatchSetup("match", "room", participants);
        AtomicReference<HeadlessPhysicsWorld> physics = new AtomicReference<>();
        AtomicInteger creations = new AtomicInteger();
        MatchRuntime runtime = new MatchRuntime(setup, () -> {
            creations.incrementAndGet();
            HeadlessPhysicsWorld created = new PhysicsWorldFactory().create();
            physics.set(created);
            return created;
        }, World::step, check);
        runtimes.add(runtime);
        return new Probe(runtime, physics, creations);
    }

    private static List<Body> bodies(World world) {
        Array<Body> array = new Array<>();
        world.getBodies(array);
        List<Body> result = new ArrayList<>();
        for (Body body : array) {
            result.add(body);
        }
        return result;
    }

    private record Probe(MatchRuntime runtime, AtomicReference<HeadlessPhysicsWorld> physics,
                         AtomicInteger creations) { }
}
