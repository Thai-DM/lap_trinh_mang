package com.smashprofs.tests.network;

import com.smashprofs.game.network.client.*;
import com.smashprofs.server.cluster.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Existing desktop lobby, input and snapshot parsers against the secured worker listener. */
public class ClusterLobbyIntegrationTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static void until(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<end) { if(condition.getAsBoolean()) { return; } Thread.sleep(10); }
        fail("Lobby did not reach expected state");
    }
    @Test(timeout=30000) public void clusterAssignmentReusesLobbyAndAuthoritativeClientMatchSession() throws Exception {
        String secret="client-integration-012345678901234567890";
        var control=new ControlPlane(secret,new RoomAllocator(RoomAllocator.Algorithm.LOAD_AWARE));
        try(var http=new HttpJson(0,control::handle)) {
            String url="http://127.0.0.1:"+http.port(); var p=new Properties();
            p.setProperty("worker.id","client-worker"); p.setProperty("tcp.port","0"); p.setProperty("admin.port","0");
            p.setProperty("control.url",url); p.setProperty("history.dir",temp.getRoot().toString());
            try(var worker=new WorkerRuntime(p,secret);
                var a=ClusterLobbyConnector.fromArguments(new String[]{"--control-url="+url,"--create-room"})) {
                String room=a.getSnapshot().roomId();
                try(var b=ClusterLobbyConnector.fromArguments(new String[]{"--control-url="+url,"--join-room","--room="+room})) {
                    assertTrue(a.startAsync()); assertTrue(b.startAsync());
                    until(() -> a.getSnapshot().canReady() && b.getSnapshot().canReady());
                    assertNotEquals(a.getSnapshot().localPlayerId(),b.getSnapshot().localPlayerId());
                    assertTrue(a.ready("Alex")); assertTrue(b.ready("Luca"));
                    until(() -> a.getSnapshot().isMatchStarted() && b.getSnapshot().isMatchStarted());
                    assertTrue(a.recordFrameInput(1,false,false,false,false,false));
                    until(() -> a.getMatchSession().getSnapshotBuffer().getLatestSnapshot()!=null
                            && b.getMatchSession().getSnapshotBuffer().getLatestSnapshot()!=null);
                    assertEquals(room,a.getMatchSession().getSnapshotBuffer().getLatestSnapshot().roomId());
                    assertEquals(2,b.getMatchSession().getSnapshotBuffer().getLatestSnapshot().players().size());
                }
            }
        }
    }
}
