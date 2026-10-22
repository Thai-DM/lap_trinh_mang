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

public class BossTcpIntegrationTest {
    private static void until(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+7_000_000_000L;
        while(!condition.getAsBoolean()) { if(System.nanoTime()>end) { fail("Boss TCP flow timeout"); }Thread.sleep(2); }
    }
    @Test(timeout=60000) public void twoAndFourHumansShareBossGunDamageTeamWinAndFreshRematch() throws Exception {
        for(int count:new int[]{2,4}) { play(count); }
    }
    private void play(int count) throws Exception {
        int baseline=HeadlessPhysicsWorld.liveWorldCount();var clock=new AtomicLong(10_000_000_000L);
        var executor=new ManualScheduledExecutor();
        var server=new GameServer(()->new FixedTickScheduler(executor,Logger.getLogger("boss-tcp")),clock::get);
        var clients=new ArrayList<LobbyClient>();String room="boss_"+count;server.start();
        try {
            for(int i=0;i<count;i++) {
                var c=new LobbyClient(new LobbyConfiguration("127.0.0.1",server.getBoundPort(),room,i==0?LobbyConfiguration.Mode.CREATE:LobbyConfiguration.Mode.JOIN));
                if(i==0) { c.enableBoss(); }else { c.enableBossSupport(); }
                clients.add(c);assertTrue(c.startAsync());until(()->c.getSnapshot().canReady());
            }
            executor.runCycle();until(()->clients.stream().allMatch(c->c.getSettings()!=null&&c.getSettings().playerCount()==count));
            assertTrue(clients.get(0).getSettings().rules().boss());
            try(var old=new LobbyClient(new LobbyConfiguration("127.0.0.1",server.getBoundPort(),room,LobbyConfiguration.Mode.JOIN))) {
                old.enableFighter();old.startAsync();until(()->old.getSnapshot().phase()==LobbyClient.Phase.ERROR);
                assertEquals("BOSS_CAPABILITY_REQUIRED",old.getSnapshot().errorCode());
            }
            for(var c:clients) { assertTrue(c.ready("Alex")); }
            var gameRoom=server.getRoomManager().getRoom(room);until(gameRoom::canStartMatch);executor.runCycle();
            clock.addAndGet(4_000_000_000L);executor.runCycle();assertNull(gameRoom.getMatchRuntime());
            for(var c:clients) { assertTrue(c.markLoaded()); }until(()->gameRoom.getLoadedPlayerCount()==count);
            clock.addAndGet(3_000_000_000L);for(int i=0;i<4;i++) { executor.runCycle(); }
            until(()->clients.stream().allMatch(c->c.getMatchSession()!=null&&c.getMatchSession().getSnapshotBuffer().getLatestSnapshot()!=null));
            var r=gameRoom.getMatchRuntime();var owner=clients.get(0).getSnapshot().localPlayerId();var boss=r.gameplay().boss;boss.until=100000;
            assertEquals(1000+250*(count-2),boss.hp,0);
            for(var c:clients) { var id=c.getSnapshot().localPlayerId();r.gameplay().state.getPlayer(id).heal(100);r.gameplay().bodies.respawn(id,boss.x-.30f,boss.y+.45f); }
            r.gameplay().bodies.respawn(owner,boss.x-.55f,boss.y+.45f);
            for(int i=0;i<3;i++) { executor.runCycle(); }
            assertTrue(clients.get(0).recordFrameInput(0,false,false,true,false,false));until(()->r.getPendingInputCount()>0);
            for(int i=0;i<6;i++) { executor.runCycle(); }
            float hp=boss.maxHp-24;
            until(()->clients.stream().allMatch(c->c.getMatchSession().getSnapshotBuffer().getLatestSnapshot().gameplay().boss().hp()==hp));
            for(var c:clients) {
                var state=c.getMatchSession().getSnapshotBuffer().getLatestSnapshot();assertEquals(count,state.players().size());
                assertEquals(100,state.player(c.getSnapshot().localPlayerId().value()).hp(),.001f);
            }
            String oldId=r.getMatchId();assertTrue(boss.damage(owner,10000));executor.runCycle();
            until(()->clients.stream().allMatch(c->c.getMatchSession().getResult()!=null));
            var result=clients.get(0).getMatchSession().getResult();assertTrue(result.teamWon());assertFalse(result.isDraw());assertNull(result.winner());
            for(var c:clients) { assertEquals(result,c.getMatchSession().getResult());assertTrue(c.requestRematch()); }
            until(()->clients.stream().allMatch(c->c.getMatchSession()==null&&c.getSnapshot().phase()==LobbyClient.Phase.WAITING_FOR_START));
            executor.runCycle();for(var c:clients) { assertTrue(c.markLoaded()); }until(()->gameRoom.getLoadedPlayerCount()==count);
            clock.addAndGet(3_000_000_000L);for(int i=0;i<4;i++) { executor.runCycle(); }
            until(()->clients.stream().allMatch(c->c.getMatchSession()!=null&&c.getMatchSession().getSnapshotBuffer().getLatestSnapshot()!=null));
            var fresh=gameRoom.getMatchRuntime();assertNotEquals(oldId,fresh.getMatchId());
            for(var c:clients) {
                var state=c.getMatchSession().getSnapshotBuffer().getLatestSnapshot();assertEquals(boss.maxHp,state.gameplay().boss().hp(),0);
                assertTrue(state.gameplay().boss().hazards().isEmpty());assertEquals(0,state.gameplay().boss().hitSerial());
                assertEquals("LASER",state.gameplay().player(c.getSnapshot().localPlayerId().value()).weapon());
            }
            fresh.gameplay().disconnect(owner);executor.runCycle();assertFalse(fresh.isFinished());
            for(var c:clients) { fresh.gameplay().disconnect(c.getSnapshot().localPlayerId()); }executor.runCycle();
            until(()->clients.stream().allMatch(c->c.getMatchSession().getResult()!=null));
            for(var c:clients) { assertFalse(c.getMatchSession().getResult().teamWon());assertEquals("TEAM_ELIMINATED",c.getMatchSession().getResult().finishReason()); }
        } finally { for(var c:clients) { c.closeAndWait(5000); }server.stop(); }
        assertEquals(baseline,HeadlessPhysicsWorld.liveWorldCount());
    }
}
