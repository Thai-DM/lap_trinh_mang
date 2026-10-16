package com.smashprofs.server.network;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.protocol.*;
import com.smashprofs.server.game.AuthoritativeMatchResult;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.Assert.*;

public class GameEndMessageMapperTest {
    private final PlayerId winner = new PlayerId("người-thắng");
    private AuthoritativeMatchResult result(boolean draw) {
        return new AuthoritativeMatchResult("match", "arena", 7200, draw ? null : winner,
                AuthoritativeMatchResult.FinishReason.TIME_LIMIT, List.of(
                new AuthoritativeMatchResult.ParticipantResult(winner, 0, "Alex", 99.5f, false),
                new AuthoritativeMatchResult.ParticipantResult(new PlayerId("b"), 1, "Leo", 91.25f, false)));
    }
    @Test public void gameEndUsesProtocolV1() throws Exception {
        var codec = new JsonFrameCodec(); var message = GameEndMessageMapper.map(result(false));
        byte[] frame = codec.encode(message);
        assertEquals(frame.length - 4, ByteBuffer.wrap(frame).getInt());
        assertTrue(new String(frame, 4, frame.length - 4, StandardCharsets.UTF_8).contains("người-thắng"));
        var decoded = codec.readNext(new ByteArrayInputStream(frame));
        assertEquals(1, decoded.version()); assertEquals(MessageType.GAME_END, decoded.type()); assertNull(decoded.requestId());
        assertArrayEquals(frame, codec.encode(decoded));
    }
    @Test public void gameEndMapsCorrectMatchResult() {
        var p = GameEndMessageMapper.map(result(false)).payload();
        assertEquals(6, p.size()); assertEquals("match", p.get("matchId").textValue());
        assertEquals("arena", p.get("roomId").textValue()); assertEquals(7200, p.get("finishedAtTick").longValue());
        assertEquals("TIME_LIMIT", p.get("finishReason").textValue());
    }
    @Test public void gameEndPreservesWinner() { assertEquals(winner.value(), GameEndMessageMapper.map(result(false)).payload().get("winnerId").textValue()); }
    @Test public void gameEndSupportsDraw() { assertTrue(GameEndMessageMapper.map(result(true)).payload().get("winnerId").isNull()); }
    @Test public void gameEndContainsCorrectParticipants() {
        var participants = GameEndMessageMapper.map(result(false)).payload().get("participants");
        assertEquals(2, participants.size()); assertEquals(winner.value(), participants.get(0).get("playerId").textValue());
        assertEquals(0, participants.get(0).get("slotIndex").intValue()); assertEquals("Alex", participants.get(0).get("characterId").textValue());
        assertEquals(99.5, participants.get(0).get("finalHp").doubleValue(), 0); assertFalse(participants.get(0).get("eliminated").booleanValue());
        assertEquals(91.25, participants.get(1).get("finalHp").doubleValue(), 0);
    }
    @Test public void gameEndDoesNotMutateMatchResult() {
        var original = result(false); var expected = result(false);
        var message = GameEndMessageMapper.map(original); message.payload().removeAll();
        assertEquals(expected, original); assertThrows(UnsupportedOperationException.class, () -> original.participants().clear());
        assertEquals(6, message.payload().size());
    }
}
