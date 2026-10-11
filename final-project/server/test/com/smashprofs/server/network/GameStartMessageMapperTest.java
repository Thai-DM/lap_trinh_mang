package com.smashprofs.server.network;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.protocol.*;
import com.smashprofs.server.game.MatchSetup;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.List;
import static org.junit.Assert.*;

public class GameStartMessageMapperTest {
    private GameStartDelivery sample() {
        return new GameStartDelivery(new MatchSetup("m", "r", List.of(
                new MatchSetup.Participant(new PlayerId("b"), 1, "Leo"), new MatchSetup.Participant(new PlayerId("a"), 0, "Alex"))), () -> true);
    }
    @Test public void mapperUsesExistingSchemaAndProtocolV1() throws Exception {
        var message = GameStartMessageMapper.map(sample()); var codec = new JsonFrameCodec(); byte[] bytes = codec.encode(message);
        assertEquals(bytes.length - 4, ByteBuffer.wrap(bytes).getInt()); assertEquals(1, message.version()); assertEquals(MessageType.GAME_START, message.type());
        assertNull(message.requestId()); assertEquals(message, codec.readNext(new ByteArrayInputStream(bytes))); assertEquals(3, message.payload().size());
        assertEquals("a", message.payload().get("players").get(0).get("playerId").textValue());
        assertEquals("Alex", message.payload().get("players").get(0).get("characterId").textValue());
    }
    @Test public void mapperDoesNotMutateSetupOrExposeMutableReferences() {
        var delivery = sample(); GameStartMessageMapper.map(delivery).payload().removeAll();
        assertEquals("b", delivery.setup().participants().get(0).playerId().value());
        assertThrows(UnsupportedOperationException.class, () -> delivery.setup().participants().clear());
        assertEquals(2, GameStartMessageMapper.map(delivery).payload().get("players").size());
    }
}
