package com.smashprofs.server.network;

/** Rates divide 60. Three pressure observations reduce rate; ten healthy observations restore it. */
public final class AdaptiveSnapshotPolicy {
    private final int baseline, minimum;
    private int rate, pressure, healthy;
    public AdaptiveSnapshotPolicy(int baseline, int minimum) {
        StateBroadcaster.validateRate(baseline); StateBroadcaster.validateRate(minimum);
        if (minimum > baseline) { throw new IllegalArgumentException("minimum > baseline"); }
        this.baseline = baseline; this.minimum = minimum; rate = baseline;
    }
    public synchronized int observe(int clients, int queueDepth, long sendNanos, double cycleP99Ms) {
        boolean overloaded = queueDepth > Math.max(1, clients / 2) || sendNanos > 50_000_000L || cycleP99Ms > 12;
        if (overloaded) { healthy = 0; pressure++; }
        else { pressure = 0; healthy++; }
        if (pressure >= 3 && rate > minimum) {
            for (int next = rate - 1; next >= minimum; next--) {
                if (60 % next == 0) { rate = next; break; }
            }
            pressure = 0;
        } else if (healthy >= 10 && rate < baseline) {
            for (int next = rate + 1; next <= baseline; next++) {
                if (60 % next == 0) { rate = next; break; }
            }
            healthy = 0;
        }
        return rate;
    }
    public synchronized int rate() { return rate; }
}
