package com.smashprofs.server.network;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Accept riêng và read workers có giới hạn; không chọn wire format hoặc chạy physics. */
public final class TcpServerTransport implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(TcpServerTransport.class.getName());
    private final Object lifecycleLock = new Object();
    private final TokenBucket acceptRate=new TokenBucket(Integer.getInteger("smashprofs.network.acceptsPerSecond",128),256);
    private final String bindHost;
    private final int bindPort;
    private final int maxConnections;
    private final ConnectionManager connections;
    private final ThreadFactory threadFactory;
    private final Consumer<ClientSession> onAccepted;
    private final Consumer<ClientSession> onClosed;
    private final TcpIoConfiguration ioConfiguration;
    private final ThreadLocal<Boolean> ioWorker = ThreadLocal.withInitial(() -> false);
    private volatile ThreadPoolExecutor readers;
    private volatile ThreadPoolExecutor writers;
    private volatile ServerSocket listener;
    private volatile Thread acceptThread;
    private volatile boolean accepting;
    private volatile IOException acceptFailure;
    private boolean closed;
    private HeartbeatManager heartbeat;
    private com.smashprofs.server.room.RoomManager lobbyRooms;

    /** Cấu hình trước bind; một controller/lifecycle và registry connection hiện có. */
    void enableHeartbeat(HeartbeatConfiguration configuration, java.util.function.LongSupplier clock,
                         java.util.function.Supplier<java.util.concurrent.ScheduledExecutorService> executorFactory,
                         com.smashprofs.server.room.RoomManager rooms) {
        if (ioConfiguration == null) { return; }
        var candidate = new HeartbeatManager(connections, configuration, clock, executorFactory.get());
        if (!ioConfiguration.dispatcher().enableHeartbeat(candidate)) { candidate.close(); return; }
        heartbeat = candidate; lobbyRooms = rooms;
    }

    private void connectionClosed(ClientSession session) {
        // Dùng leavePlayer hiện có: lobby xóa selection/ready/slot và hủy countdown.
        // PLAYING/FINISHED từ chối leave: không sửa roster/HP/result trong trận.
        if (lobbyRooms != null && session.getPlayerId() != null && session.getRoomId() != null) {
            synchronized (session) {
                var room = lobbyRooms.getRoom(session.getRoomId());
                if (room != null) {
                    synchronized(room) {
                        if(room.leavePlayer(session.getPlayerId())) {
                            session.leaveRoom();
                            if(room.isMatchmaking() && room.getPlayerCount()==0) { lobbyRooms.removeRoom(room.getRoomId()); }
                        }
                    }
                }
            }
            var room=session.getRoomId()==null?null:lobbyRooms.getRoom(session.getRoomId());
            if(room!=null && room.getRules().gameplay() && room.getPhase()==com.smashprofs.server.room.RoomPhase.FINISHED
                    && connections.getSessions().stream().noneMatch(s->s.isOpen() && room.getRoomId().equals(s.getRoomId()))) { lobbyRooms.removeRoom(room.getRoomId()); }
        }
        onClosed.accept(session);
    }

    public TcpServerTransport(String bindHost, int bindPort, int maxConnections, ConnectionManager connections) {
        this(bindHost, bindPort, maxConnections, connections,
                command -> new Thread(command, "smashprofs-tcp-accept"), session -> { });
    }

    /** Codec phải đến từ contract được duyệt; không có implementation hoặc wire default trong transport. */
    public TcpServerTransport(String bindHost, int bindPort, int maxConnections, ConnectionManager connections,
                              TcpIoConfiguration configuration) {
        this(bindHost, bindPort, maxConnections, connections,
                command -> new Thread(command, "smashprofs-tcp-accept"), session -> { }, configuration, session -> { });
    }

    /** Seams nội bộ để kiểm thử thread-start failure và chờ registration bằng latch có timeout. */
    TcpServerTransport(String bindHost, int bindPort, int maxConnections, ConnectionManager connections,
                       ThreadFactory threadFactory, Consumer<ClientSession> onAccepted) {
        this(bindHost, bindPort, maxConnections, connections, threadFactory, onAccepted, null, session -> { });
    }

    TcpServerTransport(String bindHost, int bindPort, int maxConnections, ConnectionManager connections,
                       ThreadFactory threadFactory, Consumer<ClientSession> onAccepted,
                       TcpIoConfiguration configuration, Consumer<ClientSession> onClosed) {
        this.bindHost = Objects.requireNonNull(bindHost, "bindHost không được null");
        if (bindHost.isBlank() || !bindHost.equals(bindHost.strip())) {
            throw new IllegalArgumentException("bindHost không được blank hoặc có khoảng trắng ở hai đầu");
        }
        if (bindPort < 0 || bindPort > 65535) { throw new IllegalArgumentException("bindPort phải từ 0 đến 65535"); }
        if (maxConnections <= 0) { throw new IllegalArgumentException("maxConnections phải lớn hơn 0"); }
        this.bindPort = bindPort;
        this.maxConnections = maxConnections;
        this.connections = Objects.requireNonNull(connections, "connections không được null");
        this.threadFactory = Objects.requireNonNull(threadFactory, "threadFactory không được null");
        this.onAccepted = Objects.requireNonNull(onAccepted, "onAccepted không được null");
        this.onClosed = Objects.requireNonNull(onClosed, "onClosed không được null");
        this.ioConfiguration = configuration;
    }

    /** Bind trước khi server start scheduler; lỗi bind không công bố running. */
    public void bind() {
        synchronized (lifecycleLock) {
            if (closed) { throw new IllegalStateException("Transport đã đóng, cần lifecycle mới"); }
            if (listener != null) { return; }
            ServerSocket candidate = null;
            try {
                candidate = new ServerSocket();
                candidate.bind(new InetSocketAddress(bindHost, bindPort));
                listener = candidate;
            } catch (IOException | RuntimeException failure) {
                if (candidate != null) {
                    try { candidate.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                }
                if (failure instanceof IOException io) {
                    throw new UncheckedIOException("Không bind được TCP " + bindHost + ":" + bindPort, io);
                }
                throw (RuntimeException) failure;
            }
        }
    }

    /** Listener có một accept thread; reader dùng pool bị giới hạn bởi maxConnections. */
    public void startAccepting() {
        synchronized (lifecycleLock) {
            if (closed || listener == null) { throw new IllegalStateException("Transport chưa bind hoặc đã đóng"); }
            if (accepting) { return; }
            if (acceptThread != null) { throw new IllegalStateException("Accept loop đã kết thúc, cần lifecycle mới"); }
            // Không queue read tasks: tối đa maxConnections workers, quá capacity thì reject/close socket mới.
            readers = new ThreadPoolExecutor(0, maxConnections, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), command ->
                    new Thread(() -> {
                        ioWorker.set(true);
                        try { command.run(); } finally { ioWorker.remove(); }
                    }, "smashprofs-tcp-read"));
            // Reader có thể block trên socket: outbound dùng pool riêng, bounded theo connection.
            writers = new ThreadPoolExecutor(0, maxConnections, 30, TimeUnit.SECONDS,
                    new SynchronousQueue<>(), command -> new Thread(() -> {
                        ioWorker.set(true);
                        try { command.run(); } finally { ioWorker.remove(); }
                    }, "smashprofs-tcp-write"));
            acceptThread = Objects.requireNonNull(threadFactory.newThread(this::acceptLoop), "ThreadFactory trả null");
            accepting = true;
            try { acceptThread.start(); if (heartbeat != null) { heartbeat.start(); } }
            catch (RuntimeException | Error failure) { accepting = false; throw failure; }
        }
    }

    private void acceptLoop() {
        try {
            while (accepting) {
                Socket socket;
                try { socket = listener.accept(); }
                catch (IOException failure) {
                    if (accepting) {
                        acceptFailure = failure;
                        LOGGER.log(Level.SEVERE, "TCP accept thất bại; dừng listener, cần stop/start server", failure);
                    }
                    break; // Không retry bận hoặc che giấu lỗi I/O bất thường.
                }
                registerAccepted(socket);
            }
        } finally {
            accepting = false;
            try { closeListener(); }
            finally {
                try { connections.closeAll(); }
                finally {
                    if (readers != null) { readers.shutdown(); }
                    if (writers != null) { writers.shutdown(); }
                }
            }
        }
    }

    private void registerAccepted(Socket socket) {
        ClientSession session = null;
        TcpClientConnection connection = null;
        try {
            if(!acceptRate.allow(1)) { connections.performance.rateLimitRejections.increment(); socket.close(); return; }
            synchronized (lifecycleLock) {
                if (!accepting || closed || connections.size() >= maxConnections) {
                    socket.close();
                    return;
                }
                String sessionId = UUID.randomUUID().toString();
                connection = new TcpClientConnection(socket, sessionId, connections, ioConfiguration, this::connectionClosed);
                session = connection.getSession();
                connections.register(session);
            }
            // Callback quan sát test ngoài lock; production no-op, không dispatch message hoặc join room.
            onAccepted.accept(session);
            connection.startWriting(writers);
            connection.startReading(readers);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                if (session != null) { session.close(); }
                else { socket.close(); }
            } catch (IOException | RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            LOGGER.log(Level.WARNING, "Không đăng ký được TCP connection; đã cleanup socket", failure);
            if (failure instanceof Error fatal) { throw fatal; }
        }
    }

    public int getBoundPort() {
        ServerSocket current = listener;
        return current == null || current.isClosed() ? -1 : current.getLocalPort();
    }

    public boolean isAccepting() { return accepting; }
    public IOException getAcceptFailure() { return acceptFailure; }

    private void closeListener() {
        ServerSocket current = listener;
        if (current != null) {
            try { current.close(); }
            catch (IOException failure) { throw new UncheckedIOException("Không đóng được TCP listener", failure); }
        }
    }

    /** Close listener để unblock accept, join tối đa 5 giây, không tự join accept thread. */
    @Override public void close() {
        synchronized (lifecycleLock) { closed = true; accepting = false; }
        RuntimeException closeFailure = null;
        if (heartbeat != null) {
            try { heartbeat.close(); } catch (RuntimeException failure) { closeFailure = failure; }
        }
        try { closeListener(); } catch (RuntimeException failure) {
            if (closeFailure == null) { closeFailure = failure; } else { closeFailure.addSuppressed(failure); }
        }
        Thread worker = acceptThread;
        try {
            if (worker != null && worker != Thread.currentThread()) {
                worker.join(5000);
                if (worker.isAlive()) { throw new IllegalStateException("Accept thread chưa kết thúc sau 5 giây"); }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            RuntimeException failure = new IllegalStateException("Bị interrupt khi chờ accept thread", interrupted);
            if (closeFailure == null) { closeFailure = failure; } else { closeFailure.addSuppressed(failure); }
        } catch (RuntimeException failure) {
            if (closeFailure == null) { closeFailure = failure; } else { closeFailure.addSuppressed(failure); }
        }
        try { connections.closeAll(); }
        catch (RuntimeException failure) {
            if (closeFailure == null) { closeFailure = failure; } else { closeFailure.addSuppressed(failure); }
        }
        ThreadPoolExecutor pool = readers;
        if (pool != null) {
            pool.shutdown(); // Socket đã close: không interrupt worker đang thực hiện graceful server.stop().
            // Stop từ I/O callback không được đợi chính worker; sockets đã close nên các reader khác unblock.
            if (!ioWorker.get()) {
                try {
                    if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                        pool.shutdownNow();
                        throw new IllegalStateException("TCP read workers chưa kết thúc sau 5 giây");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    RuntimeException failure = new IllegalStateException("Interrupt khi chờ read workers", interrupted);
                    if (closeFailure == null) { closeFailure = failure; } else { closeFailure.addSuppressed(failure); }
                } catch (RuntimeException failure) {
                    if (closeFailure == null) { closeFailure = failure; } else { closeFailure.addSuppressed(failure); }
                }
            }
        }
        ThreadPoolExecutor outbound = writers;
        if (outbound != null) {
            outbound.shutdown();
            if (!ioWorker.get()) {
                try {
                    if (!outbound.awaitTermination(5, TimeUnit.SECONDS)) {
                        outbound.shutdownNow(); throw new IllegalStateException("TCP write workers chưa kết thúc sau 5 giây");
                    }
                } catch (InterruptedException interrupted) {
                    outbound.shutdownNow(); Thread.currentThread().interrupt();
                    RuntimeException failure = new IllegalStateException("Interrupt khi chờ write workers", interrupted);
                    if (closeFailure == null) { closeFailure = failure; } else { closeFailure.addSuppressed(failure); }
                } catch (RuntimeException failure) {
                    if (closeFailure == null) { closeFailure = failure; } else { closeFailure.addSuppressed(failure); }
                }
            }
        }
        if (closeFailure != null) { throw closeFailure; }
    }

    /** Quan sát test lifecycle, không expose executor để caller submit gameplay. */
    boolean areReadersTerminated() { return readers == null || readers.isTerminated(); }
    boolean areWritersTerminated() { return writers == null || writers.isTerminated(); }
    int getLargestWriterPoolSize() { return writers == null ? 0 : writers.getLargestPoolSize(); }
    int getLargestReaderPoolSize() { return readers == null ? 0 : readers.getLargestPoolSize(); }
    HeartbeatManager getHeartbeatManager() { return heartbeat; }
}
