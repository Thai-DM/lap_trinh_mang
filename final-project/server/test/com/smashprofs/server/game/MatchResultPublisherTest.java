package com.smashprofs.server.game;

import com.smashprofs.common.model.PlayerId;
import org.junit.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Kết quả fixture bất biến cho boundary; không fake DB persistence hoặc tính rating. */
public class MatchResultPublisherTest {
    private final List<MatchResultPublisher> publishers = new ArrayList<>();
    @After public void close() { publishers.forEach(MatchResultPublisher::close); }
    private MatchResultPublisher publisher(RankingGateway gateway) {
        var publisher = new MatchResultPublisher(gateway); publishers.add(publisher); return publisher;
    }
    private AuthoritativeMatchResult result(String matchId, int count, boolean draw) {
        var participants = new ArrayList<AuthoritativeMatchResult.ParticipantResult>();
        for (int i = 0; i < count; i++) { participants.add(new AuthoritativeMatchResult.ParticipantResult(new PlayerId(matchId + "-p" + i), i, "Alex", 99.5f - i, false)); }
        return new AuthoritativeMatchResult(matchId, "room-" + matchId, 42, draw ? null : participants.get(0).playerId(),
                AuthoritativeMatchResult.FinishReason.TIME_LIMIT, participants);
    }
    private void status(MatchResultPublisher publisher, String id, MatchResultPublisher.Status expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (publisher.getStatus(id) != expected && System.nanoTime() < deadline) { Thread.yield(); }
        assertEquals(expected, publisher.getStatus(id));
    }
    @Test(timeout=15000) public void gatewayReceivesExactImmutableResultForTwoThreeAndFourPlayers() throws Exception {
        var received = new LinkedBlockingQueue<AuthoritativeMatchResult>(); var publisher = publisher(received::add);
        for (int count = 2; count <= 4; count++) {
            var result = result("m" + count, count, count == 3); publisher.offer(result);
            assertSame(result, received.poll(5, TimeUnit.SECONDS)); status(publisher, result.matchId(), MatchResultPublisher.Status.SUBMITTED);
            assertEquals(count, result.participants().size()); assertEquals(count == 3, result.isDraw());
            assertThrows(UnsupportedOperationException.class, () -> result.participants().clear());
        }
    }
    @Test(timeout=10000) public void duplicateFinishedCallbackDoesNotPublishTwice() throws Exception {
        var received = new LinkedBlockingQueue<AuthoritativeMatchResult>(); var publisher = publisher(received::add); var result = result("m", 4, true);
        for (int i = 0; i < 100; i++) { publisher.offer(result); }
        assertSame(result, received.poll(5, TimeUnit.SECONDS)); publisher.close(); assertTrue(received.isEmpty()); assertEquals(1, publisher.getTrackedCount());
    }
    @Test(timeout=15000) public void concurrentOffersForSameAndDifferentMatchesAreIsolated() throws Exception {
        var received = new LinkedBlockingQueue<AuthoritativeMatchResult>(); var publisher = publisher(received::add);
        var a = result("a", 4, false); var b = result("b", 2, true); var pool = Executors.newFixedThreadPool(4); var gate = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<?>>();
            for (var result : List.of(a, b, a, b)) { futures.add(pool.submit(() -> { assertTrue(gate.await(5, TimeUnit.SECONDS)); publisher.offer(result); return null; })); }
            gate.countDown(); for (var future : futures) { future.get(5, TimeUnit.SECONDS); }
            var first = received.poll(5, TimeUnit.SECONDS); var second = received.poll(5, TimeUnit.SECONDS);
            assertEquals(Set.of(a, b), Set.of(first, second)); publisher.close(); assertTrue(received.isEmpty()); assertEquals(2, publisher.getTrackedCount());
        } finally { gate.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    @Test(timeout=10000) public void gatewayFailureIsVisibleAndNotRetriedOrMutated() {
        var attempts = new AtomicInteger(); var result = result("failed", 4, false);
        var publisher = publisher(r -> { assertSame(result, r); attempts.incrementAndGet(); throw new IllegalStateException("unavailable"); });
        publisher.offer(result); status(publisher, result.matchId(), MatchResultPublisher.Status.FAILED);
        for (int i = 0; i < 20; i++) { assertEquals(MatchResultPublisher.Status.FAILED, publisher.offer(result)); }
        assertEquals(1, attempts.get()); assertEquals(new PlayerId("failed-p0"), result.winnerId()); assertEquals(42, result.finishedAtTick());
    }
    @Test public void unconfiguredGatewayNeverClaimsSuccessfulSubmission() {
        var publisher = publisher(null); var result = result("missing", 2, true);
        assertEquals(MatchResultPublisher.Status.NOT_CONFIGURED, publisher.offer(result));
        assertEquals(MatchResultPublisher.Status.NOT_CONFIGURED, publisher.offer(result)); assertEquals(0, publisher.getPendingCount()); assertTrue(publisher.isTerminated());
    }
    @Test(timeout=10000) public void queueFullIsBoundedVisibleAndNeverRetried() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var received = new LinkedBlockingQueue<AuthoritativeMatchResult>();
        var publisher = new MatchResultPublisher(r -> { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); received.add(r); }, 1, 10, Duration.ofSeconds(1)); publishers.add(publisher);
        try {
            var a = result("a", 2, false); var b = result("b", 3, true); var c = result("c", 4, false);
            publisher.offer(a); assertTrue(entered.await(5, TimeUnit.SECONDS)); publisher.offer(b);
            assertEquals(1, publisher.getPendingCount()); assertEquals(MatchResultPublisher.Status.QUEUE_FULL, publisher.offer(c));
            assertEquals(MatchResultPublisher.Status.QUEUE_FULL, publisher.offer(c)); assertEquals(1, publisher.getPendingCount());
            release.countDown(); publisher.close(); assertEquals(List.of(a, b), List.copyOf(received));
        } finally { release.countDown(); }
    }
    @Test public void trackingCapacityIsBoundedAndRejectsNewMatchesExplicitly() {
        var publisher = new MatchResultPublisher(null, 1, 1, Duration.ZERO); publishers.add(publisher);
        publisher.offer(result("a", 2, true)); assertEquals(MatchResultPublisher.Status.TRACKING_FULL, publisher.offer(result("b", 2, true)));
        assertEquals(1, publisher.getTrackedCount()); assertEquals(1, publisher.getTrackingRejections());
    }
    @Test(timeout=10000) public void shutdownCancelsPendingAndMarksInFlightUnconfirmed() throws Exception {
        var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1);
        var publisher = new MatchResultPublisher(r -> {
            entered.countDown(); try { new CountDownLatch(1).await(); }
            catch (InterruptedException failure) { interrupted.countDown(); throw failure; }
        }, 1, 10, Duration.ZERO); publishers.add(publisher);
        publisher.offer(result("a", 2, false)); assertTrue(entered.await(5, TimeUnit.SECONDS)); publisher.offer(result("b", 4, true)); publisher.close();
        assertTrue(interrupted.await(5, TimeUnit.SECONDS)); assertEquals(0, publisher.getPendingCount());
        assertEquals(MatchResultPublisher.Status.SHUTDOWN_UNCONFIRMED, publisher.getStatus("a"));
        assertEquals(MatchResultPublisher.Status.CANCELLED_SHUTDOWN, publisher.getStatus("b"));
        assertEquals(MatchResultPublisher.Status.CLOSED, publisher.offer(result("c", 2, false)));
    }
    @Test(timeout=10000) public void cooperativeShutdownDrainsPendingResults() throws Exception {
        var received = new LinkedBlockingQueue<AuthoritativeMatchResult>(); var publisher = publisher(received::add);
        var a = result("a", 2, false); var b = result("b", 4, true); publisher.offer(a); publisher.offer(b); publisher.close();
        assertTrue(publisher.isTerminated()); assertEquals(List.of(a, b), List.copyOf(received));
        assertEquals(MatchResultPublisher.Status.SUBMITTED, publisher.getStatus("a")); assertEquals(MatchResultPublisher.Status.SUBMITTED, publisher.getStatus("b"));
    }
    @Test public void configurationRejectsInvalidCapacityAndDuration() {
        assertThrows(IllegalArgumentException.class, () -> new MatchResultPublisher(null, 0, 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new MatchResultPublisher(null, 1, 0, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new MatchResultPublisher(null, 1, 1, Duration.ofSeconds(-1)));
        assertThrows(ArithmeticException.class, () -> new MatchResultPublisher(null, 1, 1, Duration.ofSeconds(Long.MAX_VALUE)));
    }
    @Test(timeout=10000) public void handoffDoesNotStepNativeWorldOrModifyAuthoritativeState() throws Exception {
        var a = new PlayerId("a"); var b = new PlayerId("b"); var steps = new AtomicInteger();
        var received = new LinkedBlockingQueue<AuthoritativeMatchResult>(); var publisher = publisher(received::add);
        var setup = new MatchSetup("native", "room", List.of(new MatchSetup.Participant(a, 0, "Alex"), new MatchSetup.Participant(b, 1, "Leo")));
        try (var runtime = new MatchRuntime(setup, new com.smashprofs.server.game.physics.PhysicsWorldFactory()::create,
                (world, dt, velocity, position) -> { steps.incrementAndGet(); world.step(dt, velocity, position); })) {
            runtime.start(); runtime.getMatchState().getPlayer(b).applyDamage(100); runtime.tick();
            var result = runtime.getMatchResult(); var snapshot = runtime.getLatestSnapshot(); publisher.offer(result);
            assertSame(result, received.poll(5, TimeUnit.SECONDS)); status(publisher, result.matchId(), MatchResultPublisher.Status.SUBMITTED);
            assertEquals(1, steps.get()); assertSame(snapshot, runtime.getLatestSnapshot()); assertSame(result, runtime.getMatchResult()); assertEquals(a, result.winnerId());
        }
    }
    @Test(timeout=10000) public void roomCleanupPublishesFinishedResultIfCompletionNotificationWasMissed() throws Exception {
        var received = new LinkedBlockingQueue<AuthoritativeMatchResult>(); var publisher = publisher(received::add);
        var clock = new java.util.concurrent.atomic.AtomicLong(10_000_000_000L);
        var scheduler = new FixedTickScheduler(new ManualScheduledExecutor(), java.util.logging.Logger.getLogger("cleanup-result"));
        var rooms = new com.smashprofs.server.room.RoomManager(scheduler, clock::get, publisher::offer);
        try {
            var room = rooms.createRoom("room"); var a = new PlayerId("a"); var b = new PlayerId("b");
            for (var id : List.of(a, b)) { room.joinPlayer(id); room.selectCharacter(id, "Alex"); room.setReady(id, true); }
            room.startCountdown(); clock.addAndGet(TimeUnit.SECONDS.toNanos(3)); room.updateCountdown(); room.prepareMatch();
            var runtime = room.startMatch(); runtime.getMatchState().getPlayer(b).applyDamage(100);
            // Fixture tái hiện tick đã kết thúc nhưng completion notification chưa chạy; scheduler chưa start.
            runtime.tick(); var result = runtime.getMatchResult(); assertNotNull(result); assertTrue(received.isEmpty());
            rooms.removeRoom("room"); assertTrue(runtime.isPhysicsDisposed()); assertSame(result, received.poll(5, TimeUnit.SECONDS));
            publisher.close(); assertTrue(received.isEmpty()); assertEquals(1, publisher.getTrackedCount());
        } finally { scheduler.stop(); rooms.shutdown(); }
    }
}
