package com.smashprofs.server.room;

import com.smashprofs.common.model.*;
import com.smashprofs.common.protocol.*;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class HostRulesTest {
    @Test public void hostValidatesCapacityOwnershipAndLocksRulesAtCountdown() {
        for(int size=2;size<=4;size++) {
            var clock=new AtomicLong(); var room=new GameRoom("r",clock::get); var host=new PlayerId("host");
            room.configureGameplay(host); room.joinPlayer(host);
            room.updateSettings(host,new MatchRules(size,0,5,MatchRules.DropRate.HIGH,true));
            assertThrows(SecurityException.class,()->room.updateSettings(new PlayerId("intruder"),MatchRules.DEFAULT));
            for(int i=1;i<size;i++) { assertEquals(GameRoom.JoinResult.JOINED,room.joinPlayer(new PlayerId("p"+i))); }
            assertEquals(GameRoom.JoinResult.ROOM_FULL,room.joinPlayer(new PlayerId("extra")));
            for(var slot:room.getPlayerSlots()) { room.selectCharacter(slot.playerId(),"Alex");room.setReady(slot.playerId(),true); }
            assertTrue(room.startCountdown());
            assertThrows(IllegalStateException.class,()->room.updateSettings(host,MatchRules.DEFAULT));
            clock.set(3_000_000_000L);room.updateCountdown();
            var setup=room.prepareMatch(); assertEquals(0,setup.rules().matchSeconds());assertEquals(5,setup.rules().lives());
        }
    }
    @Test public void invalidSettingsAreRejectedWithoutChangingRules() {
        for(int value:new int[]{-1,1,5}) { assertThrows(IllegalArgumentException.class,()->new MatchRules(value,120,3,MatchRules.DropRate.NONE,true)); }
        for(int value:new int[]{-1,1,59,301}) { assertThrows(IllegalArgumentException.class,()->new MatchRules(4,value,3,MatchRules.DropRate.NONE,true)); }
        for(int value:new int[]{0,4,6}) { assertThrows(IllegalArgumentException.class,()->new MatchRules(4,120,value,MatchRules.DropRate.NONE,true)); }
        assertEquals(MatchRules.DEFAULT,GameplayProtocol.rules(GameplayProtocol.rules(MatchRules.DEFAULT)));
    }
    @Test public void fighterRoomsDefaultToMediumDropsAndTheHostCanChangeTheRate() {
        var room=new GameRoom("fighter");var host=new PlayerId("host");room.configureGameplay(host,true);room.joinPlayer(host);
        assertEquals(MatchRules.DropRate.MEDIUM,room.getRules().dropRate());
        for(var rate:MatchRules.DropRate.values()) {
            room.updateSettings(host,new MatchRules(4,120,3,rate,true,true));
            assertEquals(rate,room.getRules().dropRate());assertTrue(room.getRules().fighter());
        }
    }
}
