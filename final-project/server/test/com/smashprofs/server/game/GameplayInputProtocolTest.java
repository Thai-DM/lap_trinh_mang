package com.smashprofs.server.game;

import com.smashprofs.common.model.*;
import com.smashprofs.common.protocol.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class GameplayInputProtocolTest {
    private final PlayerId trusted=new PlayerId("session-player");
    private PlayerInput input(boolean interact,boolean drop) { return new PlayerInput(trusted,42,60,.5f,false,false,false,false,false,interact,drop); }
    @Test public void oldInputsKeepTheirExactWireShapeAndDefaultInventoryEdgesToFalse() throws Exception {
        var p=ProtocolV1.inputPayload(input(false,false));assertEquals(8,p.size());assertFalse(p.has("playerId"));
        var decoded=ProtocolV1.playerInput(new ProtocolMessage(MessageType.PLAYER_INPUT,null,p),trusted);
        assertEquals(input(false,false),decoded);
    }
    @Test public void optionalEdgesRoundTripUsingTrustedIdentityAndStrictBooleans() throws Exception {
        var p=ProtocolV1.inputPayload(input(true,true));assertEquals(10,p.size());
        assertEquals(input(true,true),ProtocolV1.playerInput(new ProtocolMessage(MessageType.PLAYER_INPUT,null,p),trusted));
        for(String field:new String[]{"interactPressed","dropPressed"}) {
            var invalid=p.deepCopy().put(field,"true");
            assertThrows(ProtocolException.class,()->ProtocolV1.playerInput(new ProtocolMessage(MessageType.PLAYER_INPUT,null,invalid),trusted));
        }
        p.put("playerId","forged");assertThrows(ProtocolException.class,()->ProtocolV1.playerInput(new ProtocolMessage(MessageType.PLAYER_INPUT,null,p),trusted));
    }
}
