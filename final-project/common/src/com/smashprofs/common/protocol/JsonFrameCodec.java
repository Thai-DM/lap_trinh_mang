package com.smashprofs.common.protocol;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;

/** JSON UTF-8 + length int32 BE. Không giữ buffer stream, không sở hữu/đóng socket. */
public final class JsonFrameCodec {
    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(4096).maxNumberLength(32).maxNameLength(64).build()).build());

    public ProtocolMessage readNext(InputStream input) throws IOException {
        return readNext(input, ProtocolV1.MAX_FRAME_BYTES);
    }
    /** maxFrameBytes tính CẢ header, tương thích guard transport của Bước 43. */
    public ProtocolMessage readNext(InputStream input, int maxFrameBytes) throws IOException {
        Objects.requireNonNull(input);
        int first = input.read();
        if (first == -1) { return null; } // Chỉ EOF trước header mới là EOF sạch.
        DataInputStream data = new DataInputStream(input);
        int length = (first << 24) | (data.readUnsignedByte() << 16)
                | (data.readUnsignedByte() << 8) | data.readUnsignedByte();
        if (length < 1 || length > ProtocolV1.MAX_PAYLOAD_BYTES || (long) length + 4 > maxFrameBytes) {
            throw new IOException("Invalid frame length"); // Kiểm tra TRƯỚC allocation.
        }
        byte[] bytes = new byte[length];
        data.readFully(bytes); // EOF giữa header/body là lỗi framing fatal.
        return decodeJson(bytes);
    }
    public ProtocolMessage decodeJson(byte[] bytes) throws ProtocolException {
        if (bytes.length < 1 || bytes.length > ProtocolV1.MAX_PAYLOAD_BYTES) { throw ProtocolV1.invalid(null); }
        final String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) { throw ProtocolV1.invalid(null); }
        final JsonNode root;
        try (var parser = mapper.createParser(json)) {
            root = mapper.readTree(parser);
            if (parser.nextToken() != null) { throw ProtocolV1.invalid(null); }
        } catch (IOException | RuntimeException failure) { throw ProtocolV1.invalid(null); }
        if (root == null || !root.isObject()) { throw ProtocolV1.invalid(null); }
        ObjectNode envelope = (ObjectNode) root;
        String requestId = null;
        if (envelope.has("requestId")) {
            JsonNode id = envelope.get("requestId");
            if (!id.isTextual() || id.textValue().isEmpty() || id.textValue().codePointCount(0, id.textValue().length()) > 64) {
                throw ProtocolV1.invalid(null);
            }
            requestId = id.textValue();
        }
        var fields = envelope.fieldNames();
        while (fields.hasNext()) {
            if (!Set.of("version", "type", "requestId", "payload").contains(fields.next())) {
                throw ProtocolV1.invalid(requestId);
            }
        }
        JsonNode version = envelope.get("version");
        if (version == null || !version.isIntegralNumber() || !version.canConvertToInt()) { throw ProtocolV1.invalid(requestId); }
        if (version.intValue() != 1) {
            throw new ProtocolException("UNSUPPORTED_VERSION", "Only protocol version 1 is supported", requestId);
        }
        JsonNode typeNode = envelope.get("type");
        if (typeNode == null || !typeNode.isTextual()) { throw ProtocolV1.invalid(requestId); }
        MessageType type;
        try { type = MessageType.valueOf(typeNode.textValue()); }
        catch (IllegalArgumentException failure) {
            throw new ProtocolException("UNKNOWN_MESSAGE_TYPE", "Unknown message type", requestId);
        }
        if (!ProtocolV1.MVP_TYPES.contains(type)) {
            throw new ProtocolException("UNKNOWN_MESSAGE_TYPE", "Message is not part of TCP v1 MVP", requestId);
        }
        JsonNode payload = envelope.get("payload");
        if (payload == null || !payload.isObject()) { throw ProtocolV1.invalid(requestId); }
        if (ProtocolV1.CORRELATED_REQUESTS.contains(type) && requestId == null) { throw ProtocolV1.invalid(null); }
        return new ProtocolMessage(type, requestId, (ObjectNode) payload);
    }
    public byte[] encode(ProtocolMessage message) throws IOException {
        return encode(message, ProtocolV1.MAX_FRAME_BYTES);
    }
    public byte[] encode(ProtocolMessage message, int maxFrameBytes) throws IOException {
        if (maxFrameBytes < 5) { throw new IOException("Frame budget must include header and payload"); }
        ObjectNode envelope = ProtocolV1.object().put("version", message.version()).put("type", message.type().name());
        if (message.requestId() != null) { envelope.put("requestId", message.requestId()); }
        envelope.set("payload", message.payload());
        // Writer có cap: không serialize một object quá lớn rồi mới kiểm tra sau allocation.
        var buffer = new ByteArrayOutputStream();
        int limit = Math.min(ProtocolV1.MAX_PAYLOAD_BYTES, maxFrameBytes - 4);
        OutputStream bounded = new OutputStream() {
            private int size;
            @Override public void write(int value) throws IOException {
                if (size >= limit) { throw new IOException("Frame payload too large"); }
                buffer.write(value); size++;
            }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                if ((long) size + len > limit) { throw new IOException("Frame payload too large"); }
                buffer.write(b, off, len); size += len;
            }
        };
        mapper.writeValue(bounded, envelope);
        byte[] bytes = buffer.toByteArray();
        decodeJson(bytes); // Cùng quy tắc envelope/limits ở hai chiều, không coercion mặc định.
        return ByteBuffer.allocate(bytes.length + 4).putInt(bytes.length).put(bytes).array();
    }
}
