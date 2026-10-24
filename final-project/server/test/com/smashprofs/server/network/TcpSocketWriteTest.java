package com.smashprofs.server.network;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Chỉ kiểm tra transport bytes/write lock. Không có schema, header, delimiter hoặc protocol encoder. */
public class TcpSocketWriteTest {
    private final List<Pair> pairs = new ArrayList<>();

    @After public void closePairs() throws IOException { for (Pair pair : pairs) { pair.close(); } }

    @Test public void suppliedBytesAreWrittenUnchanged() throws Exception {
        Pair p = pair(); byte[] bytes = {0, -1, 12, 64, 9};
        p.channel.writeEncodedBytes(bytes, 16);
        assertArrayEquals(bytes, p.client.getInputStream().readNBytes(bytes.length));
    }

    @Test(timeout = 15000) public void concurrentWritesToSameSocketDoNotInterleave() throws Exception {
        Pair p = pair(); ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8), start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int i = 1; i <= 8; i++) {
                byte value = (byte) i;
                tasks.add(workers.submit(() -> {
                    ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS));
                    byte[] bytes = new byte[4096]; Arrays.fill(bytes, value);
                    p.channel.writeEncodedBytes(bytes, bytes.length); return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            byte[] received = p.client.getInputStream().readNBytes(8 * 4096);
            for (Future<?> task : tasks) { task.get(5, TimeUnit.SECONDS); }
            assertEquals(8 * 4096, received.length);
            HashSet<Byte> seen = new HashSet<>();
            for (int offset = 0; offset < received.length; offset += 4096) {
                byte value = received[offset]; assertTrue(seen.add(value));
                for (int j = 0; j < 4096; j++) { assertEquals(value, received[offset + j]); }
            }
            assertEquals(8, seen.size());
        } finally {
            start.countDown(); p.channel.close(); workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void writesToDifferentSocketsRemainIsolated() throws Exception {
        Pair a = pair(), b = pair();
        byte[] first = {1, 7, 2}, second = {42, 3};
        a.channel.writeEncodedBytes(first, 16); b.channel.writeEncodedBytes(second, 16);
        assertArrayEquals(first, a.client.getInputStream().readNBytes(first.length));
        assertArrayEquals(second, b.client.getInputStream().readNBytes(second.length));
        a.channel.close(); b.channel.writeEncodedBytes(second, 16);
        assertArrayEquals(second, b.client.getInputStream().readNBytes(second.length));
    }

    @Test public void overLimitBytesAreRejectedBeforeWriting() throws Exception {
        Pair p = pair();
        assertThrows(IOException.class, () -> p.channel.writeEncodedBytes(new byte[17], 16));
        p.client.setSoTimeout(100);
        assertThrows(SocketTimeoutException.class, () -> p.client.getInputStream().read());
    }

    @Test public void writeAfterCloseIsRejected() throws Exception {
        Pair p = pair(); p.channel.close(); p.channel.close();
        assertThrows(IOException.class, () -> p.channel.writeEncodedBytes(new byte[]{1}, 16));
        assertFalse(p.channel.isOpen()); assertEquals(-1, p.client.getInputStream().read());
    }

    @Test(timeout = 15000) public void closeDoesNotWaitForSocketWriteLock() throws Exception {
        Pair p = pair(); p.serverSide.setSendBufferSize(1024);
        CountDownLatch entered = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> task = worker.submit(() -> {
                entered.countDown();
                try { p.channel.writeEncodedBytes(new byte[1024 * 1024], 1024 * 1024); }
                catch (IOException expectedOnClose) { return null; }
                return null;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            p.channel.close(); task.get(5, TimeUnit.SECONDS);
            assertFalse(p.channel.isOpen());
        } finally {
            p.channel.close(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void outboundAdapterUsesOnlyBytesSuppliedByInjectedEncoder() throws Exception {
        Pair p = pair(); ConnectionManager manager = new ConnectionManager();
        byte[] supplied = {72, 0, -11, 44};
        TcpClientConnection connection = new TcpClientConnection(p.serverSide, "write-adapter", manager,
                configuration(() -> supplied), session -> { });
        ClientSession session = connection.getSession(); manager.register(session);
        try {
            session.send(new Object());
            assertArrayEquals(supplied, p.client.getInputStream().readNBytes(supplied.length));
            assertSame(session, manager.get(session.getSessionId()));
        } finally { session.close(); }
        assertEquals(0, manager.size()); assertFalse(session.isConnected());
    }

    @Test public void encoderLimitFailureClosesAndUnregistersOwningConnection() throws Exception {
        Pair p = pair(); ConnectionManager manager = new ConnectionManager(); AtomicInteger closed = new AtomicInteger();
        TcpClientConnection connection = new TcpClientConnection(p.serverSide, "too-large", manager,
                configuration(() -> new byte[17]), session -> closed.incrementAndGet());
        ClientSession session = connection.getSession(); manager.register(session);
        assertThrows(UncheckedIOException.class, () -> session.send(new Object()));
        assertFalse(session.isConnected()); assertEquals(0, manager.size()); assertEquals(1, closed.get());
        session.close(); assertEquals(1, closed.get());
        assertEquals(-1, p.client.getInputStream().read());
    }

    @Test public void ioConfigurationRejectsInvalidGuards() {
        MessageDispatcher dispatcher = new MessageDispatcher((session, type, payload) -> { });
        assertThrows(IllegalArgumentException.class, () -> new TcpIoConfiguration(() -> null, dispatcher, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> new TcpIoConfiguration(() -> null, dispatcher, 16, 0));
        assertThrows(NullPointerException.class, () -> new TcpIoConfiguration(null, dispatcher, 16, 100));
        assertThrows(NullPointerException.class, () -> new TcpIoConfiguration(() -> null, null, 16, 100));
    }

    private static TcpIoConfiguration configuration(java.util.function.Supplier<byte[]> bytes) {
        return new TcpIoConfiguration(() -> new TcpMessageCodec() {
            public DecodedMessage readNext(InputStream input, int limit) { throw new UnsupportedOperationException(); }
            public byte[] encode(Object message, int limit) { return bytes.get(); }
        }, new MessageDispatcher((session, type, payload) -> { }), 16, 1000);
    }

    private Pair pair() throws IOException { Pair p = new Pair(); pairs.add(p); return p; }
    private static final class Pair {
        private final Socket client;
        private final Socket serverSide;
        private final TcpSessionChannel channel;
        private Pair() throws IOException {
            try (ServerSocket listener = new ServerSocket()) {
                listener.bind(new InetSocketAddress("127.0.0.1", 0)); listener.setSoTimeout(2000);
                client = new Socket();
                client.connect(new InetSocketAddress("127.0.0.1", listener.getLocalPort()), 2000);
                client.setSoTimeout(3000); serverSide = listener.accept(); channel = new TcpSessionChannel(serverSide);
            }
        }
        private void close() throws IOException { try { serverSide.close(); } finally { client.close(); } }
    }
}
