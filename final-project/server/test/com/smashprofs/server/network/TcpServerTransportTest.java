package com.smashprofs.server.network;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.server.game.FixedTickScheduler;
import com.smashprofs.server.game.MatchRuntime;
import com.smashprofs.server.room.GameRoom;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** TCP loopback thật; chờ registration bằng latch, không sleep hoặc framing/gameplay qua mạng. */
public class TcpServerTransportTest {
    private final List<GameServer> servers = new ArrayList<>();
    private final List<Socket> clients = new ArrayList<>();

    @After public void cleanup() throws IOException {
        try { for (GameServer server : servers) { server.stop(); } }
        finally { for (Socket client : clients) { client.close(); } }
    }

    @Test public void serverCanBindToAvailablePort() {
        Harness h = create(1, 4); h.server.start();
        assertTrue(h.server.isRunning());
        assertTrue(h.server.getBoundPort() > 0);
    }

    @Test public void serverReportsActualBoundPort() throws Exception {
        Harness h = create(1, 4);
        assertEquals(-1, h.server.getBoundPort());
        h.server.start();
        Socket client = connect(h); h.awaitAccepted();
        assertEquals(h.server.getBoundPort(), client.getPort());
    }

    @Test public void serverAcceptsOneClient() throws Exception { assertAcceptedCount(1); }
    @Test public void serverAcceptsTwoClients() throws Exception { assertAcceptedCount(2); }
    @Test public void serverAcceptsFourClients() throws Exception { assertAcceptedCount(4); }

    private void assertAcceptedCount(int count) throws Exception {
        Harness h = create(count, 4); h.server.start();
        for (int i = 0; i < count; i++) { connect(h); }
        h.awaitAccepted();
        assertEquals(count, h.server.getConnectionManager().size());
        assertEquals(0, h.server.getRoomManager().getRoomCount());
        for (ClientSession session : h.server.getConnectionManager().getSessions()) {
            assertTrue(session.isOpen());
            assertNull(session.getPlayerId());
            assertNull(session.getRoomId());
        }
    }

    @Test public void clientsHaveIndependentConnections() throws Exception {
        Harness h = create(4, 4); h.server.start();
        for (int i = 0; i < 4; i++) { connect(h); }
        h.awaitAccepted();
        var sessions = h.server.getConnectionManager().getSessions();
        assertEquals(4, new HashSet<>(sessions.stream().map(ClientSession::getSessionId).toList()).size());
        assertEquals(4, new HashSet<>(clients.stream().map(Socket::getLocalPort).toList()).size());
        assertThrows(UnsupportedOperationException.class, () -> sessions.clear());
    }

    @Test public void acceptedConnectionsAreRegistered() throws Exception {
        Harness h = create(1, 4); h.server.start(); connect(h); h.awaitAccepted();
        ClientSession session = h.server.getConnectionManager().getSessions().get(0);
        assertSame(session, h.server.getConnectionManager().get(session.getSessionId()));
    }

    @Test public void connectionCountIsCorrect() throws Exception {
        Harness h = create(4, 4); h.server.start();
        for (int i = 0; i < 4; i++) { connect(h); }
        h.awaitAccepted();
        assertEquals(4, h.server.getConnectionManager().size());
        var old = h.server.getConnectionManager().getSessions();
        for (int i = 0; i < 4; i++) {
            old.get(i).close();
            assertEquals(3 - i, h.server.getConnectionManager().size());
        }
    }

    @Test public void clientCloseCleansUpAccordingToImplementedLifecycle() throws Exception {
        Harness h = create(1, 4); h.server.start(); Socket client = connect(h); h.awaitAccepted();
        ClientSession session = h.server.getConnectionManager().getSessions().get(0);
        client.close();
        // Bước 43: reader tự phát hiện EOF; không polling hoặc ép explicit close để làm test pass.
        assertTrue(h.closed.await(5, TimeUnit.SECONDS));
        session.close(); session.close();
        assertEquals(0, h.server.getConnectionManager().size());
        assertFalse(session.isOpen());
        assertFalse(session.isConnected());
    }

    @Test public void closingOneConnectionDoesNotCloseOthers() throws Exception {
        Harness h = create(2, 4); h.server.start();
        Socket first = connect(h); Socket second = connect(h); h.awaitAccepted();
        // Một accept loop giữ thứ tự callback theo hai connect tuần tự, độc lập với registry order.
        h.acceptedSessions.get(0).close();
        assertEof(first);
        assertTrue(h.acceptedSessions.get(1).isOpen());
        assertEquals(1, h.server.getConnectionManager().size());
        assertNoBytes(second);
    }

    @Test public void serverRejectsConnectionsBeyondConfiguredLimit() throws Exception {
        Harness h = create(2, 2); h.server.start();
        connect(h); connect(h); h.awaitAccepted();
        Socket rejected = connect(h);
        assertEof(rejected);
        assertEquals(2, h.server.getConnectionManager().size());
        assertTrue(h.server.getConnectionManager().getSessions().stream().allMatch(ClientSession::isOpen));
    }

    @Test public void repeatedStartDoesNotCreateDuplicateListener() throws Exception {
        Harness h = create(1, 4); h.server.start();
        int port = h.server.getBoundPort(); Thread worker = h.acceptThread.get();
        h.server.start(); h.server.start();
        assertEquals(port, h.server.getBoundPort());
        assertSame(worker, h.acceptThread.get());
        assertEquals(1, h.threadCount.get());
        connect(h); h.awaitAccepted();
        assertEquals(1, h.server.getConnectionManager().size());
    }

    @Test public void repeatedStopIsSafe() {
        Harness h = create(1, 4); h.server.start();
        h.server.stop(); h.server.stop();
        assertFalse(h.server.isRunning());
        assertFalse(h.acceptThread.get().isAlive());
        assertNull(h.server.getAcceptFailure());
    }

    @Test public void stopClosesListeningSocket() throws Exception {
        Harness h = create(1, 4); h.server.start(); int port = h.server.getBoundPort();
        h.server.stop();
        assertEquals(-1, h.server.getBoundPort());
        try (Socket client = new Socket()) {
            assertThrows(IOException.class, () -> client.connect(new InetSocketAddress("127.0.0.1", port), 2000));
        }
        assertCanBind(port);
    }

    @Test public void stopClosesAcceptedSockets() throws Exception {
        Harness h = create(4, 4); h.server.start();
        List<Socket> own = new ArrayList<>();
        for (int i = 0; i < 4; i++) { own.add(connect(h)); }
        h.awaitAccepted();
        var sessions = h.server.getConnectionManager().getSessions();
        h.server.stop();
        for (Socket client : own) { assertEof(client); }
        assertTrue(sessions.stream().noneMatch(ClientSession::isConnected));
        assertEquals(0, h.server.getConnectionManager().size());
    }

    @Test public void stopUnblocksAcceptLoop() {
        Harness h = create(1, 4); h.server.start();
        Thread worker = h.acceptThread.get();
        assertTrue(worker.isAlive());
        h.server.stop();
        assertFalse(worker.isAlive());
        assertNull(h.server.getAcceptFailure());
    }

    @Test public void bindFailureDoesNotLeaveServerRunning() throws Exception {
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));
            GameServer server = new GameServer("127.0.0.1", occupied.getLocalPort(), 4); servers.add(server);
            UncheckedIOException failure = assertThrows(UncheckedIOException.class, server::start);
            assertTrue(failure.getMessage().contains("bind"));
            assertFalse(server.isRunning());
            assertEquals(-1, server.getBoundPort());
            assertEquals(0, server.getConnectionManager().size());
            assertThrows(IllegalStateException.class, () -> server.getRoomManager().getOrCreateRoom("closed"));
        }
    }

    @Test public void partialStartFailureReleasesResources() throws Exception {
        AtomicReference<TcpServerTransport> transport = new AtomicReference<>();
        AtomicReference<FixedTickScheduler> scheduler = new AtomicReference<>();
        AtomicLong boundPort = new AtomicLong();
        GameServer server = new GameServer(() -> {
            FixedTickScheduler value = new FixedTickScheduler(); scheduler.set(value); return value;
        }, System::nanoTime, connections -> {
            TcpServerTransport value = new TcpServerTransport("127.0.0.1", 0, 4, connections, command -> {
                boundPort.set(transport.get().getBoundPort());
                return new Thread(command) {
                    @Override public synchronized void start() { throw new IllegalStateException("test thread start failure"); }
                };
            }, session -> { });
            transport.set(value); return value;
        });
        servers.add(server);
        assertThrows(IllegalStateException.class, server::start);
        assertFalse(server.isRunning());
        assertFalse(scheduler.get().isRunning());
        assertEquals(-1, server.getBoundPort());
        assertCanBind((int) boundPort.get());
    }

    @Test public void listenerDoesNotStepPhysicsWorld() throws Exception {
        Harness h = create(4, 4);
        MatchRuntime first = preparedRoom(h, "a").startMatch();
        MatchRuntime second = preparedRoom(h, "b").startMatch();
        // Cô lập accept boundary: hủy registration trước tick đầu tiên để giữ owner là test thread.
        assertTrue(h.scheduler.get().unregister(first));
        assertTrue(h.scheduler.get().unregister(second));
        h.server.start();
        for (int i = 0; i < 4; i++) { connect(h); }
        h.awaitAccepted();
        assertEquals(0, first.getMatchState().getServerTick());
        assertEquals(0, second.getMatchState().getServerTick());
        assertNull(first.getLatestSnapshot());
        assertNull(second.getLatestSnapshot());
        first.tick(); second.tick();
        assertEquals(1, first.getMatchState().getServerTick());
        assertEquals(1, second.getMatchState().getServerTick());
        assertNotEquals(Thread.currentThread(), h.acceptThread.get());
    }

    @Test public void twoMatchesRemainIndependent() throws Exception {
        Harness h = create(2, 4);
        MatchRuntime first = preparedRoom(h, "a").startMatch();
        MatchRuntime second = preparedRoom(h, "b").startMatch();
        h.scheduler.get().unregister(first); h.scheduler.get().unregister(second);
        h.server.start(); connect(h); connect(h); h.awaitAccepted();
        first.tick(); first.tick(); second.tick();
        assertEquals(2, first.getLatestSnapshot().serverTick());
        assertEquals(1, second.getLatestSnapshot().serverTick());
        assertNotSame(first.getMatchState(), second.getMatchState());
        assertNotEquals(first.getMatchId(), second.getMatchId());
        assertEquals("a", first.getLatestSnapshot().roomId());
        assertEquals("b", second.getLatestSnapshot().roomId());
        h.acceptedSessions.get(0).close();
        h.server.getRoomManager().removeRoom("a"); second.tick();
        assertEquals(2, second.getLatestSnapshot().serverTick());
        assertFalse(second.isClosed());
        assertTrue(h.acceptedSessions.get(1).isOpen());
    }

    @Test public void tcpSendRejectsUntilFramingExistsAndSendsNoGreeting() throws Exception {
        Harness h = create(1, 4); h.server.start(); Socket client = connect(h); h.awaitAccepted();
        ClientSession session = h.acceptedSessions.get(0);
        assertThrows(UnsupportedOperationException.class, () -> session.send(new Object()));
        assertNoBytes(client);
        session.close();
        assertThrows(IllegalStateException.class, () -> session.send(new Object()));
    }

    @Test public void serverCanRestartWithFreshListenerAndSessions() throws Exception {
        Harness h = create(1, 4); h.server.start(); Socket oldClient = connect(h); h.awaitAccepted();
        ConnectionManager oldConnections = h.server.getConnectionManager();
        ClientSession oldSession = h.acceptedSessions.get(0); Thread oldWorker = h.acceptThread.get();
        h.server.stop(); h.server.start();
        assertEof(oldClient);
        assertFalse(oldWorker.isAlive());
        assertFalse(oldSession.isOpen());
        assertNotSame(oldConnections, h.server.getConnectionManager());
        assertEquals(0, h.server.getConnectionManager().size());
        assertEquals(2, h.threadCount.get());
        Socket fresh = connect(h);
        // Callback riêng cho mỗi lifecycle để tránh latch cũ đã về 0.
        assertTrue(h.restartedAccepted.await(5, TimeUnit.SECONDS));
        assertEquals(1, h.server.getConnectionManager().size());
        assertNoBytes(fresh);
    }

    @Test public void configuredPortCanBeReboundAfterConnectedServerStops() throws Exception {
        int fixedPort;
        try (ServerSocket probe = new ServerSocket()) {
            probe.bind(new InetSocketAddress("127.0.0.1", 0)); fixedPort = probe.getLocalPort();
        }
        Harness h = new Harness(1, 4, fixedPort); servers.add(h.server);
        h.server.start(); Socket old = connect(h); h.awaitAccepted();
        h.server.stop(); assertEof(old); old.close();
        h.server.start();
        assertEquals(fixedPort, h.server.getBoundPort());
        connect(h);
        assertTrue(h.restartedAccepted.await(5, TimeUnit.SECONDS));
        assertEquals(1, h.server.getConnectionManager().size());
    }

    @Test public void invalidConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new GameServer("127.0.0.1", -1, 4));
        assertThrows(IllegalArgumentException.class, () -> new GameServer("127.0.0.1", 65536, 4));
        assertThrows(IllegalArgumentException.class, () -> new GameServer("127.0.0.1", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new GameServer(" ", 0, 4));
        assertThrows(NullPointerException.class, () -> new GameServer(null, 0, 4));
        // Port tối đa là cấu hình hợp lệ; constructor không bind port này.
        GameServer valid = new GameServer("127.0.0.1", 65535, 1); servers.add(valid);
        assertFalse(valid.isRunning());
    }

    @Test public void identityRemovalDoesNotRemoveReplacementSession() {
        ConnectionManager manager = new ConnectionManager();
        SessionChannel inert = new SessionChannel() {
            public void send(Object message) { throw new UnsupportedOperationException(); }
            public void close() { }
            public boolean isOpen() { return true; }
        };
        ClientSession old = new ClientSession("same-id", inert, manager::removeIfSame);
        ClientSession replacement = new ClientSession("same-id", inert);
        manager.register(old); manager.remove("same-id"); manager.register(replacement);
        old.close(); old.close();
        assertSame(replacement, manager.get("same-id"));
        assertEquals(1, manager.size());
    }

    @Test(timeout = 20000) public void concurrentConnectionsNeverExceedConfiguredLimit() throws Exception {
        Harness h = create(4, 4); h.server.start();
        int port = h.server.getBoundPort();
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8), start = new CountDownLatch(1);
        List<Future<Socket>> pending = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                pending.add(workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    Socket socket = new Socket();
                    try { socket.connect(new InetSocketAddress("127.0.0.1", port), 2000); return socket; }
                    catch (Exception failure) { socket.close(); throw failure; }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            List<Socket> own = new ArrayList<>();
            for (Future<Socket> future : pending) {
                Socket socket = future.get(5, TimeUnit.SECONDS); clients.add(socket); own.add(socket);
            }
            h.awaitAccepted();
            int open = 0, rejected = 0;
            for (Socket socket : own) {
                socket.setSoTimeout(200);
                try { assertEquals(-1, socket.getInputStream().read()); rejected++; }
                catch (SocketTimeoutException expected) { open++; }
            }
            assertEquals(4, open); assertEquals(4, rejected);
            assertEquals(4, h.server.getConnectionManager().size());
            assertEquals(1, h.threadCount.get());
        } finally {
            start.countDown(); workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 15000) public void stopFromAcceptThreadDoesNotJoinItself() throws Exception {
        AtomicReference<GameServer> owner = new AtomicReference<>();
        AtomicReference<Thread> worker = new AtomicReference<>();
        CountDownLatch stopped = new CountDownLatch(1);
        GameServer server = new GameServer(FixedTickScheduler::new, System::nanoTime,
                connections -> new TcpServerTransport("127.0.0.1", 0, 4, connections, command -> {
                    Thread thread = new Thread(command, "tcp-self-stop-test"); worker.set(thread); return thread;
                }, session -> { owner.get().stop(); stopped.countDown(); }));
        owner.set(server); servers.add(server); server.start();
        Socket client = new Socket(); clients.add(client);
        client.connect(new InetSocketAddress("127.0.0.1", server.getBoundPort()), 2000);
        client.setSoTimeout(2000);
        assertTrue(stopped.await(5, TimeUnit.SECONDS));
        worker.get().join(2000);
        assertFalse(worker.get().isAlive());
        assertFalse(server.isRunning());
        assertEquals(0, server.getConnectionManager().size());
        assertEof(client);
    }

    @Test(timeout = 15000) public void shutdownDuringRegistrationLeavesNoConnectionsOrAcceptThread() throws Exception {
        CountDownLatch registered = new CountDownLatch(1), releaseAccept = new CountDownLatch(1);
        CountDownLatch stopEntered = new CountDownLatch(1);
        AtomicReference<Thread> acceptWorker = new AtomicReference<>();
        GameServer server = new GameServer(FixedTickScheduler::new, System::nanoTime,
                connections -> new TcpServerTransport("127.0.0.1", 0, 4, connections, command -> {
                    Thread worker = new Thread(command, "tcp-shutdown-race-test"); acceptWorker.set(worker); return worker;
                }, session -> {
                    registered.countDown();
                    try {
                        if (!releaseAccept.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Test release timeout"); }
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt(); throw new IllegalStateException(failure);
                    }
                }));
        servers.add(server); server.start();
        int port = server.getBoundPort();
        Socket first = new Socket(); clients.add(first);
        first.connect(new InetSocketAddress("127.0.0.1", port), 2000); first.setSoTimeout(2000);
        assertTrue(registered.await(5, TimeUnit.SECONDS));
        ConnectionManager connections = server.getConnectionManager();
        var sessions = connections.getSessions();
        assertEquals(1, sessions.size());
        // Kết nối thứ hai đang ở backlog trong khi callback accepted đầu tiên tạm dừng.
        Socket pending = new Socket(); clients.add(pending);
        pending.connect(new InetSocketAddress("127.0.0.1", port), 2000);
        FutureTask<Boolean> stop = new FutureTask<>(() -> {
            stopEntered.countDown(); server.stop(); return true;
        });
        Thread stopWorker = new Thread(stop, "tcp-stop-race-test");
        try {
            stopWorker.start(); assertTrue(stopEntered.await(5, TimeUnit.SECONDS));
            releaseAccept.countDown();
            assertTrue(stop.get(5, TimeUnit.SECONDS));
            assertFalse(acceptWorker.get().isAlive());
            assertEquals(0, connections.size());
            assertTrue(sessions.stream().noneMatch(ClientSession::isOpen));
            assertFalse(server.isRunning());
            assertNull(server.getAcceptFailure());
            assertEof(first);
        } finally {
            releaseAccept.countDown();
            stopWorker.join(5000);
            assertFalse(stopWorker.isAlive());
        }
    }

    private Harness create(int acceptedCount, int limit) {
        Harness h = new Harness(acceptedCount, limit); servers.add(h.server); return h;
    }

    private Socket connect(Harness h) throws IOException {
        Socket client = new Socket(); clients.add(client);
        client.connect(new InetSocketAddress("127.0.0.1", h.server.getBoundPort()), 2000);
        client.setSoTimeout(2000);
        return client;
    }

    private static void assertEof(Socket client) throws IOException { assertEquals(-1, client.getInputStream().read()); }
    private static void assertNoBytes(Socket client) throws IOException {
        client.setSoTimeout(100);
        assertThrows(SocketTimeoutException.class, () -> client.getInputStream().read());
        client.setSoTimeout(2000);
    }
    private static void assertCanBind(int port) throws IOException {
        assertTrue(port > 0);
        try (ServerSocket listener = new ServerSocket()) { listener.bind(new InetSocketAddress("127.0.0.1", port)); }
    }

    private GameRoom preparedRoom(Harness h, String roomId) {
        GameRoom room = h.server.getRoomManager().getOrCreateRoom(roomId);
        for (int i = 0; i < 2; i++) {
            PlayerId id = new PlayerId(roomId + "-player-" + i);
            room.joinPlayer(id); room.selectCharacter(id, "Luca"); room.setReady(id, true);
        }
        assertTrue(room.startCountdown());
        h.clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
        assertTrue(room.updateCountdown()); assertNotNull(room.prepareMatch());
        return room;
    }

    private static final class Harness {
        private final AtomicLong clock = new AtomicLong();
        private final AtomicReference<Thread> acceptThread = new AtomicReference<>();
        private final AtomicReference<FixedTickScheduler> scheduler = new AtomicReference<>();
        private final AtomicLong threadCount = new AtomicLong();
        private final List<ClientSession> acceptedSessions = new ArrayList<>();
        private final CountDownLatch accepted;
        private final CountDownLatch restartedAccepted = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final GameServer server;

        private Harness(int acceptedCount, int limit) {
            this(acceptedCount, limit, 0);
        }

        private Harness(int acceptedCount, int limit, int bindPort) {
            accepted = new CountDownLatch(acceptedCount);
            server = new GameServer(() -> {
                FixedTickScheduler value = new FixedTickScheduler(); scheduler.set(value); return value;
            }, clock::get, connections -> new TcpServerTransport("127.0.0.1", bindPort, limit, connections, command -> {
                Thread worker = new Thread(command, "tcp-accept-test");
                acceptThread.set(worker); threadCount.incrementAndGet(); return worker;
            }, session -> {
                acceptedSessions.add(session);
                if (threadCount.get() == 1) { accepted.countDown(); }
                else { restartedAccepted.countDown(); }
            }, null, session -> closed.countDown()));
        }
        private void awaitAccepted() throws InterruptedException { assertTrue("Accept timeout", accepted.await(5, TimeUnit.SECONDS)); }
    }
}
