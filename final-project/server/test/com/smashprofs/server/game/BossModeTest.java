package com.smashprofs.server.game;

import com.smashprofs.common.model.*;
import com.smashprofs.common.protocol.*;
import org.junit.*;
import java.util.*;
import static org.junit.Assert.*;

public class BossModeTest {
    private final PlayerId a=new PlayerId("a"),b=new PlayerId("b");
    private MatchRuntime runtime;
    private GameplayMatch game;
    @Before public void start() {
        runtime=new MatchRuntime(new MatchSetup("boss-test","bossroom",List.of(new MatchSetup.Participant(a,0,"Alex"),new MatchSetup.Participant(b,1,"Luca")),MatchRules.BOSS));
        runtime.start();game=runtime.gameplay();
    }
    @After public void close() { runtime.close(); }
    @Test public void bossHasIndependentRosterAndStarterGuns() {
        assertEquals(2,game.snapshot().players().size());assertEquals(1000,game.snapshot().boss().maxHp(),0);
        assertEquals("LASER",game.progress.get(a).weapon);assertEquals(6,game.progress.get(a).ammo);
        assertEquals(game.snapshot(),GameplayProtocol.snapshot(GameplayProtocol.snapshot(game.snapshot())));
    }
    @Test public void allPlayerDamagePathsRejectFriendlyFire() {
        assertFalse(game.damage(a,b,"LASER",24,1,1));
        assertFalse(game.fighter.hit(a,b,"NLIGHT",12,1,.1f,1,0,false));
        assertEquals(100,game.state.getPlayer(b).getHp(),0);
    }
    @Test public void gunPassesTeamMateAndDamagesBossOnce() {
        game.boss.until=100000;
        game.bodies.respawn(a,game.boss.x-.55f,game.boss.y+.15f);
        game.bodies.respawn(b,game.boss.x-.35f,game.boss.y+.15f);
        runtime.submitInput(new PlayerInput(a,1,1,0,false,false,true,false,false));runtime.tick();
        assertEquals(976,game.boss.hp,.001f);assertEquals(100,game.state.getPlayer(b).getHp(),0);
        assertNull(game.fighter.fighters.get(a).attack);
        for(int i=0;i<8;i++) { runtime.tick(); }
        assertEquals(976,game.boss.hp,.001f);
        assertEquals(24,game.progress.get(a).damage,.001);
    }
    @Test public void emptyGunReloadsWithoutPunching() {
        game.boss.until=100000;game.progress.get(a).ammo=1;
        runtime.submitInput(new PlayerInput(a,1,1,0,false,false,true,false,false));runtime.tick();
        assertEquals("LASER",game.progress.get(a).weapon);assertEquals(0,game.progress.get(a).ammo);
        for(int i=0;i<91;i++) { runtime.tick(); }
        assertEquals(6,game.progress.get(a).ammo);assertNull(game.fighter.fighters.get(a).attack);
    }
    @Test public void oneSurvivingHumanDoesNotWinOrEndTheFight() {
        game.disconnect(a);runtime.tick();assertFalse(runtime.isFinished());assertEquals(0,game.progress.get(a).lives);
    }
    @Test public void defeatedBossWinsEvenIfBothPlayersLoseTheirLastLifeInSameTick() {
        game.boss.damage(a,2000);game.progress.get(a).lives=1;game.progress.get(b).lives=1;
        game.state.getPlayer(a).applyDamage(100);game.state.getPlayer(b).applyDamage(100);runtime.tick();
        assertEquals(AuthoritativeMatchResult.FinishReason.BOSS_DEFEATED,runtime.getMatchResult().finishReason());
        assertFalse(runtime.getMatchResult().isDraw());assertNull(runtime.getMatchResult().winnerId());
    }
    @Test public void wholeTeamDisconnectsAndLoses() {
        game.disconnect(a);game.disconnect(b);runtime.tick();
        assertEquals(AuthoritativeMatchResult.FinishReason.TEAM_ELIMINATED,runtime.getMatchResult().finishReason());
    }
    @Test public void timeoutIsTeamLossAndWaitingRespawnsAreStillAlive() {
        game.state.getPlayer(a).applyDamage(100);game.state.getPlayer(b).applyDamage(100);runtime.tick();
        assertFalse(runtime.isFinished());assertEquals("RESPAWNING",game.snapshot().player("a").status());
        assertEquals(AuthoritativeMatchResult.FinishReason.BOSS_TIMEOUT,MatchEndEvaluator.evaluate(runtime.getLatestSnapshot(),true).finishReason());
    }
    @Test public void secondPhaseAndHazardsRemainBoundedAndAuthoritative() {
        game.boss.damage(a,501);game.boss.update(90);assertEquals(2,game.boss.phase);
        assertEquals("TELEGRAPH",game.boss.action);
        for(int i=91;i<1500;i++) { game.boss.update(i);assertTrue(game.boss.snapshot().hazards().size()<=BossSnapshot.MAX_HAZARDS); }
    }
    @Test public void deadOrUnknownPlayerCannotClaimBossDamage() {
        assertFalse(game.boss.damage(new PlayerId("outsider"),20));
        game.state.getPlayer(a).applyDamage(100);assertFalse(game.boss.damage(a,20));assertEquals(1000,game.boss.hp,0);
    }
    @Test public void everyGunAndThrownWeaponCanDamageTheBoss() {
        game.bodies.respawn(a,game.boss.x-.55f,game.boss.y+.15f);
        for(String weapon:List.of("SHOTGUN","LASER","BAZOOKA","GRENADE")) {
            game.items.close();game.boss.hp=game.boss.maxHp;
            var p=game.progress.get(a);p.weapon=weapon;p.ammo=6;p.nextShot=0;
            assertTrue(game.items.attack(a,0));for(int i=1;i<=130;i++) { game.items.update(i); }
            assertTrue(weapon,game.boss.hp<game.boss.maxHp);
        }
        game.items.close();game.boss.hp=game.boss.maxHp;game.progress.get(a).weapon="LASER";game.progress.get(a).ammo=4;
        assertTrue(game.items.throwWeapon(a,1,0,0));for(int i=1;i<=40;i++) { game.items.update(i); }
        assertEquals(game.boss.maxHp-8,game.boss.hp,0);
    }
    @Test public void allFiveCharacterAbilitiesHaveABossTarget() {
        for(String character:List.of("Alex","Luca","Maurice","Leo","Viktor")) {
            runtime.close();runtime=new MatchRuntime(new MatchSetup("skill-"+character,"bossroom",List.of(new MatchSetup.Participant(a,0,character),new MatchSetup.Participant(b,1,"Luca")),MatchRules.BOSS));
            runtime.start();game=runtime.gameplay();game.boss.until=100000;
            game.bodies.respawn(a,game.boss.x-.20f,game.boss.y+.3f);
            runtime.submitInput(new PlayerInput(a,1,1,0,false,false,false,false,true));
            for(int i=0;i<20;i++) { runtime.tick(); }
            assertTrue(character,game.boss.hp<game.boss.maxHp);assertTrue(game.progress.get(a).nextUltimate>0);
        }
    }
    @Test public void cleaveHasOneHitPerTargetAndRespectsShield() {
        for(var p:game.progress.values()) { p.invulnerableUntil=0; }
        game.bodies.respawn(a,game.boss.x+.4f,game.boss.y+.15f);game.progress.get(a).shield=true;
        game.boss.action="ACTIVE";game.boss.attack="CLEAVE";game.boss.until=100;
        game.boss.update(1);assertFalse(game.progress.get(a).shield);assertEquals(100,game.state.getPlayer(a).getHp(),0);
        game.boss.update(2);assertEquals(100,game.state.getPlayer(a).getHp(),0);
    }
    @Test public void eruptionWarnsBeforeDamageAndHitsOnlyOnce() {
        for(var p:game.progress.values()) { p.invulnerableUntil=0; }
        game.bodies.respawn(a,game.boss.x+2,game.boss.y+.15f);game.bodies.respawn(b,game.boss.x-2,game.boss.y+.15f);
        game.boss.pattern=1;game.boss.update(90);assertEquals("ERUPTION",game.boss.attack);
        assertFalse(game.boss.snapshot().hazards().get(0).active());
        game.boss.update(149);assertEquals(100,game.state.getPlayer(a).getHp(),0);
        game.boss.update(150);assertEquals(76,game.state.getPlayer(a).getHp(),0);
        game.boss.update(151);assertEquals(76,game.state.getPlayer(a).getHp(),0);
    }
    @Test public void fireballTracksTheChosenAimAndCannotDamageTheSamePlayerTwice() {
        for(var p:game.progress.values()) { p.invulnerableUntil=0; }
        game.bodies.respawn(a,game.boss.x+1.5f,game.boss.y+.35f);game.bodies.respawn(b,game.boss.x-3,game.boss.y+.35f);
        game.boss.update(90);assertEquals("FIREBALL",game.boss.attack);game.boss.update(138);
        assertTrue(game.boss.snapshot().hazards().stream().anyMatch(h->h.type().equals("FIREBALL")));
        for(int i=139;i<=180;i++) { game.boss.update(i); }
        assertEquals(84,game.state.getPlayer(a).getHp(),0);
    }
    @Test(expected=IllegalArgumentException.class) public void malformedBossHpIsRejectedByCodec() {
        var json=BossProtocol.snapshot(game.boss.snapshot());json.put("hp",-1);BossProtocol.snapshot(json);
    }
}
