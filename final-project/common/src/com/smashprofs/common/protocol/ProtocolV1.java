package com.smashprofs.common.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import java.util.Set;

/** Quy tắc payload inbound dùng chung; không có socket, room hoặc gameplay state. */
public final class ProtocolV1 {
    public static final int VERSION = 1;
    public static final int MAX_PAYLOAD_BYTES = 1_048_576;
    public static final int MAX_FRAME_BYTES = MAX_PAYLOAD_BYTES + 4;
    public static final Set<MessageType> MVP_TYPES = Set.of(MessageType.CONNECT, MessageType.CONNECT_ACK,
            MessageType.CREATE_ROOM, MessageType.ROOM_CREATED, MessageType.JOIN_ROOM, MessageType.ROOM_STATE,
            MessageType.LEAVE_ROOM, MessageType.PLAYER_READY, MessageType.GAME_START, MessageType.PLAYER_INPUT,
            MessageType.GAME_STATE, MessageType.GAME_END, MessageType.ERROR, MessageType.PING, MessageType.PONG);
    public static final Set<MessageType> CORRELATED_REQUESTS = Set.of(MessageType.CONNECT, MessageType.CREATE_ROOM,
            MessageType.JOIN_ROOM, MessageType.LEAVE_ROOM, MessageType.PLAYER_READY, MessageType.PING);
    private ProtocolV1() { }

    public static ObjectNode object() { return JsonNodeFactory.instance.objectNode(); }
    public static ProtocolMessage error(String requestId, String code, String message) {
        return new ProtocolMessage(MessageType.ERROR, requestId, object().put("code", code).put("message", message));
    }
    public static void fields(ObjectNode payload, String requestId, String... names) throws ProtocolException {
        Set<String> allowed = Set.of(names);
        var fields = payload.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (field.equals("playerId") || field.equals("sessionId")) {
                throw new ProtocolException("UNAUTHORIZED_PLAYER", "Client must not supply player identity", requestId);
            }
            if (!allowed.contains(field)) { throw invalid(requestId); }
        }
    }
    public static String roomId(ObjectNode payload, String requestId) throws ProtocolException {
        JsonNode id = payload.get("roomId");
        if (id == null || !id.isTextual() || !id.textValue().matches("[A-Za-z0-9_-]{1,64}")) {
            throw invalid(requestId);
        }
        return id.textValue();
    }
    public static boolean bool(ObjectNode payload, String field, String requestId) throws ProtocolException {
        JsonNode value = payload.get(field);
        if (value == null || !value.isBoolean()) { throw invalid(requestId); }
        return value.booleanValue();
    }
    public static long nonNegativeLong(ObjectNode payload, String field, String requestId) throws ProtocolException {
        JsonNode value = payload.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalid(requestId);
        }
        return value.longValue();
    }
    /** Identity chỉ đến từ session; giữ đúng DTO/edge/held đã có, không thêm model input khác. */
    public static PlayerInput playerInput(ProtocolMessage message, PlayerId trustedPlayerId) throws ProtocolException {
        ObjectNode p = message.payload();
        String id = message.requestId();
        fields(p, id, "sequence", "clientTick", "moveAxis", "jumpPressed", "stompPressed", "meleePressed",
                "abilityPressed", "blockHeld");
        JsonNode axis = p.get("moveAxis");
        if (axis == null || !axis.isNumber() || !Double.isFinite(axis.doubleValue())
                || axis.doubleValue() < -1 || axis.doubleValue() > 1) { throw invalid(id); }
        return new PlayerInput(trustedPlayerId, nonNegativeLong(p, "sequence", id), nonNegativeLong(p, "clientTick", id),
                axis.floatValue(), bool(p, "jumpPressed", id), bool(p, "stompPressed", id), bool(p, "meleePressed", id),
                bool(p, "blockHeld", id), bool(p, "abilityPressed", id));
    }
    /** Bỏ PlayerId của DTO khi encode input: không bao giờ đưa ID client tự chọn lên wire. */
    public static ObjectNode inputPayload(PlayerInput input) {
        return object().put("sequence", input.sequence()).put("clientTick", input.clientTick())
                .put("moveAxis", input.moveAxis()).put("jumpPressed", input.jumpPressed())
                .put("stompPressed", input.stompPressed()).put("meleePressed", input.meleePressed())
                .put("abilityPressed", input.abilityPressed()).put("blockHeld", input.blockHeld());
    }
    public static ProtocolException invalid(String requestId) {
        return new ProtocolException("INVALID_PAYLOAD", "Invalid message payload", requestId);
    }
}
