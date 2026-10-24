package com.smashprofs.server.metrics;

import java.lang.management.*;
import java.util.*;

/** JVM accounting only. RSS is collected externally; external Box2D malloc is not heap/direct-buffer usage. */
public final class JvmMetrics {
    private JvmMetrics() { }
    public static Map<String,Object> sample() {
        var result=new LinkedHashMap<String,Object>(); var memory=ManagementFactory.getMemoryMXBean();
        var threads=ManagementFactory.getThreadMXBean(); var heap=memory.getHeapMemoryUsage();
        result.put("heapCommittedBytes",heap.getCommitted()); result.put("nonHeapUsedBytes",memory.getNonHeapMemoryUsage().getUsed());
        result.put("liveThreads",threads.getThreadCount()); result.put("peakThreads",threads.getPeakThreadCount());
        if(threads instanceof com.sun.management.ThreadMXBean extended && extended.isThreadAllocatedMemorySupported()) {
            if(!extended.isThreadAllocatedMemoryEnabled()) { extended.setThreadAllocatedMemoryEnabled(true); }
            long allocated=0; for(long n:extended.getThreadAllocatedBytes(threads.getAllThreadIds())) { if(n>0) { allocated+=n; } }
            result.put("allocatedLiveThreadBytes",allocated);
        }
        for(var pool:ManagementFactory.getMemoryPoolMXBeans()) {
            if(pool.getName().equals("Metaspace")) { result.put("metaspaceUsedBytes",pool.getUsage().getUsed()); }
        }
        for(var pool:ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            result.put(pool.getName()+"BufferBytes",pool.getMemoryUsed()); result.put(pool.getName()+"BufferCount",pool.getCount());
        }
        result.put("liveBox2dWorlds",com.smashprofs.server.game.physics.HeadlessPhysicsWorld.liveWorldCount());
        result.put("sampledAtMillis",System.currentTimeMillis()); result.put("metricsValidForMillis",2500);
        return result;
    }
}
