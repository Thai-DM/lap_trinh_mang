package com.smashprofs.server.cluster;

import com.smashprofs.server.network.AdaptiveSnapshotPolicy;
import com.smashprofs.server.metrics.ServerMetrics;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ScalingPoliciesTest {
    @Test public void weightedAllocationAvoidsHotWorkerAndRejectsCapacity() {
        var allocator=new RoomAllocator(RoomAllocator.Algorithm.LOAD_AWARE);
        var hot=new RoomAllocator.Capacity("a",80,100,300,400,.8,14,.8,60);
        var cold=new RoomAllocator.Capacity("b",2,100,8,400,.1,1,.1,0);
        assertEquals("b",allocator.select(List.of(hot,cold)).id());
        assertFalse(new RoomAllocator.Capacity("full",100,100,0,400,0,0,0,0).accepts());
        assertFalse(new RoomAllocator.Capacity("slow",0,100,0,400,0,17,0,0).accepts());
        var cpuOnly=new RoomAllocator(RoomAllocator.Algorithm.LOAD_AWARE,1,0,0,0,0,0);
        assertTrue(cpuOnly.score(cold)<cpuOnly.score(hot));
    }
    @Test public void adaptiveUsesHysteresisAndStaysWithinBounds() {
        var policy=new AdaptiveSnapshotPolicy(20,5);
        for (int i=0;i<2;i++) { assertEquals(20,policy.observe(4,4,0,1)); }
        assertEquals(15,policy.observe(4,4,0,1));
        for (int i=0;i<100;i++) { policy.observe(4,4,0,1); }
        assertEquals(5,policy.rate());
        for (int i=0;i<9;i++) { assertEquals(5,policy.observe(4,0,0,1)); }
        assertEquals(6,policy.observe(4,0,0,1));
        for (int i=0;i<100;i++) { policy.observe(4,0,0,1); }
        assertEquals(20,policy.rate());
    }
    @Test public void metricsBoundedWindowReportsBudgetOverruns() {
        var metrics=new ServerMetrics(); for (int i=0;i<5000;i++) { metrics.recordCycle(1_000_000); }
        metrics.recordCycle(20_000_000);
        var snapshot=metrics.snapshot(); assertEquals(5001L,snapshot.get("cycles")); assertEquals(1L,snapshot.get("tickOverruns"));
        assertEquals(1.0,((Number)snapshot.get("tickP99Ms")).doubleValue(),0);
    }
    @Test public void autoscaleRequiresSustainedLoadCooldownAndDrainBeforeStop() throws Exception {
        var actions=new ArrayList<String>();
        var provisioner=new AutoScalingController.WorkerProvisioner() {
            public void start() { actions.add("start"); }
            public void drain(String id) { actions.add("drain:"+id); }
            public void stopEmpty(String id) { actions.add("stop:"+id); }
        };
        var scaler=new AutoScalingController(1,3,100);
        var hot=List.of(new AutoScalingController.WorkerLoad("a",.8,8,false));
        for(int i=0;i<3;i++) { scaler.reconcile(hot,1000,provisioner); }
        assertEquals(List.of("start"),actions); scaler.reconcile(hot,1050,provisioner); assertEquals(1,actions.size());
        var cold=List.of(new AutoScalingController.WorkerLoad("a",.1,1,false),new AutoScalingController.WorkerLoad("b",0,0,false));
        for(int i=0;i<10;i++) { scaler.reconcile(cold,1200,provisioner); }
        assertEquals("drain:b",actions.get(1));
        scaler.reconcile(List.of(cold.get(0),new AutoScalingController.WorkerLoad("b",0,1,true)),1400,provisioner); assertEquals(2,actions.size());
        scaler.reconcile(List.of(cold.get(0),new AutoScalingController.WorkerLoad("b",0,0,true)),1400,provisioner);
        assertEquals("stop:b",actions.get(2));
    }
}
