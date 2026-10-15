package com.smashprofs.server.network;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.protocol.*;
import org.junit.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

/** Clock manual, không socket/thread/sleep; kiểm tra state và deadline từng connection. */
public class HeartbeatManagerTest {
    private final ConnectionManager connections = new ConnectionManager();
    private final AtomicLong clock = new AtomicLong();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final HeartbeatManager manager = new HeartbeatManager(connections,
            HeartbeatConfiguration.DEFAULT, clock::get, executor);
    private final List<Channel> channels = new ArrayList<>();
    @After public void close() { manager.close(); connections.closeAll(); assertTrue(executor.isTerminated()); }
    private ClientSession session(boolean activate) {
        var channel = new Channel(); channels.add(channel);
        var session = new ClientSession("s" + channels.size(), channel, connections::removeIfSame); connections.register(session);
        if (activate) { session.bindPlayer(new PlayerId("p" + channels.size())); manager.connected(session); }
        return session;
    }
    private void seconds(long seconds) { clock.addAndGet(TimeUnit.SECONDS.toNanos(seconds)); manager.runMaintenance(); }
    private String ping(ClientSession session) { return session.heartbeat.outstanding; }
    @Test public void stalledWriteIsClosedBeforeHeartbeatActivationWithoutAffectingOtherSessions() {
        var stalled = session(false); var healthy = session(true);
        channels.get(0).writeStarted = System.nanoTime() - TimeUnit.SECONDS.toNanos(6);
        manager.runMaintenance();
        assertFalse(stalled.isOpen()); assertTrue(healthy.isOpen()); assertEquals(1, connections.size());
    }
    @Test public void heartbeatDoesNotStartBeforeConnect() { var session = session(false); seconds(50); assertNull(ping(session)); assertTrue(session.isOpen()); assertTrue(channels.get(0).deliveries.isEmpty()); }
    @Test public void heartbeatStartsAfterConnect() { var session = session(true); assertTrue(session.heartbeat.active); seconds(5); assertNotNull(ping(session)); }
    @Test public void pingUsesProtocolV1Envelope() throws Exception {
        var session = session(true); seconds(5); var delivery = channels.get(0).deliveries.get(0);
        var message = delivery.message(); assertEquals(1, message.version()); assertEquals(MessageType.PING, message.type());
        assertEquals(ping(session), message.requestId()); assertTrue(message.payload().isEmpty());
        var codec = new JsonFrameCodec(); assertEquals(message, codec.readNext(new java.io.ByteArrayInputStream(codec.encode(message))));
    }
    @Test public void pingIsSentAtConfiguredInterval() { var session = session(true); seconds(4); assertNull(ping(session)); seconds(1); assertNotNull(ping(session)); }
    @Test public void oneOutstandingPingDoesNotResetDeadline() { var session = session(true); seconds(5); var id = ping(session); seconds(5); assertEquals(id, ping(session)); assertEquals(1, channels.get(0).deliveries.size()); seconds(10); assertFalse(session.isOpen()); }
    @Test public void validPongAcknowledgesOutstandingPing() { var session = session(true); seconds(5); assertTrue(manager.acknowledge(session, ping(session))); assertNull(ping(session)); assertEquals(clock.get(), session.heartbeat.lastValidNanos); }
    @Test public void stalePongIsRejected() { var session = session(true); seconds(5); var old = ping(session); assertTrue(manager.acknowledge(session, old)); seconds(5); assertNotEquals(old, ping(session)); assertFalse(manager.acknowledge(session, old)); }
    @Test public void duplicatePongDoesNotRefreshHeartbeat() { var session = session(true); seconds(5); var id = ping(session); assertTrue(manager.acknowledge(session, id)); seconds(1); assertFalse(manager.acknowledge(session, id)); assertEquals(TimeUnit.SECONDS.toNanos(5), session.heartbeat.lastValidNanos); }
    @Test public void wrongPingIdIsRejected() { var session = session(true); seconds(5); assertFalse(manager.acknowledge(session, "wrong")); assertNotNull(ping(session)); }
    @Test public void pongCannotAcknowledgeAnotherSession() { var a = session(true); var b = session(true); seconds(5); assertNotEquals(ping(a), ping(b)); assertFalse(manager.acknowledge(a, ping(b))); assertFalse(manager.acknowledge(b, ping(a))); }
    @Test public void missingPongTriggersTimeoutAtExactDeadline() { var session = session(true); seconds(5); seconds(14); assertTrue(session.isOpen()); seconds(1); assertFalse(session.isOpen()); assertEquals(0, connections.size()); }
    @Test public void validPongPreventsTimeout() { var a = session(true); var b = session(true); seconds(5); assertTrue(manager.acknowledge(b, ping(b))); seconds(15); assertFalse(a.isOpen()); assertTrue(b.isOpen()); assertEquals(1, connections.size()); }
    @Test public void latePongCannotRescueExpiredConnection() { var session = session(true); seconds(5); var id = ping(session); clock.addAndGet(TimeUnit.SECONDS.toNanos(15)); assertFalse(manager.acknowledge(session, id)); manager.runMaintenance(); assertFalse(session.isOpen()); }
    @Test public void repeatedConnectDoesNotRefreshDeadline() { var session = session(true); seconds(5); seconds(14); manager.connected(session); seconds(1); assertFalse(session.isOpen()); }
    @Test public void heartbeatStateIsRemovedOnClose() { var session = session(true); seconds(5); var delivery = channels.get(0).deliveries.get(0); session.close(); assertNull(ping(session)); assertFalse(session.heartbeat.active); assertFalse(delivery.stillValid().getAsBoolean()); assertEquals(0, connections.size()); }
    @Test public void repeatedTimeoutCleanupIsSafe() { var session = session(true); seconds(5); seconds(15); seconds(100); session.close(); assertEquals(0, connections.size()); assertFalse(session.heartbeat.active); }
    @Test public void failedOfferClosesOnlyItsSession() { var a = session(true); var b = session(true); channels.get(0).accept = false; seconds(5); assertFalse(a.isOpen()); assertTrue(b.isOpen()); assertEquals(1, connections.size()); }
    @Test public void shutdownInvalidatesPendingPingAndState() { var session = session(true); seconds(5); var delivery = channels.get(0).deliveries.get(0); manager.close(); assertFalse(delivery.stillValid().getAsBoolean()); assertFalse(session.heartbeat.active); manager.runMaintenance(); assertEquals(1, channels.get(0).deliveries.size()); }
    @Test public void timeoutConfigurationIsValidated() {
        for (var interval : List.of(Duration.ZERO, Duration.ofSeconds(-1))) { assertThrows(IllegalArgumentException.class, () -> new HeartbeatConfiguration(interval, Duration.ofSeconds(15))); }
        assertThrows(IllegalArgumentException.class, () -> new HeartbeatConfiguration(Duration.ofSeconds(5), Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> new HeartbeatConfiguration(Duration.ofSeconds(5), Duration.ofSeconds(4)));
        assertThrows(ArithmeticException.class, () -> new HeartbeatConfiguration(Duration.ofSeconds(Long.MAX_VALUE), Duration.ofSeconds(Long.MAX_VALUE)));
        assertThrows(NullPointerException.class, () -> new HeartbeatConfiguration(null, Duration.ofSeconds(5)));
    }
    @Test public void timeoutUsesMonotonicClockIncludingNanoTimeWrap() {
        var nanoExecutor = Executors.newSingleThreadScheduledExecutor();
        clock.set(Long.MAX_VALUE - 3);
        try (var nanoManager = new HeartbeatManager(connections,
                new HeartbeatConfiguration(Duration.ofNanos(2), Duration.ofNanos(6)), clock::get, nanoExecutor)) {
            var session = session(false); session.bindPlayer(new PlayerId("wrap")); nanoManager.connected(session);
            clock.addAndGet(2); nanoManager.runMaintenance(); assertNotNull(ping(session));
            clock.addAndGet(5); nanoManager.runMaintenance(); assertTrue(session.isOpen());
            clock.addAndGet(1); nanoManager.runMaintenance(); assertFalse(session.isOpen());
        }
    }
    @Test(timeout=10000) public void eofAndTimeoutRaceIsSafe() throws Exception {
        var session = session(true); seconds(5); clock.addAndGet(TimeUnit.SECONDS.toNanos(15));
        var pool = Executors.newFixedThreadPool(2); var gate = new CountDownLatch(1);
        try {
            var eof = pool.submit(() -> { assertTrue(gate.await(5, TimeUnit.SECONDS)); session.close(); return null; });
            var timeout = pool.submit(() -> { assertTrue(gate.await(5, TimeUnit.SECONDS)); manager.runMaintenance(); return null; });
            gate.countDown(); eof.get(5, TimeUnit.SECONDS); timeout.get(5, TimeUnit.SECONDS);
            assertEquals(0, connections.size()); assertFalse(session.isOpen()); assertNull(ping(session));
        } finally { gate.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    private static final class Channel implements SessionChannel {
        private final List<HeartbeatDelivery> deliveries = new ArrayList<>();
        private boolean open = true, accept = true;
        private long writeStarted;
        @Override public long writeStartedNanos() { return writeStarted; }
        @Override public boolean offerHeartbeat(HeartbeatDelivery delivery) { if (accept) { deliveries.add(delivery); } return accept; }
        @Override public void send(Object message) { fail("Maintenance không được send đồng bộ"); }
        @Override public synchronized void close() { open = false; }
        @Override public synchronized boolean isOpen() { return open; }
    }
}
