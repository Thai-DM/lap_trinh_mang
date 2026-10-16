package com.smashprofs.server.game;

import com.smashprofs.common.model.*;
import com.smashprofs.server.game.physics.*;
import org.junit.*;
import java.util.List;
import static org.junit.Assert.*;

public class LavaReboundTest {
    private final PlayerId a=new PlayerId("a"),b=new PlayerId("b");
    private MatchRuntime runtime;
    @Before public void start() {
        runtime=new MatchRuntime(new MatchSetup("lava","lava-room",List.of(new MatchSetup.Participant(a,0,"Luca"),new MatchSetup.Participant(b,1,"Viktor")),new MatchRules(2,0,3,MatchRules.DropRate.NONE,true)));
        runtime.start();
    }
    @After public void close() { runtime.close(); }
    @Test public void visibleLavaReboundsBeforeLeavingTheMapAndCostsTenHpWithoutAStock() {
        assertFalse(new TmxCollisionLoader().loadOriginalMap().lavaZones().isEmpty());
        var game=runtime.gameplay();game.bodies.respawn(a,4.9f,.2f);runtime.tick();
        assertEquals(90,runtime.getMatchState().getPlayer(a).getHp(),0);assertEquals(3,game.snapshot().player("a").lives());
        assertEquals("ACTIVE",game.snapshot().player("a").status());assertTrue(game.bodies.getVelocity(a).yMetersPerSecond()>2);
        assertTrue(game.bodies.getPosition(a).yMeters()>.2f);assertTrue(game.eventsAfter(0).isEmpty());
    }
    @Test public void preAndPostPhysicsContactsCannotChargeTwiceAndDamageRearmsAt60Ticks() {
        var game=runtime.gameplay();game.beforeTick(1);game.bodies.respawn(a,4.9f,.2f);game.applyLavaContacts();game.applyLavaContacts();
        assertEquals(90,runtime.getMatchState().getPlayer(a).getHp(),0);
        game.beforeTick(60);game.bodies.respawn(a,4.9f,.2f);game.applyLavaContacts();assertEquals(90,runtime.getMatchState().getPlayer(a).getHp(),0);
        game.beforeTick(61);game.bodies.respawn(a,4.9f,.2f);game.applyLavaContacts();assertEquals(80,runtime.getMatchState().getPlayer(a).getHp(),0);
    }
    @Test public void lethalLavaConsumesExactlyOneStockAndThenRespawnsWithProtection() {
        var game=runtime.gameplay();runtime.getMatchState().getPlayer(a).applyDamage(90);game.bodies.respawn(a,4.9f,.2f);runtime.tick();
        assertEquals(2,game.snapshot().player("a").lives());assertEquals("RESPAWNING",game.snapshot().player("a").status());
        assertEquals("PLAYER_FELL",game.eventsAfter(0).get(0).type());assertNull(game.eventsAfter(0).get(0).killer());
        for(int i=0;i<89;i++) { runtime.tick(); }assertEquals("RESPAWNING",game.snapshot().player("a").status());
        runtime.tick();assertEquals(100,runtime.getMatchState().getPlayer(a).getHp(),0);assertEquals(2,game.snapshot().player("a").lives());
        game.bodies.respawn(a,4.9f,.2f);runtime.tick();assertEquals(100,runtime.getMatchState().getPlayer(a).getHp(),0);assertTrue(game.bodies.getVelocity(a).yMetersPerSecond()>0);
    }
    @Test public void blockAndWeaponShieldDoNotMakeLavaHarmlessOrConsumeTheShield() {
        var game=runtime.gameplay();game.progress.get(a).shield=true;runtime.getMatchState().getPlayer(a).setBlocking(true);
        game.bodies.respawn(a,4.9f,.2f);runtime.tick();assertEquals(90,runtime.getMatchState().getPlayer(a).getHp(),0);assertTrue(game.snapshot().player("a").shield());
    }
    @Test public void standingOnPlatformsDoesNotCountAsLavaContact() {
        for(int i=0;i<120;i++) { runtime.tick(); }
        assertEquals(100,runtime.getMatchState().getPlayer(a).getHp(),0);assertEquals(100,runtime.getMatchState().getPlayer(b).getHp(),0);
    }
}
