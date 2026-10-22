package com.smashprofs.server.cluster;

import java.util.*;

/** Control-plane only; caller serializes selection and reservation in one critical section. */
public final class RoomAllocator {
    public enum Algorithm { ROUND_ROBIN, LOAD_AWARE }
    public record Capacity(String id, int rooms, int maxRooms, int connections, int maxConnections,
                           double cpu, double tickP99Ms, double heapRatio, int outboundDepth) {
        public boolean accepts() {
            return rooms < maxRooms && connections + 4 <= maxConnections && cpu < .90
                    && tickP99Ms < 16.666667 && heapRatio < .90 && outboundDepth < Math.max(8, connections * 2);
        }
    }
    private final Algorithm algorithm;
    private final double[] weights;
    private long cursor;
    public RoomAllocator(Algorithm algorithm, double... weights) {
        this.algorithm = Objects.requireNonNull(algorithm);
        this.weights = weights.length == 0 ? new double[]{.20, .25, .20, .10, .15, .10} : weights.clone();
        if (this.weights.length != 6 || Arrays.stream(this.weights).anyMatch(w -> !Double.isFinite(w) || w < 0)
                || Arrays.stream(this.weights).sum() == 0) { throw new IllegalArgumentException("Six non-negative weights required"); }
    }
    public synchronized Capacity select(List<Capacity> workers) {
        var eligible = workers.stream().filter(Capacity::accepts).sorted(Comparator.comparing(Capacity::id)).toList();
        if (eligible.isEmpty()) { throw new HttpJson.Failure(503, "NO_CAPACITY"); }
        if (algorithm == Algorithm.ROUND_ROBIN) { return eligible.get((int) Math.floorMod(cursor++, eligible.size())); }
        return eligible.stream().min(Comparator.comparingDouble(this::score).thenComparing(Capacity::id)).orElseThrow();
    }
    public double score(Capacity w) {
        double[] features = {Math.max(0, w.cpu), w.tickP99Ms / 16.666667, (double) w.rooms / w.maxRooms,
                (double) w.connections / w.maxConnections, w.heapRatio, (double) w.outboundDepth / Math.max(1, w.connections)};
        double score = 0; for (int i = 0; i < 6; i++) { score += features[i] * weights[i]; } return score;
    }
}
