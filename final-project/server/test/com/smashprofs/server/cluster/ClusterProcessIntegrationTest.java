package com.smashprofs.server.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.smashprofs.common.protocol.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

public class ClusterProcessIntegrationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String SECRET="integration-secret-01234567890123456789";
    private final List<Process> children=new ArrayList<>();
    private final Map<String,Process> byId=new HashMap<>();
    private HttpJson http; private String url;
    private JsonNode call(String path,Object body,String token) throws Exception { return HttpJson.call(url,path,body,token); }
    private void until(BooleanSupplier condition,long millis) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
        while(System.nanoTime()<deadline) { if(condition.getAsBoolean()) { return; } Thread.sleep(50); }
        fail("Timed out waiting for cluster condition");
    }
    private JsonNode metrics() { try { return call("/metrics",Map.of(),null); } catch(Exception e) { throw new AssertionError(e); } }
    private void startWorker(int number) throws Exception {
        String id="worker-"+number; Path file=temp.getRoot().toPath().resolve(id+".properties");
        var properties=new Properties(); properties.setProperty("worker.id",id); properties.setProperty("tcp.port","0");
        properties.setProperty("admin.port","0"); properties.setProperty("control.url",url); properties.setProperty("snapshot.mode","ADAPTIVE");
        properties.setProperty("history.dir",temp.getRoot().toPath().resolve("history").toString());
        try(var output=Files.newOutputStream(file)) { properties.store(output,""); }
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        var builder=new ProcessBuilder(java,"-Xmx256m","-cp",System.getProperty("cluster.classpath"),ClusterMain.class.getName(),"worker",file.toString());
        builder.environment().put("SMASHPROFS_CLUSTER_SECRET",SECRET);
        builder.redirectErrorStream(true); builder.redirectOutput(temp.getRoot().toPath().resolve(id+".log").toFile());
        Process process=builder.start(); children.add(process); byId.put(id,process);
    }
    @After public void cleanup() throws Exception {
        for(Process child:children) { child.destroy(); }
        for(Process child:children) { if(!child.waitFor(8,TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(3,TimeUnit.SECONDS); } }
        if(http!=null) { http.close(); }
    }
    @Test(timeout=90000) public void realWorkerProcessesServeV1RejectReplayAndFenceAfterControlLoss() throws Exception {
        var control=new ControlPlane(SECRET,new RoomAllocator(RoomAllocator.Algorithm.ROUND_ROBIN));
        http=new HttpJson(0,control::handle); url="http://127.0.0.1:"+http.port();
        for(int i=1;i<=3;i++) { startWorker(i); }
        until(() -> metrics().path("workers").size()==3,15000);
        var pids=new HashSet<Long>();
        for(var worker:metrics().path("workers")) { pids.add(worker.path("report").path("pid").asLong()); }
        assertEquals(3,pids.size()); assertFalse(pids.contains(ProcessHandle.current().pid()));
        var load=LoadGenerator.run(url,16,7);
        assertEquals(0,((Number)load.get("connectionFailures")).intValue()); assertEquals(0,((Number)load.get("protocolErrors")).intValue());
        assertEquals(16L,load.get("startedClients")); assertEquals(16L,load.get("clientsWithSnapshots"));
        assertEquals(3,((Set<?>)load.get("workersUsed")).size()); assertTrue(((Number)load.get("snapshotDeliveries")).longValue()>100);
        String token=call("/auth/guest",Map.of(),null).path("token").asText();
        var assignment=call("/rooms/create",Map.of(),token); int port=assignment.path("port").asInt();
        var codec=new JsonFrameCodec(); var payload=ProtocolV1.object().put("capability","cluster-v1").put("assignment",assignment.path("ticket").asText());
        try(var first=new Socket("127.0.0.1",port); var replay=new Socket("127.0.0.1",port)) {
            first.setSoTimeout(3000); replay.setSoTimeout(3000);
            first.getOutputStream().write(codec.encode(new ProtocolMessage(MessageType.CONNECT,"one",payload)));
            assertEquals(MessageType.CONNECT_ACK,codec.readNext(first.getInputStream()).type());
            replay.getOutputStream().write(codec.encode(new ProtocolMessage(MessageType.CONNECT,"replay",payload)));
            assertEquals(MessageType.ERROR,codec.readNext(replay.getInputStream()).type());
            first.getOutputStream().write(codec.encode(new ProtocolMessage(MessageType.JOIN_ROOM,"wrong",ProtocolV1.object().put("roomId","unassigned"))));
            assertEquals("UNAUTHORIZED_PLAYER",codec.readNext(first.getInputStream()).payload().path("code").asText());
        }
        try(var legacy=new Socket("127.0.0.1",port)) {
            legacy.setSoTimeout(3000); legacy.getOutputStream().write(codec.encode(new ProtocolMessage(MessageType.CONNECT,"legacy",ProtocolV1.object())));
            assertEquals("CLUSTER_CAPABILITY_REQUIRED",codec.readNext(legacy.getInputStream()).payload().path("code").asText());
        }
        byId.get(assignment.path("workerId").asText()).destroyForcibly().waitFor(5,TimeUnit.SECONDS);
        until(() -> {
            for(var worker:metrics().path("workers")) { if(worker.path("workerId").equals(assignment.path("workerId"))) { return worker.path("health").asText().equals("LOST"); } }
            return false;
        },8000);
        assertEquals("ABORTED",metrics().path("roomStates").path(assignment.path("roomId").asText()).asText());
        var next=call("/rooms/create",Map.of(),token); assertNotEquals(assignment.path("workerId"),next.path("workerId"));
        http.close(); http=null;
        until(() -> children.stream().noneMatch(Process::isAlive),10000);
    }
}
