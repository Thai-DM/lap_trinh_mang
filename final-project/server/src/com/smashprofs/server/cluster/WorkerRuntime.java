package com.smashprofs.server.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.protocol.*;
import com.smashprofs.server.network.*;
import com.smashprofs.server.room.RoomPhase;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.smashprofs.server.cluster.HttpJson.*;

/** One real GameServer per JVM. Central claims plus local expiry enforce one session/slot. */
public final class WorkerRuntime implements AutoCloseable, SessionAdmission {
    private final String id, incarnation = UUID.randomUUID().toString(), region, control, secret;
    private final int maxRooms, maxConnections;
    private final GameServer server;
    private final com.smashprofs.server.room.RoomManager rooms;
    private final ConnectionManager connections;
    private final StateBroadcaster broadcaster;
    private final com.smashprofs.server.metrics.ServerMetrics metrics;
    private final com.smashprofs.server.game.MatchResultPublisher results;
    private final HttpJson admin;
    private final FileMatchHistory history;
    private final ConcurrentHashMap<String, Claim> claims = new ConcurrentHashMap<>();
    private final Map<String, Long> roomTimers = new HashMap<>();
    private final ScheduledExecutorService maintenance = Executors.newScheduledThreadPool(2);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch done = new CountDownLatch(1);
    private final AdaptiveSnapshotPolicy adaptive;
    private volatile long leaseDeadline;
    private volatile boolean draining;
    private final long started = System.nanoTime();
    private long previousSample = started, previousBytes, previousSnapshots, previousMessages, previousErrors;
    private volatile Map<String, Object> latest;

    public WorkerRuntime(Properties config, String secret) throws IOException {
        this.secret = secret; id = config.getProperty("worker.id"); region = config.getProperty("region", "local");
        control = config.getProperty("control.url", "http://127.0.0.1:8080");
        maxRooms = integer(config, "max.rooms", 256, 1, 1024);
        maxConnections = integer(config, "max.connections", 1024, 4, 10000);
        int rate = integer(config, "snapshot.hz", 20, 1, 60);
        adaptive = config.getProperty("snapshot.mode", "BASELINE").equals("ADAPTIVE")
                ? new AdaptiveSnapshotPolicy(rate, integer(config, "snapshot.min.hz", 5, 1, rate)) : null;
        history = new FileMatchHistory(Path.of(config.getProperty("history.dir", "run/history")));
        server = GameServer.clusterWorker("127.0.0.1", integer(config, "tcp.port", 9001, 0, 65535), maxConnections, rate, this, history);
        rooms = server.getRoomManager(); connections = server.getConnectionManager(); broadcaster = server.getBroadcaster();
        metrics = server.getMetrics(); results = server.getResultPublisher();
        server.start();
        HttpJson nextAdmin = null;
        try {
            nextAdmin = new HttpJson(integer(config, "admin.port", 9101, 0, 65535), this::admin);
            admin = nextAdmin;
            latest = sample(); heartbeat();
            maintenance.scheduleWithFixedDelay(this::maintain, 200, 200, TimeUnit.MILLISECONDS);
            maintenance.scheduleWithFixedDelay(() -> {
                try { latest = sample(); heartbeat(); }
                catch (Exception failure) { System.err.println("Worker heartbeat failed: " + id); }
            }, 1, 1, TimeUnit.SECONDS);
        } catch (Exception failure) {
            if (nextAdmin != null) { nextAdmin.close(); } maintenance.shutdownNow(); server.stop();
            throw new IOException("Worker registration failed", failure);
        }
    }
    public static int integer(Properties p, String name, int fallback, int min, int max) {
        int value = Integer.parseInt(p.getProperty(name, Integer.toString(fallback)));
        if (value < min || value > max) { throw new IllegalArgumentException(name + " out of range"); } return value;
    }
    @Override public PlayerId connect(ClientSession session, ProtocolMessage request) throws ProtocolException {
        ProtocolV1.fields(request.payload(), request.requestId(), "capability", "assignment");
        if (!request.payload().path("capability").asText().equals("cluster-v1")
                || !request.payload().path("assignment").isTextual()) { throw denied(request, "CLUSTER_CAPABILITY_REQUIRED"); }
        if (closed.get() || System.nanoTime() >= leaseDeadline) { throw denied(request, "WORKER_FENCED"); }
        String ticket = request.payload().path("assignment").asText();
        Claim existing = claims.get(session.getSessionId());
        if (existing != null) {
            if (!existing.ticket.equals(ticket) || existing.expiresAt <= System.currentTimeMillis()) { throw denied(request, "INVALID_SESSION"); }
            return new PlayerId(existing.player);
        }
        try {
            var response = call(control, "/claim", Map.of("workerId", id, "incarnation", incarnation, "ticket", ticket), secret);
            if (System.nanoTime() >= leaseDeadline) { throw new IOException("Lease expired during claim"); }
            String room = text(response, "roomId"), player = text(response, "playerId");
            synchronized (claims) {
                if (!rooms.containsRoom(room) && rooms.getRoomCount() >= maxRooms) {
                    throw new IOException("Worker room limit");
                }
                rooms.getOrCreateRoom(room).setMinimumPlayers(response.path("minimumPlayers").asInt(2));
                claims.put(session.getSessionId(), new Claim(ticket, text(response, "sessionId"), player, room, response.path("expiresAt").asLong()));
            }
            return new PlayerId(player);
        } catch (IOException | RuntimeException failure) { throw denied(request, "INVALID_ASSIGNMENT"); }
    }
    @Override public void authorize(ClientSession session, ProtocolMessage request) throws ProtocolException {
        Claim claim = claims.get(session.getSessionId());
        if (claim == null || claim.expiresAt <= System.currentTimeMillis() || System.nanoTime() >= leaseDeadline) {
            throw denied(request, "INVALID_SESSION");
        }
        if (request.type() == MessageType.CREATE_ROOM) { throw denied(request, "CONTROL_PLANE_REQUIRED"); }
        if (request.payload().has("roomId") && !claim.room.equals(request.payload().path("roomId").asText())) {
            throw denied(request, "UNAUTHORIZED_PLAYER");
        }
    }
    private static ProtocolException denied(ProtocolMessage request, String code) { return new ProtocolException(code, "Cluster admission rejected", request.requestId()); }
    private void heartbeat() throws IOException {
        long requested = System.nanoTime();
        var response = call(control, "/workers/heartbeat", latest, secret);
        // Conservative deadline starts before request; no extension from a delayed response.
        leaseDeadline = requested + TimeUnit.MILLISECONDS.toNanos(ControlPlane.LEASE_MILLIS - 1000);
        draining |= response.path("draining").asBoolean();
        // Existing publisher keeps bounded idempotency tombstones. Drain before exhausting them.
        if (results.getTrackedCount() >= com.smashprofs.server.game.MatchResultPublisher.DEFAULT_TRACKED_CAPACITY - maxRooms) { draining = true; }
    }
    private void maintain() {
        if (closed.get()) { return; }
        if (System.nanoTime() >= leaseDeadline || !server.isRunning()) {
            System.err.println("Worker fenced: " + id); close(); return;
        }
        try {
            long now = System.currentTimeMillis();
            for (ClientSession session : connections.getSessions()) {
                Claim claim = claims.get(session.getSessionId());
                long writing = session.getWriteStartedNanos();
                if ((claim != null && now >= claim.expiresAt) || (writing != 0 && System.nanoTime() - writing > TimeUnit.SECONDS.toNanos(5))) { session.close(); }
            }
            claims.keySet().removeIf(key -> connections.get(key) == null);
            var active = connections.getSessions();
            for (var room : rooms.getRooms()) {
                long connected = active.stream().filter(s -> room.getRoomId().equals(s.getRoomId()) && s.isOpen()).count();
                boolean finished = room.getPhase() == RoomPhase.FINISHED;
                if (connected == 0 || finished) {
                    long since = roomTimers.computeIfAbsent(room.getRoomId(), k -> now);
                    // WAITING assignments have 20 seconds to connect; finished frames have a drain window.
                    if (now - since > (finished ? 2000 : 25_000)) {
                        for (var session : active) { Claim claim = claims.get(session.getSessionId()); if (claim != null && claim.room.equals(room.getRoomId())) { session.close(); } }
                        rooms.removeRoom(room.getRoomId()); roomTimers.remove(room.getRoomId());
                    }
                } else { roomTimers.remove(room.getRoomId()); }
                // Bound abandoned lobbies even if one peer remains connected.
                if (room.getPhase() == RoomPhase.WAITING || room.getPhase() == RoomPhase.READY) {
                    long since = roomTimers.computeIfAbsent("lobby:" + room.getRoomId(), k -> now);
                    if (now - since > 120_000) {
                        for (var session : active) { Claim claim = claims.get(session.getSessionId()); if (claim != null && claim.room.equals(room.getRoomId())) { session.close(); } }
                        rooms.removeRoom(room.getRoomId());
                    }
                } else { roomTimers.remove("lobby:" + room.getRoomId()); }
            }
            var liveRooms = rooms.getRooms().stream().map(r -> r.getRoomId()).collect(java.util.stream.Collectors.toSet());
            roomTimers.keySet().removeIf(key -> !liveRooms.contains(key.startsWith("lobby:") ? key.substring(6) : key));
        } catch (RuntimeException failure) { metrics.errors.increment(); }
    }
    private synchronized Map<String, Object> sample() {
        var result = new LinkedHashMap<String, Object>(metrics.snapshot());
        result.putAll(com.smashprofs.server.metrics.JvmMetrics.sample());
        var sessions = connections.getSessions();
        int outbound = sessions.stream().mapToInt(ClientSession::getOutboundQueueDepth).sum();
        long send = sessions.stream().mapToLong(ClientSession::getLastSendNanos).max().orElse(0);
        int inputs = 0, players = 0; var states = new LinkedHashMap<String, String>();
        for (var room : rooms.getRooms()) {
            states.put(room.getRoomId(), room.getPhase().name()); players += room.getPlayerCount();
            var runtime = room.getMatchRuntime(); if (runtime != null) { inputs += runtime.getPendingInputCount(); }
        }
        var memory = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long now = System.nanoTime(); double seconds = Math.max(.001, (now - previousSample) / 1e9);
        long bytes = connections.sentBytes.sum(), snapshots = connections.snapshotBytes.sum();
        long messages = connections.sentMessages.sum(), errors = connections.errors.sum();
        result.put("sentBytesPerSecond", (bytes - previousBytes) / seconds); result.put("snapshotBytesPerSecond", (snapshots - previousSnapshots) / seconds);
        result.put("messagesPerSecond", (messages - previousMessages) / seconds); result.put("errorsPerSecond", (errors - previousErrors) / seconds);
        previousSample = now; previousBytes = bytes; previousSnapshots = snapshots; previousMessages = messages; previousErrors = errors;
        result.put("sentBytes", bytes); result.put("receivedBytes", connections.receivedBytes.sum()); result.put("snapshotBytes", snapshots);
        result.put("sentMessages", messages); result.put("receivedMessages", connections.receivedMessages.sum()); result.put("networkErrors", errors);
        result.put("workerId", id); result.put("incarnation", incarnation); result.put("region", region);
        result.put("pid", ProcessHandle.current().pid()); result.put("port", server.getBoundPort()); result.put("adminPort", admin == null ? 0 : admin.port());
        result.put("maxRooms", maxRooms); result.put("maxConnections", maxConnections);
        result.put("activeConnections", sessions.size()); result.put("activeRooms", states.size()); result.put("activePlayers", players);
        result.put("inputQueueDepth", inputs); result.put("outboundQueueDepth", outbound); result.put("maxSendMs", send / 1e6);
        result.put("cpu", Math.max(0, os.getProcessCpuLoad())); result.put("heapBytes", memory.getUsed());
        result.put("heapRatio", memory.getUsed() / (double) memory.getMax());
        result.put("gcCollectionMillis", ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(g -> Math.max(0, g.getCollectionTime())).sum());
        result.put("rooms", states); result.put("draining", draining); result.put("uptimeSeconds", (now - started) / 1e9);
        var perRoom=new LinkedHashMap<String,Object>();
        for(var room:rooms.getRooms()) { var runtime=room.getMatchRuntime(); if(runtime!=null) { perRoom.put(room.getRoomId(),runtime.tickMetrics()); } }
        result.put("roomTicks",perRoom);
        result.put("persistencePending", results.getPendingCount()); result.put("persistenceTracked", results.getTrackedCount());
        if (adaptive != null) {
            int rate = adaptive.observe(sessions.size(), outbound, send, ((Number) result.get("tickP99Ms")).doubleValue());
            broadcaster.setSnapshotsPerSecond(rate);
        }
        result.put("snapshotHz", broadcaster.getSnapshotsPerSecond());
        return Collections.unmodifiableMap(result);
    }
    private Object admin(String path, JsonNode body, String credential) throws IOException {
        if (path.equals("/metrics")) { return latest; }
        requireSecret(credential, secret);
        if(path.equals("/measurement/reset")) {
            for(var room:rooms.getRooms()) { var runtime=room.getMatchRuntime(); if(runtime!=null) { runtime.resetTickMetrics(); } }
            metrics.resetMeasurement(); return Map.of("reset",true);
        }
        if(path.equals("/measurement/snapshot")) {
            var report=new LinkedHashMap<String,Object>(latest); report.putAll(metrics.snapshot());
            var perRoom=new LinkedHashMap<String,Object>();
            for(var room:rooms.getRooms()) { var runtime=room.getMatchRuntime(); if(runtime!=null) { perRoom.put(room.getRoomId(),runtime.tickMetrics()); } }
            report.put("roomTicks",perRoom);
            report.putAll(com.smashprofs.server.metrics.JvmMetrics.sample()); return report;
        }
        if (path.equals("/drain")) { draining = true; return Map.of("draining", true); }
        if (path.equals("/history")) { return history.history(text(body, "playerId"), body.path("limit").asInt(20)); }
        if (path.equals("/shutdown")) {
            draining = true;
            if (rooms.getRoomCount() != 0) { throw new Failure(409, "WORKER_NOT_EMPTY"); }
            return new HttpJson.AfterResponse(Map.of("stopping", true), () -> maintenance.execute(this::close));
        }
        throw new Failure(404, "UNKNOWN_ENDPOINT");
    }
    public void await() throws InterruptedException { done.await(); }
    public int adminPort() { return admin.port(); }
    public int tcpPort() { return server.getBoundPort(); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) { return; }
        draining = true; maintenance.shutdownNow();
        try { server.stop(); } finally { admin.close(); done.countDown(); }
    }
    private record Claim(String ticket, String sessionId, String player, String room, long expiresAt) { }
}
