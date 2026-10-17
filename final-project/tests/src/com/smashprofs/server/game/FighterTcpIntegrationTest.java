package com.smashprofs.server.game;

import com.smashprofs.common.model.*;
import com.smashprofs.game.network.client.*;
import com.smashprofs.server.network.GameServer;
import com.smashprofs.server.game.physics.HeadlessPhysicsWorld;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import static org.junit.Assert.*;

public class FighterTcpIntegrationTest {
    @Test public void nativePredictionMatchesAuthorityThroughLavaReboundAndRecovery() {
        var a=new PlayerId("a");var b=new PlayerId("b");
        var map=new com.smashprofs.server.game.physics.TmxCollisionLoader().loadOriginalMap();
        var geometry=map.rectangles().stream().map(p->new LocalFighterPrediction.Rect(p.xPixels()/100,p.yPixels()/100,p.widthPixels()/100,p.heightPixels()/100)).toList();
        try(var r=new MatchRuntime(new MatchSetup("lava","room",List.of(new MatchSetup.Participant(a,0,"Alex"),new MatchSetup.Participant(b,1,"Luca")),
                new MatchRules(2,0,3,MatchRules.DropRate.NONE,true,true)));var prediction=new LocalFighterPrediction(geometry)) {
            r.start();r.gameplay().bodies.respawn(a,2.5f,.7f);r.gameplay().fighter.physics.reset(a);r.tick();
            var message=com.smashprofs.server.network.GameStateMessageMapper.map(new com.smashprofs.server.network.GameStateDelivery(r.getLatestSnapshot(),"PLAYING",0,()->true));
            var base=WireGameSnapshot.parse(message.payload());var inputs=new ArrayList<PlayerInput>();
            for(int i=1;i<=15;i++) {
                var input=new PlayerInput(a,i,i,0,false,false,false,false,false,false,false,new FighterInput(true,false,false,false,false,false,false,false));
                inputs.add(input);r.submitInput(input);r.tick();
            }
            var shown=prediction.sample(base.player("a"),base.gameplay().fighter().player("a"),inputs,1/60f);
            var expected=r.gameplay().bodies.getPosition(a);
            assertEquals(expected.xMeters(),shown.x(),.00001f);assertEquals(expected.yMeters(),shown.y(),.00001f);
            assertEquals(0,r.getLatestSnapshot().gameplay().fighter().player("a").damagePercent(),0);assertEquals(90,r.gameplay().state.getPlayer(a).getHp(),0);
        }
    }
    private static void until(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+5_000_000_000L;
        while(!condition.getAsBoolean()) { if(System.nanoTime()>deadline) { fail("Fighter TCP timeout"); }Thread.sleep(2); }
    }
    @Test(timeout=60000) public void twoThreeFourClientsAgreeOnDamageKOStocksAndFreshRematches() throws Exception {
        for(int count=2;count<=4;count++) { play(count); }
    }
    private void play(int count) throws Exception {
        int baseline=HeadlessPhysicsWorld.liveWorldCount();var clock=new AtomicLong(10_000_000_000L);var executor=new ManualScheduledExecutor();
        var server=new GameServer(()->new FixedTickScheduler(executor,Logger.getLogger("fighter-tcp")),clock::get);
        var clients=new ArrayList<LobbyClient>();String room="fighter_"+count;server.start();
        try {
            for(int i=0;i<count;i++) {
                var client=new LobbyClient(new LobbyConfiguration("127.0.0.1",server.getBoundPort(),room,i==0?LobbyConfiguration.Mode.CREATE:LobbyConfiguration.Mode.JOIN));
                client.enableFighter();clients.add(client);assertTrue(client.startAsync());until(()->client.getSnapshot().canReady());
            }
            executor.runCycle();until(()->clients.stream().allMatch(c->c.getSettings()!=null && c.getSettings().playerCount()==count));
            assertTrue(clients.get(0).getSettings().rules().fighter());for(var c:clients) { assertTrue(c.ready("Alex")); }
            var gameRoom=server.getRoomManager().getRoom(room);until(gameRoom::canStartMatch);executor.runCycle();
            clock.addAndGet(4_000_000_000L);executor.runCycle();assertNull("A not-yet-loaded client must hold the countdown",gameRoom.getMatchRuntime());
            for(var c:clients) { assertTrue(c.markLoaded()); }until(()->gameRoom.getLoadedPlayerCount()==count);
            clock.addAndGet(3_000_000_000L);for(int i=0;i<4;i++) { executor.runCycle(); }
            until(()->clients.stream().allMatch(c->c.getMatchSession()!=null && c.getMatchSession().getSnapshotBuffer().getLatestSnapshot()!=null));
            var r=gameRoom.getMatchRuntime();var owner=clients.get(0).getSnapshot().localPlayerId();var victim=clients.get(1).getSnapshot().localPlayerId();
            assertEquals(baseline+1,HeadlessPhysicsWorld.liveWorldCount());
            // Same attack through real client input, frame data, native sensors, codec and all recipients.
            r.gameplay().bodies.respawn(owner,5.45f,.23f);r.gameplay().bodies.respawn(victim,5.72f,.23f);
            r.gameplay().fighter.physics.reset(owner);r.gameplay().fighter.physics.reset(victim);
            for(int i=0;i<10;i++) { executor.runCycle(); }
            assertTrue(clients.get(0).recordFrameInput(0,false,false,true,false,false));until(()->r.getPendingInputCount()>0);
            for(int i=0;i<9;i++) { executor.runCycle(); }
            double damage=r.getLatestSnapshot().gameplay().fighter().player(victim.value()).damagePercent();assertTrue(damage>0);
            until(()->clients.stream().allMatch(c->c.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().fighter().player(victim.value()).damagePercent()==damage));
            String oldId=r.getMatchId();
            // Every losing participant exhausts three stocks. Platform invulnerability does not block a blast KO.
            for(int stock=0;stock<3;stock++) {
                for(int i=1;i<count;i++) { r.gameplay().bodies.respawn(clients.get(i).getSnapshot().localPlayerId(),.9f,2); }
                executor.runCycle();for(int i=0;i<(stock<2?92:3);i++) { executor.runCycle(); }
            }
            until(()->clients.stream().allMatch(c->c.getMatchSession().getResult()!=null));
            var expected=clients.get(0).getMatchSession().getResult();assertEquals(owner.value(),expected.winnerId());
            for(var c:clients) { assertEquals(expected,c.getMatchSession().getResult()); }
            assertEquals(baseline,HeadlessPhysicsWorld.liveWorldCount());
            if(count==3) { var departed=clients.remove(2);departed.closeAndWait(5000);until(()->server.getConnectionManager().getSessions().stream().filter(s->s.isOpen() && room.equals(s.getRoomId())).count()==clients.size()); }
            for(var c:clients) { assertTrue(c.requestRematch()); }
            until(()->clients.stream().allMatch(c->c.getMatchSession()==null && c.getSnapshot().phase()==LobbyClient.Phase.WAITING_FOR_START));
            assertNull(gameRoom.getMatchRuntime());assertEquals(0,gameRoom.getLoadedPlayerCount());
            executor.runCycle();for(var c:clients) { assertTrue(c.markLoaded()); }until(()->gameRoom.getLoadedPlayerCount()==clients.size());
            clock.addAndGet(3_000_000_000L);for(int i=0;i<4;i++) { executor.runCycle(); }
            until(()->clients.stream().allMatch(c->c.getMatchSession()!=null && c.getMatchSession().getSnapshotBuffer().getLatestSnapshot()!=null));
            var fresh=gameRoom.getMatchRuntime();assertNotEquals(oldId,fresh.getMatchId());assertEquals(baseline+1,HeadlessPhysicsWorld.liveWorldCount());
            var stale=com.smashprofs.common.protocol.ProtocolV1.inputPayload(new PlayerInput(owner,999999,999999,1,true,false,true,false,false));stale.put("matchId",oldId);
            assertTrue(clients.get(0).getNetworkClient().sendMessage(new com.smashprofs.common.protocol.ProtocolMessage(com.smashprofs.common.protocol.MessageType.PLAYER_INPUT,null,stale)));
            assertTrue(clients.get(0).recordFrameInput(0,false,false,false,false,false));until(()->fresh.getPendingInputCount()>0);
            for(int i=0;i<3;i++) { executor.runCycle(); }
            until(()->clients.get(0).getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().fighter().player(owner.value()).acknowledgedSequence()>0);
            assertTrue(clients.get(0).getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().fighter().player(owner.value()).acknowledgedSequence()<999999);
            assertNull(clients.get(0).getSnapshot().errorCode());
            for(var c:clients) {
                var snapshot=c.getMatchSession().getSnapshotBuffer().getLatestSnapshot();assertEquals(fresh.getMatchId(),snapshot.matchId());
                assertTrue(snapshot.gameplay().items().isEmpty());assertTrue(snapshot.gameplay().shots().isEmpty());assertEquals(0,c.getMatchSession().getKillFeed().sequence());
                for(var p:snapshot.gameplay().players()) { assertEquals(3,p.lives());assertEquals("NONE",p.weapon());assertEquals(0,snapshot.gameplay().fighter().player(p.playerId()).damagePercent(),0); }
            }
        } finally { for(var c:clients) { c.closeAndWait(5000); }server.stop(); }
        assertEquals(baseline,HeadlessPhysicsWorld.liveWorldCount());
    }
}
