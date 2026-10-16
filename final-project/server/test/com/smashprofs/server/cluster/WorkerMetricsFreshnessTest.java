package com.smashprofs.server.cluster;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class WorkerMetricsFreshnessTest {
    @Test public void missingOrStaleMetricsCannotAdmitEvenWhenHeartbeatIsAlive() {
        String secret="test-secret-012345678901234567890123"; var clock=new AtomicLong(10000);
        var cp=new ControlPlane(secret,new RoomAllocator(RoomAllocator.Algorithm.LOAD_AWARE),clock::get);
        var report=HttpJson.object().put("workerId","w").put("incarnation","boot").put("port",9001).put("maxRooms",10).put("maxConnections",40);
        cp.handle("/workers/heartbeat",report,secret);
        var token=HttpJson.JSON.valueToTree(cp.handle("/auth/guest",HttpJson.object(),null)).path("token").asText();
        reject(()->cp.handle("/rooms/create",HttpJson.object(),token));
        report.put("cpu",0).put("tickP99Ms",1).put("heapRatio",.1).put("activeConnections",0).put("activeRooms",0)
                .put("outboundQueueDepth",0).put("sampledAtMillis",10000).put("metricsValidForMillis",500);
        clock.set(11000); cp.handle("/workers/heartbeat",report,secret); reject(()->cp.handle("/rooms/create",HttpJson.object(),token));
        clock.set(14500); report.put("sampledAtMillis",14500); cp.handle("/workers/heartbeat",report,secret);
        assertNotNull(cp.handle("/rooms/create",HttpJson.object(),token));
    }
    private void reject(Runnable request) {
        try { request.run(); fail("Expected NO_CAPACITY"); } catch(HttpJson.Failure expected) { assertEquals(503,expected.status); }
    }
}
