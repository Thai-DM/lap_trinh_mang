package com.smashprofs.server.network;

import com.smashprofs.common.protocol.ProtocolException;
import com.smashprofs.common.protocol.ProtocolV1;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Một reader tuần tự/session, không Socket trong gameplay và không registry connection thứ hai. */
final class TcpClientConnection implements SessionChannel {
    private static final Logger LOGGER = Logger.getLogger(TcpClientConnection.class.getName());
    private final TcpSessionChannel channel;
    private final InputStream input;
    private final TcpIoConfiguration configuration;
    private final TcpMessageCodec readerCodec;
    private final TcpMessageCodec encoderCodec;
    private final ClientSession session;
    private final ConnectionManager connections;
    private final Consumer<ClientSession> onClosed;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    // Fair lock: control writer đang chờ không bị snapshot worker chiếm lại liên tục.
    private final ReentrantLock sendLock = new ReentrantLock(true);
    private final Object outboundLock = new Object();
    private GameStateDelivery pendingSnapshot;
    private HeartbeatDelivery pendingHeartbeat;
    private GameStartDelivery pendingStart;
    private String lastAcceptedStartMatch;
    private static final int MAX_PENDING_ENDS = 4;
    private final java.util.ArrayDeque<GameEndDelivery> pendingEnds = new java.util.ArrayDeque<>();
    private String lastAcceptedEndMatch;
    private long lastOfferedTick = -1;
    private String lastOfferedMatch;
    private final AtomicBoolean writerStarted = new AtomicBoolean();
    private volatile Future<?> writeTask;
    private volatile Future<?> readTask;
    private volatile long writeStarted, lastSendDuration;
    private final TokenBucket inboundBytes=new TokenBucket(Integer.getInteger("smashprofs.network.inboundBytesPerSecond",2_097_152),4_194_304);
    private final TokenBucket invalidFrameRate = new TokenBucket(8, 32);
    @Override public long writeStartedNanos() { return writeStarted; }
    @Override public long lastSendNanos() { return lastSendDuration; }
    @Override public int outboundQueueDepth() {
        synchronized (outboundLock) {
            return (pendingSnapshot == null ? 0 : 1) + (pendingStart == null ? 0 : 1)
                    + (pendingHeartbeat == null ? 0 : 1) + pendingEnds.size();
        }
    }

    TcpClientConnection(Socket socket, String sessionId, ConnectionManager connections,
                        TcpIoConfiguration configuration, Consumer<ClientSession> onClosed) throws IOException {
        this.connections = Objects.requireNonNull(connections);
        this.configuration = configuration;
        this.onClosed = Objects.requireNonNull(onClosed);
        channel = new TcpSessionChannel(socket);
        socket.setSoTimeout(configuration == null ? 1000 : Math.min(1000, configuration.incompleteMessageTimeoutMillis()));
        input = socket.getInputStream();
        if (configuration == null) { readerCodec = null; encoderCodec = null; }
        else {
            readerCodec = Objects.requireNonNull(configuration.codecFactory().get(), "Reader codec factory trả null");
            encoderCodec = Objects.requireNonNull(configuration.codecFactory().get(), "Encoder codec factory trả null");
            if (readerCodec == encoderCodec) {
                throw new IllegalArgumentException("Factory phải tạo codec riêng cho reader và encoder");
            }
        }
        session = new ClientSession(sessionId, this, connections::removeIfSame);
    }

    ClientSession getSession() { return session; }

    void startWriting(ExecutorService executor) {
        if (configuration == null || closed.get() || !writerStarted.compareAndSet(false, true)) { return; }
        try {
            Future<?> task = executor.submit(this::writeLoop);
            writeTask = task;
            if (closed.get()) { task.cancel(false); }
        } catch (RuntimeException failure) { close(); throw failure; }
    }

    /** Một control heartbeat pending; queue đầy đóng riêng connection, không block maintenance. */
    @Override public boolean offerHeartbeat(HeartbeatDelivery delivery) {
        Objects.requireNonNull(delivery);
        synchronized (outboundLock) {
            if (!isOpen() || !writerStarted.get()) { return false; }
            if (pendingHeartbeat == null) {
                pendingHeartbeat = delivery; outboundLock.notifyAll(); return true;
            }
        }
        close(); return false;
    }

    /** Queue đúng một snapshot chưa ghi; không encode, chờ sendLock hay gọi Socket.write. */
    @Override public boolean offerGameState(GameStateDelivery delivery) {
        Objects.requireNonNull(delivery);
        synchronized (outboundLock) {
            if (!isOpen() || !writerStarted.get()) { return false; }
            var snapshot = delivery.snapshot();
            if (snapshot.matchId().equals(lastAcceptedEndMatch)) { return false; }
            if (snapshot.matchId().equals(lastOfferedMatch) && snapshot.serverTick() <= lastOfferedTick) { return false; }
            if(pendingSnapshot!=null) { connections.performance.snapshotReplacements.increment(); }
            pendingSnapshot = delivery;
            lastOfferedMatch = snapshot.matchId(); lastOfferedTick = snapshot.serverTick();
            outboundLock.notifyAll(); return true;
        }
    }

    @Override public void discardInvalidGameState() {
        GameStartDelivery start;
        synchronized (outboundLock) { start = pendingStart; }
        if (start != null && !start.stillValid().getAsBoolean()) {
            synchronized (outboundLock) { if (pendingStart == start) { pendingStart = null; } }
        }
        GameStateDelivery observed;
        synchronized (outboundLock) { observed = pendingSnapshot; }
        // Guard có thể lấy khóa metadata room: không gọi guard dưới khóa queue.
        if (observed != null && !observed.stillValid().getAsBoolean()) {
            synchronized (outboundLock) { if (pendingSnapshot == observed) { pendingSnapshot = null; } }
        }
        java.util.List<GameEndDelivery> ends;
        synchronized (outboundLock) { ends = java.util.List.copyOf(pendingEnds); }
        for (var end : ends) {
            if (!end.finalState().stillValid().getAsBoolean()) {
                synchronized (outboundLock) { pendingEnds.remove(end); }
            }
        }
    }

    /** Một start pending/session; không latest-wins, không encode/chờ send lock hoặc ghi socket. */
    @Override public boolean offerGameStart(GameStartDelivery delivery) {
        Objects.requireNonNull(delivery);
        synchronized (outboundLock) {
            if (!isOpen() || !writerStarted.get() || delivery.setup().matchId().equals(lastAcceptedStartMatch)) { return false; }
            if (pendingStart == null) {
                pendingStart = delivery; lastAcceptedStartMatch = delivery.setup().matchId();
                outboundLock.notifyAll(); return true;
            }
        }
        LOGGER.warning("Đóng connection vì queue start đầy"); close(); return false;
    }

    /** Terminal không đi qua latest-wins: thay snapshot cũ bằng cặp cuối-state/end có thứ tự. */
    @Override public boolean offerGameEnd(GameEndDelivery delivery) {
        Objects.requireNonNull(delivery);
        synchronized (outboundLock) {
            if (!isOpen() || !writerStarted.get() || delivery.result().matchId().equals(lastAcceptedEndMatch)) { return false; }
            // Lifecycle hiện tại chỉ có một trận/session. Không tạo backlog vô hạn cho extension tương lai.
            if (pendingEnds.size() < MAX_PENDING_ENDS) {
                if (pendingSnapshot != null && pendingSnapshot.snapshot().matchId().equals(delivery.result().matchId())) {
                    pendingSnapshot = null;
                }
                pendingEnds.addLast(delivery);
                lastAcceptedEndMatch = delivery.result().matchId();
                outboundLock.notifyAll(); return true;
            }
        }
        // Extension vi phạm lifecycle một trận/session: fail riêng connection, không drop âm thầm.
        LOGGER.warning("Đóng connection vì queue terminal đầy");
        close(); return false;
    }

    private void writeLoop() {
        try {
            while (isOpen()) {
                GameStateDelivery delivery;
                GameEndDelivery end;
                GameStartDelivery start;
                HeartbeatDelivery heartbeat;
                synchronized (outboundLock) {
                    while (pendingStart == null && pendingSnapshot == null && pendingEnds.isEmpty() && pendingHeartbeat == null && isOpen()) { outboundLock.wait(); }
                    if (!isOpen()) { return; }
                    start = pendingStart; pendingStart = null;
                    end = start == null ? pendingEnds.pollFirst() : null;
                    heartbeat = start == null && end == null ? pendingHeartbeat : null;
                    if (heartbeat != null) { pendingHeartbeat = null; }
                    delivery = start == null && end == null && heartbeat == null ? pendingSnapshot : null;
                    if (delivery != null) { pendingSnapshot = null; }
                }
                sendLock.lock();
                try {
                    // Nếu chưa bắt đầu encode/write và có bản mới cùng match, dùng ngay latest.
                    synchronized (outboundLock) {
                        if (start == null && pendingStart != null) {
                            start = pendingStart; pendingStart = null;
                            if (delivery != null && pendingSnapshot == null
                                    && !delivery.snapshot().matchId().equals(lastAcceptedEndMatch)) { pendingSnapshot = delivery; }
                            delivery = null;
                        }
                        if (start == null && end == null && !pendingEnds.isEmpty()) { end = pendingEnds.pollFirst(); }
                        if ((start != null || end != null) && heartbeat != null) {
                            // Không làm rơi control khi START/END tới sau claim. Một outstanding/session.
                            if (pendingHeartbeat == null) { pendingHeartbeat = heartbeat; }
                            heartbeat = null;
                        }
                        if (start == null && end == null && pendingSnapshot != null && delivery != null && pendingSnapshot.snapshot().matchId().equals(delivery.snapshot().matchId())) {
                            delivery = pendingSnapshot; pendingSnapshot = null;
                        }
                    }
                    if (start != null) {
                        if (isOpen() && start.stillValid().getAsBoolean()) { send(GameStartMessageMapper.map(start)); }
                    }
                    if (end != null) {
                        if (isOpen() && end.finalState().stillValid().getAsBoolean()) {
                            // Giữ cùng sendLock cho hai frame; không control writer nào chen giữa chúng.
                            sendSnapshot(end.finalState());
                            send(GameEndMessageMapper.map(end.result()));
                        }
                    } else if (start == null && heartbeat != null && isOpen() && heartbeat.stillValid().getAsBoolean()) {
                        send(heartbeat.message());
                    } else if (start == null && delivery != null && isOpen() && delivery.stillValid().getAsBoolean()) {
                        sendSnapshot(delivery);
                    }
                } finally { sendLock.unlock(); }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            if (isOpen()) { LOGGER.warning("Đóng connection do lỗi outbound GAME_STATE/GAME_END"); }
        } finally { close(); }
    }

    int pendingSnapshotCount() { synchronized (outboundLock) { return pendingSnapshot == null ? 0 : 1; } }
    int pendingGameEndCount() { synchronized (outboundLock) { return pendingEnds.size(); } }
    int pendingGameStartCount() { synchronized (outboundLock) { return pendingStart == null ? 0 : 1; } }
    int pendingHeartbeatCount() { synchronized (outboundLock) { return pendingHeartbeat == null ? 0 : 1; } }

    void startReading(ExecutorService executor) {
        if (closed.get() || !started.compareAndSet(false, true)) { return; }
        try {
            Future<?> task = executor.submit(this::readLoop);
            readTask = task;
            if (closed.get()) { task.cancel(false); } // Socket close unblock read, không interrupt shutdown caller.
        } catch (RuntimeException failure) { close(); throw failure; }
    }

    private void readLoop() {
        try {
            MessageInput guarded = new MessageInput(input);
            while (isOpen()) {
                guarded.beginMessage();
                if (readerCodec == null) {
                    if (guarded.read() == -1) { break; }
                    // Fail closed: không discard application bytes rồi giả báo đã xử lý message.
                    throw new IOException("Chưa có wire contract/codec; không chấp nhận application bytes");
                }
                final TcpMessageCodec.DecodedMessage message;
                try { message = readerCodec.readNext(guarded, configuration.maxMessageBytes()); }
                catch (ProtocolException invalidApplication) {
                    connections.errors.increment();
                    if (!invalidFrameRate.allow(1)) {
                        connections.performance.rateLimitRejections.increment();
                        throw new IOException("Invalid frame rate exceeded");
                    }
                    // Frame đã được tiêu thụ hết: gửi lỗi an toàn rồi tiếp tục, không mất framing.
                    send(ProtocolV1.error(invalidApplication.requestId(), invalidApplication.code(),
                            invalidApplication.getMessage()));
                    continue;
                }
                if (message == null) { break; }
                connections.receivedMessages.increment();
                if (isOpen() && connections.get(session.getSessionId()) == session) {
                    if (!configuration.dispatcher().dispatch(session, message.type(), message.payload())) { connections.errors.increment(); }
                }
            }
        } catch (IOException | RuntimeException failure) {
            if (isOpen()) {
                connections.errors.increment();
                // Không log payload hoặc message exception do decoder có thể chứa dữ liệu client không giới hạn.
                LOGGER.log(Level.WARNING, "Đóng TCP session {0}; lỗi read/decode/dispatch: {1}",
                        new Object[]{session.getSessionId(), failure.getClass().getSimpleName()});
            }
        } finally { close(); }
    }

    private void sendSnapshot(GameStateDelivery delivery) {
        if(connections.shareSnapshots && encoderCodec instanceof JsonTcpMessageCodec) {
            try {
                var frame=connections.snapshotFrames.frame(delivery).encode(encoderCodec,connections.performance);
                writeStarted=System.nanoTime();
                try {
                    channel.writeSharedFrame(frame,configuration.maxMessageBytes());
                    connections.sentBytes.add(frame.size()); connections.snapshotBytes.add(frame.size()); connections.sentMessages.increment();
                } finally {
                    lastSendDuration=System.nanoTime()-writeStarted; writeStarted=0;
                    connections.performance.record(com.smashprofs.server.metrics.PerformanceMetrics.Stage.NETWORK_WRITE,lastSendDuration);
                }
            } catch(IOException failure) { connections.errors.increment(); close(); throw new UncheckedIOException(failure); }
            return;
        }
        long started=System.nanoTime(); var message=GameStateMessageMapper.map(delivery);
        sendInternal(message,System.nanoTime()-started);
    }
    @Override public void send(Object message) { sendInternal(message,0); }
    private void sendInternal(Object message,long mappingNanos) {
        if (!isOpen()) { throw new IllegalStateException("Connection đã đóng"); }
        if (encoderCodec == null) { throw new UnsupportedOperationException("Chưa có TCP wire encoder/framing được duyệt"); }
        // Encode riêng từng direction; giữ cùng khóa encode+write để giữ thứ tự cùng connection.
        long lockStarted=System.nanoTime(); sendLock.lock();
        connections.performance.record(com.smashprofs.server.metrics.PerformanceMetrics.Stage.SEND_LOCK_WAIT,System.nanoTime()-lockStarted);
        try {
            if (!isOpen()) { throw new IllegalStateException("Connection đã đóng"); }
            try {
                long serializationStarted=System.nanoTime();
                byte[] encoded = Objects.requireNonNull(encoderCodec.encode(message, configuration.maxMessageBytes()));
                connections.performance.record(com.smashprofs.server.metrics.PerformanceMetrics.Stage.JSON_SERIALIZATION,System.nanoTime()-serializationStarted+mappingNanos);
                if(message instanceof com.smashprofs.common.protocol.ProtocolMessage wire && wire.type()==com.smashprofs.common.protocol.MessageType.GAME_STATE) { connections.performance.snapshotEncodes.increment(); }
                if (encoded.length > configuration.maxMessageBytes()) { throw new IOException("Encoded message vượt byte limit"); }
                writeStarted = System.nanoTime();
                try {
                    channel.writeEncodedBytes(encoded, configuration.maxMessageBytes());
                    connections.sentBytes.add(encoded.length); connections.sentMessages.increment();
                    if (message instanceof com.smashprofs.common.protocol.ProtocolMessage wire
                            && wire.type() == com.smashprofs.common.protocol.MessageType.GAME_STATE) {
                        connections.snapshotBytes.add(encoded.length);
                    }
                } finally {
                    lastSendDuration = System.nanoTime() - writeStarted; writeStarted = 0;
                    connections.performance.record(com.smashprofs.server.metrics.PerformanceMetrics.Stage.NETWORK_WRITE,lastSendDuration);
                }
            } catch (IOException failure) {
                if (!closed.get()) {
                    connections.errors.increment();
                    LOGGER.log(Level.WARNING, "TCP send thất bại tại session {0}; loại lỗi {1}",
                            new Object[]{session.getSessionId(), failure.getClass().getSimpleName()});
                }
                close(); throw new UncheckedIOException("TCP send thất bại; connection đã đóng", failure);
            } catch (RuntimeException failure) {
                if (!closed.get()) { connections.errors.increment(); LOGGER.warning("TCP encoder thất bại; đóng connection"); }
                close(); throw failure;
            }
        } finally { sendLock.unlock(); }
    }

    @Override public boolean isOpen() { return !closed.get() && channel.isOpen(); }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) { return; }
        synchronized (outboundLock) { pendingHeartbeat = null; pendingStart = null; lastAcceptedStartMatch = null; pendingSnapshot = null; pendingEnds.clear(); lastAcceptedEndMatch = null; outboundLock.notifyAll(); }
        session.markDisconnected();
        try { channel.close(); }
        finally {
            Future<?> task = readTask;
            if (task != null) { task.cancel(false); }
            Future<?> outbound = writeTask;
            if (outbound != null) { outbound.cancel(false); }
            if (connections.removeIfSame(session)) { onClosed.accept(session); }
        }
    }

    /** Không buffer/parse wire; cap số byte tiêu thụ mỗi decode call, deadline bắt đầu từ byte đầu tiên. */
    private final class MessageInput extends InputStream {
        private final InputStream source;
        private int consumed;
        private boolean partial;
        private long firstByteNanos;

        private MessageInput(InputStream source) { this.source = source; }
        private void beginMessage() { consumed = 0; partial = false; }
        private void checkBudget() throws IOException {
            if (configuration != null && consumed >= configuration.maxMessageBytes()) {
                throw new IOException("Decode call vượt byte limit");
            }
            if (configuration != null && partial && System.nanoTime() - firstByteNanos
                    >= TimeUnit.MILLISECONDS.toNanos(configuration.incompleteMessageTimeoutMillis())) {
                throw new IOException("Incomplete message vượt deadline");
            }
        }
        private void account(int count) throws IOException {
            if (count > 0) {
                if(!inboundBytes.allow(count)) { connections.performance.rateLimitRejections.increment(); throw new IOException("Inbound byte rate exceeded"); }
                if (!partial) { partial = true; firstByteNanos = System.nanoTime(); }
                consumed += count;
                connections.receivedBytes.add(count);
            }
        }
        @Override public int read() throws IOException {
            while (isOpen()) {
                checkBudget();
                try { int value = source.read(); if (value != -1) { account(1); } return value; }
                catch (SocketTimeoutException idle) { checkBudget(); } // Idle không phải disconnect.
            }
            return -1;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) { return 0; }
            while (isOpen()) {
                checkBudget();
                int allowed = configuration == null ? length : Math.min(length, configuration.maxMessageBytes() - consumed);
                try { int count = source.read(bytes, offset, allowed); account(count); return count; }
                catch (SocketTimeoutException idle) { checkBudget(); }
            }
            return -1;
        }
    }
}
