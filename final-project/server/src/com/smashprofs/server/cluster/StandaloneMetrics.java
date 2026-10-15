package com.smashprofs.server.cluster;

import com.smashprofs.server.network.*;
import java.lang.management.ManagementFactory;
import java.util.*;

/** Read-only benchmark adapter for the unchanged standalone transport. */
final class StandaloneMetrics {
    static Map<String,Object> capture(GameServer server) {
        var result = new LinkedHashMap<String,Object>(server.getMetrics().snapshot());
        result.putAll(com.smashprofs.server.metrics.JvmMetrics.sample());
        var roomTicks=new LinkedHashMap<String,Object>();
        for(var room:server.getRoomManager().getRooms()) { var runtime=room.getMatchRuntime(); if(runtime!=null) { roomTicks.put(room.getRoomId(),runtime.tickMetrics()); } }
        result.put("roomTicks",roomTicks);
        var c = server.getConnectionManager(); var sessions = c.getSessions(); var rooms = server.getRoomManager().getRooms();
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        result.put("activeConnections", sessions.size()); result.put("activeRooms", rooms.size());
        result.put("activePlayers", rooms.stream().mapToInt(r -> r.getPlayerCount()).sum());
        result.put("inputQueueDepth", rooms.stream().map(r -> r.getMatchRuntime()).filter(Objects::nonNull).mapToInt(r -> r.getPendingInputCount()).sum());
        result.put("outboundQueueDepth", sessions.stream().mapToInt(ClientSession::getOutboundQueueDepth).sum());
        result.put("cpu", Math.max(0,os.getProcessCpuLoad())); result.put("heapBytes",heap.getUsed()); result.put("heapRatio",heap.getUsed()/(double)heap.getMax());
        result.put("sentBytes",c.sentBytes.sum()); result.put("receivedBytes",c.receivedBytes.sum()); result.put("snapshotBytes",c.snapshotBytes.sum());
        result.put("sentMessages",c.sentMessages.sum()); result.put("receivedMessages",c.receivedMessages.sum()); result.put("networkErrors",c.errors.sum());
        result.put("gcCollectionMillis",ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(g -> Math.max(0,g.getCollectionTime())).sum());
        result.put("pid",ProcessHandle.current().pid()); result.put("snapshotHz",server.getBroadcaster().getSnapshotsPerSecond());
        return result;
    }
}
