package com.smashprofs.server.network;

import com.smashprofs.server.game.MatchRuntime;
import com.smashprofs.server.game.AuthoritativeGameSnapshot;
import com.smashprofs.server.room.GameRoom;
import com.smashprofs.server.room.RoomManager;
import com.smashprofs.server.room.RoomPhase;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** Hook sau chu kỳ simulation; chỉ offer snapshot/kết quả bất biến, không encode hoặc Socket.write. */
public final class StateBroadcaster implements AutoCloseable {
    public static final int DEFAULT_SNAPSHOTS_PER_SECOND = 20;
    private static final Logger LOGGER = Logger.getLogger(StateBroadcaster.class.getName());
    private final RoomManager rooms;
    private final ConnectionManager connections;
    private volatile int interval;
    public void setSnapshotsPerSecond(int rate) { validateRate(rate); interval = MatchRuntime.TICKS_PER_SECOND / rate; }
    private final Map<String, Cursor> cursors = new HashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final boolean indexedRecipients=Boolean.parseBoolean(System.getProperty("smashprofs.performance.indexedRecipients","true"));
    public StateBroadcaster(RoomManager rooms, ConnectionManager connections) {
        this(rooms, connections, DEFAULT_SNAPSHOTS_PER_SECOND);
    }
    public StateBroadcaster(RoomManager rooms, ConnectionManager connections, int snapshotsPerSecond) {
        validateRate(snapshotsPerSecond);
        this.rooms = Objects.requireNonNull(rooms);
        this.connections = Objects.requireNonNull(connections);
        interval = MatchRuntime.TICKS_PER_SECOND / snapshotsPerSecond;
    }
    public static void validateRate(int rate) {
        if (rate < 1 || rate > MatchRuntime.TICKS_PER_SECOND || MatchRuntime.TICKS_PER_SECOND % rate != 0) {
            throw new IllegalArgumentException("Snapshot rate phải là ước số dương của 60 Hz");
        }
    }
    public int getSnapshotsPerSecond() { return MatchRuntime.TICKS_PER_SECOND / interval; }
    public synchronized void broadcast() {
        if (closed.get()) { return; }
        var active = new HashSet<String>();
        var index=indexedRecipients && rooms.getRoomCount()>=4?new RecipientIndex():null;
        for (GameRoom room : rooms.getRooms()) {
            MatchRuntime runtime = room.getMatchRuntime();
            if (runtime == null || runtime.isClosed() || room.isClosed()) { continue; }
            active.add(runtime.getRoomId());
            RoomPhase phase = room.getPhase();
            if (phase != RoomPhase.PLAYING && phase != RoomPhase.FINISHED) { continue; }
            final AuthoritativeGameSnapshot snapshot;
            final boolean terminal;
            final com.smashprofs.server.game.AuthoritativeMatchResult result;
            final long remaining;
            // Chụp metadata cùng một version dưới khóa tick, nhưng không encode/offer/send dưới khóa này.
            synchronized (runtime) {
                if (runtime.isClosed()) { continue; }
                snapshot = runtime.getLatestSnapshot();
                if (snapshot == null || snapshot.serverTick() <= 0) { continue; }
                terminal = runtime.isFinished();
                result = runtime.getMatchResult();
                remaining = runtime.getRemainingTimeMillis();
            }
            if (!snapshot.matchId().equals(runtime.getMatchId()) || !snapshot.roomId().equals(runtime.getRoomId())
                    || rooms.getRoom(snapshot.roomId()) != room) { continue; }
            Cursor cursor = cursors.get(snapshot.roomId());
            if (cursor == null || cursor.runtime != runtime) { cursor = new Cursor(runtime); cursors.put(snapshot.roomId(), cursor); }
            cursor.endRecipients.removeIf(session -> !session.isOpen() || connections.get(session.getSessionId()) != session);
            if (!cursor.startPublished && !terminal && runtime.isStarted()) {
                // Hook sau cycle đã có: publish start trước bất kỳ offer snapshot nào, không đổi lifecycle.
                for (ClientSession session : recipients(index,room.getRoomId())) {
                    var playerId = session.getPlayerId();
                    var setup = runtime.getMatchSetup();
                    if (playerId == null || !session.isOpen() || !setup.roomId().equals(session.getRoomId())
                            || setup.participants().stream().noneMatch(p -> p.playerId().equals(playerId))) { continue; }
                    var start = new GameStartDelivery(setup,
                            () -> !closed.get() && !runtime.isClosed() && !room.isClosed()
                                    && rooms.getRoom(setup.roomId()) == room && room.getMatchRuntime() == runtime
                                    && session.isOpen() && connections.get(session.getSessionId()) == session
                                    && playerId.equals(session.getPlayerId()) && setup.roomId().equals(session.getRoomId()));
                    try { if (!closed.get()) { session.offerGameStart(start); } }
                    catch (RuntimeException failure) { LOGGER.warning("Không offer được GAME_START cho một session"); }
                }
                cursor.startPublished = true;
            }
            if (cursor.terminal || snapshot.serverTick() <= cursor.lastTick
                    || (!terminal && snapshot.serverTick() % interval != 0)) { continue; }
            for (ClientSession session : recipients(index,room.getRoomId())) {
                var playerId = session.getPlayerId();
                if (playerId == null || !snapshot.roomId().equals(session.getRoomId()) || !session.isOpen()
                        || snapshot.players().stream().noneMatch(p -> p.playerId().equals(playerId))) { continue; }
                var delivery = new GameStateDelivery(snapshot, terminal ? "FINISHED" : "PLAYING", remaining,
                        () -> !closed.get() && !runtime.isClosed() && !room.isClosed()
                                && rooms.getRoom(snapshot.roomId()) == room && room.getMatchRuntime() == runtime
                                && session.isOpen() && connections.get(session.getSessionId()) == session
                                && playerId.equals(session.getPlayerId()) && snapshot.roomId().equals(session.getRoomId()));
                long enqueueStarted=System.nanoTime(); connections.performance.recipientVisits.increment();
                try {
                    if (!closed.get()) {
                        if (terminal && result != null) {
                            if (!cursor.endRecipients.contains(session) && session.offerGameEnd(new GameEndDelivery(result, delivery))) {
                                cursor.endRecipients.add(session);
                            }
                        } else { session.offerGameState(delivery); }
                    }
                }
                catch (RuntimeException failure) {
                    // Channel vi phạm boundary không được làm dừng các recipient/room khác.
                    LOGGER.warning("Không offer được GAME_STATE/GAME_END cho một session");
                } finally { connections.performance.record(com.smashprofs.server.metrics.PerformanceMetrics.Stage.SNAPSHOT_ENQUEUE,System.nanoTime()-enqueueStarted); }
            }
            cursor.lastTick = snapshot.serverTick(); cursor.terminal = terminal;
        }
        cursors.entrySet().removeIf(entry -> !active.contains(entry.getKey()));
        connections.snapshotFrames.retainRooms(active);
        // Xóa snapshot chờ nếu room/runtime đã đóng; không cắt ngang frame đang ghi.
        for (ClientSession session : connections.getSessions()) { session.discardInvalidGameState(); }
    }
    @Override public void close() {
        closed.set(true);
        connections.snapshotFrames.clear();
        synchronized (this) { cursors.clear(); }
        for (ClientSession session : connections.getSessions()) { session.discardInvalidGameState(); }
    }
    private static final class Cursor {
        final MatchRuntime runtime;
        long lastTick;
        boolean terminal;
        boolean startPublished;
        // Cursor gắn đúng runtime; terminal gate ngăn enqueue lặp cho từng session trong lifecycle này.
        final java.util.Set<ClientSession> endRecipients = new HashSet<>();
        Cursor(MatchRuntime runtime) { this.runtime = runtime; }
    }
    private java.util.List<ClientSession> recipients(RecipientIndex index,String room) {
        return index==null?connections.getSessions():index.forRoom(room);
    }
    private final class RecipientIndex {
        private Map<String,java.util.List<ClientSession>> byRoom;
        java.util.List<ClientSession> forRoom(String room) {
            if(byRoom==null) {
                byRoom=new HashMap<>();
                for(var session:connections.getSessions()) {
                    String id=session.getRoomId(); if(id!=null) { byRoom.computeIfAbsent(id,k->new java.util.ArrayList<>(4)).add(session); }
                }
            }
            return byRoom.getOrDefault(room,java.util.List.of());
        }
    }
}
