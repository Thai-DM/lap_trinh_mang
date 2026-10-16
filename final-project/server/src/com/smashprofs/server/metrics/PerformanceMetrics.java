package com.smashprofs.server.metrics;

import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/** Bounded whole-window logarithmic histograms: quantile upper bounds, <=1.57% relative bin error. */
public final class PerformanceMetrics {
    public enum Stage {
        INPUT_VALIDATION, INPUT_QUEUE_WAIT, INPUT_DEQUEUE, INPUT_LOCK_WAIT, TICK_LOCK_WAIT,
        MOVEMENT, COMBAT, PROJECTILES, PHYSICS_STEP, COLLISION_CALLBACKS, POST_PHYSICS,
        SNAPSHOT_BUILD, SNAPSHOT_ENQUEUE, JSON_SERIALIZATION, NETWORK_WRITE, SEND_LOCK_WAIT,
        ROOM_TICK, WORKER_CYCLE, SCHEDULING_LAG, ROOM_SCHEDULING_DELAY
    }
    public static final PerformanceMetrics DISABLED = new PerformanceMetrics(false);
    public static final long TICK_BUDGET = 1_000_000_000L / 60;
    private final boolean enabled;
    private final Histogram[] histograms;
    public final LongAdder snapshotEncodes = new LongAdder(), snapshotCacheHits = new LongAdder();
    public final LongAdder snapshotReplacements = new LongAdder(), recipientVisits = new LongAdder();
    public final LongAdder rateLimitRejections = new LongAdder(), inputRejections = new LongAdder();
    private volatile long started = System.nanoTime(), startedMillis = System.currentTimeMillis();
    public PerformanceMetrics() { this(true); }
    private PerformanceMetrics(boolean enabled) {
        this.enabled = enabled; histograms = new Histogram[enabled ? Stage.values().length : 0];
        for (int i=0; i<histograms.length; i++) { histograms[i] = new Histogram(TICK_BUDGET); }
    }
    public void record(Stage stage, long nanos) { if (enabled) { histograms[stage.ordinal()].record(Math.max(0,nanos)); } }
    public synchronized void reset() {
        for (var histogram : histograms) { histogram.reset(); }
        snapshotEncodes.reset(); snapshotCacheHits.reset(); snapshotReplacements.reset(); recipientVisits.reset();
        rateLimitRejections.reset(); inputRejections.reset(); started=System.nanoTime(); startedMillis=System.currentTimeMillis();
    }
    public Map<String,Object> snapshot() {
        var stages = new LinkedHashMap<String,Object>();
        if (enabled) { for (var stage : Stage.values()) { stages.put(stage.name(),histograms[stage.ordinal()].snapshot()); } }
        return Map.of("windowStartedAtMillis",startedMillis,"windowSeconds",(System.nanoTime()-started)/1e9,
                "quantiles","whole window, histogram upper bounds; relative bin error <=1.57% above 64 ns",
                "stages",stages,"snapshotEncodes",snapshotEncodes.sum(),"snapshotCacheHits",snapshotCacheHits.sum(),
                "snapshotReplacements",snapshotReplacements.sum(),"recipientVisits",recipientVisits.sum(),
                "rateLimitRejections",rateLimitRejections.sum(),"inputRejections",inputRejections.sum());
    }
    public static final class Histogram {
        private final long[] bins = new long[4096];
        private final long budget;
        private long count,max,exceeded; private double sum;
        public Histogram(long budget) { this.budget=budget; }
        public synchronized void record(long nanos) {
            if (nanos<0) { throw new IllegalArgumentException("Negative duration"); }
            int e = nanos<64 ? 0 : 63-Long.numberOfLeadingZeros(nanos);
            int index = nanos<64 ? (int)nanos : (e-6)*64+(int)(nanos >>> (e-6));
            bins[index]++; count++; sum+=nanos; max=Math.max(max,nanos); if(nanos>budget) { exceeded++; }
        }
        public synchronized void reset() { Arrays.fill(bins,0); count=0;max=0;sum=0;exceeded=0; }
        public synchronized Map<String,Object> snapshot() {
            return Map.of("count",count,"meanMs",count==0?0:sum/count/1e6,"p50Ms",quantile(.50)/1e6,
                    "p95Ms",quantile(.95)/1e6,"p99Ms",quantile(.99)/1e6,"maxMs",max/1e6,
                    "budgetExceeds",exceeded,"budgetExceedRatio",count==0?0:exceeded/(double)count);
        }
        private long quantile(double p) {
            if(count==0) { return 0; } long rank=(long)Math.ceil(count*p), accumulated=0;
            for(int i=0;i<bins.length;i++) {
                accumulated+=bins[i]; if(accumulated<rank) { continue; }
                if(i<64) { return Math.min(i,max); }
                int shift=i/64-1; long normalized=64L+i%64;
                if(shift>=56 && normalized==127) { return max; }
                return Math.min(((normalized+1)<<shift)-1,max);
            }
            return max;
        }
    }
}
