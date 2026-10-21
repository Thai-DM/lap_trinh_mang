package com.smashprofs.server.game;

import com.smashprofs.common.model.*;
import org.junit.*;
import java.util.*;
import static org.junit.Assert.*;

public class ItemGameplayTest {
    @Test public void equippedAmmoIsAuthoritativeAndClearedWhenTheLastRoundIsUsed() {
        var game=runtime.gameplay();var position=game.bodies.getPosition(id(0));
        game.items.drop("SHOTGUN",position.xMeters(),position.yMeters(),0);runtime.tick();
        assertEquals(6,game.snapshot().player("p0").ammo());
        for(int i=0;i<6;i++) { assertTrue(game.items.attack(id(0),2+i*24));assertEquals(5-i,game.snapshot().player("p0").ammo()); }
        assertEquals("NONE",game.snapshot().player("p0").weapon());
    }
    private MatchRuntime runtime;
    private static PlayerId id(int i) { return new PlayerId("p"+i); }
    @Before public void start() {
        var roster=new ArrayList<MatchSetup.Participant>();for(int i=0;i<4;i++) { roster.add(new MatchSetup.Participant(id(i),i,"Luca")); }
        runtime=new MatchRuntime(new MatchSetup("items","room",roster,new MatchRules(4,0,3,MatchRules.DropRate.NONE,true)));runtime.start();
        for(int i=0;i<4;i++) { runtime.gameplay().bodies.respawn(id(i),4.5f+i*.5f,3); }
    }
    @After public void stop() { runtime.close(); }
    private GameplayMatch game() { return runtime.gameplay(); }
    private void pickup(String type) {
        var g=game();var position=g.bodies.getPosition(id(0));assertTrue(g.items.drop(type,position.xMeters(),position.yMeters(),1));g.beforeTick(1);g.items.update(1);
    }
    @Test public void healthClampsAndShieldAbsorbsExactlyOneValidHitWithoutDamageOrImpulse() {
        var g=game();g.state.getPlayer(id(0)).applyDamage(50);pickup("HEALTH");assertEquals(80,g.state.getPlayer(id(0)).getHp(),0);
        pickup("HEALTH");assertEquals(100,g.state.getPlayer(id(0)).getHp(),0);pickup("SHIELD");
        var before=g.bodies.getVelocity(id(0));assertFalse(g.damage(id(1),id(0),"MELEE",10,2,3));assertEquals(before,g.bodies.getVelocity(id(0)));
        assertEquals(0,g.snapshot().player("p1").damageDealt(),0);assertTrue(g.damage(id(1),id(0),"MELEE",10,2,3));assertEquals(90,g.state.getPlayer(id(0)).getHp(),0);
    }
    @Test public void shotgunFiresThreeDistinctFanProjectilesAndWeaponCooldownConsumesTheAttackEdge() {
        pickup("SHOTGUN");var items=game().items;assertTrue(items.attack(id(0),1));
        var shots=items.shotViews();assertEquals(3,shots.size());assertTrue(shots.get(0).vy()<0);assertEquals(0,shots.get(1).vy(),0);assertTrue(shots.get(2).vy()>0);
        assertTrue(items.attack(id(0),2));assertEquals(3,items.shotViews().size());
    }
    @Test public void laserSweepsThroughMultipleOpponentsWithoutTunnelingOrDuplicateDamage() {
        var g=game();g.bodies.respawn(id(1),4.9f,3);g.bodies.respawn(id(2),5.2f,3);g.bodies.respawn(id(3),8,1);
        pickup("LASER");g.items.attack(id(0),1);
        for(int tick=2;tick<10;tick++) { g.beforeTick(tick);g.items.update(tick); }
        assertEquals(76,g.state.getPlayer(id(1)).getHp(),0);assertEquals(76,g.state.getPlayer(id(2)).getHp(),0);
        assertEquals(48,g.snapshot().player("p0").damageDealt(),.001);
    }
    @Test public void bazookaExplodesOnceAndDamagesNearbyOpponentsWithBoundedImpulse() {
        var g=game();g.bodies.respawn(id(1),5,3);g.bodies.respawn(id(2),5.3f,3);g.bodies.respawn(id(3),8,1);
        pickup("BAZOOKA");g.items.attack(id(0),1);
        for(int tick=2;tick<25;tick++) { g.beforeTick(tick);g.items.update(tick); }
        assertEquals(68,g.state.getPlayer(id(1)).getHp(),0);assertEquals(68,g.state.getPlayer(id(2)).getHp(),0);
        assertEquals(64,g.snapshot().player("p0").damageDealt(),.001);
    }
    @Test public void grenadeUsesBallisticGravityAndExplodesAtExactlyTwoSeconds() {
        var g=game();for(int i=1;i<4;i++) { g.bodies.respawn(id(i),10+i*.2f,3); }
        pickup("GRENADE");g.items.attack(id(0),1);var first=g.items.shotViews().get(0);
        g.beforeTick(2);g.items.update(2);assertTrue(g.items.shotViews().get(0).vy()<first.vy());assertTrue(g.items.shotViews().get(0).y()>first.y());
        for(int tick=3;tick<=120;tick++) { g.beforeTick(tick);g.items.update(tick); }
        assertEquals("GRENADE",g.items.shotViews().get(0).type());g.beforeTick(121);g.items.update(121);assertEquals("EXPLOSION",g.items.shotViews().get(0).type());
    }
    @Test public void itemAndProjectileCountsAreBoundedAndCleanedOnClose() {
        var g=game();for(int i=0;i<100;i++) { g.items.drop("SHIELD",3.5f,3.5f,1); }
        assertEquals(16,g.items.itemViews().size());
        for(int i=0;i<100;i++) { g.items.orbit(id(0),1); }assertEquals(64,g.items.shotViews().size());
        g.items.close();assertTrue(g.items.itemViews().isEmpty());assertTrue(g.items.shotViews().isEmpty());
    }
    @Test public void pickupStatsCountWeaponsSeparatelyAndAwardTiesAreSharedWithoutZeroWinners() {
        pickup("HEALTH");pickup("SHOTGUN");var stats=game().snapshot();assertEquals(2,stats.player("p0").itemsCollected());assertEquals(1,stats.player("p0").weaponsCollected());
        assertEquals(List.of("p0"),stats.leaders("WEAPONS"));assertTrue(stats.leaders("DAMAGE").isEmpty());
        var g=game();g.beforeTick(2);g.damage(id(0),id(2),"LASER",10,0,0);g.damage(id(1),id(3),"MELEE",10,0,0);
        assertEquals(List.of("p0","p1"),g.snapshot().leaders("DAMAGE"));
    }
    @Test public void weaponKillAttributionCountsEffectiveDamageAndOneKoPerVictimStock() {
        var g=game();g.bodies.respawn(id(1),5,3);g.bodies.respawn(id(2),5.3f,3);g.bodies.respawn(id(3),8,1);
        g.state.getPlayer(id(1)).applyDamage(90);g.state.getPlayer(id(2)).applyDamage(90);
        pickup("BAZOOKA");g.items.attack(id(0),1);
        for(int tick=2;tick<25;tick++) { g.beforeTick(tick);g.items.update(tick); }
        g.afterTick();g.afterTick();var p=g.snapshot().player("p0");assertEquals(20,p.damageDealt(),.001);assertEquals(2,p.kills());
        var kills=g.eventsAfter(0).stream().filter(e->e.type().equals("WEAPON_KILL")).toList();assertEquals(2,kills.size());
        for(var event:kills) { assertEquals("p0",event.killer());assertEquals("BAZOOKA",event.weapon()); }
    }
    @Test public void configuredDropIntervalsSpawnAtTheBoundaryInsteadOfEveryTick() {
        for(var rate:MatchRules.DropRate.values()) {
            var setup=new MatchSetup("drops_"+rate,"room",List.of(new MatchSetup.Participant(id(0),0,"Alex"),new MatchSetup.Participant(id(1),1,"Leo")),new MatchRules(2,0,3,rate,true));
            try(var r=new MatchRuntime(setup)) {
                r.start();var g=r.gameplay();int interval=rate.intervalTicks;
                if(interval==0) { g.beforeTick(10000);g.items.update(10000);assertTrue(g.items.itemViews().isEmpty());continue; }
                g.beforeTick(interval-1);g.items.update(interval-1);assertTrue(g.items.itemViews().isEmpty());
                g.beforeTick(interval);g.items.update(interval);
                int count=g.items.itemViews().size()+g.snapshot().players().stream().mapToInt(GameplaySnapshot.Player::itemsCollected).sum();assertEquals(1,count);
                g.beforeTick(interval+1);g.items.update(interval+1);
                assertEquals(1,g.items.itemViews().size()+g.snapshot().players().stream().mapToInt(GameplaySnapshot.Player::itemsCollected).sum());
            }
        }
    }
    @Test public void terrainStopsShotgunBeforeAPlayerBehindTheWallWhileLaserPiercesIt() {
        var g=game();var original=new com.smashprofs.server.game.physics.TmxCollisionLoader().loadOriginalMap();
        var rectangles=new ArrayList<>(original.rectangles());rectangles.add(new com.smashprofs.server.game.physics.MapCollisionDefinition.CollisionRectangle(999,480,250,5,100));
        var geometry=new com.smashprofs.server.game.physics.MapCollisionDefinition("test-wall",1200,400,rectangles);
        try(var weapons=new ItemSystem(g,geometry,new Random(1))) {
            weapons.drop("SHOTGUN",4.5f,3,1);g.beforeTick(1);weapons.update(1);weapons.attack(id(0),1);
            for(int tick=2;tick<=30;tick++) { g.beforeTick(tick);weapons.update(tick); }
            assertEquals(100,g.state.getPlayer(id(1)).getHp(),0);
            weapons.drop("LASER",4.5f,3,31);g.beforeTick(31);weapons.update(31);weapons.attack(id(0),31);
            for(int tick=32;tick<=34;tick++) { g.beforeTick(tick);weapons.update(tick); }
            assertEquals(76,g.state.getPlayer(id(1)).getHp(),0);
        }
    }
}
