package com.smashprofs.server.game;

import com.smashprofs.common.model.*;
import com.smashprofs.common.protocol.*;
import com.smashprofs.game.network.client.*;
import com.smashprofs.server.network.GameServer;
import com.smashprofs.server.network.HeartbeatConfiguration;
import com.smashprofs.server.game.physics.HeadlessPhysicsWorld;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import static org.junit.Assert.*;

/** Real GameServer and TCP clients; only the scheduler/clock is manual, so native mutations stay on its actual owner thread. */
public class GameplayTcpIntegrationTest {
    private static void until(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+5_000_000_000L;
        while(!condition.getAsBoolean()) { if(System.nanoTime()>deadline) { fail("TCP client did not reach expected state"); }Thread.sleep(2); }
    }
    private static LobbyClient client(GameServer server,String room,boolean create) {
        var client=new LobbyClient(new LobbyConfiguration("127.0.0.1",server.getBoundPort(),room,create?LobbyConfiguration.Mode.CREATE:LobbyConfiguration.Mode.JOIN));
        client.enableGameplay();assertTrue(client.startAsync());return client;
    }
    @Test(timeout=60000) public void twoThreeAndFourClientsSeeHostRulesRespawnSpectatorReliableEventsAndIdenticalResults() throws Exception {
        for(int count=2;count<=4;count++) { play(count); }
    }
    @Test(timeout=30000) public void concurrentRoomsKeepRulesEventsStocksAndNativeCleanupIsolated() throws Exception {
        int baseline=HeadlessPhysicsWorld.liveWorldCount();var clock=new AtomicLong(10_000_000_000L);var executor=new ManualScheduledExecutor();
        var server=new GameServer(()->new FixedTickScheduler(executor,Logger.getLogger("two-gameplay-rooms")),clock::get);
        var all=new ArrayList<LobbyClient>();server.start();
        try {
            var a=client(server,"room_a",true);all.add(a);until(()->a.getSnapshot().canReady());
            var ap=client(server,"room_a",false);all.add(ap);until(()->ap.getSnapshot().canReady());
            var b=client(server,"room_b",true);all.add(b);until(()->b.getSnapshot().canReady());
            var bp=client(server,"room_b",false);all.add(bp);until(()->bp.getSnapshot().canReady());
            executor.runCycle();until(()->a.getSettings()!=null && b.getSettings()!=null);
            a.updateSettings(new MatchRules(2,0,1,MatchRules.DropRate.HIGH,true));b.updateSettings(new MatchRules(2,90,5,MatchRules.DropRate.NONE,true));
            until(()->a.getSnapshot().canReady() && b.getSnapshot().canReady() && a.getSettings().rules().lives()==1 && b.getSettings().rules().lives()==5);
            for(var c:all) { c.ready("Luca"); }until(()->server.getRoomManager().getRoom("room_a").canStartMatch() && server.getRoomManager().getRoom("room_b").canStartMatch());
            executor.runCycle();clock.addAndGet(3_000_000_000L);executor.runCycle();executor.runCycle();executor.runCycle();
            until(()->all.stream().allMatch(c->c.getMatchSession()!=null && c.getMatchSession().getSnapshotBuffer().getLatestSnapshot()!=null));
            assertEquals(baseline+2,HeadlessPhysicsWorld.liveWorldCount());var ar=server.getRoomManager().getRoom("room_a").getMatchRuntime();
            ar.getMatchState().getPlayer(a.getSnapshot().localPlayerId()).applyDamage(100);for(int i=0;i<3;i++) { executor.runCycle(); }
            until(()->a.getMatchSession().getResult()!=null && ap.getMatchSession().getResult()!=null);
            assertEquals(0,b.getMatchSession().getKillFeed().sequence());assertNull(b.getMatchSession().getResult());
            assertEquals(5,b.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().player(b.getSnapshot().localPlayerId().value()).lives());
            assertEquals(baseline+1,HeadlessPhysicsWorld.liveWorldCount());
            a.closeAndWait(5000);ap.closeAndWait(5000);until(()->!server.getRoomManager().containsRoom("room_a"));
            assertTrue(server.getRoomManager().containsRoom("room_b"));
        } finally { for(var c:all) { c.closeAndWait(5000); }server.stop(); }
        assertEquals(baseline,HeadlessPhysicsWorld.liveWorldCount());
    }
    private void play(int count) throws Exception {
        int worlds=HeadlessPhysicsWorld.liveWorldCount();var clock=new AtomicLong(10_000_000_000L);var executor=new ManualScheduledExecutor();
        var server=new GameServer(()->new FixedTickScheduler(executor,Logger.getLogger("gameplay-tcp")),clock::get,
                new HeartbeatConfiguration(java.time.Duration.ofMillis(50),java.time.Duration.ofMillis(500)),System::nanoTime,java.util.concurrent.Executors::newSingleThreadScheduledExecutor);
        var clients=new ArrayList<LobbyClient>();
        server.start();String room="gameplay_"+count;
        try {
            var host=client(server,room,true);clients.add(host);until(()->host.getSnapshot().canReady());
            executor.runCycle();until(()->host.getSettings()!=null);
            assertTrue(host.updateSettings(new MatchRules(count,0,3,MatchRules.DropRate.HIGH,true)));
            until(()->host.getSnapshot().canReady() && host.getSettings().rules().matchSeconds()==0);
            for(int i=1;i<count;i++) { var peer=client(server,room,false);clients.add(peer);until(()->peer.getSnapshot().canReady()); }
            executor.runCycle();until(()->clients.stream().allMatch(c->c.getSettings()!=null && c.getSettings().playerCount()==count));
            assertEquals(host.getSettings().rules(),clients.get(1).getSettings().rules());
            var peer=clients.get(1);var attack=ProtocolV1.object().put("roomId",room);attack.set("rules",GameplayProtocol.rules(MatchRules.DEFAULT));
            assertTrue(peer.getNetworkClient().sendMessage(new ProtocolMessage(MessageType.ROOM_SETTINGS_UPDATE,"intruder",attack)));
            until(()->"UNAUTHORIZED_PLAYER".equals(peer.getSnapshot().errorCode()));assertEquals(count,server.getRoomManager().getRoom(room).getRules().maxPlayers());
            for(var c:clients) { assertTrue(c.ready("Luca")); }
            until(()->server.getRoomManager().getRoom(room).canStartMatch());executor.runCycle();clock.addAndGet(3_000_000_000L);executor.runCycle();
            for(int i=0;i<2;i++) { executor.runCycle(); }
            until(()->clients.stream().allMatch(c->c.getMatchSession()!=null && c.getMatchSession().getSnapshotBuffer().getLatestSnapshot()!=null));
            var runtime=server.getRoomManager().getRoom(room).getMatchRuntime();var owner=host.getSnapshot().localPlayerId();
            long rttDeadline=System.nanoTime()+3_000_000_000L;
            while(clients.stream().anyMatch(c->c.getMatchSession().getPings().values().stream().noneMatch(p->p.milliseconds()!=null))) {
                if(System.nanoTime()>rttDeadline) { fail("Real heartbeat RTT was not published"); }executor.runCycle();Thread.sleep(10);
            }
            assertTrue(host.getMatchSession().getPings().get(owner.value()).milliseconds()>=0);
            for(var c:clients) { assertEquals(3,c.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().player(owner.value()).lives()); }
            assertTrue(host.recordFrameInput(0,false,false,false,false,true));until(()->runtime.getPendingInputCount()>0);
            for(int i=0;i<3;i++) { executor.runCycle(); }
            until(()->host.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().player(owner.value()).ultimateTicks()>0);
            // Server-only death fixture, never a client damage/HP message. The JUnit thread is this scheduler's owner.
            runtime.getMatchState().getPlayer(owner).applyDamage(100);executor.runCycle();
            for(int i=0;i<2;i++) { executor.runCycle(); }
            until(()->host.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().player(owner.value()).status().equals("RESPAWNING"));
            long received=server.getConnectionManager().receivedMessages.sum();
            assertTrue(host.getNetworkClient().sendMessage(new ProtocolMessage(MessageType.PLAYER_INPUT,null,ProtocolV1.inputPayload(new PlayerInput(owner,10000,10000,1,true,false,true,false,true)))));
            until(()->server.getConnectionManager().receivedMessages.sum()>received);
            assertEquals(0,runtime.getPendingInputCount());assertNull(host.getSnapshot().errorCode());
            for(int i=0;i<90;i++) { executor.runCycle(); }
            until(()->host.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().player(owner.value()).status().equals("ACTIVE"));
            assertTrue(host.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().player(owner.value()).invulnerableTicks()>0);
            for(int stock=0;stock<2;stock++) {
                runtime.getMatchState().getPlayer(owner).applyDamage(100);executor.runCycle();
                for(int i=0;i<(stock==0?92:2);i++) { executor.runCycle(); }
            }
            until(()->host.getMatchSession().isSpectating());assertFalse(host.recordFrameInput(1,true,false,true,false,true));
            if(count>2) {
                assertFalse(runtime.isFinished());long before=host.getMatchSession().getSnapshotBuffer().getLatestSnapshot().serverTick();
                for(int i=0;i<6;i++) { executor.runCycle(); }
                until(()->host.getMatchSession().getSnapshotBuffer().getLatestSnapshot().serverTick()>before);
                for(int stock=0;stock<3;stock++) {
                    for(int i=1;i<count-1;i++) { runtime.getMatchState().getPlayer(clients.get(i).getSnapshot().localPlayerId()).applyDamage(100); }
                    executor.runCycle();for(int tick=0;tick<(stock<2?92:2);tick++) { executor.runCycle(); }
                }
            }
            until(()->clients.stream().allMatch(c->c.getMatchSession().getResult()!=null));
            var expected=host.getMatchSession().getResult();assertNotNull(expected.gameplay());
            assertEquals(clients.get(count-1).getSnapshot().localPlayerId().value(),expected.winnerId());
            for(var c:clients) { assertEquals(expected,c.getMatchSession().getResult());assertEquals(runtime.getEventsAfter(0).size(),c.getMatchSession().getKillFeed().sequence()); }
            assertTrue(runtime.isPhysicsDisposed());assertEquals(worlds,HeadlessPhysicsWorld.liveWorldCount());
            server.getRoomManager().removeRoom(room);assertEquals(worlds,HeadlessPhysicsWorld.liveWorldCount());
        } finally { for(var c:clients) { c.closeAndWait(5000); }server.stop(); }
        assertEquals(worlds,HeadlessPhysicsWorld.liveWorldCount());
    }
}
