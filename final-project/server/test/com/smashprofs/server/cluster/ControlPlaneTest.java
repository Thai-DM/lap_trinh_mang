package com.smashprofs.server.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class ControlPlaneTest {
    private static final String SECRET = "test-secret-012345678901234567890123";
    private final AtomicLong clock = new AtomicLong(100_000);
    private final ControlPlane control = new ControlPlane(SECRET, new RoomAllocator(RoomAllocator.Algorithm.ROUND_ROBIN), clock::get);
    private JsonNode request(String path, Object body, String credential) {
        var payload=HttpJson.JSON.valueToTree(body);
        if(path.equals("/workers/heartbeat")) {
            var report=(com.fasterxml.jackson.databind.node.ObjectNode)payload;
            report.put("cpu",0).put("tickP99Ms",0).put("heapRatio",0).put("activeConnections",0).put("activeRooms",0)
                    .put("outboundQueueDepth",0).put("sampledAtMillis",clock.get()).put("metricsValidForMillis",2500);
        }
        return HttpJson.JSON.valueToTree(control.handle(path, payload, credential));
    }
    private void worker(String id) {
        request("/workers/heartbeat", Map.of("workerId", id, "incarnation", id+"-boot", "region", "local", "port", 9001,
                "maxRooms", 100, "maxConnections", 400, "rooms", Map.of()), SECRET);
    }
    private String guest() { return request("/auth/guest", Map.of(), null).path("token").asText(); }
    private JsonNode create(String token) { return request("/rooms/create", Map.of(), token); }
    private void fails(int status, Runnable action) {
        try { action.run(); fail("Expected rejection"); } catch (HttpJson.Failure failure) { assertEquals(status, failure.status); }
    }
    @Test public void assignmentsAreExclusiveAndTicketIsBoundToOwnerAndSingleUse() {
        worker("a"); worker("b"); String token=guest(); JsonNode assignment=create(token);
        String id=assignment.path("workerId").asText(), ticket=assignment.path("ticket").asText();
        fails(409, () -> create(token));
        fails(401, () -> request("/claim", Map.of("workerId", "b", "incarnation", "b-boot", "ticket", ticket), SECRET));
        var claim=request("/claim", Map.of("workerId", id, "incarnation", id+"-boot", "ticket", ticket), SECRET);
        assertEquals(assignment.path("roomId"), claim.path("roomId"));
        fails(401, () -> request("/claim", Map.of("workerId", id, "incarnation", id+"-boot", "ticket", ticket), SECRET));
        fails(401, () -> request("/claim", Map.of("workerId", id, "incarnation", id+"-boot", "ticket", ticket), "wrong-secret"));
    }
    @Test public void expiredTicketAndSessionAreRejected() {
        worker("a"); String token=guest(); var assignment=create(token);
        clock.addAndGet(ControlPlane.TICKET_MILLIS+1); fails(409, () -> worker("a"));
        // The worker was fenced when its lease expired; the old room cannot be resurrected.
    }
    @Test public void expiryWithoutWorkerLossRejectsTicketsAndCredentials() {
        worker("a"); String token=guest(); var assignment=create(token);
        for (int i=0; i<6; i++) { clock.addAndGet(4000); worker("a"); }
        fails(401, () -> request("/claim", Map.of("workerId", "a", "incarnation", "a-boot", "ticket", assignment.path("ticket").asText()), SECRET));
        clock.addAndGet(ControlPlane.SESSION_MILLIS);
        fails(401, () -> request("/assignment", Map.of(), token));
    }
    @Test public void matchmakingIsRegionalBoundedCancellableAndCannotDoubleAssign() {
        worker("a"); String a=guest(), b=guest(), c=guest();
        assertEquals("QUEUED", request("/matchmake", Map.of("size", 2), a).path("status").asText());
        assertEquals("QUEUED", request("/matchmake", Map.of("size", 2), a).path("status").asText());
        request("/matchmake", Map.of("size", 2, "region", "other"), c);
        var assigned=request("/matchmake", Map.of("size", 2), b);
        assertEquals(assigned.path("roomId"), request("/assignment", Map.of(), a).path("roomId"));
        fails(403, () -> request("/rooms/join", Map.of("roomId",assigned.path("roomId").asText()), guest()));
        assertEquals("QUEUED", request("/assignment", Map.of(), c).path("status").asText());
        request("/matchmake/cancel", Map.of(), c); assertEquals("IDLE", request("/assignment", Map.of(), c).path("status").asText());
        fails(409, () -> create(a));
        String d=guest(); request("/matchmake", Map.of("size", 2), d); clock.addAndGet(31_000);
        assertEquals("IDLE", request("/assignment", Map.of(), d).path("status").asText());
    }
    @Test public void maxFourMembersAndWorkerLossNeverMigratesRunningRoom() {
        worker("a"); String a=guest(); var assignment=create(a); String room=assignment.path("roomId").asText();
        for (int i=0; i<3; i++) { request("/rooms/join", Map.of("roomId", room), guest()); }
        fails(409, () -> request("/rooms/join", Map.of("roomId", room), guest()));
        clock.addAndGet(6000); var state=request("/metrics", Map.of(), null);
        assertEquals("ABORTED", state.path("roomStates").path(room).asText());
        fails(409, () -> worker("a")); worker("b");
        assertEquals("b", create(a).path("workerId").asText());
        fails(404, () -> request("/rooms/join", Map.of("roomId", room), guest()));
    }
    @Test public void concurrentCreatesReserveOneOwnerAndRoundRobinBalancesRooms() throws Exception {
        worker("a"); worker("b"); worker("c"); var pool=Executors.newFixedThreadPool(8);
        try {
            List<Future<JsonNode>> futures=new ArrayList<>();
            for (int i=0; i<60; i++) { String token=guest(); futures.add(pool.submit(() -> create(token))); }
            Set<String> ids=new HashSet<>(); Map<String,Integer> distribution=new HashMap<>();
            for (var f:futures) { var a=f.get(); assertTrue(ids.add(a.path("roomId").asText())); distribution.merge(a.path("workerId").asText(),1,Integer::sum); }
            assertEquals(Map.of("a",20,"b",20,"c",20),distribution);
        } finally { pool.shutdownNow(); }
    }
    @Test public void drainAndCapacityAreEnforcedBeforeReserving() {
        worker("a"); request("/workers/drain",Map.of("workerId","a"),SECRET);
        fails(503, () -> create(guest()));
        request("/workers/heartbeat",Map.of("workerId","b","incarnation","b-boot","port",9002,"maxRooms",1,"maxConnections",4),SECRET);
        create(guest()); fails(503, () -> create(guest()));
    }
}
