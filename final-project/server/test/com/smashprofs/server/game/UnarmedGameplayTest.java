package com.smashprofs.server.game;

import com.smashprofs.common.model.*;
import com.smashprofs.server.game.physics.*;
import org.junit.*;
import java.util.*;
import static org.junit.Assert.*;

/** Native production-tick acceptance tests for the Unarmed MVP, never canned combo claims. */
public class UnarmedGameplayTest {
    private final PlayerId a=new PlayerId("a"),b=new PlayerId("b");
    private MatchRuntime runtime;private GameplayMatch game;private long sequence;
    @Before public void start() { runtime=new MatchRuntime(new MatchSetup("unarmed","room",List.of(new MatchSetup.Participant(a,0,"Alex"),new MatchSetup.Participant(b,1,"Luca")),new MatchRules(2,0,3,MatchRules.DropRate.NONE,true,true)));runtime.start();game=runtime.gameplay(); }
    @After public void close() { runtime.close(); }
    private FighterSnapshot.Player state() { return game.snapshot().fighter().player("a"); }
    private FighterEngine.Fighter fighter() { return game.fighter.fighters.get(a); }
    private void ticks(int n) { for(int i=0;i<n;i++) { runtime.tick(); } }
    private void input(float x,boolean jump,boolean light,boolean heavy,boolean down,boolean up,boolean dodge,boolean heldHeavy) {
        runtime.submitInput(new PlayerInput(a,sequence++,sequence,x,jump,false,light,false,false,false,false,new FighterInput(down,jump,up,heavy,heldHeavy,dodge,false,false)));runtime.tick();
    }
    private void floor(boolean targetClose) { game.bodies.respawn(a,5.45f,.23f);game.bodies.respawn(b,targetClose?5.72f:8,.23f);game.fighter.physics.reset(a);game.fighter.physics.reset(b);ticks(10); }
    private void air() { game.bodies.respawn(a,6,3);game.bodies.respawn(b,8,3);game.fighter.physics.reset(a);game.fighter.physics.reset(b);runtime.tick(); }
    private void attack(boolean light,boolean heavy,float x,boolean down,boolean up) { input(x,false,light,heavy,down,up,false,false); }
    @Test public void resolverHasDeterministicDownNeutralAndSidePriorities() {
        assertEquals(UnarmedInputResolver.Direction.DOWN,UnarmedInputResolver.direction(1,true,true,true));
        assertEquals(UnarmedInputResolver.Direction.NEUTRAL,UnarmedInputResolver.direction(1,false,true,true));
        assertEquals(UnarmedInputResolver.Direction.SIDE,UnarmedInputResolver.direction(1,false,true,false));
        assertEquals("SHEAVY",UnarmedInputResolver.move(true,true,UnarmedInputResolver.Direction.SIDE));
        assertEquals("RECOVERY",UnarmedInputResolver.move(false,true,UnarmedInputResolver.Direction.SIDE));
    }
    @Test public void everyGroundAndAirMoveResolvesFromOneSimultaneousFrame() {
        for(boolean grounded:new boolean[]{true,false}) { for(boolean heavy:new boolean[]{false,true}) { for(var direction:UnarmedInputResolver.Direction.values()) {
            fighter().attack=null;fighter().air.reset();fighter().hitstun=0;
            if(grounded) { floor(false); }else { air(); }
            attack(!heavy,heavy,direction==UnarmedInputResolver.Direction.SIDE?-1:0,direction==UnarmedInputResolver.Direction.DOWN,false);
            assertEquals(UnarmedInputResolver.move(grounded,heavy,direction),state().attack());
        } } }
    }
    @Test public void jumpAndLightInTheSameFrameChooseAnAirAttack() {
        floor(false);input(1,true,true,false,false,false,false,false);assertEquals("SAIR",state().attack());
        assertEquals(1,state().airJumps());assertEquals(0,state().detail().aerialActionsUsed());assertTrue(game.bodies.getVelocity(a).yMetersPerSecond()>4);
    }
    @Test public void aimingUpWithSideMovementChoosesNeutralWithoutSpendingAJump() {
        floor(false);attack(true,false,1,false,true);assertEquals("NLIGHT",state().attack());assertEquals(1,state().airJumps());
        assertTrue(game.jumps.getJumpState(a).grounded());
    }
    @Test public void allAirActionCombinationsExhaustTheSameBudget() {
        for(int jumps=0;jumps<=1;jumps++) {
            var budget=new AirActionBudget();for(int i=0;i<jumps;i++) { assertTrue(budget.jump()); }
            for(int i=0;i<2-jumps;i++) { assertEquals(i==0?4.8f:UnarmedRules.EXHAUSTED_RECOVERY_SPEED,budget.recover(),.0001f); }
            assertEquals(2,budget.used());assertFalse(budget.jump());assertFalse(budget.canRecover());assertEquals(0,budget.recover(),0);
            budget.reset();assertEquals(1,budget.jumps());assertEquals(0,budget.used());
        }
    }
    @Test public void recoveriesUseWeakerExhaustedLaunchAndThenRefuseAFurtherOne() {
        air();for(int i=0;i<2;i++) {
            fighter().attack=null;game.bodies.respawn(a,6,3);game.fighter.physics.reset(a);runtime.tick();
            attack(false,true,0,false,false);assertEquals("RECOVERY",state().attack());
            assertTrue(game.bodies.getVelocity(a).yMetersPerSecond()>(i==0?4.5f:2.9f));
            assertEquals(i+1,state().detail().recoveries());
        }
        fighter().attack=null;attack(false,true,0,false,false);assertEquals("NONE",state().attack());assertFalse(state().recoveryAvailable());
    }
    @Test public void landingResetsAllAirResources() {
        air();fighter().air.jump();fighter().air.recover();assertEquals(2,fighter().air.used());
        floor(false);assertEquals(1,state().airJumps());assertEquals(0,state().detail().recoveries());assertEquals(0,state().detail().aerialActionsUsed());
    }
    @Test public void wallTouchResetsAirBudgetUntilWallSlipThreshold() {
        var wall=new TmxCollisionLoader().loadOriginalMap().rectangles().stream().filter(r->r.heightPixels()>45 && r.yPixels()+r.heightPixels()>40).findFirst().orElseThrow();
        for(boolean slipping:new boolean[]{false,true}) {
            fighter().air.reset();fighter().air.jump();fighter().air.recover();fighter().wallActions=slipping?UnarmedRules.WALL_SLIP:0;fighter().lastWall=0;
            game.bodies.respawn(a,wall.xPixels()/100-.055f,(wall.yPixels()+wall.heightPixels()*.7f)/100);game.fighter.physics.reset(a);
            input(1,false,false,false,false,false,false,false);input(1,false,false,false,false,false,false,false);
            assertTrue(game.jumps.wallDirection(a)!=0);assertEquals(slipping?2:0,state().detail().aerialActionsUsed());
        }
    }
    @Test public void directionalGroundDodgeHasInvulnerabilityAndCooldown() {
        floor(false);input(1,false,false,false,false,false,true,false);assertEquals("DODGE",state().action());assertTrue(state().dodgeTicks()>0);
        assertTrue(state().dodgeCooldownTicks()>0);
        runtime.tick();runtime.tick();assertFalse(game.fighter.hit(b,a,"NLIGHT",1,.8f,.2f,1,0,false));
    }
    @Test public void wallJumpUsesTheSharedFatigueThresholdInsteadOfAnOldThreeJumpCap() {
        var wall=new TmxCollisionLoader().loadOriginalMap().rectangles().stream().filter(r->r.heightPixels()>45 && r.yPixels()+r.heightPixels()>40).findFirst().orElseThrow();
        fighter().wallActions=3;fighter().wallJumps=0;
        game.bodies.respawn(a,wall.xPixels()/100-.055f,(wall.yPixels()+wall.heightPixels()*.7f)/100);game.fighter.physics.reset(a);
        input(1,false,false,false,false,false,false,false);input(1,false,false,false,false,false,false,false);
        input(0,true,false,false,false,false,false,false);
        assertEquals(4,state().detail().wallActions());assertTrue(game.bodies.getVelocity(a).yMetersPerSecond()>4);
    }
    @Test public void backdashPreservesFacingAndDashJumpKeepsBurstVelocity() {
        floor(false);assertTrue(game.state.getPlayer(a).isFacingRight());
        runtime.submitInput(new PlayerInput(a,sequence++,sequence,-1,true,false,false,false,false,false,false,new FighterInput(false,true,false,false,false,false,false,true)));runtime.tick();assertEquals("BACKDASH",state().detail().state());
        assertTrue(game.state.getPlayer(a).isFacingRight());assertTrue(game.bodies.getVelocity(a).yMetersPerSecond()>4);
        assertEquals(-FighterMovement.DASH_SPEED,game.bodies.getVelocity(a).xMetersPerSecond(),.04f);
    }
    @Test public void groundSpotDodgeUsesItsConfiguredInvulnerableWindow() {
        floor(false);input(0,false,false,false,false,false,true,false);assertEquals("GROUND_SPOT",state().detail().dodgeKind());
        for(int age=1;age<UnarmedRules.DODGE_STARTUP+UnarmedRules.GROUND_IFRAMES;age++) {
            runtime.tick();if(age>=UnarmedRules.DODGE_STARTUP) { assertFalse(game.fighter.hit(b,a,"NLIGHT",1,.8f,.2f,1,0,false)); }
        }
        runtime.tick();assertTrue(game.fighter.hit(b,a,"NLIGHT",1,.8f,.2f,1,0,false));
    }
    @Test public void airSpotAndDirectionalDodgeHaveDifferentDurations() {
        air();input(0,false,false,false,false,false,true,false);assertEquals("AIR_SPOT",state().detail().dodgeKind());assertEquals(26,state().dodgeTicks());
        fighter().dodgeStart=-1000;fighter().nextDodge=0;fighter().airDodgeUsed=false;
        input(1,false,false,false,false,true,true,false);assertEquals("AIR_DIRECTION",state().detail().dodgeKind());assertEquals(18,state().dodgeTicks());assertEquals(163,state().dodgeCooldownTicks());
    }
    @Test public void airSpotDodgeCanGravityCancelIntoEveryGroundMove() {
        for(boolean heavy:new boolean[]{false,true}) { for(var direction:UnarmedInputResolver.Direction.values()) {
            fighter().attack=null;fighter().dodgeStart=-1000;fighter().nextDodge=0;fighter().airDodgeUsed=false;air();
            input(0,false,false,false,false,false,true,false);attack(!heavy,heavy,direction==UnarmedInputResolver.Direction.SIDE?1:0,direction==UnarmedInputResolver.Direction.DOWN,false);
            assertEquals(UnarmedInputResolver.move(true,heavy,direction),state().attack());assertTrue(state().detail().gravityCancelled());
            assertEquals(0,game.bodies.getVelocity(a).yMetersPerSecond(),0);assertTrue(state().dodgeCooldownTicks()>0);
        } }
    }
    @Test public void gravityCancelWindowAcceptsFrame18ButRejectsFrame19() {
        air();input(0,false,false,false,false,false,true,false);ticks(17);attack(true,false,0,false,false);assertTrue(state().detail().gravityCancelled());
        fighter().attack=null;fighter().dodgeStart=-1000;fighter().nextDodge=0;fighter().airDodgeUsed=false;air();
        input(0,false,false,false,false,false,true,false);ticks(18);attack(true,false,0,false,false);assertEquals("NONE",state().attack());assertEquals("DODGE",state().action());
    }
    @Test public void directionalAirDodgeCannotUseTheSpotGravityCancelPath() {
        air();input(1,false,false,false,false,false,true,false);attack(true,false,1,false,false);
        assertEquals("NONE",state().attack());assertFalse(state().detail().gravityCancelled());
    }
    @Test public void missedGravityCancelEndsInAirWithDodgeStillConsumed() {
        air();input(0,false,false,false,false,false,true,false);attack(true,false,0,false,false);float y=game.bodies.getPosition(a).yMeters();
        ticks(FighterMoves.get("NLIGHT").total(false)+2);assertTrue(game.bodies.getPosition(a).yMeters()<y);assertTrue(fighter().airDodgeUsed);
        assertEquals("NONE",state().attack());assertFalse(state().detail().gravityCancelled());
    }
    @Test public void lateRecoveryBufferExecutesExactlyOnce() {
        floor(false);attack(true,false,0,false,false);int first=(int)state().attackId();
        while(fighter().attack.frame<FighterMoves.get("NLIGHT").total(false)-3) { runtime.tick(); }
        attack(true,false,0,false,false);assertTrue(state().detail().bufferTicks()>0);ticks(4);
        assertEquals(first+1,state().attackId());ticks(FighterMoves.get("NLIGHT").total(false)+3);assertEquals("NONE",state().attack());
    }
    @Test public void earlyRecoveryInputIsNotReplayedLater() {
        floor(false);attack(true,false,0,false,false);attack(true,false,0,false,false);ticks(30);assertEquals("NONE",state().attack());assertEquals(0,state().detail().bufferTicks());
    }
    @Test public void hitstunClearsABufferedAttack() {
        floor(false);attack(true,false,0,false,false);while(fighter().attack.frame<18) { runtime.tick(); }attack(true,false,0,false,false);
        assertNotNull(fighter().pending);game.fighter.hit(b,a,"NLIGHT",1,.8f,.2f,1,0,false);assertNull(fighter().pending);ticks(25);assertEquals("NONE",state().attack());
    }
    @Test public void whiffAndHitRecoveryAreDifferentAndHitDoesNotRepeatEveryFrame() {
        int[] duration=new int[2];for(int test=0;test<2;test++) {
            fighter().attack=null;floor(test==1);game.state.getPlayer(b).heal(100);attack(true,false,0,false,false);
            while(!state().attack().equals("NONE")) { runtime.tick();duration[test]++; }
            if(test==1) { assertEquals(100-7*1.1f,game.state.getPlayer(b).getHp(),.001f); }
        }
        assertTrue(duration[0]>duration[1]);
    }
    @Test public void poseHurtboxShrinksDuringDownAttackActiveFramesOnly() {
        floor(false);float y=game.bodies.getPosition(a).yMeters();game.fighter.physics.hurtbox(a,"DLIGHT",4);
        assertFalse(game.fighter.physics.targets(5.44f,y+.21f,5.46f,y+.22f).contains(a));
        game.fighter.physics.hurtbox(a,"DLIGHT",0);assertTrue(game.fighter.physics.targets(5.44f,y+.21f,5.46f,y+.22f).contains(a));
    }
    @Test public void chaseDodgeRequiresARealHitAndHasOnlyTwoCharges() {
        floor(true);attack(true,false,0,false,false);ticks(18);assertEquals(2,state().detail().chaseCharges());
        input(1,false,false,false,false,false,true,false);assertEquals("CHASE",state().detail().dodgeKind());assertEquals(1,state().detail().chaseCharges());
        input(1,false,false,false,false,false,true,false);assertEquals(0,state().detail().chaseCharges());long start=fighter().dodgeStart,nextDash=fighter().nextDash;
        input(1,false,false,false,false,false,true,false);assertEquals(start,fighter().dodgeStart);assertEquals(nextDash,fighter().nextDash);
    }
    @Test public void aWhiffCannotGrantChaseDodge() {
        floor(false);attack(true,false,0,false,false);ticks(24);assertEquals(0,state().detail().chaseCharges());
        input(1,false,false,false,false,false,true,false);assertNotEquals("CHASE",state().detail().dodgeKind());
    }
    @Test public void showStunMeasuresZeroWindowAndStringInsteadOfTrustingMoveNames() {
        floor(true);game.fighter.hit(a,b,"NLIGHT",1,.8f,.2f,1,0,false);assertEquals(-1,game.snapshot().fighter().player("b").detail().dodgeFrames());
        game.fighter.hit(a,b,"NLIGHT",1,.8f,.2f,1,0,false);assertEquals("ZERO_WINDOW",game.snapshot().fighter().player("b").detail().combo());
        ticks(25);game.fighter.hit(a,b,"NLIGHT",1,.8f,.2f,1,0,false);var d=game.snapshot().fighter().player("b").detail();
        assertTrue(d.dodgeFrames()>0);assertEquals("STRING",d.combo());
    }
    @Test public void oneFreeDodgeInputFrameMustNotBeReportedAsZeroWindow() {
        floor(true);game.fighter.hit(a,b,"NLIGHT",1,.8f,.2f,1,0,false);
        int stun=game.snapshot().fighter().player("b").hitstunTicks();ticks(stun+1);
        game.fighter.hit(a,b,"NLIGHT",1,.8f,.2f,1,0,false);var detail=game.snapshot().fighter().player("b").detail();
        assertEquals(1,detail.dodgeFrames());assertEquals("STRING",detail.combo());
    }
    @Test public void neutralLightPerformsThreeHitJabComboWithVacuumAndGentleFinisher() {
        floor(true);
        // Hit 1: NLIGHT
        attack(true,false,0,false,false);assertEquals("NLIGHT",state().attack());
        ticks(FighterMoves.get("NLIGHT").total(true)+1);
        // Hit 2: NLIGHT2 connects
        attack(true,false,0,false,false);assertEquals("NLIGHT2",state().attack());
        ticks(FighterMoves.get("NLIGHT2").total(true)+1);
        // Hit 3: NLIGHT3 finisher
        attack(true,false,0,false,false);assertEquals("NLIGHT3",state().attack());
        ticks(FighterMoves.get("NLIGHT3").total(true)+1);
        // Combo resets back to NLIGHT
        attack(true,false,0,false,false);assertEquals("NLIGHT",state().attack());
    }
}
