package com.smashprofs.server.game;

import com.smashprofs.common.model.*;
import org.junit.*;
import java.util.*;
import static org.junit.Assert.*;

/** Equipment and Unarmed actions share the real simulation, ammo and hit registration. */
public class FighterEquipmentTest {
    private final PlayerId a=new PlayerId("a"),b=new PlayerId("b");
    private MatchRuntime runtime;private GameplayMatch game;private long sequence;
    @Before public void start() {
        runtime=new MatchRuntime(new MatchSetup("equipment","room",List.of(new MatchSetup.Participant(a,0,"Alex"),new MatchSetup.Participant(b,1,"Luca")),
                new MatchRules(2,0,3,MatchRules.DropRate.NONE,true,true)));
        runtime.start();game=runtime.gameplay();game.bodies.respawn(a,5.45f,.23f);game.bodies.respawn(b,5.72f,.23f);ticks(10);
    }
    @After public void close() { runtime.close(); }
    private void ticks(int count) { for(int i=0;i<count;i++) { runtime.tick(); } }
    private PlayerInput input(boolean light,boolean pickup,boolean drop,boolean throwing) {
        return new PlayerInput(a,sequence++,sequence,0,false,false,light,false,false,pickup,drop,
                new FighterInput(false,false,false,false,false,false,throwing,false));
    }
    private void send(PlayerInput input) { assertEquals(InputQueue.OfferResult.ACCEPTED,runtime.submitInput(input));runtime.tick(); }
    private void pickup(String type) {
        var position=game.bodies.getPosition(a);assertTrue(game.items.drop(type,position.xMeters(),position.yMeters(),runtime.getMatchState().getServerTick()));
        send(input(false,true,false,false));
    }
    @Test public void activeFramesFireOnlyOnceAndProjectileAndMeleeCannotDoubleHitAVictim() {
        pickup("LASER");send(input(true,false,false,false));
        assertEquals(6,game.progress.get(a).ammo);assertTrue(game.items.shotViews().isEmpty());
        ticks(6);assertEquals(5,game.progress.get(a).ammo);
        float hp=game.state.getPlayer(b).getHp();assertEquals(100-7*1.1f*1.35f,hp,.001f);
        ticks(8);assertEquals(hp,game.state.getPlayer(b).getHp(),.001f);assertEquals(5,game.progress.get(a).ammo);
    }
    @Test public void finalRoundIsUsedOnceAndTheNextAttackReturnsToUnarmed() {
        pickup("LASER");game.progress.get(a).ammo=1;send(input(true,false,false,false));ticks(25);
        assertEquals("NONE",game.progress.get(a).weapon);assertEquals(0,game.progress.get(a).ammo);
        send(input(true,false,false,false));assertEquals(1,game.fighter.fighters.get(a).attack.reach,0);
        ticks(8);assertEquals(0,game.progress.get(a).ammo);
    }
    @Test public void pickupSwapAndDropPreserveAmmoAndStaleEdgesCannotDuplicateItems() {
        pickup("SHOTGUN");game.progress.get(a).ammo=4;pickup("LASER");
        assertEquals("LASER",game.progress.get(a).weapon);
        var previous=game.items.itemViews().stream().filter(i->i.type().equals("SHOTGUN")).findFirst().orElseThrow();
        game.bodies.respawn(a,previous.x(),previous.y());send(input(false,true,false,false));
        assertEquals("SHOTGUN",game.progress.get(a).weapon);assertEquals(4,game.progress.get(a).ammo);
        var drop=input(false,true,true,false);send(drop);int items=game.items.itemViews().size();
        assertEquals("NONE",game.progress.get(a).weapon);assertEquals(InputQueue.OfferResult.DUPLICATE_OR_STALE,runtime.submitInput(drop));
        runtime.tick();assertEquals(items,game.items.itemViews().size());assertEquals("NONE",game.progress.get(a).weapon);
    }
    @Test public void throwInputWinsOverPickupAndAttackAndReturnsAmmoToARecoverableDrop() {
        pickup("LASER");game.progress.get(a).ammo=4;send(input(true,true,false,true));
        assertNull(game.fighter.fighters.get(a).attack);assertEquals("NONE",game.progress.get(a).weapon);
        assertTrue(game.items.shotViews().stream().anyMatch(s->s.type().equals("THROWN")));
        ticks(100);var weapon=game.items.itemViews().stream().filter(i->i.type().equals("LASER")).findFirst().orElseThrow();
        game.bodies.respawn(a,weapon.x(),weapon.y());send(input(false,true,false,false));
        assertEquals("LASER",game.progress.get(a).weapon);assertEquals(4,game.progress.get(a).ammo);
    }
    @Test public void healthAndShieldStillWorkWithTheUnifiedHpModel() {
        game.state.getPlayer(a).applyDamage(60);pickup("HEALTH");assertEquals(70,game.state.getPlayer(a).getHp(),0);
        pickup("SHIELD");assertTrue(game.progress.get(a).shield);
        assertTrue(game.fighter.hit(b,a,"LASER",24,.8f,.2f,1,0,false));assertFalse(game.progress.get(a).shield);
        assertEquals(70,game.state.getPlayer(a).getHp(),0);
        assertTrue(game.fighter.hit(b,a,"LASER",24,.8f,.2f,1,0,false));assertEquals(46,game.state.getPlayer(a).getHp(),0);
    }
    @Test public void hitstunCannotPickupOrThrowOrFireEquippedWeapons() {
        pickup("LASER");game.fighter.hit(b,a,"NLIGHT",1,.8f,.2f,1,0,false);
        send(input(true,true,true,true));assertEquals("LASER",game.progress.get(a).weapon);assertEquals(6,game.progress.get(a).ammo);
        assertTrue(game.items.shotViews().isEmpty());assertNull(game.fighter.fighters.get(a).attack);
    }
    @Test public void noneRateKeepsAutomaticDropsOffWithoutDisablingEquipment() {
        for(int tick=1;tick<=1800;tick++) { game.items.update(tick); }
        assertTrue(game.items.itemViews().isEmpty());pickup("BAZOOKA");assertEquals("BAZOOKA",game.progress.get(a).weapon);
    }
}
