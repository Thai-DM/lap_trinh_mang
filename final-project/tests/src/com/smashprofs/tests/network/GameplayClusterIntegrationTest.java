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
        verify(false);
    }
    @Test(timeout=30000) public void fighterCapabilityAndLoadingGateSurviveControlPlaneAssignmentAndWorkerClaim() throws Exception {
        verify(true);
    }
    private void verify(boolean fighter) throws Exception {
        verify(fighter,false);
    }
    @Test(timeout=30000) public void bossAssignmentNegotiationAndSnapshotReachWorkerClients() throws Exception { verify(true,true); }
    private void verify(boolean fighter,boolean boss) throws Exception {
        int worlds=HeadlessPhysicsWorld.liveWorldCount();String secret="gameplay-cluster-integration-secret-0123456789";
        var control=new ControlPlane(secret,new RoomAllocator(RoomAllocator.Algorithm.LOAD_AWARE));
        try(var http=new HttpJson(0,control::handle)) {
            var url="http://127.0.0.1:"+http.port();var config=new Properties();
            config.setProperty("worker.id","gameplay-worker");config.setProperty("tcp.port","0");config.setProperty("admin.port","0");config.setProperty("control.url",url);config.setProperty("history.dir",temp.getRoot().toString());
            try(var worker=new WorkerRuntime(config,secret);var host=ClusterLobbyConnector.fromArguments(new String[]{"--control-url="+url,"--create-room",boss?"--boss":fighter?"--fighter":"--gameplay"})) {
                String room=host.getSnapshot().roomId();
                try(var peer=ClusterLobbyConnector.fromArguments(new String[]{"--control-url="+url,"--join-room","--room="+room})) {
                    assertTrue(peer.supportsGameplay());peer.startAsync();until(()->peer.getSnapshot().canReady() && peer.getSettings()!=null);
                    assertFalse(peer.updateSettings(MatchRules.DEFAULT));
                    host.startAsync();until(()->host.getSnapshot().canReady() && host.getSettings()!=null);
                    assertEquals(host.getSnapshot().localPlayerId().value(),host.getSettings().hostId());
                    assertTrue(host.updateSettings(new MatchRules(3,90,2,MatchRules.DropRate.HIGH,true,fighter,boss)));
                    until(()->host.getSnapshot().canReady() && peer.getSettings().rules().lives()==2);
                    host.ready("Viktor");peer.ready("Luca");
                    if(fighter) {
                        until(()->host.getSnapshot().phase()==LobbyClient.Phase.WAITING_FOR_START && peer.getSnapshot().phase()==LobbyClient.Phase.WAITING_FOR_START);
                        assertTrue(host.markLoaded());assertTrue(peer.markLoaded());
                    }
                    until(()->host.getMatchSession()!=null && peer.getMatchSession()!=null && host.getMatchSession().getSnapshotBuffer().getLatestSnapshot()!=null && peer.getMatchSession().getSnapshotBuffer().getLatestSnapshot()!=null);
                    var state=host.getMatchSession().getSnapshotBuffer().getLatestSnapshot();assertNotNull(state.gameplay());
                    assertEquals(90,state.gameplay().rules().matchSeconds());assertEquals(2,state.gameplay().player(host.getSnapshot().localPlayerId().value()).lives());
                    assertEquals(state.gameplay().rules(),peer.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().rules());
                    assertEquals(fighter,state.gameplay().rules().fighter());
                    assertEquals(boss,state.gameplay().rules().boss());if(boss) { assertNotNull(state.gameplay().boss());assertTrue(state.gameplay().boss().hp()>0); }
                    if(fighter) { assertEquals(0,state.gameplay().fighter().player(host.getSnapshot().localPlayerId().value()).damagePercent(),0); }
                }
            }
        }
        assertEquals(worlds,HeadlessPhysicsWorld.liveWorldCount());
    }
}
