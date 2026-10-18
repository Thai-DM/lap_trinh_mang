package com.smashprofs.server.game;

import com.smashprofs.common.model.*;
import com.smashprofs.server.game.physics.*;
import org.junit.*;
import java.util.*;
import static org.junit.Assert.*;

/** Real Box2D, production tick and owner: no client-side damage or fake collision fixtures. */
public class FighterEngineTest {
    private final PlayerId a=new PlayerId("a"),b=new PlayerId("b");
    private MatchRuntime runtime;private GameplayMatch game;private long sequence;
    @Before public void start() {
        runtime=new MatchRuntime(new MatchSetup("fighter","room",List.of(new MatchSetup.Participant(a,0,"Alex"),new MatchSetup.Participant(b,1,"Luca")),new MatchRules(2,0,3,MatchRules.DropRate.NONE,true,true)));
        runtime.start();game=runtime.gameplay();
    }
    @After public void close() { runtime.close(); }
    private void frame(float axis,boolean jump,boolean light,boolean heavy,boolean down,boolean heldJump,boolean dodge) {
        assertEquals(InputQueue.OfferResult.ACCEPTED,runtime.submitInput(new PlayerInput(a,sequence++,0,axis,jump,false,light,false,false,false,false,
                new FighterInput(down,heldJump,false,heavy,heavy,dodge,false,false))));runtime.tick();
    }
    private FighterSnapshot.Player state() { return game.snapshot().fighter().player("a"); }
    private void floor() {
        game.bodies.respawn(a,5.45f,.23f);game.bodies.respawn(b,5.72f,.23f);
        for(int i=0;i<10;i++) { runtime.tick(); }assertTrue(game.jumps.getJumpState(a).grounded());
    }
    @Test public void runningIsSlowerAndReleasingStopsWithinFiveCentimeters() {
        floor();game.bodies.respawn(b,8,.23f);
        for(int i=0;i<15;i++) { frame(1,false,false,false,false,false,false); }
        float speed=game.bodies.getVelocity(a).xMetersPerSecond();assertTrue(speed>1.4f);assertTrue(speed<=1.601f);
        assertTrue(game.jumps.getJumpState(a).grounded());
        float x=game.bodies.getPosition(a).xMeters();
        for(int i=0;i<10;i++) { frame(0,false,false,false,false,false,false); }
        assertTrue("Ground stopping distance="+(game.bodies.getPosition(a).xMeters()-x),game.bodies.getPosition(a).xMeters()-x<.05f);
        assertTrue(Math.abs(game.bodies.getVelocity(a).xMetersPerSecond())<.1f);
    }
    @Test public void airReleaseBrakesDriftAndOppositeInputCanReverseDirection() {
        game.bodies.respawn(a,6,6);game.fighter.physics.velocity(a,2.8f,0);
        for(int i=0;i<15;i++) { frame(0,false,false,false,false,false,false); }
        assertFalse(game.jumps.getJumpState(a).grounded());
        assertTrue(game.bodies.getPosition(a).xMeters()-6<.35f);
        assertTrue(game.bodies.getVelocity(a).xMetersPerSecond()<.5f);
        for(int i=0;i<4;i++) { frame(-1,false,false,false,false,false,false); }
        assertTrue(game.bodies.getVelocity(a).xMetersPerSecond()<0);
    }
    @Test public void dashAndDirectionalDodgeUseTheReducedBurstSpeed() {
        floor();
        runtime.submitInput(new PlayerInput(a,sequence++,0,1,false,false,false,false,false,false,false,
                new FighterInput(false,false,false,false,false,false,false,true)));runtime.tick();
        float dash=game.bodies.getVelocity(a).xMetersPerSecond();assertTrue(dash>2.4f);assertTrue(dash<=2.601f);
        runtime.submitInput(new PlayerInput(a,sequence++,0,1,false,false,false,false,false,false,false,
                new FighterInput(false,false,true,false,false,true,false,false)));runtime.tick();runtime.tick();runtime.tick();
        var velocity=game.bodies.getVelocity(a);
        assertEquals(2.6,Math.hypot(velocity.xMetersPerSecond(),velocity.yMetersPerSecond()),.06);
    }
    @Test public void lavaReboundsAtTheVisibleSurfaceWithoutLosingStocks() {
        for(float x:new float[]{1.2f,2.5f,4.9f,6.65f,8.5f,10.5f,11.3f}) {
            game.state.getPlayer(a).heal(100);game.fighter.fighters.get(a).nextLavaDamage=0;
            game.bodies.respawn(a,x,1.2f);game.fighter.physics.reset(a);
            boolean bounced=false;
            for(int i=0;i<45;i++) {
                runtime.tick();bounced|=game.bodies.getVelocity(a).yMetersPerSecond()>0;
                assertTrue("Lava at x="+x,game.bodies.getPosition(a).yMeters()>=FighterArena.LAVA_CENTER_Y-.001f);
            }
            assertTrue(bounced);assertFalse(game.jumps.getJumpState(a).grounded());
            assertEquals(3,game.snapshot().player("a").lives());assertEquals(90,game.state.getPlayer(a).getHp(),0);
        }
    }
    @Test public void fastFallAndDownwardLaunchCannotTunnelBelowTheLava() {
        game.bodies.respawn(a,2.5f,.4f);game.fighter.physics.reset(a);
        for(int i=0;i<3;i++) { frame(0,false,false,false,true,true,false); }
        assertTrue(game.bodies.getVelocity(a).yMetersPerSecond()>0);assertFalse(state().fastFalling());
        game.bodies.respawn(a,2.5f,.4f);game.fighter.physics.velocity(a,0,-50);game.fighter.fighters.get(a).hitstun=20;
        runtime.tick();assertEquals(FighterArena.LAVA_CENTER_Y,game.bodies.getPosition(a).yMeters(),.001f);
        assertEquals(FighterArena.LAVA_BOUNCE_SPEED,game.bodies.getVelocity(a).yMetersPerSecond(),.001f);
        assertEquals(2,state().airJumps());assertTrue(state().recoveryAvailable());assertEquals(3,game.snapshot().player("a").lives());
    }
    @Test public void lavaDamageIsTenHitPointsAtMostOncePerSecondAndInvulnerabilityStillRebounds() {
        for(int i=0;i<40;i++) {
            game.bodies.respawn(a,2.5f,.2f);runtime.tick();
            assertEquals(90,game.state.getPlayer(a).getHp(),0);assertEquals(0,state().damagePercent(),0);
        }
        game.progress.get(a).invulnerableUntil=runtime.getMatchState().getServerTick()+120;
        for(int i=0;i<65;i++) { game.bodies.respawn(a,2.5f,.2f);runtime.tick(); }
        assertEquals(90,game.state.getPlayer(a).getHp(),0);assertTrue(game.bodies.getVelocity(a).yMetersPerSecond()>0);
        game.progress.get(a).invulnerableUntil=0;game.bodies.respawn(a,2.5f,.2f);runtime.tick();assertEquals(80,game.state.getPlayer(a).getHp(),0);
        assertEquals(0,state().damagePercent(),0);assertEquals(3,game.snapshot().player("a").lives());
    }
    @Test public void lethalLavaConsumesOneStockThenRespawnsAtFullHealthAndAcceptsMovement() {
        for(int hit=1;hit<=10;hit++) {
            game.bodies.respawn(a,2.5f,.2f);game.fighter.physics.reset(a);runtime.tick();
            assertEquals(100-hit*10,game.state.getPlayer(a).getHp(),0);
            if(hit<10) {
                assertEquals(3,game.snapshot().player("a").lives());
                game.bodies.respawn(a,5.45f,.23f);game.fighter.physics.reset(a);
                for(int i=0;i<59;i++) { runtime.tick(); }
            }
        }
        assertTrue(game.state.getPlayer(a).isDead());assertEquals(2,game.snapshot().player("a").lives());
        assertEquals("RESPAWNING",game.snapshot().player("a").status());assertEquals("LAVA",game.eventsAfter(0).get(0).cause());
        for(int i=0;i<89;i++) { runtime.tick();assertEquals(2,game.snapshot().player("a").lives()); }
        runtime.tick();assertEquals(100,game.state.getPlayer(a).getHp(),0);assertEquals(0,state().damagePercent(),0);
        assertEquals("RESPAWN_PLATFORM",state().action());assertEquals(180,state().platformTicks());
        float x=game.bodies.getPosition(a).xMeters();
        for(int i=0;i<10;i++) { frame(1,false,false,false,false,false,false); }
        assertTrue(game.bodies.getPosition(a).xMeters()>x+.05f);assertTrue(game.snapshot().player("a").invulnerableTicks()>0);
        game.bodies.respawn(a,2.5f,.2f);runtime.tick();assertEquals(100,game.state.getPlayer(a).getHp(),0);
    }
    @Test public void standingOnTheOriginalSolidPlatformDoesNotTriggerLava() {
        floor();for(int i=0;i<120;i++) { runtime.tick(); }
        assertTrue(game.jumps.getJumpState(a).grounded());assertEquals(0,state().damagePercent(),0);
    }
    @Test public void fightersUseOneHealthModelAndLethalCombatConsumesAStock() {
        assertEquals(0,state().damagePercent(),0);
        assertTrue(game.fighter.hit(b,a,"TEST",240,.8f,.2f,1,0,false));
        assertEquals(0,state().damagePercent(),0);assertEquals(0,game.state.getPlayer(a).getHp(),0);
        runtime.tick();assertEquals(2,game.snapshot().player("a").lives());assertTrue(game.state.getPlayer(a).isDead());
    }
    @Test public void formulaGrowsFourfoldAt120PercentAndHonorsDefenseAndFiniteNativeLimit() {
        double low=FighterMoves.knockback(0,.8f,.2f,6),high=FighterMoves.knockback(120,.8f,.2f,6);
        assertEquals(4,high/low,.00001);assertTrue(FighterMoves.knockback(120,.8f,.2f,7)<FighterMoves.knockback(120,.8f,.2f,4));
        assertEquals(50,FighterMoves.knockback(Double.MAX_VALUE,.8f,.2f,6),0);
        assertEquals(45,FighterMoves.hitstun(10000,.3f));
    }
    @Test public void activeHitboxHonorsStartupRangeAndPerAttackDeduplication() {
        floor();frame(0,false,true,false,false,false,false);
        assertEquals("NLIGHT",state().attack());assertEquals(0,game.snapshot().fighter().player("b").damagePercent(),0);
        runtime.tick();runtime.tick();assertEquals(0,game.snapshot().fighter().player("b").damagePercent(),0);
        runtime.tick();double first=game.state.getPlayer(b).getHp();assertTrue(first<100);
        for(int i=0;i<8;i++) { runtime.tick(); }
        assertEquals(first,game.state.getPlayer(b).getHp(),0);
        assertEquals(1,state().attackId());
    }
    @Test public void targetBehindTheAttackerIsOutsideTheForwardHitbox() {
        floor();game.bodies.respawn(b,5.18f,.23f);runtime.tick();frame(0,false,true,false,false,false,false);
        for(int i=0;i<12;i++) { runtime.tick(); }assertEquals(100,game.state.getPlayer(b).getHp(),0);
    }
    @Test public void shieldAbsorbsTheEntireAttackInsteadOfOnlyItsFirstActiveFrame() {
        floor();game.progress.get(b).shield=true;frame(0,false,true,false,false,false,false);
        for(int i=0;i<9;i++) { runtime.tick(); }
        assertFalse(game.progress.get(b).shield);assertEquals(100,game.state.getPlayer(b).getHp(),0);
    }
    @Test public void groundJumpDoubleJumpShortHopAndFastFallHaveBoundedAirResources() {
        floor();frame(0,true,false,false,false,true,false);assertTrue(game.bodies.getVelocity(a).yMetersPerSecond()>4);
        frame(0,false,false,false,false,false,false);assertTrue(game.bodies.getVelocity(a).yMetersPerSecond()<1.8f);
        game.fighter.physics.velocity(a,0,-4);frame(0,true,false,false,false,true,false);
        assertEquals(1,state().airJumps());assertTrue(game.bodies.getVelocity(a).yMetersPerSecond()>4);
        frame(0,true,false,false,false,true,false);assertEquals(0,state().airJumps());assertTrue(game.bodies.getVelocity(a).yMetersPerSecond()>4);
        frame(0,true,false,false,false,true,false);assertEquals(0,state().airJumps());assertTrue(game.bodies.getVelocity(a).yMetersPerSecond()<4);
        game.fighter.physics.velocity(a,0,-1);frame(0,false,false,false,true,false,false);
        assertTrue(state().fastFalling());assertEquals(-9.6f,game.bodies.getVelocity(a).yMetersPerSecond(),.001f);
    }
    @Test public void dodgeHasStartupTwelveInvulnerableTicksRecoveryAndGroundCooldown() {
        floor();frame(0,false,false,false,false,false,true);assertEquals(22,state().dodgeTicks());
        runtime.tick();runtime.tick();assertFalse(game.fighter.hit(b,a,"TEST",10,.8f,.2f,1,0,false));
        int cooldown=state().dodgeCooldownTicks();frame(1,false,false,false,false,false,true);assertTrue(state().dodgeCooldownTicks()<cooldown);
        for(int i=0;i<15;i++) { runtime.tick(); }
        assertTrue(game.fighter.hit(b,a,"TEST",10,.8f,.2f,1,0,false));assertTrue(state().hitstunTicks()>0);
    }
    @Test public void hitstunRejectsActionsAndPreservesTheAuthoritativeLaunch() {
        floor();game.fighter.hit(b,a,"TEST",20,1,.25f,-1,1,false);float vx=game.bodies.getVelocity(a).xMetersPerSecond();
        frame(1,true,true,true,false,true,true);assertEquals(vx,game.bodies.getVelocity(a).xMetersPerSecond(),.0001f);
        assertEquals("NONE",state().attack());assertEquals(0,state().dodgeTicks());assertTrue(state().hitstunTicks()>0);
    }
    @Test public void eachBlastZoneChargesExactlyOneStockAndTopRequiresHitstun() {
        for(float[] point:new float[][]{{.9f,2},{11.6f,2},{6,-1.6f}}) {
            game.bodies.respawn(a,point[0],point[1]);runtime.tick();int lives=game.snapshot().player("a").lives();
            if(lives==0) { assertEquals("ELIMINATED",game.snapshot().player("a").status());assertTrue(runtime.isFinished());assertEquals(b,runtime.getMatchResult().winnerId());return; }
            assertEquals("RESPAWNING",game.snapshot().player("a").status());runtime.tick();assertEquals(lives,game.snapshot().player("a").lives());
            for(int i=0;i<89;i++) { runtime.tick(); }frame(1,false,false,false,false,false,false);
        }
    }
    @Test public void voluntaryHighJumpCannotCauseTopKOButLaunchCan() {
        game.bodies.respawn(a,6,9.2f);runtime.tick();assertEquals(3,game.snapshot().player("a").lives());
        game.fighter.fighters.get(a).hitstun=10;runtime.tick();assertEquals(2,game.snapshot().player("a").lives());
        assertEquals("BLAST_TOP",game.eventsAfter(0).get(0).cause());
    }
    @Test public void respawnPlatformWaitsForInputThenGrantsExactlyTwoSecondsProtection() {
        game.fighter.hit(b,a,"TEST",120,1,.3f,1,0,false);game.bodies.respawn(a,.9f,2);runtime.tick();
        assertEquals(1,game.snapshot().player("b").kills());assertEquals(2,game.snapshot().player("a").lives());
        for(int i=0;i<89;i++) { runtime.tick(); }assertEquals("RESPAWNING",game.snapshot().player("a").status());
        runtime.tick();assertEquals("RESPAWN_PLATFORM",state().action());assertEquals(0,state().damagePercent(),0);assertEquals(180,state().platformTicks());
        float y=game.bodies.getPosition(a).yMeters();for(int i=0;i<10;i++) { runtime.tick(); }assertEquals(y,game.bodies.getPosition(a).yMeters(),0);
        assertFalse(game.fighter.hit(b,a,"TEST",10,1,.2f,1,0,false));frame(1,false,false,false,false,false,false);
        assertEquals(0,state().platformTicks());assertEquals(120,game.snapshot().player("a").invulnerableTicks());
        game.bodies.respawn(a,5.5f,.23f);game.fighter.physics.reset(a);
        for(int i=0;i<119;i++) { runtime.tick(); }assertFalse(game.fighter.hit(b,a,"TEST",10,1,.2f,1,0,false));
        runtime.tick();assertTrue(game.fighter.hit(b,a,"TEST",10,1,.2f,1,0,false));
    }
    @Test public void oneWayPlatformAllowsUpwardPassageLandingAndDownJumpDropThrough() {
        var platform=new TmxCollisionLoader().loadOriginalMap().rectangles().stream().filter(FighterPhysics::oneWay)
                .max(Comparator.comparingDouble(MapCollisionDefinition.CollisionRectangle::widthPixels)).orElseThrow();
        float x=(platform.xPixels()+platform.widthPixels()/2)/100,top=(platform.yPixels()+platform.heightPixels())/100;
        game.bodies.respawn(a,x,top-.15f);game.fighter.physics.velocity(a,0,3);
        for(int i=0;i<12;i++) { frame(0,false,false,false,false,true,false); }
        assertTrue(game.bodies.getPosition(a).yMeters()>top+.05f);
        for(int i=0;i<45;i++) { runtime.tick(); }assertTrue(game.jumps.getJumpState(a).grounded());
        frame(0,true,false,false,true,true,false);for(int i=0;i<10;i++) { frame(0,false,false,false,true,false,false); }
        assertTrue(game.bodies.getPosition(a).yMeters()<top-.05f);
    }
    @Test public void heavyChargeRecoveryAndAirDodgeCannotBeReusedWithoutLanding() {
        floor();frame(0,false,false,true,false,false,false);for(int i=0;i<8;i++) { frame(0,false,false,false,false,false,false); }
        assertNotEquals("CHARGE",state().action());assertEquals("NHEAVY",state().attack());
        for(int i=0;i<45;i++) { runtime.tick(); }
        game.bodies.respawn(a,6,2);runtime.tick();frame(0,false,false,true,false,false,false);
        assertEquals("RECOVERY",state().attack());assertTrue(state().recoveryAvailable());assertEquals(1,state().detail().recoveries());
    }
    @Test public void capsuleHurtboxRejectsBroadphaseCornerFalsePositives() {
        game.bodies.respawn(a,6,2);runtime.tick();float y=game.bodies.getPosition(a).yMeters();
        assertFalse(game.fighter.physics.targets(6.075f,y+.24f,6.09f,y+.255f).contains(a));
        assertTrue(game.fighter.physics.targets(5.99f,y+.20f,6.01f,y+.23f).contains(a));
    }
    @Test public void leftSideAttackUsesTheNewDirectionOnItsFirstTick() {
        floor();game.bodies.respawn(b,5.15f,.23f);runtime.tick();frame(-1,false,true,false,false,false,false);
        assertEquals("SLIGHT",state().attack());assertFalse(game.state.getPlayer(a).isFacingRight());
        for(int i=0;i<8;i++) { runtime.tick(); }assertTrue(game.state.getPlayer(b).getHp()<100);
    }
    @Test public void actualLaunchGrowsWithPercentAndHeavyHitstopFreezesExactlyTwoSteps() {
        floor();game.fighter.hit(b,a,"TEST",10,.8f,.2f,1,0,false);float low=game.bodies.getVelocity(a).xMetersPerSecond();
        game.state.getPlayer(a).applyDamage(70);game.fighter.hit(b,a,"TEST",10,.8f,.2f,1,0,false);
        assertTrue(game.bodies.getVelocity(a).xMetersPerSecond()>low*2.5f);assertTrue(game.bodies.getVelocity(a).xMetersPerSecond()<low*3);
        game.fighter.hit(b,a,"TEST",1,.8f,.2f,1,1,true);float x=game.bodies.getPosition(a).xMeters();
        runtime.tick();assertEquals(x,game.bodies.getPosition(a).xMeters(),.00001f);runtime.tick();assertEquals(x,game.bodies.getPosition(a).xMeters(),.00001f);
        runtime.tick();assertTrue(game.bodies.getPosition(a).xMeters()>x);
    }
    @Test public void unarmedRejectsPickupDropThrowAndWeaponProjectiles() {
        floor();assertFalse(game.items.drop("LASER",5.45f,.23f,0));
        game.items.interact(new PlayerInput(a,1,0,0,false,false,false,false,false,true,true),1);
        assertFalse(game.items.throwWeapon(a,1,0,1));assertFalse(game.items.attack(a,1));
        assertEquals("NONE",game.progress.get(a).weapon);assertTrue(game.items.itemViews().isEmpty());assertTrue(game.items.shotViews().isEmpty());
    }
    @Test public void airDodgeCooldownAndHealthAreAuthoritative() {
        game.bodies.respawn(a,6,2);runtime.tick();frame(0,false,false,false,false,false,true);assertEquals(UnarmedRules.AIR_COOLDOWN,state().dodgeCooldownTicks());
        game.state.getPlayer(a).applyDamage(60);game.fighter.heal(a);
        assertEquals(0,state().damagePercent(),0);assertEquals(70,game.state.getPlayer(a).getHp(),0);
    }
    @Test public void unarmedNeverSpawnsItemsEvenWhenOlderRoomRulesRequestHighDrops() {
        runtime.close();runtime=new MatchRuntime(new MatchSetup("no-drop","room",List.of(new MatchSetup.Participant(a,0,"Alex"),new MatchSetup.Participant(b,1,"Luca")),new MatchRules(2,0,3,MatchRules.DropRate.HIGH,true,true)));
        runtime.start();game=runtime.gameplay();for(int i=0;i<1000;i++) { runtime.tick(); }
        assertTrue(game.items.itemViews().isEmpty());assertTrue(game.items.shotViews().isEmpty());
    }
}
