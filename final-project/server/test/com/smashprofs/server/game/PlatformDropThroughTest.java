package com.smashprofs.server.game;

import com.smashprofs.common.model.*;
import com.smashprofs.server.game.physics.*;
import org.junit.*;
import java.util.*;
import static org.junit.Assert.*;

public class PlatformDropThroughTest {
    private final PlayerId a=new PlayerId("a"),b=new PlayerId("b");
    private MatchRuntime runtime;private GameplayMatch game;private long sequence;
    @Before public void setup() {
        runtime=new MatchRuntime(new MatchSetup("platform","room",List.of(new MatchSetup.Participant(a,0,"Alex"),new MatchSetup.Participant(b,1,"Luca")),new MatchRules(2,0,3,MatchRules.DropRate.NONE,true,true)));
        runtime.start();game=runtime.gameplay();game.bodies.respawn(b,8,.23f);
    }
    @After public void close() { runtime.close(); }
    private void frame(boolean down,boolean light,boolean drop) {
        assertEquals(InputQueue.OfferResult.ACCEPTED,runtime.submitInput(new PlayerInput(a,sequence++,0,0,false,false,light,false,false,false,drop,
                new FighterInput(down,false,false,false,false,false,false,false))));runtime.tick();
    }
    private void platform(float x) {
        game.bodies.respawn(a,x,1.55f);game.fighter.physics.reset(a);
        for(int i=0;i<30;i++) { frame(false,false,false); }
        assertTrue(game.jumps.getJumpState(a).grounded());assertEquals(1.265f,game.bodies.getPosition(a).yMeters(),.025f);
    }
    private void dropAssembly(float x) {
        platform(x);frame(true,false,false);assertTrue("First S only aims down",game.jumps.getJumpState(a).grounded());
        frame(false,false,false);frame(true,false,false);
        for(int i=0;i<7;i++) { frame(false,false,false); }
        assertTrue("One double-tap must clear every overlapping fixture",game.bodies.getPosition(a).yMeters()<1.05f);
        for(int i=0;i<40;i++) { frame(false,false,false); }
        assertTrue("The distinct floor below must stay solid",game.jumps.getJumpState(a).grounded());
        assertEquals(.225f,game.bodies.getPosition(a).yMeters(),.025f);
    }
    @Test public void oneDoubleTapClearsLeftOverlappingBlock() { dropAssembly(5.8f); }
    @Test public void oneDoubleTapClearsRightOverlappingBlock() { dropAssembly(6.25f); }
    @Test public void bridgeAlsoDropsInOneAction() { dropAssembly(6.08f); }
    @Test public void downLightOnPlatformDoesNotBecomeADrop() {
        platform(5.8f);frame(true,false,false);frame(false,false,false);frame(true,true,true);
        assertEquals("DLIGHT",game.snapshot().fighter().player("a").attack());assertTrue(game.jumps.getJumpState(a).grounded());
    }
    @Test public void airborneDownLightUsesDairWithoutForcedFastFall() {
        game.bodies.respawn(a,6,2.2f);game.fighter.physics.velocity(a,0,-1);frame(true,true,false);
        assertEquals("DAIR",game.snapshot().fighter().player("a").attack());
        assertFalse(game.snapshot().fighter().player("a").fastFalling());assertTrue(game.bodies.getVelocity(a).yMetersPerSecond()>-2);
    }
    @Test public void armedDownLightFiresDownWithoutMeleeOrForcedFastFall() {
        game.bodies.respawn(a,6,2.2f);game.fighter.physics.velocity(a,0,-1);game.progress.get(a).weapon="LASER";game.progress.get(a).ammo=5;
        frame(true,true,false);assertEquals("NONE",game.snapshot().fighter().player("a").attack());
        assertEquals(4,game.progress.get(a).ammo);assertFalse(game.snapshot().fighter().player("a").fastFalling());
        var shot=game.snapshot().shots().stream().filter(s->s.type().equals("LASER")).findFirst().orElseThrow();assertTrue(shot.vy()<0);
    }
    @Test public void adjacentLayerDoesNotMergeWithARealLowerFloor() {
        var surfaces=List.of(new PlatformAssembly.Surface(0,2,1),new PlatformAssembly.Surface(.4f,1.96f,1),
                new PlatformAssembly.Surface(1.3f,1.95f,.5f),new PlatformAssembly.Surface(0,1,2));
        assertEquals(Set.of(0,1,2),PlatformAssembly.connected(surfaces,0));
    }
}
