package com.smashprofs.server.network;

import com.smashprofs.common.protocol.ProtocolV1;
import com.smashprofs.server.metrics.PerformanceMetrics;
import java.io.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** One most-recent immutable frame per room. Encoding runs on writers, never on simulation. */
final class SharedSnapshotCache {
    private static final int MAX_ROOMS=1024;
    private final ConcurrentHashMap<String,Frame> latest=new ConcurrentHashMap<>();
    private boolean closed;
    Frame frame(GameStateDelivery delivery) {
        String room=delivery.snapshot().roomId();
        Frame candidate;
        synchronized(latest) {
            if(closed || (latest.size()>=MAX_ROOMS && !latest.containsKey(room))) { return new Frame(delivery); }
            Frame current=latest.get(room);
            if(current!=null && current.matches(delivery)) { return current; }
            if(current!=null && current.sameMatch(delivery) && current.delivery.snapshot().serverTick()>delivery.snapshot().serverTick()) { return new Frame(delivery); }
            candidate=new Frame(delivery); latest.put(room,candidate);
        }
        // A lagging writer never replaces the newer cached frame with an old tick.
        return candidate.matches(delivery)?candidate:new Frame(delivery);
    }
    void retainRooms(Set<String> active) { latest.keySet().removeIf(id -> !active.contains(id)); }
    void clear() { synchronized(latest) { closed=true; latest.clear(); } }
    int size() { return latest.size(); }
    static final class Frame {
        private final GameStateDelivery delivery;
        private byte[] bytes;
        Frame(GameStateDelivery source) {
            // Do not retain the per-recipient guard/session/room object in a shared cache.
            delivery=new GameStateDelivery(source.snapshot(),source.phase(),source.remainingTimeMs(),()->true);
        }
        boolean sameMatch(GameStateDelivery other) { return delivery.snapshot().matchId().equals(other.snapshot().matchId()); }
        boolean matches(GameStateDelivery other) {
            return delivery.snapshot()==other.snapshot() && delivery.phase().equals(other.phase()) && delivery.remainingTimeMs()==other.remainingTimeMs();
        }
        synchronized Frame encode(TcpMessageCodec codec,PerformanceMetrics metrics) throws IOException {
            if(bytes!=null) { metrics.snapshotCacheHits.increment(); return this; }
            long started=System.nanoTime();
            byte[] encoded=codec.encode(GameStateMessageMapper.map(delivery),ProtocolV1.MAX_FRAME_BYTES);
            // Only the final JsonTcpMessageCodec may use this path; it returns an owned fresh byte[].
            bytes=encoded; metrics.snapshotEncodes.increment();
            metrics.record(PerformanceMetrics.Stage.JSON_SERIALIZATION,System.nanoTime()-started); return this;
        }
        synchronized int size() { if(bytes==null) { throw new IllegalStateException("Not encoded"); } return bytes.length; }
        void writeTo(OutputStream output) throws IOException {
            byte[] owned;
            synchronized(this) { if(bytes==null) { throw new IllegalStateException("Not encoded"); } owned=bytes; }
            output.write(owned); // Socket output stream is not given ownership of the immutable frame.
        }
    }
}
