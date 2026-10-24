package com.smashprofs.server.cluster;

import com.smashprofs.server.metrics.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PerformanceMetricsTest {
    @Test public void wholeWindowQuantilesHaveBoundedErrorAndExactBudgetCounts() {
        var histogram=new PerformanceMetrics.Histogram(16_666_666);
        for(int i=1;i<=10000;i++) { histogram.record(i); } histogram.record(40_000_000);
        var stats=histogram.snapshot(); double p99=((Number)stats.get("p99Ms")).doubleValue()*1e6;
        assertEquals(10001L,stats.get("count")); assertEquals(1L,stats.get("budgetExceeds"));
        assertTrue(p99>=9901 && p99<=9901*1.016); assertEquals(40.0,((Number)stats.get("maxMs")).doubleValue(),0);
        histogram.reset(); assertEquals(0L,histogram.snapshot().get("count"));
    }
    @Test public void resetSeparatesMeasurementFromWarmupWithoutChangingSimulation() {
        var metrics=new ServerMetrics(); metrics.recordCycle(100_000_000); metrics.performance.snapshotEncodes.increment();
        metrics.resetMeasurement(); metrics.recordCycle(1_000_000);
        var snapshot=metrics.snapshot(); assertEquals(1L,snapshot.get("cycles")); assertEquals(0L,snapshot.get("tickOverruns"));
        assertEquals(0,metrics.performance.snapshotEncodes.sum());
    }
}
