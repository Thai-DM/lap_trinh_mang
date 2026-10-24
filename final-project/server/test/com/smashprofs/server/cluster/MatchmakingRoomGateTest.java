package com.smashprofs.server.cluster;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.server.room.GameRoom;
import org.junit.Test;
import static org.junit.Assert.*;

public class MatchmakingRoomGateTest {
    @Test public void fourPlayerAssignmentCannotStartBeforeEntireGroupConnects() {
        var room=new GameRoom("four"); room.setMinimumPlayers(4);
        for(int i=0;i<4;i++) {
            var player=new PlayerId("p"+i); room.joinPlayer(player);
            room.selectCharacter(player,"Alex"); room.setReady(player,true);
            assertEquals(i==3,room.canStartMatch());
        }
    }
}
