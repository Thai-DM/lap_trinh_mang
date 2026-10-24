package com.smashprofs.web;
import com.smashprofs.web.json.*;
import com.smashprofs.common.protocol.*;
import com.smashprofs.game.network.client.WireGameSnapshot;
import java.nio.file.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises the real shared validators using captured server frames, not invented browser state. */
public class PortableProtocolTest {
    @Test public void capturedBossFramesPassSharedSnapshotValidation() throws Exception {
        var root=JsonNode.parse(Files.readString(Path.of("../../docs/gameplay/runs/20261022-teavm-network/verification.json")));
        int frames=0;
        for(var state:root.path("states")) {
            var node=state.get("snapshot");if(node==null||node.isNull()) { continue; }
            var snapshot=WireGameSnapshot.parse((ObjectNode)node);
            assertEquals(2,snapshot.players().size());assertNotNull(snapshot.gameplay().boss());
            assertEquals(node,JsonNode.parse(node.toString()));frames++;
        }
        assertTrue("Expected recorded multiplayer frames",frames>=3);
    }
    @Test public void deepCopiesDoNotShareMutableProtocolTrees() {
        var source=ProtocolV1.object();source.putArray("players").addObject().put("name","Việt Nam \"\\\n");
        var message=new ProtocolMessage(MessageType.ROOM_STATE,null,source);
        source.put("extra",true);((ObjectNode)source.path("players").get(0)).put("name","changed");
        assertFalse(message.payload().has("extra"));
        assertEquals("Việt Nam \"\\\n",message.payload().path("players").get(0).path("name").asText());
        assertEquals(message.payload(),JsonNode.parse(message.payload().toString()));
    }
    @Test public void missingNullAndNumericTypesStayDistinct() {
        var root=JsonNode.parse("{\"empty\":null,\"count\":7,\"fraction\":0.5,\"flag\":false}");
        assertNull(root.get("absent"));assertTrue(root.path("absent").isMissingNode());
        assertTrue(root.path("empty").isNull());assertFalse(root.path("empty").isMissingNode());
        assertTrue(root.path("count").isIntegralNumber());assertFalse(root.path("fraction").isIntegralNumber());
        assertFalse(root.path("flag").asBoolean(true));
    }
    @Test(expected=IllegalArgumentException.class) public void duplicateKeysAreRejected() { JsonNode.parse("{\"version\":1,\"version\":2}"); }
    @Test(expected=IllegalArgumentException.class) public void excessiveNestingIsRejected() { JsonNode.parse("[".repeat(34)+"0"+"]".repeat(34)); }
    @Test public void rankingPayloadStillUsesSharedValidator() {
        var request=RankingProtocol.createGetLeaderboardMessage("test",50);
        assertEquals(MessageType.GET_LEADERBOARD,request.type());
        assertEquals(50,request.payload().path("limit").asInt());
        assertEquals(request.payload(),JsonNode.parse(request.payload().toString()));
    }
    @Test public void v1EnvelopeRoundTripsWithoutLosingRequestIdOrPayload() throws Exception {
        var message=new ProtocolMessage(MessageType.CONNECT,"request-1",RankingProtocol.connectPayload("web-profile","Việt Nam","boss-v1"));
        assertEquals(message,WebProtocol.decode(WebProtocol.encode(message)));
    }
    @Test(expected=ProtocolException.class) public void fractionalEnvelopeVersionIsRejected() throws Exception { WebProtocol.decode("{\"version\":1.5,\"type\":\"GAME_STATE\",\"payload\":{}}"); }
    @Test(expected=ProtocolException.class) public void unexpectedEnvelopeIdentityIsRejected() throws Exception { WebProtocol.decode("{\"version\":1,\"type\":\"GAME_STATE\",\"playerId\":\"spoof\",\"payload\":{}}"); }
    @Test(expected=ProtocolException.class) public void correlatedRequestNeedsRequestId() throws Exception { WebProtocol.decode("{\"version\":1,\"type\":\"CONNECT\",\"payload\":{}}"); }
}
