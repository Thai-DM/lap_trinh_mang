package com.smashprofs.server.game;

import com.smashprofs.common.model.*;
import com.smashprofs.server.game.physics.HeadlessPhysicsWorld;
import org.junit.*;
import java.util.*;
import static org.junit.Assert.*;

public class StockGameplayTest {
    private final List<MatchRuntime> runtimes=new ArrayList<>();
    private static PlayerId id(int slot) { return new PlayerId("p"+slot); }
    private MatchRuntime runtime(int count,int lives,int seconds) {
        var roster=new ArrayList<MatchSetup.Participant>();for(int i=0;i<count;i++) { roster.add(new MatchSetup.Participant(id(i),i,"Viktor")); }
        var runtime=new MatchRuntime(new MatchSetup("stock","room",roster,new MatchRules(count,seconds,lives,MatchRules.DropRate.NONE,true)));
        runtimes.add(runtime);runtime.start();return runtime;
    }
    @After public void cleanup() { runtimes.forEach(MatchRuntime::close); }
    @Test public void deathChargesOneStockRespawnsAtExactly90TicksAndProtectsFor120Ticks() {
        var r=runtime(2,3,0);var g=r.gameplay();
        g.beforeTick(10);assertTrue(g.damage(id(1),id(0),"MELEE",100,0,0));g.afterTick();g.afterTick();
        assertEquals(2,g.snapshot().player("p0").lives());assertEquals(1,g.snapshot().player("p0").deaths());
        g.beforeTick(99);assertEquals("RESPAWNING",g.snapshot().player("p0").status());
        assertEquals(InputQueue.OfferResult.PLAYER_INACTIVE,r.submitInput(new PlayerInput(id(0),0,0,1,false,false,false,false,false)));
        g.beforeTick(100);assertEquals(100,r.getMatchState().getPlayer(id(0)).getHp(),0);assertEquals("ACTIVE",g.snapshot().player("p0").status());
        assertFalse(g.damage(id(1),id(0),"MELEE",10,1,1));g.beforeTick(219);assertFalse(g.damage(id(1),id(0),"MELEE",10,1,1));
        g.beforeTick(220);assertTrue(g.damage(id(1),id(0),"MELEE",10,1,1));
        assertEquals(1,g.snapshot().player("p1").kills());assertEquals(110,g.snapshot().player("p1").damageDealt(),.001);
    }
    @Test public void simultaneousRespawningPlayersDoNotEndInfiniteMatchAndZeroStockIsDraw() {
        for(int count=2;count<=4;count++) {
            var r=runtime(count,2,0);
            for(int i=0;i<count;i++) { r.getMatchState().getPlayer(id(i)).applyDamage(100); }
            r.tick();assertFalse(r.isFinished());
            for(int tick=2;tick<=91;tick++) { r.tick(); }
            assertFalse(r.getMatchState().getPlayer(id(0)).isDead());
            for(int i=0;i<count;i++) { r.getMatchState().getPlayer(id(i)).applyDamage(100); }
            r.tick();assertTrue(r.isFinished());assertTrue(r.getMatchResult().isDraw());
            assertEquals(AuthoritativeMatchResult.FinishReason.ALL_PLAYERS_ELIMINATED,r.getMatchResult().finishReason());
        }
    }
    @Test public void disconnectedPlayerIsEliminatedOnSimulationOwnerAndWinnerIsAuthoritative() {
        var r=runtime(2,5,0);r.requestDisconnect(id(1));r.tick();assertTrue(r.isFinished());assertEquals(id(0),r.getMatchResult().winnerId());
        assertEquals(0,r.getMatchResult().gameplay().player("p1").lives());
    }
    @Test public void fallWithoutCombatDoesNotInventKillerAndAttributionExpires() {
        var r=runtime(3,3,0);var g=r.gameplay();g.beforeTick(1);g.fell(id(0));g.afterTick();
        assertNull(g.eventsAfter(0).get(0).killer());assertEquals("PLAYER_FELL",g.eventsAfter(0).get(0).type());
        g.beforeTick(100);g.damage(id(1),id(2),"BAZOOKA",1,0,0);g.beforeTick(401);g.fell(id(2));g.afterTick();
        assertNull(g.eventsAfter(1).get(0).killer());
    }
    @Test public void nativeWorldReturnsToBaselineAfterStockRuntimeClose() {
        int baseline=HeadlessPhysicsWorld.liveWorldCount();var r=runtime(4,5,300);r.tick();assertEquals(baseline+1,HeadlessPhysicsWorld.liveWorldCount());
        r.close();r.close();assertEquals(baseline,HeadlessPhysicsWorld.liveWorldCount());assertNull(r.getLatestSnapshot());
    }
    @Test public void ultimateRejectsSpamAndBecomesReadyAtTick600Boundary() {
        var r=runtime(2,5,0);var g=r.gameplay();g.beforeTick(1);assertTrue(g.ultimateReady(id(0)));g.ultimateUsed(id(0));
        for(int tick=2;tick<=600;tick++) { g.beforeTick(tick);assertFalse(g.ultimateReady(id(0))); }
        g.beforeTick(601);assertTrue(g.ultimateReady(id(0)));
        r.tick(); // Confirm the extension can publish immutable state through the production runtime.
        assertNotNull(r.getLatestSnapshot().gameplay());
    }
    @Test public void knockbackScalesAndClampsWithoutChangingDirectionOrProducingNonFiniteValues() {
        var scale=KnockbackScaling.DEFAULT;
        assertTrue(scale.multiplier(30)>scale.multiplier(100));assertTrue(scale.multiplier(0)>=scale.multiplier(30));
        var impulse=scale.scale(0,-Float.MAX_VALUE,Float.MAX_VALUE);
        assertTrue(impulse.x()<0 && impulse.y()>0);assertTrue(Math.hypot(impulse.x(),impulse.y())<=8.00001);
        assertThrows(IllegalArgumentException.class,()->scale.scale(Float.NaN,1,1));
    }
    @Test public void everyConfiguredStockCountLoadsAndSingleStockEliminationEndsMatch() {
        for(int lives:new int[]{1,2,3,5}) {
            var r=runtime(2,lives,0);r.tick();assertEquals(lives,r.getLatestSnapshot().gameplay().player("p0").lives());
            r.getMatchState().getPlayer(id(0)).applyDamage(100);r.tick();assertEquals(lives-1,r.getLatestSnapshot().gameplay().player("p0").lives());
            assertEquals(lives==1,r.isFinished());
            if(lives==1) { assertTrue(r.isPhysicsDisposed());assertNotNull(r.getLatestSnapshot());assertNotNull(r.getMatchResult()); }
        }
    }
    @Test public void timeLimitUses3600SimulationTicksAndPreservesTheExistingHighestHpDrawPolicy() {
        var r=runtime(2,3,60);
        for(int tick=1;tick<3600;tick++) { r.tick();assertFalse(r.isFinished()); }
        r.tick();assertTrue(r.isFinished());assertEquals(AuthoritativeMatchResult.FinishReason.TIME_LIMIT,r.getMatchResult().finishReason());
        assertTrue(r.getMatchResult().isDraw());assertEquals(0,r.getRemainingTimeMillis());
    }
    @Test public void repeatedHeavyStockMatchesReleaseWorldsItemsProjectilesAndSnapshots() {
        int baseline=HeadlessPhysicsWorld.liveWorldCount();
        for(int round=0;round<12;round++) {
            var r=runtime(4,5,0);var g=r.gameplay();
            for(int i=0;i<16;i++) { g.items.drop("SHOTGUN",3.5f,3.5f,0); }
            for(int i=0;i<32;i++) { g.items.orbit(id(i%4),0); }
            for(int tick=0;tick<60 && !r.isFinished();tick++) { r.tick(); }
            assertTrue(g.snapshot().items().size()<=16);assertTrue(g.snapshot().shots().size()<=64);
            r.close();assertNull(r.getLatestSnapshot());assertEquals(0,r.getPendingInputCount());assertEquals(baseline,HeadlessPhysicsWorld.liveWorldCount());
        }
    }
}
