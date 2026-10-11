package com.smashprofs.game.network.client;

import com.smashprofs.common.protocol.NetworkPacket;
import com.smashprofs.common.protocol.PacketFraming;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP client transport for Super-Smash-Profs.
 * <p>
 * THREADING & ARCHITECTURE GUARANTEES:
 * 1. Dedicated Reader and Writer threads.
 * 2. ALL ConnectionListener callbacks are invoked exclusively on the Reader Thread.
 * 3. sendPacket() is non-blocking (uses offer()) and will NEVER block the caller (e.g. Render Thread).
 * 4. onDisconnected() is guaranteed to be called at most once per connection lifetime.
 * 5. If Writer Thread encounters a socket error, it records the cause and closes the socket to unblock Reader;
 *    the Reader Thread is the sole dispatcher of onDisconnected().
 * 6. LIFECYCLE: A NetworkClient instance represents a SINGLE connection session (one-shot).
 *    To reconnect after disconnection, create a new NetworkClient instance.
 * </p>
 */
public class NetworkClient {

    private static final Logger log = LogManager.getLogger(NetworkClient.class);
    public static final int DEFAULT_QUEUE_CAPACITY = 64;
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final ConnectionListener listener;
    private final BlockingQueue<NetworkPacket> sendQueue;

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;

    private Thread readerThread;
    private Thread writerThread;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean userDisconnected = new AtomicBoolean(false);
    private final AtomicBoolean disconnectedNotified = new AtomicBoolean(false);

    private volatile String writerError = null;

    public NetworkClient(String host, int port, ConnectionListener listener) {
        this(host, port, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_QUEUE_CAPACITY, listener);
    }

    public NetworkClient(String host, int port, int connectTimeoutMs, int queueCapacity, ConnectionListener listener) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.listener = listener;
        this.sendQueue = new LinkedBlockingQueue<>(Math.max(1, queueCapacity));
    }

    /**
     * Establishes a TCP connection to the server.
     * Starts the Reader and Writer background threads.
     *
     * @throws IOException If the connection cannot be established within the timeout.
     */
    public synchronized void connect() throws IOException {
        if (connected.get()) {
            throw new IllegalStateException("NetworkClient is already connected");
        }

        userDisconnected.set(false);
        disconnectedNotified.set(false);
        writerError = null;
        sendQueue.clear();

        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        socket.setTcpNoDelay(true);

        in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

        connected.set(true);

        writerThread = new Thread(this::runWriterLoop, "NetworkClient-Writer");
        readerThread = new Thread(this::runReaderLoop, "NetworkClient-Reader");

        writerThread.start();
        readerThread.start();
    }

    /**
     * Non-blocking send operation.
     *
     * @param packet The packet to send.
     * @return true if enqueued successfully, false if queue is full or client is disconnected.
     */
    public boolean sendPacket(NetworkPacket packet) {
        if (packet == null || !isConnected()) {
            return false;
        }
        return sendQueue.offer(packet);
    }

    /**
     * Checks if the client is currently connected.
     */
    public boolean isConnected() {
        return connected.get() && !userDisconnected.get() && socket != null && !socket.isClosed();
    }

    /**
     * Actively disconnects the client. Closes sockets and wakes up background threads.
     */
    public synchronized void disconnect() {
        if (userDisconnected.compareAndSet(false, true)) {
            connected.set(false);
            closeQuietly(socket);
            if (writerThread != null) {
                writerThread.interrupt();
            }
        }
    }

    /**
     * Disconnects and waits for threads to terminate.
     *
     * @param timeoutMs Max wait time in milliseconds.
     */
    public void disconnectAndWait(long timeoutMs) {
        disconnect();
        long deadline = System.currentTimeMillis() + timeoutMs;
        joinThreadQuietly(readerThread, Math.max(1, deadline - System.currentTimeMillis()));
        joinThreadQuietly(writerThread, Math.max(1, deadline - System.currentTimeMillis()));
    }

    private void runReaderLoop() {
        String disconnectReason = "Unknown disconnect reason";

        try {
            if (listener != null) {
                listener.onConnected();
            }

            while (connected.get() && !userDisconnected.get()) {
                NetworkPacket packet = PacketFraming.readPacketOrNull(in);
                if (packet == null) {
                    disconnectReason = "Connection closed cleanly by remote peer";
                    break;
                }

                if (listener != null) {
                    try {
                        listener.onPacketReceived(packet);
                    } catch (Exception e) {
                        log.error("Exception in ConnectionListener.onPacketReceived", e);
                    }
                }
            }
        } catch (EOFException e) {
            if (userDisconnected.get()) {
                disconnectReason = "Disconnected by client";
            } else {
                disconnectReason = "Connection truncated prematurely: " + e.getMessage();
            }
        } catch (SocketException e) {
            if (userDisconnected.get()) {
                disconnectReason = "Disconnected by client";
            } else if (writerError != null) {
                disconnectReason = "Writer error: " + writerError;
            } else {
                disconnectReason = "Connection reset by peer: " + e.getMessage();
            }
        } catch (IOException e) {
            if (userDisconnected.get()) {
                disconnectReason = "Disconnected by client";
            } else if (writerError != null) {
                disconnectReason = "Writer error: " + writerError;
            } else {
                disconnectReason = "Protocol or I/O error: " + e.getMessage();
            }
        } finally {
            if (userDisconnected.get()) {
                disconnectReason = "Disconnected by client";
            }
            cleanupResources();
            notifyDisconnectedOnReaderThread(disconnectReason);
        }
    }

    private void runWriterLoop() {
        try {
            while (connected.get() && !userDisconnected.get()) {
                NetworkPacket packet = sendQueue.poll(100, TimeUnit.MILLISECONDS);
                if (packet != null) {
                    PacketFraming.writePacket(out, packet);
                }
            }
        } catch (InterruptedException e) {
            // Normal exit on shutdown
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (!userDisconnected.get()) {
                writerError = e.getMessage();
                log.warn("Writer encountered IOException, closing socket to notify Reader: {}", e.getMessage());
                closeQuietly(socket); // Unblocks Reader waiting on in.read()
            }
        }
    }

    private void cleanupResources() {
        connected.set(false);
        closeQuietly(socket);
        if (writerThread != null && Thread.currentThread() != writerThread) {
            writerThread.interrupt();
        }
    }

    private void notifyDisconnectedOnReaderThread(String reason) {
        if (disconnectedNotified.compareAndSet(false, true)) {
            if (listener != null) {
                try {
                    listener.onDisconnected(reason);
                } catch (Exception e) {
                    log.error("Exception in ConnectionListener.onDisconnected", e);
                }
            }
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void joinThreadQuietly(Thread thread, long timeoutMs) {
        if (thread != null && thread.isAlive()) {
            try {
                thread.join(timeoutMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public int getSendQueueRemainingCapacity() {
        return sendQueue.remainingCapacity();
    }

    public int getPort() {
        return port;
    }

    public String getHost() {
        return host;
    }
}
