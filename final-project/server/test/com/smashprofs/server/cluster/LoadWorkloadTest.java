package com.smashprofs.server.cluster;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.protocol.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class LoadWorkloadTest {
    @Test public void allProfilesProduceInputsAcceptedByTheExistingProtocolValidator() throws Exception {
        var player=new PlayerId("bot");
        for(var profile:LoadWorkload.Profile.values()) {
            for(int i=0;i<100;i++) {
                var input=LoadWorkload.input(profile,player,i,0);
                var parsed=ProtocolV1.playerInput(new ProtocolMessage(MessageType.PLAYER_INPUT,null,ProtocolV1.inputPayload(input)),player);
                assertEquals(input,parsed);
            }
        }
    }
    @Test public void imbalancedRoomsUseDifferentWorkloadsWithoutChangingInputIdentity() {
        var player=new PlayerId("bot");
        assertTrue(LoadWorkload.input(LoadWorkload.Profile.IMBALANCED,player,0,0).abilityPressed());
        var idle=LoadWorkload.input(LoadWorkload.Profile.IMBALANCED,player,0,4);
        assertEquals(0,idle.moveAxis(),0); assertFalse(idle.abilityPressed()); assertEquals(player,idle.playerId());
    }
}
