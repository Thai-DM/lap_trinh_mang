package com.smashprofs.game.network.client;

import com.smashprofs.common.protocol.NetworkPacket;
import com.smashprofs.common.protocol.PacketFraming;
import com.smashprofs.common.protocol.JsonFrameCodec;
import com.smashprofs.common.protocol.MessageType;
import com.smashprofs.common.protocol.ProtocolMessage;
import com.smashprofs.common.protocol.ProtocolV1;
import com.smashprofs.common.model.PlayerId;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP client transport for Super-Smash-Profs.
 * Constructors use TCP Protocol v1. Legacy packets on the wire are available only through forMock().
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
    private volatile ConnectionListener listener;
    private record OutboundMessage(ProtocolMessage protocol, NetworkPacket mock) { }

    private final boolean mockProtocol;
    private final JsonFrameCodec codec = new JsonFrameCodec();
    private final BlockingQueue<OutboundMessage> sendQueue;
    // Keep automatic PONG independent of a saturated application queue. Only the Writer writes bytes.
    private final BlockingQueue<ProtocolMessage> heartbeatQueue = new LinkedBlockingQueue<>(1);
    private volatile PlayerId playerId;
    private String connectRequestId;
    private com.fasterxml.jackson.databind.node.ObjectNode connectPayload = ProtocolV1.object();
    public synchronized void enableGameplay() {
        if(connected.get() || mockProtocol) { throw new IllegalStateException("Negotiate before connecting"); }
        connectPayload.put("gameplay",com.smashprofs.common.protocol.GameplayProtocol.CAPABILITY);
    }

    /** Explicit cluster-v1 capability on a separate secured listener; normal constructors send {}. */
    public static NetworkClient forCluster(String host, int port, String ticket, ConnectionListener listener) {
        if (ticket == null || ticket.isBlank()) { throw new IllegalArgumentException("Assignment ticket required"); }
        var client = new NetworkClient(host, port, listener);
        client.connectPayload = ProtocolV1.object().put("capability", "cluster-v1").put("assignment", ticket);
        return client;
    }

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
        this(host, port, connectTimeoutMs, queueCapacity, listener, false);
    }

    private NetworkClient(String host, int port, int connectTimeoutMs, int queueCapacity,
                          ConnectionListener listener, boolean mockProtocol) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.listener = listener;
        this.mockProtocol = mockProtocol;
        this.sendQueue = new LinkedBlockingQueue<>(Math.max(1, queueCapacity));
    }

    /** Explicit opt-in to the original MockServer wire format; no v1 handshake or identity. */
    public static NetworkClient forMock(String host, int port, ConnectionListener listener) {
        return forMock(host, port, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_QUEUE_CAPACITY, listener);
    }

    public static NetworkClient forMock(String host, int port, int connectTimeoutMs,
                                        int queueCapacity, ConnectionListener listener) {
        return new NetworkClient(host, port, connectTimeoutMs, queueCapacity, listener, true);
    }

    public void setListener(ConnectionListener listener) {
        this.listener = listener;
    }

    public ConnectionListener getListener() {
        return listener;
    }

    /**
     * Establishes a TCP connection to the server.
     * Starts the Reader and Writer background threads; production CONNECT/ACK runs there.
     * This method returns after TCP connect, before the v1 handshake necessarily completes.
     * onConnected signals handshake success. TCP connect itself retains the existing blocking API.
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
        playerId = null;
        sendQueue.clear();
        heartbeatQueue.clear();

        socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setTcpNoDelay(true);
            if (!mockProtocol) {
                socket.setSoTimeout(connectTimeoutMs); // Bounded ACK wait; disabled after successful handshake.
                connectRequestId = UUID.randomUUID().toString();
                sendQueue.add(new OutboundMessage(
                        new ProtocolMessage(MessageType.CONNECT, connectRequestId, connectPayload.deepCopy()), null));
            }
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        } catch (IOException failure) {
            closeQuietly(socket);
            throw failure;
        }

        connected.set(true);

        writerThread = new Thread(this::runWriterLoop, "NetworkClient-Writer");
        readerThread = new Thread(this::runReaderLoop, "NetworkClient-Reader");

        writerThread.start();
        readerThread.start();
    }

    /**
     * Non-blocking send operation.
     * In production, adapts payloadJson to a v1 object and ignores legacy senderId/timestamp.
     * Use sendMessage when the caller needs to choose or correlate a requestId.
     *
     * @param packet The packet to send.
     * @return true if enqueued successfully, false if queue is full or client is disconnected.
     */
    public boolean sendPacket(NetworkPacket packet) {
        if (packet == null || !isConnected()) {
            return false;
        }
        if (mockProtocol) {
            return sendQueue.offer(new OutboundMessage(null, packet));
        }
        if (packet.getType() == null) {
            return false;
        }
        // Preserve the API, not the legacy envelope. Never put senderId/timestamp on the v1 wire.
        String requestId = ProtocolV1.CORRELATED_REQUESTS.contains(packet.getType())
                ? UUID.randomUUID().toString() : null;
        String payload = packet.getPayloadJson() == null ? "{}" : packet.getPayloadJson();
        String envelope = "{\"version\":1,\"type\":\"" + packet.getType().name() + "\""
                + (requestId == null ? "" : ",\"requestId\":\"" + requestId + "\"")
                + ",\"payload\":" + payload + "}";
        try {
            return sendMessage(codec.decodeJson(envelope.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException failure) {
            log.warn("Packet cannot be represented as a v1 envelope: {}", failure.getMessage());
            return false;
        }
    }

    /** Non-blocking native v1 send; requestId and payload are preserved exactly. */
    public synchronized boolean sendMessage(ProtocolMessage message) {
        return !mockProtocol && message != null && isConnected()
                && sendQueue.offer(new OutboundMessage(message, null));
    }

    /** Null until CONNECT_ACK or after disconnection. Identity is never supplied by the caller. */
    public PlayerId getPlayerId() {
        return isConnected() ? playerId : null;
    }

    /**
     * Checks TCP connectivity. A production identity is available only after CONNECT_ACK.
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
            playerId = null;
            sendQueue.clear();
            heartbeatQueue.clear();
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
            if (mockProtocol && listener != null) {
                listener.onConnected();
            }

            while (connected.get() && !userDisconnected.get()) {
                Object message = mockProtocol ? PacketFraming.readPacketOrNull(in) : codec.readNext(in);
                if (message == null) {
                    disconnectReason = "Connection closed cleanly by remote peer";
                    break;
                }

                if (!isConnected()) {
                    break;
                }
                if (!mockProtocol) {
                    handleProtocolMessage((ProtocolMessage) message);
                } else if (listener != null) {
                    try {
                        listener.onPacketReceived((NetworkPacket) message);
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
        } catch (RuntimeException e) {
            disconnectReason = "Protocol or callback error: " + e.getMessage();
        } finally {
            if (userDisconnected.get()) {
                disconnectReason = "Disconnected by client";
            }
            cleanupResources();
            notifyDisconnectedOnReaderThread(disconnectReason);
        }
    }

    private void handleProtocolMessage(ProtocolMessage message) throws IOException {
        boolean handshakeCompleted = false;
        if (message.type() == MessageType.CONNECT_ACK) {
            var payload = message.payload();
            var id = payload.get("playerId");
            if (message.requestId() == null || payload.size() != 1 || id == null || !id.isTextual() || id.textValue().isBlank()
                    || (playerId == null && !connectRequestId.equals(message.requestId()))
                    || (playerId != null && !playerId.value().equals(id.textValue()))) {
                throw new IOException("Invalid CONNECT_ACK identity or correlation");
            }
            if (playerId == null) {
                playerId = new PlayerId(id.textValue());
                socket.setSoTimeout(0); // Idle connections remain alive through server PING/PONG.
                handshakeCompleted = true;
            }
        } else if (message.type() == MessageType.PING) {
            if (playerId == null || !message.payload().isEmpty()
                    || !heartbeatQueue.offer(new ProtocolMessage(MessageType.PONG,
                            message.requestId(), ProtocolV1.object()))) {
                throw new IOException("Invalid PING or heartbeat queue full");
            }
        }

        if (listener != null) {
            try {
                if (handshakeCompleted) {
                    listener.onConnected();
                }
                listener.onProtocolMessageReceived(message);
            } catch (Exception failure) {
                log.error("Exception in ConnectionListener v1 callback", failure);
            }
        }
        if (message.type() == MessageType.ERROR && playerId == null
                && connectRequestId.equals(message.requestId())) {
            throw new IOException("CONNECT rejected: " + message.payload().path("code").asText());
        }
    }

    private void runWriterLoop() {
        try {
            while (connected.get() && !userDisconnected.get()) {
                ProtocolMessage pong = heartbeatQueue.poll();
                if (pong != null) {
                    out.write(codec.encode(pong));
                    out.flush();
                    continue;
                }
                OutboundMessage message = sendQueue.poll(100, TimeUnit.MILLISECONDS);
                if (message != null) {
                    if (mockProtocol) {
                        PacketFraming.writePacket(out, message.mock());
                    } else {
                        out.write(codec.encode(message.protocol()));
                        out.flush();
                    }
                }
            }
        } catch (InterruptedException e) {
            // Normal exit on shutdown
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            if (!userDisconnected.get()) {
                writerError = e.getMessage();
                log.warn("Writer error, closing socket to notify Reader: {}", e.getMessage());
                closeQuietly(socket); // Unblocks Reader waiting on in.read()
            }
        }
    }

    private synchronized void cleanupResources() {
        connected.set(false);
        playerId = null;
        sendQueue.clear();
        heartbeatQueue.clear();
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

    /** Allows the existing mock gameplay adapter to stay separate from production v1. */
    public boolean isMockProtocol() { return mockProtocol; }
}
