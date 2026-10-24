package com.smashprofs.server.metrics;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Bounded rolling cycle histogram, including lifecycle and snapshot publication costs. */
public final class ServerMetrics {
    public final PerformanceMetrics performance = new PerformanceMetrics();
    private long windowStarted = System.nanoTime();
    public synchronized void resetMeasurement() {
        Arrays.fill(durations,0); cursor=0;count=0;cycles=0;overruns=0;total=0; errors.reset();
        windowStarted=System.nanoTime(); performance.reset();
    }
    private final long[] durations = new long[4096];
    private int cursor, count;
    private long cycles, overruns, total;
    public final LongAdder errors = new LongAdder();
    public synchronized void recordCycle(long nanos) {
        durations[cursor++ % durations.length] = nanos;
        if (cursor == durations.length) { cursor = 0; }
        count = Math.min(count + 1, durations.length);
        cycles++; total += nanos;
        if (nanos > 1_000_000_000L / 60) { overruns++; }
        performance.record(PerformanceMetrics.Stage.WORKER_CYCLE,nanos);
    }
    public synchronized Map<String, Object> snapshot() {
        long[] sorted = Arrays.copyOf(durations, count); Arrays.sort(sorted);
        var result = new LinkedHashMap<String, Object>();
        result.put("cycles", cycles); result.put("tickOverruns", overruns);
        result.put("tickMeanMs", cycles == 0 ? 0 : total / (double) cycles / 1e6);
        result.put("tickP95Ms", percentile(sorted, .95)); result.put("tickP99Ms", percentile(sorted, .99));
        result.put("cycleP95Ms",percentile(sorted,.95)); result.put("cycleP99Ms",percentile(sorted,.99));
        result.put("cycleHz",cycles/Math.max(.000001,(System.nanoTime()-windowStarted)/1e9));
        result.put("performance",performance.snapshot()); result.put("errors", errors.sum()); return result;
    }
    private static double percentile(long[] values, double p) {
        return values.length == 0 ? 0 : values[(int) Math.ceil(values.length * p) - 1] / 1e6;
    }
}
