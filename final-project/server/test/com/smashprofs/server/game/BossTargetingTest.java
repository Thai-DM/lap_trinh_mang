package com.smashprofs.server.game;

import com.smashprofs.common.model.*;
import org.junit.*;
import java.util.List;
import static org.junit.Assert.*;

/** Real boss decisions and positions with authoritative HP/life state. */
public class BossTargetingTest {
    private final PlayerId a=new PlayerId("a"),b=new PlayerId("b");
    private MatchRuntime runtime;
    private GameplayMatch game;
    @Before public void start() {
        runtime=new MatchRuntime(new MatchSetup("boss-chase","bossroom",List.of(new MatchSetup.Participant(a,0,"Alex"),new MatchSetup.Participant(b,1,"Luca")),MatchRules.BOSS));
        runtime.start();game=runtime.gameplay();game.boss.until=10000;
        place(a,game.boss.x-2);place(b,game.boss.x+.8f);
    }
    @After public void close() { runtime.close(); }
    private void place(PlayerId id,float x) { game.bodies.respawn(id,x,game.boss.y+.15f); }
    @Test public void followsLowerHpPlayerEvenWhenAnotherPlayerIsCloser() {
        game.state.getPlayer(a).applyDamage(60);float x=game.boss.x;game.boss.update(1);
        assertTrue(game.boss.x<x);assertFalse(game.boss.facingRight);assertEquals("WALK",game.boss.action);
    }
    @Test public void switchesWhenSomeoneElseBecomesMoreWounded() {
        game.state.getPlayer(b).applyDamage(60);float x=game.boss.x;game.boss.update(1);assertTrue(game.boss.x>x);
        game.state.getPlayer(a).applyDamage(80);x=game.boss.x;game.boss.update(2);
        assertTrue(game.boss.x<x);assertFalse(game.boss.facingRight);
    }
    @Test public void hpPriorityDoesNotMeanFewestRemainingLives() {
        game.progress.get(b).lives=1;game.state.getPlayer(a).applyDamage(20);
        float x=game.boss.x;game.boss.update(1);assertTrue(game.boss.x<x);
    }
    @Test public void keepsEqualHpTargetWhenTheOtherPlayerRunsCloser() {
        game.state.getPlayer(a).applyDamage(60);game.state.getPlayer(b).applyDamage(60);
        place(a,game.boss.x-.8f);place(b,game.boss.x+2);game.boss.update(1);
        place(a,game.boss.x-3);place(b,game.boss.x+.7f);float x=game.boss.x;game.boss.update(2);
        assertTrue(game.boss.x<x);assertFalse(game.boss.facingRight);
    }
    @Test public void ignoresDeadRespawningAndEliminatedPlayers() {
        game.state.getPlayer(a).applyDamage(80);game.progress.get(a).respawnAt=999;
        float x=game.boss.x;game.boss.update(1);assertTrue(game.boss.x>x);
        game.progress.get(a).respawnAt=0;game.progress.get(a).lives=0;x=game.boss.x;game.boss.update(2);assertTrue(game.boss.x>x);
        game.progress.get(a).lives=3;game.state.getPlayer(a).applyDamage(100);x=game.boss.x;game.boss.update(3);assertTrue(game.boss.x>x);
    }
    @Test public void stopsSafelyAtPlatformEdgeInsteadOfWalkingInPlace() {
        game.state.getPlayer(a).applyDamage(80);game.boss.x=game.boss.left+.001f;
        for(int tick=1;tick<=120;tick++) { game.boss.update(tick);assertTrue(game.boss.x>=game.boss.left);assertTrue(game.boss.x<=game.boss.right); }
        assertEquals(game.boss.left,game.boss.x,.00001f);assertEquals("IDLE",game.boss.action);
    }
    @Test public void stopsAtCleaveDistanceWithoutOvershootingOrOscillating() {
        place(a,game.boss.x+.651f);game.state.getPlayer(a).applyDamage(50);
        float target=game.bodies.getPosition(a).xMeters();game.boss.update(1);float x=game.boss.x;
        assertEquals(.65f,target-x,.00001f);game.boss.update(2);assertEquals(x,game.boss.x,.00001f);assertEquals("IDLE",game.boss.action);
    }
    @Test public void telegraphLocksMovementAndFireballTargetUntilRelease() {
        game.state.getPlayer(a).applyDamage(60);game.boss.until=90;float x=game.boss.x;game.boss.update(90);
        assertEquals("FIREBALL",game.boss.attack);assertFalse(game.boss.facingRight);
        game.state.getPlayer(b).applyDamage(80);game.boss.update(91);
        assertEquals(x,game.boss.x,0);assertFalse(game.boss.facingRight);
        game.boss.update(138);assertTrue(game.boss.snapshot().hazards().stream().allMatch(h->h.vx()<0));
        assertFalse(game.boss.snapshot().hazards().isEmpty());
    }
    @Test public void noShotIsRedirectedIfTheTelegraphedTargetDies() {
        game.state.getPlayer(a).applyDamage(60);game.boss.until=90;game.boss.update(90);
        game.state.getPlayer(a).applyDamage(100);game.boss.update(138);assertTrue(game.boss.snapshot().hazards().isEmpty());
    }
    @Test public void secondPhasePursuesFasterAndNoSurvivorsMeansIdle() {
        game.state.getPlayer(a).applyDamage(50);float x=game.boss.x;game.boss.update(1);float slow=x-game.boss.x;
        game.boss.hp=game.boss.maxHp/2;game.boss.update(2);float fast=x-slow-game.boss.x;
        assertTrue(fast>slow);
        game.state.getPlayer(a).applyDamage(100);game.state.getPlayer(b).applyDamage(100);x=game.boss.x;game.boss.update(3);
        assertEquals(x,game.boss.x,0);assertEquals("IDLE",game.boss.action);
    }
}
