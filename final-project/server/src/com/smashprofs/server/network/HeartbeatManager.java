package com.smashprofs.server.network;

import com.smashprofs.common.protocol.*;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/** Một maintenance loop/lifecycle, dùng registry hiện có; không socket write, room hoặc JNI. */
public final class HeartbeatManager implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(HeartbeatManager.class.getName());
    private final ConnectionManager connections;
    private final HeartbeatConfiguration configuration;
    private final LongSupplier clock;
    private final ScheduledExecutorService executor;
    private volatile boolean closed;
    private volatile Thread maintenanceThread;
    private ScheduledFuture<?> task;
    private final long writeTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(
            Math.max(1, Long.getLong("smashprofs.network.writeTimeoutMillis", 5000L)));

    /** State thuộc session, không có map connection thứ hai. Truy cập dưới khóa session. */
    static final class State {
        boolean active;
        long lastValidNanos, pingStartedNanos, sequence;
        long writeStartedNanos;
        boolean pingWritten;
        volatile long rttNanos=-1;
        final String nonce = UUID.randomUUID().toString();
        String outstanding;
        void clear() { active = false; outstanding = null;rttNanos=-1;pingWritten=false; }
    }
    public HeartbeatManager(ConnectionManager connections, HeartbeatConfiguration configuration,
                            LongSupplier clock, ScheduledExecutorService executor) {
        this.connections = Objects.requireNonNull(connections); this.configuration = Objects.requireNonNull(configuration);
        this.clock = Objects.requireNonNull(clock); this.executor = Objects.requireNonNull(executor);
    }
    static ScheduledExecutorService newExecutor() {
        return Executors.newSingleThreadScheduledExecutor(command -> new Thread(command, "smashprofs-heartbeat"));
    }
    public synchronized void start() {
        if (closed) { throw new IllegalStateException("Heartbeat đã đóng"); }
        if (task != null) { return; }
        task = executor.scheduleAtFixedRate(this::runMaintenance, configuration.intervalNanos(),
                configuration.intervalNanos(), TimeUnit.NANOSECONDS);
    }
    /** Chỉ gọi sau CONNECT_ACK gửi thành công; CONNECT lặp không reset deadline. */
    void connected(ClientSession session) {
        synchronized (session) {
            if (!valid(session) || session.getPlayerId() == null || session.heartbeat.active) { return; }
            session.heartbeat.lastValidNanos = clock.getAsLong(); session.heartbeat.active = true;
        }
    }
    boolean acknowledge(ClientSession session, String requestId) {
        synchronized (session) {
            var state = session.heartbeat;
            if (!valid(session) || !state.active || requestId == null || !requestId.equals(state.outstanding)) { return false; }
            long now = clock.getAsLong();
            if (now - state.pingStartedNanos >= configuration.timeoutNanos()) { return false; }
            if(state.pingWritten) { state.rttNanos=Math.max(0,now-state.writeStartedNanos); }
            state.lastValidNanos = now; state.outstanding = null; return true;
        }
    }
    private boolean valid(ClientSession session) {
        return !closed && session.isOpen() && connections.get(session.getSessionId()) == session;
    }
    /** Seam cho clock/executor deterministic; production chỉ scheduler maintenance gọi. */
    public void runMaintenance() {
        maintenanceThread = Thread.currentThread();
        try {
            if (closed) { return; }
            for (var session : connections.getSessions()) {
                try { maintain(session); }
                catch (RuntimeException failure) { LOGGER.warning("Cô lập lỗi heartbeat của một session"); }
            }
        } finally { maintenanceThread = null; }
    }
    private void maintain(ClientSession session) {
        long writeStarted = session.getWriteStartedNanos();
        if (writeStarted != 0 && System.nanoTime() - writeStarted >= writeTimeoutNanos) {
            session.close();
            return;
        }
        HeartbeatDelivery delivery = null; boolean expired = false;
        synchronized (session) {
            var state = session.heartbeat;
            if (!valid(session)) { state.clear(); return; }
            if (!state.active) { return; }
            long now = clock.getAsLong();
            if (state.outstanding != null) {
                if (now - state.pingStartedNanos >= configuration.timeoutNanos()) {
                    // Đánh dấu trước khi nhả khóa: PONG/routing không thể sống lại session đã hết hạn.
                    session.markDisconnected(); expired = true;
                }
            } else if (now - state.lastValidNanos >= configuration.intervalNanos()) {
                if (state.sequence == Long.MAX_VALUE) { session.markDisconnected(); expired = true; }
                else {
                    String id = state.nonce + "-" + state.sequence++;
                    state.outstanding = id; state.pingStartedNanos = now;
                    state.pingWritten=false;
                    delivery = new HeartbeatDelivery(new ProtocolMessage(MessageType.PING, id, ProtocolV1.object()), () -> {
                        synchronized (session) { return valid(session) && id.equals(session.heartbeat.outstanding); }
                    },()-> { synchronized(session) { if(valid(session) && id.equals(session.heartbeat.outstanding)) { session.heartbeat.writeStartedNanos=clock.getAsLong();session.heartbeat.pingWritten=true; } } });
                }
            }
        }
        // Offer/close ngoài khóa metadata. Không chờ sendLock, không encode hoặc Socket.write.
        if (expired) { session.close(); }
        else if (delivery != null && !session.offerHeartbeat(delivery)) { session.close(); }
    }
    @Override public void close() {
        ScheduledFuture<?> scheduled;
        synchronized (this) { closed = true; scheduled = task; }
        if (scheduled != null) { scheduled.cancel(false); }
        executor.shutdown();
        if (Thread.currentThread() != maintenanceThread) {
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow(); throw new IllegalStateException("Heartbeat worker chưa kết thúc");
                }
            } catch (InterruptedException failure) {
                executor.shutdownNow(); Thread.currentThread().interrupt(); throw new IllegalStateException(failure);
            }
        }
        for (var session : connections.getSessions()) { synchronized (session) { session.heartbeat.clear(); } }
    }
}
