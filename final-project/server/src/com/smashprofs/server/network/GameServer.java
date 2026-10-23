package com.smashprofs.server.network;

import com.smashprofs.server.game.FixedTickScheduler;
import com.smashprofs.server.game.RankingGateway;
import com.smashprofs.server.game.MatchResultPublisher;
import com.smashprofs.server.room.RoomManager;
import com.smashprofs.server.ranking.LeaderboardBroadcaster;
import com.smashprofs.server.ranking.RankingService;
import com.smashprofs.common.protocol.ProtocolV1;

import java.util.Objects;
import java.io.IOException;
import java.util.function.Function;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Sở hữu TCP listener, registry session và scheduler/phòng của một lifecycle server.
 */
public final class GameServer {
    private final Supplier<FixedTickScheduler> schedulerFactory;
    private final LongSupplier nanoClock;
    private final BiFunction<ConnectionManager, RoomManager, TcpServerTransport> transportFactory;
    private final int snapshotsPerSecond;
    private final HeartbeatConfiguration heartbeatConfiguration;
    private final LongSupplier heartbeatClock;
    private final Supplier<java.util.concurrent.ScheduledExecutorService> heartbeatExecutorFactory;
    private StateBroadcaster broadcaster;
    private FixedTickScheduler scheduler;
    private RoomManager roomManager;
    private ConnectionManager connectionManager;
    private TcpServerTransport transport;
    private volatile boolean running;
    private boolean stopped;
    private final RankingGateway rankingGateway;
    private MatchResultPublisher resultPublisher;

    public GameServer(String host, int port, int maxConnections, RankingGateway rankingGateway) {
        this(FixedTickScheduler::new, System::nanoTime,
                (connections, rooms) -> protocolTransport(host, port, maxConnections, connections, rooms),
                StateBroadcaster.DEFAULT_SNAPSHOTS_PER_SECOND, HeartbeatConfiguration.DEFAULT,
                System::nanoTime, HeartbeatManager::newExecutor, Objects.requireNonNull(rankingGateway));
    }

    /** Injection in-process; Member 3 phải cung cấp implementation thật, không no-op success. */
    public static GameServer withRankingGateway(Supplier<FixedTickScheduler> schedulerFactory,
                                               LongSupplier clock, RankingGateway rankingGateway) {
        return new GameServer(schedulerFactory, clock,
                (connections, rooms) -> protocolTransport("127.0.0.1", 0, 64, connections, rooms),
                StateBroadcaster.DEFAULT_SNAPSHOTS_PER_SECOND, HeartbeatConfiguration.DEFAULT,
                System::nanoTime, HeartbeatManager::newExecutor, Objects.requireNonNull(rankingGateway));
    }

    public static GameServer withRanking(Supplier<FixedTickScheduler> schedulerFactory,
                                         LongSupplier clock,
                                         RankingService rankingService,
                                         LeaderboardBroadcaster broadcaster) {
        return new GameServer(schedulerFactory, clock,
                (connections, rooms) -> new TcpServerTransport("127.0.0.1", 0, 64, connections,
                        new TcpIoConfiguration(JsonTcpMessageCodec::new,
                                MessageDispatcher.protocolV1(connections, rooms, null, rankingService, broadcaster),
                                ProtocolV1.MAX_FRAME_BYTES, 5000)),
                StateBroadcaster.DEFAULT_SNAPSHOTS_PER_SECOND, HeartbeatConfiguration.DEFAULT,
                System::nanoTime, HeartbeatManager::newExecutor, Objects.requireNonNull(rankingService));
    }

    public static GameServer withRanking(RankingService rankingService, LeaderboardBroadcaster broadcaster) {
        return withRanking(FixedTickScheduler::new, System::nanoTime, rankingService, broadcaster);
    }

    public static GameServer withRanking(String host, int port, int maxConnections,
                                         RankingService rankingService,
                                         LeaderboardBroadcaster broadcaster) {
        return new GameServer(FixedTickScheduler::new, System::nanoTime,
                (connections, rooms) -> new TcpServerTransport(host, port, maxConnections, connections,
                        new TcpIoConfiguration(JsonTcpMessageCodec::new,
                                MessageDispatcher.protocolV1(connections, rooms, null, rankingService, broadcaster),
                                ProtocolV1.MAX_FRAME_BYTES, 5000)),
                StateBroadcaster.DEFAULT_SNAPSHOTS_PER_SECOND, HeartbeatConfiguration.DEFAULT,
                System::nanoTime, HeartbeatManager::newExecutor, Objects.requireNonNull(rankingService));
    }

    public GameServer() {
        this("127.0.0.1", 0, 64);
    }

    /** Port 0 cấp tự động; bind 0.0.0.0 chỉ khi caller cấu hình tường minh cho LAN. */
    public GameServer(String bindHost, int bindPort, int maxConnections) {
        this(bindHost, bindPort, maxConnections, StateBroadcaster.DEFAULT_SNAPSHOTS_PER_SECOND);
    }

    public GameServer(String bindHost, int bindPort, int maxConnections, int snapshotsPerSecond) {
        this(FixedTickScheduler::new, System::nanoTime,
                (connections, rooms) -> protocolTransport(bindHost, bindPort, maxConnections, connections, rooms), snapshotsPerSecond);
    }

    private static TcpServerTransport protocolTransport(String host, int port, int maxConnections,
                                                       ConnectionManager connections, RoomManager rooms) {
        return new TcpServerTransport(host, port, maxConnections, connections,
                new TcpIoConfiguration(JsonTcpMessageCodec::new, MessageDispatcher.protocolV1(connections, rooms),
                        ProtocolV1.MAX_FRAME_BYTES, 5000));
    }

    /** Separate secured listener; existing constructors retain the exact v1 CONNECT contract. */
    public static GameServer clusterWorker(String host, int port, int maxConnections, int snapshotsPerSecond,
                                           SessionAdmission admission, RankingGateway ranking) {
        return new GameServer(FixedTickScheduler::new, System::nanoTime,
                (connections, rooms) -> new TcpServerTransport(host, port, maxConnections, connections,
                        new TcpIoConfiguration(JsonTcpMessageCodec::new,
                                MessageDispatcher.protocolV1(connections, rooms, Objects.requireNonNull(admission)),
                                ProtocolV1.MAX_FRAME_BYTES, 5000)),
                snapshotsPerSecond, HeartbeatConfiguration.DEFAULT, System::nanoTime,
                HeartbeatManager::newExecutor, Objects.requireNonNull(ranking));
    }

    public synchronized com.smashprofs.server.metrics.ServerMetrics getMetrics() { return scheduler.getMetrics(); }
    public synchronized StateBroadcaster getBroadcaster() { return broadcaster; }

    /** Giữ codec injection; dispatcher hỗ trợ v1 envelope và DTO nội bộ cũ. */
    public GameServer(String bindHost, int bindPort, int maxConnections, Supplier<TcpMessageCodec> codecFactory,
                      int maxMessageBytes, int incompleteMessageTimeoutMillis) {
        this(FixedTickScheduler::new, System::nanoTime, (connections, rooms) -> {
            MessageDispatcher dispatcher = MessageDispatcher.protocolV1(connections, rooms);
            return new TcpServerTransport(bindHost, bindPort, maxConnections, connections,
                    new TcpIoConfiguration(codecFactory, dispatcher, maxMessageBytes, incompleteMessageTimeoutMillis));
        });
    }

    /**
     * Cho phép inject scheduler/clock khi test. Factory phải tạo scheduler mới cho mỗi lifecycle.
     * Server sở hữu và dừng scheduler được cung cấp, không chia sẻ với server khác.
     */
    public GameServer(Supplier<FixedTickScheduler> schedulerFactory, LongSupplier nanoClock) {
        this(schedulerFactory, nanoClock,
                (connections, rooms) -> protocolTransport("127.0.0.1", 0, 64, connections, rooms));
    }

    /** Clock/executor heartbeat riêng: test không dùng clock trận để giả timeout connection. */
    public GameServer(Supplier<FixedTickScheduler> schedulerFactory, LongSupplier nanoClock,
                      HeartbeatConfiguration heartbeatConfiguration, LongSupplier heartbeatClock,
                      Supplier<java.util.concurrent.ScheduledExecutorService> heartbeatExecutorFactory) {
        this(schedulerFactory, nanoClock,
                (connections, rooms) -> protocolTransport("127.0.0.1", 0, 64, connections, rooms),
                StateBroadcaster.DEFAULT_SNAPSHOTS_PER_SECOND, heartbeatConfiguration, heartbeatClock, heartbeatExecutorFactory);
    }

    public GameServer(String host, int port, int maxConnections, int snapshotsPerSecond,
                      HeartbeatConfiguration heartbeatConfiguration) {
        this(FixedTickScheduler::new, System::nanoTime,
                (connections, rooms) -> protocolTransport(host, port, maxConnections, connections, rooms),
                snapshotsPerSecond, heartbeatConfiguration, System::nanoTime, HeartbeatManager::newExecutor);
    }

    /** Seam nội bộ cho kiểm thử lỗi start và notification registration, không thêm wire protocol. */
    GameServer(Supplier<FixedTickScheduler> schedulerFactory, LongSupplier nanoClock,
               Function<ConnectionManager, TcpServerTransport> transportFactory) {
        this(schedulerFactory, nanoClock, adaptTransportFactory(transportFactory));
    }

    private static BiFunction<ConnectionManager, RoomManager, TcpServerTransport> adaptTransportFactory(
            Function<ConnectionManager, TcpServerTransport> factory) {
        Objects.requireNonNull(factory, "transportFactory không được null");
        return (connections, rooms) -> factory.apply(connections);
    }

    GameServer(Supplier<FixedTickScheduler> schedulerFactory, LongSupplier nanoClock,
               BiFunction<ConnectionManager, RoomManager, TcpServerTransport> transportFactory) {
        this(schedulerFactory, nanoClock, transportFactory, StateBroadcaster.DEFAULT_SNAPSHOTS_PER_SECOND);
    }

    GameServer(Supplier<FixedTickScheduler> schedulerFactory, LongSupplier nanoClock,
               BiFunction<ConnectionManager, RoomManager, TcpServerTransport> transportFactory, int snapshotsPerSecond) {
        this(schedulerFactory, nanoClock, transportFactory, snapshotsPerSecond,
                HeartbeatConfiguration.DEFAULT, System::nanoTime, HeartbeatManager::newExecutor);
    }

    private GameServer(Supplier<FixedTickScheduler> schedulerFactory, LongSupplier nanoClock,
               BiFunction<ConnectionManager, RoomManager, TcpServerTransport> transportFactory, int snapshotsPerSecond,
               HeartbeatConfiguration heartbeatConfiguration, LongSupplier heartbeatClock,
               Supplier<java.util.concurrent.ScheduledExecutorService> heartbeatExecutorFactory) {
        this(schedulerFactory, nanoClock, transportFactory, snapshotsPerSecond, heartbeatConfiguration,
                heartbeatClock, heartbeatExecutorFactory, null);
    }

    private GameServer(Supplier<FixedTickScheduler> schedulerFactory, LongSupplier nanoClock,
               BiFunction<ConnectionManager, RoomManager, TcpServerTransport> transportFactory, int snapshotsPerSecond,
               HeartbeatConfiguration heartbeatConfiguration, LongSupplier heartbeatClock,
               Supplier<java.util.concurrent.ScheduledExecutorService> heartbeatExecutorFactory, RankingGateway rankingGateway) {
        this.rankingGateway = rankingGateway;
        this.heartbeatConfiguration = Objects.requireNonNull(heartbeatConfiguration);
        this.heartbeatClock = Objects.requireNonNull(heartbeatClock);
        this.heartbeatExecutorFactory = Objects.requireNonNull(heartbeatExecutorFactory);
        StateBroadcaster.validateRate(snapshotsPerSecond);
        this.snapshotsPerSecond = snapshotsPerSecond;
        this.schedulerFactory = Objects.requireNonNull(schedulerFactory, "schedulerFactory không được null");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock không được null");
        this.transportFactory = Objects.requireNonNull(transportFactory, "transportFactory không được null");
        createLifecycle();
    }

    private void createLifecycle() {
        FixedTickScheduler nextScheduler = Objects.requireNonNull(schedulerFactory.get(),
                "schedulerFactory không được trả null");
        MatchResultPublisher nextResults = new MatchResultPublisher(rankingGateway);
        RoomManager nextRooms = new RoomManager(nextScheduler, nanoClock, nextResults::offer);
        ConnectionManager nextConnections = new ConnectionManager();
        nextConnections.performance=nextScheduler.getMetrics().performance;
        StateBroadcaster nextBroadcaster = new StateBroadcaster(nextRooms, nextConnections, snapshotsPerSecond);
        TcpServerTransport nextTransport = null;
        try {
            nextScheduler.setLifecycleUpdate(nextRooms::updateLifecycles);
            nextScheduler.setAfterCycle(nextBroadcaster::broadcast);
            nextTransport = Objects.requireNonNull(transportFactory.apply(nextConnections, nextRooms), "transportFactory trả null");
            nextTransport.enableHeartbeat(heartbeatConfiguration, heartbeatClock, heartbeatExecutorFactory, nextRooms);
        } catch (RuntimeException | Error failure) {
            cleanup(failure, nextBroadcaster, nextTransport, nextScheduler, nextRooms, nextResults);
            throw failure;
        }
        roomManager = nextRooms;
        connectionManager = nextConnections;
        transport = nextTransport;
        scheduler = nextScheduler;
        broadcaster = nextBroadcaster;
        resultPublisher = nextResults;
        stopped = false;
    }

    /**
     * Chỉ khởi động một scheduler. Restart tạo lifecycle mới, không phục hồi trận cũ
     * vì MatchRuntime chưa hỗ trợ chuyển simulation owner sang thread khác.
     */
    public synchronized void start() {
        if (running) {
            if (!transport.isAccepting()) {
                throw new IllegalStateException("TCP listener đã lỗi; gọi stop rồi start để tạo lifecycle mới");
            }
            return;
        }
        if (stopped) {
            createLifecycle();
        }
        try {
            transport.bind();
            scheduler.start();
            transport.startAccepting();
            running = true;
        } catch (RuntimeException | Error failure) {
            running = false;
            stopped = true;
            cleanup(failure, broadcaster, transport, scheduler, roomManager, resultPublisher);
            throw failure;
        }
    }

    /**
     * Unblock/join accept và đóng sessions trước, rồi dừng tick và đóng registry phòng.
     * Khóa lifecycle server không được simulation tick sử dụng. Stop có thể gọi lại an toàn.
     */
    public synchronized void stop() {
        running = false;
        stopped = true;
        RuntimeException failure = new IllegalStateException("Lỗi khi shutdown server");
        cleanup(failure, broadcaster, transport, scheduler, roomManager, resultPublisher);
        if (failure.getSuppressed().length != 0) { throw failure; }
    }

    /** Thử tất cả cleanup; lỗi sau không che lỗi start gốc hoặc bỏ qua World của phòng. */
    private static void cleanup(Throwable failure, StateBroadcaster broadcaster, TcpServerTransport transport,
                                FixedTickScheduler scheduler, RoomManager rooms, MatchResultPublisher results) {
        if (broadcaster != null) {
            try { broadcaster.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
        }
        if (transport != null) {
            try { transport.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
        }
        try { scheduler.stop(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
        // Shutdown có thể thắng unregister callback sau một tick đang chạy. Chỉ handoff result đã có, không ép kết thúc.
        try {
            for (var room : rooms.getRooms()) {
                var runtime = room.getMatchRuntime();
                if (runtime != null && runtime.getMatchResult() != null) { results.offer(runtime.getMatchResult()); }
            }
        } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
        try { rooms.shutdown(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
        try { results.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
    }

    /** -1 nếu chưa bind/đã đóng; cần lấy lại port sau restart khi cấu hình port 0. */
    public synchronized int getBoundPort() { return transport.getBoundPort(); }

    /** Một registry duy nhất; transport là nơi đăng ký TCP sessions, caller đóng qua session.close(). */
    public synchronized ConnectionManager getConnectionManager() { return connectionManager; }

    /** Quan sát trạng thái handoff, không phải ACK persistence từ RankingService. */
    public synchronized MatchResultPublisher getResultPublisher() { return resultPublisher; }

    /** Lỗi accept bất thường của lifecycle hiện tại, null khi không có lỗi; không nuốt IOException. */
    public synchronized IOException getAcceptFailure() { return transport.getAcceptFailure(); }

    /** Registry của lifecycle hiện tại; sau restart cần lấy lại, registry cũ đã đóng. */
    public synchronized RoomManager getRoomManager() {
        return roomManager;
    }

    /**
     * Kiểm tra server hiện có đang chạy hay không.
     *
     * @return {@code true} nếu server đang chạy, ngược lại trả về {@code false}
     */
    public boolean isRunning() {
        return running && transport.isAccepting();
    }
}
