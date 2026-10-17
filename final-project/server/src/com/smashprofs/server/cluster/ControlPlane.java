package com.smashprofs.server.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.LongSupplier;
import static com.smashprofs.server.cluster.HttpJson.*;

/** Single regional authority for the local prototype. No room is reassigned after worker loss. */
public final class ControlPlane {
    public static final long LEASE_MILLIS = 5000, TICKET_MILLIS = 20_000, SESSION_MILLIS = 900_000;
    private static final long ROOM_WAIT_MILLIS = 120_000, QUEUE_MILLIS = 30_000;
    private final String secret;
    private final LongSupplier clock;
    private final RoomAllocator allocator;
    private final Map<String, Worker> workers = new LinkedHashMap<>();
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, Session> sessionsById = new HashMap<>();
    private final Map<String, Session> tickets = new HashMap<>();
    private final Map<String, Room> rooms = new LinkedHashMap<>();
    private final LinkedHashMap<String, Pending> queue = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final com.smashprofs.server.network.TokenBucket guestRate = new com.smashprofs.server.network.TokenBucket(100,200);
    public ControlPlane(String secret, RoomAllocator allocator) { this(secret, allocator, System::currentTimeMillis); }
    public ControlPlane(String secret, RoomAllocator allocator, LongSupplier clock) {
        if (secret == null || secret.length() < 32) { throw new IllegalArgumentException("Cluster secret must have at least 32 characters"); }
        this.secret = secret; this.allocator = allocator; this.clock = clock;
    }
    public synchronized Object handle(String path, JsonNode body, String credential) {
        reap();
        if (path.equals("/auth/guest")) {
            if(!guestRate.allow(1)) { throw new Failure(429,"AUTH_RATE_LIMITED"); }
            if (sessions.size() >= 100_000) { throw new Failure(503, "SESSION_CAPACITY"); }
            String token = token(); var session = new Session(UUID.randomUUID().toString(), UUID.randomUUID().toString(), clock.getAsLong() + SESSION_MILLIS);
            sessions.put(token, session);
            sessionsById.put(session.id, session);
            return Map.of("token", token, "playerId", session.player, "sessionId", session.id, "expiresAt", session.expiry);
        }
        if (path.startsWith("/workers/") || path.equals("/claim") || path.equals("/admin/metrics")) {
            requireSecret(credential, secret);
            return switch (path) {
                case "/workers/heartbeat" -> heartbeat(body);
                case "/workers/drain" -> { Worker worker = worker(text(body, "workerId")); worker.draining = true; yield Map.of("draining", true); }
                case "/claim" -> claim(body);
                case "/admin/metrics" -> metrics();
                default -> throw new Failure(404, "UNKNOWN_ENDPOINT");
            };
        }
        if (path.equals("/metrics")) { return metrics(); }
        Session session = session(credential);
        return switch (path) {
            case "/rooms/create" -> {
                if(body.has("gameplay") && !body.path("gameplay").isBoolean()) { throw new Failure(400,"INVALID_GAMEPLAY_CAPABILITY"); }
                if(body.has("fighter") && (!body.path("fighter").isBoolean() || body.path("fighter").asBoolean() && !body.path("gameplay").asBoolean())) { throw new Failure(400,"INVALID_GAMEPLAY_CAPABILITY"); }
                var room=create(List.of(session),region(body));room.gameplay=body.path("gameplay").asBoolean(false);room.hostPlayer=session.player;
                room.fighter=body.path("fighter").asBoolean(false);
                yield assignment(session,room);
            }
            case "/rooms/join" -> join(session, text(body, "roomId"));
            case "/matchmake" -> matchmake(session, body);
            case "/matchmake/cancel" -> { queue.remove(session.id); yield Map.of("cancelled", true); }
            case "/assignment" -> {
                if (session.room == null) { yield Map.of("status", queue.containsKey(session.id) ? "QUEUED" : "IDLE"); }
                yield assignment(session, rooms.get(session.room));
            }
            default -> throw new Failure(404, "UNKNOWN_ENDPOINT");
        };
    }
    private Object heartbeat(JsonNode body) {
        String id = text(body, "workerId"), incarnation = text(body, "incarnation");
        Worker worker = workers.get(id);
        if (worker != null && (!worker.incarnation.equals(incarnation) || worker.dead)) { throw new Failure(409, "WORKER_FENCED"); }
        if (worker == null) {
            if (workers.size() >= 128) { throw new Failure(503, "WORKER_CAPACITY"); }
            int port = body.path("port").asInt(), maxRooms = body.path("maxRooms").asInt(), maxConnections = body.path("maxConnections").asInt();
            if (port < 1 || port > 65535 || maxRooms < 1 || maxConnections < 4) { throw new Failure(400, "INVALID_WORKER"); }
            worker = new Worker(id, incarnation, region(body), "127.0.0.1", port, maxRooms, maxConnections);
            workers.put(id, worker);
        }
        worker.lastHeartbeat = clock.getAsLong(); worker.report = body.deepCopy();
        worker.draining |= body.path("draining").asBoolean();
        var states = body.path("rooms");
        for (Room room : rooms.values()) {
            if (!room.worker.equals(id) || room.terminal()) { continue; }
            JsonNode phase = states.get(room.id);
            if (phase != null && Set.of("WAITING", "READY", "COUNTDOWN", "PLAYING", "FINISHED").contains(phase.asText())) {
                room.seen = true; room.state = phase.asText();
                int limit=body.path("roomLimits").path(room.id).asInt(4);if(limit>=2 && limit<=4) { room.maxPlayers=limit; }
            } else if (room.seen) { finish(room, "CLEANUP"); }
        }
        return Map.of("leaseMillis", LEASE_MILLIS, "draining", worker.draining);
    }
    private Object claim(JsonNode body) {
        Worker worker = worker(text(body, "workerId"));
        if (worker.dead || !worker.incarnation.equals(text(body, "incarnation"))) { throw new Failure(409, "WORKER_FENCED"); }
        String ticket = text(body, "ticket");
        Session session = tickets.get(ticket);
        if (session == null) { throw new Failure(401, "INVALID_TICKET"); }
        Room room = rooms.get(session.room);
        if (room == null || room.terminal() || !room.worker.equals(worker.id) || session.claimed || session.ticketExpiry <= clock.getAsLong()) {
            throw new Failure(401, "INVALID_TICKET");
        }
        session.claimed = true;
        return Map.of("playerId", session.player, "sessionId", session.id, "roomId", room.id, "expiresAt", session.expiry,
                "minimumPlayers", room.minimumPlayers,"gameplay",room.gameplay,"hostPlayer",room.hostPlayer,"fighter",room.fighter);
    }
    private Room create(List<Session> members, String region) {
        if (rooms.size() >= 10_000) { throw new Failure(503, "ROOM_CAPACITY"); }
        for (Session session : members) { requireIdle(session); }
        var capacities = new ArrayList<RoomAllocator.Capacity>();
        for (Worker worker : workers.values()) {
            if (worker.dead || worker.draining || !worker.region.equals(region)) { continue; }
            var capacity = capacity(worker);
            if (!capacity.accepts()) { worker.blockedUntil = clock.getAsLong() + 3000; continue; }
            if (clock.getAsLong() < worker.blockedUntil) { continue; }
            capacities.add(capacity);
        }
        String owner = allocator.select(capacities).id();
        Room room = new Room("r_" + UUID.randomUUID().toString().replace("-", ""), owner, clock.getAsLong());
        room.minimumPlayers = Math.max(2, members.size());
        room.matchmade = members.size() > 1;
        room.hostPlayer=members.get(0).player;
        rooms.put(room.id, room);
        for (Session session : members) { bind(session, room); }
        return room;
    }
    private Object join(Session session, String id) {
        Room room = rooms.get(id);
        if (room == null || room.terminal()) { throw new Failure(404, "ROOM_NOT_FOUND"); }
        if (id.equals(session.room)) { return assignment(session, room); }
        if (room.matchmade) { throw new Failure(403, "MATCHMADE_ROOM_PRIVATE"); }
        requireIdle(session);
        if (!Set.of("WAITING", "READY").contains(room.state)) { throw new Failure(409, "ROOM_STARTED"); }
        if (room.members.size() >= room.maxPlayers) { throw new Failure(409, "ROOM_FULL"); }
        if (worker(room.worker).dead) { throw new Failure(503, "WORKER_LOST"); }
        bind(session, room); return assignment(session, room);
    }
    private Object matchmake(Session session, JsonNode body) {
        if (session.room != null) { return assignment(session, rooms.get(session.room)); }
        String region = region(body); int size = body.path("size").asInt(4);
        if (size < 2 || size > 4) { throw new Failure(400, "INVALID_MATCH_SIZE"); }
        Pending previous = queue.get(session.id);
        if (previous != null && (!previous.region.equals(region) || previous.size != size)) { throw new Failure(409, "ALREADY_QUEUED"); }
        if (previous == null) { queue.put(session.id, new Pending(session, region, size, clock.getAsLong() + QUEUE_MILLIS)); }
        var candidates = queue.values().stream().filter(p -> p.region.equals(region) && p.size == size).limit(size).toList();
        if (candidates.size() == size) {
            Room room = create(candidates.stream().map(p -> p.session).toList(), region);
            candidates.forEach(p -> queue.remove(p.session.id));
            return assignment(session, room);
        }
        return Map.of("status", "QUEUED", "expiresAt", queue.get(session.id).expiry);
    }
    private void bind(Session session, Room room) {
        session.room = room.id; session.ticket = token(); session.ticketExpiry = clock.getAsLong() + TICKET_MILLIS;
        session.claimed = false; room.members.add(session.id); queue.remove(session.id);
        tickets.put(session.ticket, session);
    }
    private Object assignment(Session session, Room room) {
        if (room == null || room.terminal()) { throw new Failure(409, "ROOM_TERMINAL"); }
        Worker worker = worker(room.worker);
        return Map.of("status", "ASSIGNED", "workerId", worker.id, "host", worker.host, "port", worker.port,
                "roomId", room.id, "ticket", session.ticket, "ticketExpiresAt", session.ticketExpiry,
                "capability", "cluster-v1","gameplay",room.gameplay,"fighter",room.fighter);
    }
    private RoomAllocator.Capacity capacity(Worker worker) {
        JsonNode r = worker.report;
        boolean fresh=freshMetrics(r);
        int reservedRooms = (int) rooms.values().stream().filter(room -> room.worker.equals(worker.id) && !room.terminal()).count();
        int reservedConnections = rooms.values().stream().filter(room -> room.worker.equals(worker.id) && !room.terminal()).mapToInt(room -> room.members.size()).sum();
        return new RoomAllocator.Capacity(worker.id, Math.max(reservedRooms, r.path("activeRooms").asInt()), worker.maxRooms,
                Math.max(reservedConnections, r.path("activeConnections").asInt()), worker.maxConnections,
                fresh?finite(r, "cpu"):1, fresh?finite(r, "tickP99Ms"):1000,
                fresh?finite(r, "heapRatio"):1, r.path("outboundQueueDepth").asInt());
    }
    private boolean freshMetrics(JsonNode report) {
        for(String field:List.of("cpu","tickP99Ms","heapRatio","activeConnections","activeRooms","outboundQueueDepth","sampledAtMillis","metricsValidForMillis")) {
            if(!report.path(field).isNumber() || !Double.isFinite(report.path(field).asDouble()) || report.path(field).asDouble()<0) { return false; }
        }
        long age=clock.getAsLong()-report.path("sampledAtMillis").asLong();
        long validity=report.path("metricsValidForMillis").asLong();
        return validity>0 && validity<=2500 && age>=0 && age<validity;
    }
    private static double finite(JsonNode node, String field) {
        double value = node.path(field).asDouble(); return Double.isFinite(value) && value >= 0 ? value : 1;
    }
    private Session session(String token) {
        Session session = token == null ? null : sessions.get(token);
        if (session == null || session.expiry <= clock.getAsLong()) { throw new Failure(401, "INVALID_SESSION"); }
        return session;
    }
    private Worker worker(String id) { Worker result = workers.get(id); if (result == null) { throw new Failure(404, "WORKER_NOT_FOUND"); } return result; }
    private void requireIdle(Session session) {
        if (session.room != null) { throw new Failure(409, "SESSION_ALREADY_ASSIGNED"); }
    }
    private String region(JsonNode body) { String region = body.path("region").asText("local"); if (!region.matches("[a-zA-Z0-9_-]{1,32}")) { throw new Failure(400, "INVALID_REGION"); } return region; }
    private String token() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private void finish(Room room, String state) {
        room.state = state; room.finished = clock.getAsLong();
        for (String member : room.members) {
            Session session = sessionsById.get(member);
            if (session != null && room.id.equals(session.room)) {
                tickets.remove(session.ticket); session.room = null; session.ticket = null; session.claimed = false;
            }
        }
    }
    private void reap() {
        long now = clock.getAsLong();
        for (Worker worker : workers.values()) {
            if (!worker.dead && now - worker.lastHeartbeat > LEASE_MILLIS) {
                worker.dead = true;
                for (Room room : rooms.values()) { if (room.worker.equals(worker.id) && !room.terminal()) { finish(room, "ABORTED"); } }
            }
        }
        queue.values().removeIf(p -> p.expiry <= now || p.session.expiry <= now);
        for (Room room : rooms.values()) {
            // Never reclaim a seen simulation via an absent client or an expired ticket.
            if (!room.terminal() && !room.seen && now - room.created > ROOM_WAIT_MILLIS) { finish(room, "EXPIRED"); }
        }
        sessions.values().removeIf(s -> {
            if (s.expiry > now) { return false; }
            sessionsById.remove(s.id); if (s.ticket != null) { tickets.remove(s.ticket); } return true;
        });
        tickets.values().removeIf(s -> s.ticketExpiry <= now);
        rooms.values().removeIf(r -> r.terminal() && now - r.finished > 60_000);
    }
    public synchronized Map<String, Object> metrics() {
        reap();
        var reports = new ArrayList<Map<String, Object>>();
        for (Worker worker : workers.values()) {
            var value = new LinkedHashMap<String, Object>(); value.put("workerId", worker.id); value.put("region", worker.region);
            value.put("health", worker.dead ? "LOST" : worker.draining ? "DRAINING" : "HEALTHY");
            value.put("report", worker.report); value.put("reserved", capacity(worker)); reports.add(value);
        }
        return Map.of("workers", reports, "sessions", sessions.size(), "queued", queue.size(),
                "activeRooms", rooms.values().stream().filter(r -> !r.terminal()).count(),
                "roomStates", rooms.values().stream().collect(java.util.stream.Collectors.toMap(r -> r.id, r -> r.state)));
    }
    private static final class Worker {
        final String id, incarnation, region, host; final int port, maxRooms, maxConnections;
        long lastHeartbeat, blockedUntil; boolean dead, draining; JsonNode report = object();
        Worker(String id, String incarnation, String region, String host, int port, int maxRooms, int maxConnections) {
            this.id=id; this.incarnation=incarnation; this.region=region; this.host=host; this.port=port; this.maxRooms=maxRooms; this.maxConnections=maxConnections;
        }
    }
    private static final class Session {
        final String id, player; final long expiry; String room, ticket; long ticketExpiry; boolean claimed;
        Session(String id, String player, long expiry) { this.id=id; this.player=player; this.expiry=expiry; }
    }
    private static final class Room {
        final String id, worker; final long created; final Set<String> members = new HashSet<>();
        String state = "WAITING"; boolean seen; long finished;
        int minimumPlayers = 2,maxPlayers=4;
        boolean gameplay;
        boolean fighter;
        String hostPlayer="";
        boolean matchmade;
        Room(String id, String worker, long created) { this.id=id; this.worker=worker; this.created=created; }
        boolean terminal() { return Set.of("CLEANUP", "ABORTED", "EXPIRED").contains(state); }
    }
    private record Pending(Session session, String region, int size, long expiry) { }
}
