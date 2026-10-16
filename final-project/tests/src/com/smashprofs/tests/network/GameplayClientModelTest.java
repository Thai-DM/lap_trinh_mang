package com.smashprofs.tests.network;

import com.smashprofs.common.model.*;
import com.smashprofs.common.protocol.*;
import com.smashprofs.game.network.client.*;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class GameplayClientModelTest {
    @Test public void ammoIsOptionalForOldSnapshotsAndValidatedWhenPresent() {
        var encoded=GameplayProtocol.snapshot(state());assertEquals(-1,GameplayProtocol.snapshot(encoded).player("p0").ammo());
        var player=(com.fasterxml.jackson.databind.node.ObjectNode)encoded.path("players").get(0);player.put("ammo",5);
        assertEquals(5,GameplayProtocol.snapshot(encoded).player("p0").ammo());
        player.put("ammo",7);assertThrows(IllegalArgumentException.class,()->GameplayProtocol.snapshot(encoded));
        player.put("ammo",-1);assertThrows(IllegalArgumentException.class,()->GameplayProtocol.snapshot(encoded));
    }
    private GameplaySnapshot state() {
        return new GameplaySnapshot(MatchRules.DEFAULT,600,List.of(
                new GameplaySnapshot.Player("p0",2,"ACTIVE",0,30,120,"SHOTGUN",true,20,3,1,1,1,"WALL_SLIDING"),
                new GameplaySnapshot.Player("p1",0,"ELIMINATED",0,0,0,"NONE",false,20,2,1,1,3,"NONE")),List.of(new GameplaySnapshot.Item(1,"HEALTH",4,2)),List.of(new GameplaySnapshot.Shot(2,"GRENADE","p0",5,2,1,1)));
    }
    @Test public void extensionRoundTripsImmutablyWithSharedMvpTiesAndNoZeroAwards() {
        var state=state();assertEquals(state,GameplayProtocol.snapshot(GameplayProtocol.snapshot(state)));
        assertEquals(List.of("p0","p1"),state.leaders("DAMAGE"));assertEquals(List.of("p0","p1"),state.leaders("WEAPONS"));
        assertThrows(UnsupportedOperationException.class,()->state.players().clear());
        var bad=GameplayProtocol.snapshot(state);((com.fasterxml.jackson.databind.node.ObjectNode)bad.path("players").get(0)).put("lives",99);
        assertThrows(IllegalArgumentException.class,()->GameplayProtocol.snapshot(bad));
    }
    @Test public void feedDeduplicatesReliableEventsFadesAndExpiresWithoutNewSnapshots() {
        var clock=new AtomicLong();var feed=new KillFeedModel(clock::get);var death=new MatchEvent(1,10,"PLAYER_KILLED","p0","p1","BAZOOKA","HP_ZERO");
        assertTrue(feed.accept(death));assertFalse(feed.accept(death));assertEquals(1,feed.entries().size());
        clock.set(3_500_000_000L);assertEquals(.5f,feed.entries().get(0).alpha(),.001f);
        clock.set(4_000_000_000L);assertTrue(feed.entries().isEmpty());
        assertThrows(IllegalArgumentException.class,()->feed.accept(new MatchEvent(3,20,"MATCH_END",null,null,"NONE","END")));
    }
}
