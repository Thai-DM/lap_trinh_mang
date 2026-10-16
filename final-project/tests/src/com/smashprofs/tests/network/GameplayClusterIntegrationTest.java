package com.smashprofs.tests.network;

import com.smashprofs.common.model.*;
import com.smashprofs.game.network.client.*;
import com.smashprofs.server.cluster.*;
import com.smashprofs.server.game.physics.HeadlessPhysicsWorld;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

public class GameplayClusterIntegrationTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static void until(BooleanSupplier ready) throws Exception {
        long deadline=System.nanoTime()+10_000_000_000L;
        while(!ready.getAsBoolean()) { if(System.nanoTime()>deadline) { fail("Cluster gameplay did not reach expected state"); }Thread.sleep(5); }
    }
    @Test(timeout=30000) public void creatorRemainsHostWhenPeerClaimsFirstAndGameplayRulesReachAllWorkerClients() throws Exception {
        int worlds=HeadlessPhysicsWorld.liveWorldCount();String secret="gameplay-cluster-integration-secret-0123456789";
        var control=new ControlPlane(secret,new RoomAllocator(RoomAllocator.Algorithm.LOAD_AWARE));
        try(var http=new HttpJson(0,control::handle)) {
            var url="http://127.0.0.1:"+http.port();var config=new Properties();
            config.setProperty("worker.id","gameplay-worker");config.setProperty("tcp.port","0");config.setProperty("admin.port","0");config.setProperty("control.url",url);config.setProperty("history.dir",temp.getRoot().toString());
            try(var worker=new WorkerRuntime(config,secret);var host=ClusterLobbyConnector.fromArguments(new String[]{"--control-url="+url,"--create-room","--gameplay"})) {
                String room=host.getSnapshot().roomId();
                try(var peer=ClusterLobbyConnector.fromArguments(new String[]{"--control-url="+url,"--join-room","--room="+room})) {
                    assertTrue(peer.supportsGameplay());peer.startAsync();until(()->peer.getSnapshot().canReady() && peer.getSettings()!=null);
                    assertFalse(peer.updateSettings(MatchRules.DEFAULT));
                    host.startAsync();until(()->host.getSnapshot().canReady() && host.getSettings()!=null);
                    assertEquals(host.getSnapshot().localPlayerId().value(),host.getSettings().hostId());
                    assertTrue(host.updateSettings(new MatchRules(3,90,2,MatchRules.DropRate.HIGH,true)));
                    until(()->host.getSnapshot().canReady() && peer.getSettings().rules().lives()==2);
                    host.ready("Viktor");peer.ready("Luca");
                    until(()->host.getMatchSession()!=null && peer.getMatchSession()!=null && host.getMatchSession().getSnapshotBuffer().getLatestSnapshot()!=null && peer.getMatchSession().getSnapshotBuffer().getLatestSnapshot()!=null);
                    var state=host.getMatchSession().getSnapshotBuffer().getLatestSnapshot();assertNotNull(state.gameplay());
                    assertEquals(90,state.gameplay().rules().matchSeconds());assertEquals(2,state.gameplay().player(host.getSnapshot().localPlayerId().value()).lives());
                    assertEquals(state.gameplay().rules(),peer.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().rules());
                }
            }
        }
        assertEquals(worlds,HeadlessPhysicsWorld.liveWorldCount());
    }
}
