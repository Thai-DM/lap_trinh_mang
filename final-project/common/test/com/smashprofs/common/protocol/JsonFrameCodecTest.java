package com.smashprofs.common.protocol;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import org.junit.Test;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import static org.junit.Assert.*;

/** Golden bytes và kiểm tra độc lập framing/envelope; không socket hoặc sleep. */
public class JsonFrameCodecTest {
    private final JsonFrameCodec codec = new JsonFrameCodec();
    private static final String CONNECT = "{\"version\":1,\"type\":\"CONNECT\",\"requestId\":\"r1\",\"payload\":{}}";
    private ProtocolMessage connect() { return new ProtocolMessage(MessageType.CONNECT, "r1", ProtocolV1.object()); }
    private ProtocolException invalid(String json) {
        return assertThrows(ProtocolException.class, () -> codec.decodeJson(json.getBytes(StandardCharsets.UTF_8)));
    }
    @Test public void goldenConnectHasExactBytesAndBigEndianHeader() throws Exception {
        byte[] expected = HexFormat.of().parseHex("0000003c7b2276657273696f6e223a312c2274797065223a22434f4e4e454354222c22726571756573744964223a227231222c227061796c6f6164223a7b7d7d");
        assertArrayEquals(expected, codec.encode(connect()));
        assertEquals(connect(), codec.readNext(new ByteArrayInputStream(expected)));
    }
    @Test public void allMvpGoldenVectorsDecodeAndReencodeExactly() throws Exception {
        int count = 0;
        try (var reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/protocol-v1-vectors.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#") || line.isBlank()) { continue; }
                String[] parts = line.split("\\|", 2);
                byte[] header = HexFormat.of().parseHex(parts[0]);
                byte[] body = parts[1].getBytes(StandardCharsets.UTF_8);
                assertEquals(ByteBuffer.wrap(header).getInt(), body.length);
                byte[] expected = ByteBuffer.allocate(body.length + 4).put(header).put(body).array();
                ProtocolMessage message = codec.readNext(new ByteArrayInputStream(expected));
                assertArrayEquals(expected, codec.encode(message));
                assertEquals(message, codec.decodeJson(body));
                if (message.type() == MessageType.GAME_STATE) {
                    assertEquals(99.5, message.payload().get("players").get(0).get("hp").doubleValue(), 0);
                    assertEquals(91.25, message.payload().get("players").get(1).get("hp").doubleValue(), 0);
                    assertEquals("IBM", message.payload().get("projectiles").get(0).get("projectileType").textValue());
                }
                count++;
            }
        }
        assertEquals(15, count);
    }
    @Test public void fragmentedStreamReadsExactlyOneFrame() throws Exception {
        byte[] bytes = codec.encode(connect());
        InputStream oneByte = new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] b, int off, int len) { return super.read(b, off, Math.min(1, len)); }
        };
        assertEquals(connect(), codec.readNext(oneByte)); assertNull(codec.readNext(oneByte));
    }
    @Test public void multipleFramesPreserveBoundaries() throws Exception {
        var bytes = new ByteArrayOutputStream(); bytes.write(codec.encode(connect())); bytes.write(codec.encode(connect()));
        var input = new ByteArrayInputStream(bytes.toByteArray());
        assertEquals(connect(), codec.readNext(input)); assertEquals(connect(), codec.readNext(input)); assertNull(codec.readNext(input));
    }
    @Test public void utf8UnicodeCountsBytesNotCharacters() throws Exception {
        var message = ProtocolV1.error("yêu-cầu", "INVALID_PAYLOAD", "Lỗi 🎮");
        byte[] bytes = codec.encode(message);
        assertEquals(bytes.length - 4, ByteBuffer.wrap(bytes).getInt());
        assertEquals(message, codec.readNext(new ByteArrayInputStream(bytes)));
    }
    @Test public void cleanEofReturnsNull() throws Exception { assertNull(codec.readNext(new ByteArrayInputStream(new byte[0]))); }
    @Test public void truncatedHeaderIsFatal() {
        for (int n = 1; n < 4; n++) {
            byte[] bytes = new byte[n];
            assertThrows(EOFException.class, () -> codec.readNext(new ByteArrayInputStream(bytes)));
        }
    }
    @Test public void truncatedBodyIsFatal() {
        assertThrows(EOFException.class, () -> codec.readNext(new ByteArrayInputStream(new byte[]{0,0,0,4,123})));
    }
    @Test public void illegalLengthIsRejectedBeforeAllocation() {
        for (int length : new int[]{0, -1, Integer.MIN_VALUE, 1_048_577, Integer.MAX_VALUE}) {
            assertThrows(IOException.class, () -> codec.readNext(new ByteArrayInputStream(ByteBuffer.allocate(4).putInt(length).array())));
        }
    }
    @Test public void exactMaximumPayloadIsAccepted() throws Exception {
        byte[] body = new byte[ProtocolV1.MAX_PAYLOAD_BYTES]; java.util.Arrays.fill(body, (byte)' ');
        byte[] json = CONNECT.getBytes(StandardCharsets.UTF_8); System.arraycopy(json, 0, body, 0, json.length);
        assertEquals(connect(), codec.readNext(new ByteArrayInputStream(ByteBuffer.allocate(body.length + 4).putInt(body.length).put(body).array())));
    }
    @Test public void transportLimitIncludesHeader() {
        assertThrows(IOException.class, () -> codec.readNext(new ByteArrayInputStream(new byte[]{0,0,0,60}), 63));
        assertThrows(IOException.class, () -> codec.encode(connect(), 63));
        for (int limit : new int[]{Integer.MIN_VALUE, -1, 0, 4}) {
            assertThrows(IOException.class, () -> codec.encode(connect(), limit));
        }
    }
    @Test public void malformedJsonIsApplicationError() { assertEquals("INVALID_PAYLOAD", invalid("{").code()); }
    @Test public void duplicateEnvelopeKeysAreRejected() {
        assertEquals("INVALID_PAYLOAD", invalid(CONNECT.replace("\"version\":1", "\"version\":1,\"version\":1")).code());
    }
    @Test public void duplicateNestedKeysAreRejected() {
        assertEquals("INVALID_PAYLOAD", invalid(CONNECT.replace("\"payload\":{}", "\"payload\":{\"x\":{\"a\":1,\"a\":2}}")).code());
    }
    @Test public void trailingSecondJsonIsRejected() { invalid(CONNECT + "{}"); }
    @Test public void unsupportedVersionKeepsCorrelation() {
        var error = invalid(CONNECT.replace("\"version\":1", "\"version\":2"));
        assertEquals("UNSUPPORTED_VERSION", error.code()); assertEquals("r1", error.requestId());
    }
    @Test public void unknownTypeKeepsCorrelation() {
        var error = invalid(CONNECT.replace("CONNECT", "WHATEVER"));
        assertEquals("UNKNOWN_MESSAGE_TYPE", error.code()); assertEquals("r1", error.requestId());
    }
    @Test public void legacyTypesAreNotSilentlyEnabledOnWire() { assertEquals("UNKNOWN_MESSAGE_TYPE", invalid(CONNECT.replace("CONNECT", "MATCH_RESULT")).code()); }
    @Test public void requiredEnvelopeFieldsAndTypesAreStrict() {
        for (String json : List.of("{}", "[]", "null", CONNECT.replace("\"version\":1,", ""),
                CONNECT.replace("\"type\":\"CONNECT\",", ""), CONNECT.replace(",\"payload\":{}", ""),
                CONNECT.replace("\"version\":1", "\"version\":1.0"), CONNECT.replace("\"payload\":{}", "\"payload\":null"),
                CONNECT.replace("\"payload\":{}", "\"payload\":[]"))) { invalid(json); }
    }
    @Test public void requestIdIsRequiredAndBounded() {
        invalid(CONNECT.replace("\"requestId\":\"r1\",", "")); invalid(CONNECT.replace("r1", "a".repeat(65)));
        invalid(CONNECT.replace("\"r1\"", "null")); invalid(CONNECT.replace("r1", ""));
    }
    @Test public void invalidUtf8IsRejected() {
        assertEquals("INVALID_PAYLOAD", assertThrows(ProtocolException.class, () -> codec.decodeJson(new byte[]{(byte)0xc3, 0x28})).code());
    }
    @Test public void malformedFrameCanBeFollowedByGoodFrame() throws Exception {
        var bytes = new ByteArrayOutputStream(); new DataOutputStream(bytes).writeInt(1); bytes.write('{'); bytes.write(codec.encode(connect()));
        var input = new ByteArrayInputStream(bytes.toByteArray());
        assertThrows(ProtocolException.class, () -> codec.readNext(input)); assertEquals(connect(), codec.readNext(input));
    }
    @Test public void excessiveDepthIsRejectedWithoutStackOverflow() {
        invalid(CONNECT.replace("\"payload\":{}", "\"payload\":{\"a\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}"));
    }
    @Test public void envelopePayloadIsDefensivelyCopied() {
        var p = ProtocolV1.object().put("x", 1); var message = new ProtocolMessage(MessageType.ERROR, null, p);
        p.put("x", 2); message.payload().put("x", 3); assertEquals(1, message.payload().get("x").intValue());
    }
    private ProtocolMessage inputMessage() {
        return new ProtocolMessage(MessageType.PLAYER_INPUT, null,
                ProtocolV1.inputPayload(new PlayerInput(new PlayerId("ignored"), 0, 1, 1, true, false, false, true, false)));
    }
    @Test public void inputUsesTrustedIdentityAndPreservesEdgeHeldFields() throws Exception {
        var id = new PlayerId("server-assigned");
        var dto = ProtocolV1.playerInput(inputMessage(), id);
        assertSame(id, dto.playerId()); assertTrue(dto.jumpPressed()); assertTrue(dto.blockHeld());
        assertFalse(dto.abilityPressed()); assertEquals(1, dto.moveAxis(), 0); assertFalse(inputMessage().payload().has("playerId"));
    }
    @Test public void inputIdentityFieldIsRejected() {
        var p = inputMessage().payload().put("playerId", "victim");
        assertEquals("UNAUTHORIZED_PLAYER", assertThrows(ProtocolException.class, () -> ProtocolV1.playerInput(
                new ProtocolMessage(MessageType.PLAYER_INPUT, null, p), new PlayerId("owner"))).code());
    }
    @Test public void inputValidationRejectsCoercionAndOutOfRange() {
        for (String field : List.of("sequence", "clientTick", "moveAxis", "jumpPressed", "stompPressed", "meleePressed", "abilityPressed", "blockHeld")) {
            var p = inputMessage().payload(); p.remove(field);
            assertThrows(ProtocolException.class, () -> ProtocolV1.playerInput(new ProtocolMessage(MessageType.PLAYER_INPUT, null, p), new PlayerId("p")));
        }
        for (var p : List.of(inputMessage().payload().put("sequence", -1), inputMessage().payload().put("clientTick", -1),
                inputMessage().payload().put("sequence", 1.5), inputMessage().payload().put("moveAxis", 1.01),
                inputMessage().payload().put("jumpPressed", "true"), inputMessage().payload().put("clientTick", "1"))) {
            assertThrows(ProtocolException.class, () -> ProtocolV1.playerInput(new ProtocolMessage(MessageType.PLAYER_INPUT, null, p), new PlayerId("p")));
        }
    }
}
