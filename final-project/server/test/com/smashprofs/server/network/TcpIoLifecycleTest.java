package com.smashprofs.server.network;

import com.smashprofs.common.protocol.MessageType;
import com.smashprofs.server.game.FixedTickScheduler;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/** Chỉ test I/O/boundary bằng stream thật. Scripted codec test double KHÔNG là framing/protocol tạm. */
public class TcpIoLifecycleTest {
    private final List<Harness> servers = new ArrayList<>();
    private final List<Socket> clients = new ArrayList<>();

    @After public void cleanup() throws IOException {
        try { for (Harness h : servers) { h.server.stop(); } }
        finally { for (Socket client : clients) { client.close(); } }
    }

    @Test public void eofUnregistersConnectionWithoutExplicitServerClose() throws Exception {
        Harness h = create(1, null, null, 16, 1000); h.server.start();
        Socket client = connect(h); h.awaitAccepted();
        ClientSession session = h.acceptedSessions.poll(5, TimeUnit.SECONDS);
        client.close(); h.awaitClosed();
        assertFalse(session.isConnected()); assertFalse(session.isOpen());
        assertEquals(0, h.server.getConnectionManager().size());
        assertTrue(h.server.isRunning());
    }

    @Test public void peerHalfCloseIsHandledAsEof() throws Exception {
        Harness h = create(1, null, null, 16, 1000); h.server.start();
        Socket client = connect(h); h.awaitAccepted();
        client.shutdownOutput(); h.awaitClosed();
        assertEquals(-1, client.getInputStream().read());
        assertEquals(0, h.server.getConnectionManager().size());
    }

    @Test public void closingOnePeerDoesNotCloseOtherReader() throws Exception {
        Harness h = create(2, null, null, 16, 1000); h.server.start();
        Socket first = connect(h); Socket second = connect(h); h.awaitAccepted();
        first.close(); h.awaitClosed();
        assertEquals(1, h.server.getConnectionManager().size());
        assertTrue(h.server.getConnectionManager().getSessions().get(0).isOpen());
        second.setSoTimeout(100);
        assertThrows(SocketTimeoutException.class, () -> second.getInputStream().read());
    }

    @Test public void fourReadersHaveBoundedWorkerCountAndIndependentSessions() throws Exception {
        CountDownLatch entered = new CountDownLatch(4);
        Harness h = create(4, () -> new ScriptCodec(input -> {
            entered.countDown(); return input.read() == -1 ? null : new TcpMessageCodec.DecodedMessage(MessageType.JOIN_ROOM, null);
        }), null, 16, 1000);
        h.server.start(); for (int i = 0; i < 4; i++) { connect(h); }
        h.awaitAccepted(); assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(4, h.server.getConnectionManager().size());
        assertEquals(4, h.transport.get().getLargestReaderPoolSize());
        clients.get(0).close(); h.awaitClosed();
        assertEquals(3, h.server.getConnectionManager().size());
        assertTrue(h.server.getConnectionManager().getSessions().stream().allMatch(ClientSession::isOpen));
    }

    @Test public void readRunsOutsideAcceptAndSimulationThreads() throws Exception {
        CountDownLatch entered = new CountDownLatch(1); AtomicReference<Thread> reader = new AtomicReference<>();
        Harness h = create(1, () -> new ScriptCodec(input -> {
            reader.set(Thread.currentThread()); entered.countDown(); return input.read() == -1 ? null : opaqueMessage();
        }), null, 16, 1000);
        h.server.start(); connect(h); h.awaitAccepted(); assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertNotSame(h.acceptThread.get(), reader.get());
        assertNotSame(Thread.currentThread(), reader.get());
        assertEquals("smashprofs-tcp-read", reader.get().getName());
        assertEquals(0, h.server.getRoomManager().getRoomCount());
    }

    @Test public void unconfiguredApplicationBytesAreRejectedWithoutInventingProtocol() throws Exception {
        Harness h = create(2, null, null, 16, 1000); h.server.start();
        Socket first = connect(h); Socket second = connect(h); h.awaitAccepted();
        first.getOutputStream().write(27); h.awaitClosed();
        assertEquals(1, h.server.getConnectionManager().size());
        assertEquals(-1, first.getInputStream().read());
        second.setSoTimeout(100);
        assertThrows(SocketTimeoutException.class, () -> second.getInputStream().read());
    }

    @Test public void idleReadTimeoutIsNotDisconnect() throws Exception {
        Harness h = create(1, null, null, 16, 1000); h.server.start();
        Socket client = connect(h); h.awaitAccepted();
        client.setSoTimeout(1300); // Chờ bằng blocking socket, vượt một lần server SO_TIMEOUT, không sleep.
        assertThrows(SocketTimeoutException.class, () -> client.getInputStream().read());
        assertEquals(1, h.server.getConnectionManager().size());
        assertTrue(h.server.getConnectionManager().getSessions().get(0).isOpen());
    }

    @Test public void readerIOExceptionClosesOnlyOwningSession() throws Exception { assertReaderFailureIsIsolated(false); }
    @Test public void readerRuntimeExceptionClosesOnlyOwningSession() throws Exception { assertReaderFailureIsIsolated(true); }

    private void assertReaderFailureIsIsolated(boolean runtime) throws Exception {
        Harness h = create(2, () -> new ScriptCodec(input -> {
            if (input.read() == -1) { return null; }
            if (runtime) { throw new IllegalArgumentException("test reader failure"); }
            throw new IOException("test I/O failure");
        }), null, 16, 1000);
        h.server.start(); Socket bad = connect(h); connect(h); h.awaitAccepted();
        bad.getOutputStream().write(1); h.awaitClosed();
        assertEquals(1, h.server.getConnectionManager().size()); assertTrue(h.server.isRunning());
        assertNull(h.server.getAcceptFailure());
    }

    @Test public void byteBudgetBoundsDecoderConsumptionWithoutParsingHeader() throws Exception {
        AtomicInteger consumed = new AtomicInteger();
        Harness h = create(1, () -> new ScriptCodec(input -> {
            while (input.read() != -1) { consumed.incrementAndGet(); }
            return null;
        }), null, 16, 1000);
        h.server.start(); Socket client = connect(h); h.awaitAccepted();
        client.getOutputStream().write(new byte[128]); h.awaitClosed();
        assertEquals(16, consumed.get()); assertEquals(0, h.server.getConnectionManager().size());
    }

    @Test public void incompleteReadDeadlineStartsOnlyAfterFirstByte() throws Exception {
        CountDownLatch firstByte = new CountDownLatch(1);
        Harness h = create(1, () -> new ScriptCodec(input -> {
            if (input.read() == -1) { return null; }
            firstByte.countDown(); input.read(); return opaqueMessage();
        }), null, 16, 100);
        h.server.start(); Socket client = connect(h); h.awaitAccepted();
        client.getOutputStream().write(1);
        assertTrue(firstByte.await(5, TimeUnit.SECONDS)); h.awaitClosed();
        assertEquals(0, h.server.getConnectionManager().size());
    }

    @Test public void codecFactoryMustProvideIndependentReaderAndEncoder() throws Exception {
        ScriptCodec shared = new ScriptCodec(input -> null);
        Harness h = create(1, () -> shared, null, 16, 1000); h.server.start();
        Socket client = connect(h);
        assertEquals(-1, client.getInputStream().read());
        assertEquals(0, h.server.getConnectionManager().size()); assertTrue(h.server.isRunning());
    }

    @Test public void adapterDispatchesWithOwningServerSessionNotPayloadIdentity() throws Exception {
        CountDownLatch dispatched = new CountDownLatch(1); AtomicReference<ClientSession> routed = new AtomicReference<>();
        Object payload = new Object();
        Harness h = create(1, () -> new ScriptCodec(input -> input.read() == -1 ? null :
                new TcpMessageCodec.DecodedMessage(MessageType.JOIN_ROOM, payload)), (session, value) -> {
            assertSame(payload, value); routed.set(session); dispatched.countDown();
        }, 16, 1000);
        h.server.start(); Socket client = connect(h); h.awaitAccepted();
        ClientSession expected = h.acceptedSessions.poll(5, TimeUnit.SECONDS);
        client.getOutputStream().write(1); assertTrue(dispatched.await(5, TimeUnit.SECONDS));
        assertSame(expected, routed.get()); assertNull(expected.getPlayerId()); assertNull(expected.getRoomId());
    }

    @Test public void separateConnectionsKeepIndependentAdapterState() throws Exception {
        ArrayBlockingQueue<Integer> results = new ArrayBlockingQueue<>(4);
        ArrayBlockingQueue<ClientSession> owners = new ArrayBlockingQueue<>(4);
        Harness h = create(2, () -> new TcpMessageCodec() {
            private int localCount;
            public DecodedMessage readNext(InputStream input, int limit) throws IOException {
                return input.read() == -1 ? null : new DecodedMessage(MessageType.JOIN_ROOM, ++localCount);
            }
            public byte[] encode(Object value, int limit) { throw new UnsupportedOperationException(); }
        }, (session, value) -> { owners.add(session); results.add((Integer) value); }, 16, 1000);
        h.server.start(); Socket a = connect(h); Socket b = connect(h); h.awaitAccepted();
        a.getOutputStream().write(1); assertEquals(Integer.valueOf(1), results.poll(5, TimeUnit.SECONDS));
        ClientSession ownerA = owners.poll(5, TimeUnit.SECONDS);
        b.getOutputStream().write(1); assertEquals(Integer.valueOf(1), results.poll(5, TimeUnit.SECONDS));
        assertNotSame(ownerA, owners.poll(5, TimeUnit.SECONDS));
        a.getOutputStream().write(1); assertEquals(Integer.valueOf(2), results.poll(5, TimeUnit.SECONDS));
        assertSame(ownerA, owners.poll(5, TimeUnit.SECONDS));
    }

    @Test public void adapterDispatchOrderIsSequentialOnOneReader() throws Exception {
        ArrayBlockingQueue<Integer> results = new ArrayBlockingQueue<>(4);
        Harness h = create(1, () -> new TcpMessageCodec() {
            private int sequence;
            public DecodedMessage readNext(InputStream input, int limit) throws IOException {
                return input.read() == -1 ? null : new DecodedMessage(MessageType.JOIN_ROOM, ++sequence);
            }
            public byte[] encode(Object value, int limit) { throw new UnsupportedOperationException(); }
        }, (session, value) -> results.add((Integer) value), 16, 1000);
        h.server.start(); Socket client = connect(h); h.awaitAccepted();
        client.getOutputStream().write(new byte[]{91, 13, 84});
        assertEquals(Integer.valueOf(1), results.poll(5, TimeUnit.SECONDS));
        assertEquals(Integer.valueOf(2), results.poll(5, TimeUnit.SECONDS));
        assertEquals(Integer.valueOf(3), results.poll(5, TimeUnit.SECONDS));
        // Các bytes opaque chỉ làm test double tiến; đây KHÔNG là test multiple frames của protocol.
    }

    @Test public void stopTerminatesAllBlockedReadersAndIsRepeatable() throws Exception {
        Harness h = create(4, null, null, 16, 1000); h.server.start();
        for (int i = 0; i < 4; i++) { connect(h); }
        h.awaitAccepted(); h.server.stop(); h.server.stop();
        assertTrue(h.transport.get().areReadersTerminated());
        assertFalse(h.acceptThread.get().isAlive());
        assertEquals(0, h.server.getConnectionManager().size());
        for (Socket client : clients) { assertEquals(-1, client.getInputStream().read()); }
    }

    @Test(timeout = 15000) public void stopFromReaderDoesNotAwaitOrInterruptItself() throws Exception {
        CountDownLatch stopped = new CountDownLatch(1); AtomicReference<Harness> owner = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Harness h = create(1, () -> new ScriptCodec(input -> input.read() == -1 ? null : opaqueMessage()),
                (session, payload) -> {
                    try { owner.get().server.stop(); } catch (Throwable unexpected) { failure.set(unexpected); }
                    finally { stopped.countDown(); }
                }, 16, 1000);
        owner.set(h); h.server.start(); Socket client = connect(h); h.awaitAccepted();
        client.getOutputStream().write(1); assertTrue(stopped.await(5, TimeUnit.SECONDS));
        assertNull(failure.get()); h.server.stop();
        assertTrue(h.transport.get().areReadersTerminated());
    }

    @Test public void restartCreatesNewReaderLifecycleWithoutReusingOldSockets() throws Exception {
        Harness h = create(1, null, null, 16, 1000); h.server.start();
        Socket old = connect(h); h.awaitAccepted(); TcpServerTransport prior = h.transport.get();
        h.server.stop(); assertTrue(prior.areReadersTerminated());
        h.server.start(); connect(h);
        assertNotNull(h.acceptedSessions.poll(5, TimeUnit.SECONDS)); // old receipt
        assertNotNull(h.acceptedSessions.poll(5, TimeUnit.SECONDS)); // new receipt
        assertNotSame(prior, h.transport.get()); assertEquals(-1, old.getInputStream().read());
        assertEquals(1, h.server.getConnectionManager().size());
    }

    @Test public void sendWithoutWireEncoderStillRejects() throws Exception {
        Harness h = create(1, null, null, 16, 1000); h.server.start(); connect(h); h.awaitAccepted();
        assertThrows(UnsupportedOperationException.class, () -> h.acceptedSessions.poll(5, TimeUnit.SECONDS).send(new Object()));
        assertEquals(1, h.server.getConnectionManager().size());
    }

    @Test public void idleConnectionDoesNotBlockAnotherReaderBoundary() throws Exception {
        ArrayBlockingQueue<ClientSession> routed = new ArrayBlockingQueue<>(2);
        Harness h = create(2, () -> new ScriptCodec(input -> input.read() == -1 ? null : opaqueMessage()),
                (session, payload) -> routed.add(session), 16, 1000);
        h.server.start(); connect(h); Socket active = connect(h); h.awaitAccepted();
        ClientSession idleOwner = h.acceptedSessions.poll(5, TimeUnit.SECONDS);
        ClientSession activeOwner = h.acceptedSessions.poll(5, TimeUnit.SECONDS);
        active.getOutputStream().write(1);
        assertSame(activeOwner, routed.poll(5, TimeUnit.SECONDS));
        assertTrue(idleOwner.isOpen()); assertEquals(2, h.server.getConnectionManager().size());
    }

    @Test public void optInConstructorUsesExistingHandlersWithoutInventingPlayerBinding() throws Exception {
        CountDownLatch reading = new CountDownLatch(1), processed = new CountDownLatch(1);
        GameServer server = new GameServer("127.0.0.1", 0, 1, () -> new TcpMessageCodec() {
            private int reads;
            public DecodedMessage readNext(InputStream input, int limit) throws IOException {
                if (++reads == 1) { reading.countDown(); } else { processed.countDown(); }
                return input.read() == -1 ? null : new DecodedMessage(MessageType.JOIN_ROOM, "not-created");
            }
            public byte[] encode(Object message, int limit) { throw new UnsupportedOperationException(); }
        }, 16, 1000);
        try (Socket client = new Socket()) {
            server.start(); client.connect(new InetSocketAddress("127.0.0.1", server.getBoundPort()), 2000);
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            ClientSession session = server.getConnectionManager().getSessions().get(0);
            assertNull(session.getPlayerId());
            client.getOutputStream().write(1);
            assertTrue(processed.await(5, TimeUnit.SECONDS));
            assertEquals(0, server.getRoomManager().getRoomCount()); assertNull(session.getRoomId());
        } finally { server.stop(); }
    }

    private Harness create(int count, Supplier<TcpMessageCodec> factory, BiConsumer<ClientSession, Object> callback,
                           int byteLimit, int partialTimeout) {
        Harness h = new Harness(count, factory, callback, byteLimit, partialTimeout); servers.add(h); return h;
    }
    private Socket connect(Harness h) throws IOException {
        Socket client = new Socket(); clients.add(client);
        client.connect(new InetSocketAddress("127.0.0.1", h.server.getBoundPort()), 2000); client.setSoTimeout(2000); return client;
    }
    private static TcpMessageCodec.DecodedMessage opaqueMessage() {
        return new TcpMessageCodec.DecodedMessage(MessageType.JOIN_ROOM, new Object());
    }
    @FunctionalInterface private interface ReadScript { TcpMessageCodec.DecodedMessage read(InputStream input) throws IOException; }
    private static final class ScriptCodec implements TcpMessageCodec {
        private final ReadScript script;
        private ScriptCodec(ReadScript script) { this.script = script; }
        public DecodedMessage readNext(InputStream input, int limit) throws IOException { return script.read(input); }
        public byte[] encode(Object value, int limit) { throw new UnsupportedOperationException("Test double không encoder"); }
    }
    private static final class Harness {
        private final AtomicReference<TcpServerTransport> transport = new AtomicReference<>();
        private final AtomicReference<Thread> acceptThread = new AtomicReference<>();
        private final CountDownLatch accepted;
        private final CountDownLatch closed = new CountDownLatch(1);
        private final ArrayBlockingQueue<ClientSession> acceptedSessions = new ArrayBlockingQueue<>(8);
        private final GameServer server;
        private Harness(int count, Supplier<TcpMessageCodec> factory, BiConsumer<ClientSession, Object> callback,
                        int byteLimit, int partialTimeout) {
            accepted = new CountDownLatch(count);
            server = new GameServer(FixedTickScheduler::new, System::nanoTime, connections -> {
                MessageDispatcher dispatcher = new MessageDispatcher((session, type, payload) -> {
                    if (callback != null) { callback.accept(session, payload); }
                });
                TcpIoConfiguration configuration = factory == null ? null : new TcpIoConfiguration(factory, dispatcher, byteLimit, partialTimeout);
                TcpServerTransport value = new TcpServerTransport("127.0.0.1", 0, count, connections,
                        command -> { Thread thread = new Thread(command, "tcp-accept-io-test"); acceptThread.set(thread); return thread; },
                        session -> { acceptedSessions.add(session); accepted.countDown(); }, configuration, session -> closed.countDown());
                transport.set(value); return value;
            });
        }
        private void awaitAccepted() throws InterruptedException { assertTrue(accepted.await(5, TimeUnit.SECONDS)); }
        private void awaitClosed() throws InterruptedException { assertTrue(closed.await(5, TimeUnit.SECONDS)); }
    }
}
