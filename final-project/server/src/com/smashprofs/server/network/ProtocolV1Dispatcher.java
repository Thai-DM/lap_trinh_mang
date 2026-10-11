package com.smashprofs.server.network;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.protocol.*;
import com.smashprofs.server.room.GameRoom;
import com.smashprofs.server.room.RoomManager;
import java.util.UUID;

/** Chỉ chuyển wire request vào handler đã tồn tại; không tick, broadcast hoặc đọc JNI. */
final class ProtocolV1Dispatcher {
    private HeartbeatManager heartbeat;
    void enableHeartbeat(HeartbeatManager heartbeat) { this.heartbeat = heartbeat; }
    private final ConnectionManager connections;
    private final RoomManager rooms;
    private final JoinRoomHandler joins;
    private final PlayerReadyHandler ready;
    private final PlayerInputHandler inputs;
    ProtocolV1Dispatcher(ConnectionManager connections, RoomManager rooms) {
        this.connections = connections;
        this.rooms = rooms;
        joins = new JoinRoomHandler(connections, rooms);
        ready = new PlayerReadyHandler(connections, rooms);
        inputs = new PlayerInputHandler(connections, rooms);
    }
    boolean dispatch(ClientSession session, ProtocolMessage message) {
        ProtocolMessage response;
        boolean accepted = true;
        try {
            // Giữ metadata ổn định trong routing; KHÔNG giữ khóa khi gửi bytes ra socket.
            synchronized (session) { response = route(session, message); }
        } catch (ProtocolException failure) {
            response = ProtocolV1.error(message.requestId(), failure.code(), failure.getMessage());
            accepted = false;
        } catch (RuntimeException failure) {
            response = ProtocolV1.error(message.requestId(), "INTERNAL_ERROR", "Request could not be processed");
            accepted = false;
        }
        if (response != null) {
            session.send(response);
            if (response.type() == MessageType.CONNECT_ACK && heartbeat != null) { heartbeat.connected(session); }
        }
        return accepted;
    }
    private ProtocolMessage route(ClientSession session, ProtocolMessage message) throws ProtocolException {
        String requestId = message.requestId();
        if (!session.isOpen() || connections.get(session.getSessionId()) != session) {
            throw error("INVALID_SESSION", requestId);
        }
        ObjectNode payload = message.payload();
        if (message.type() == MessageType.CONNECT) {
            ProtocolV1.fields(payload, requestId);
            // CONNECT lặp giữ nguyên ID; không lấy PlayerId/IP/port từ client.
            if (session.getPlayerId() == null) { session.bindPlayer(new PlayerId(UUID.randomUUID().toString())); }
            return new ProtocolMessage(MessageType.CONNECT_ACK, requestId,
                    ProtocolV1.object().put("playerId", session.getPlayerId().value()));
        }
        if (session.getPlayerId() == null) { throw error("INVALID_SESSION", requestId); }
        return switch (message.type()) {
            case CREATE_ROOM -> {
                ProtocolV1.fields(payload, requestId, "roomId");
                String roomId = ProtocolV1.roomId(payload, requestId);
                final GameRoom created;
                try { created = rooms.createRoom(roomId); }
                catch (IllegalArgumentException duplicate) { throw error("INVALID_ROOM_STATE", requestId); }
                // Chỉ create: không sửa session binding, không join/ready/start; send nằm ngoài khóa routing.
                yield new ProtocolMessage(MessageType.ROOM_CREATED, requestId,
                        ProtocolV1.object().put("roomId", created.getRoomId()));
            }
            case JOIN_ROOM -> {
                ProtocolV1.fields(payload, requestId, "roomId");
                String roomId = ProtocolV1.roomId(payload, requestId);
                var result = joins.handle(session, roomId);
                if (!result.isSuccess()) {
                    throw error(switch (result.status()) {
                        case ROOM_FULL -> "ROOM_FULL";
                        case INVALID_SESSION -> "INVALID_SESSION";
                        case INVALID_ROOM_ID -> "INVALID_PAYLOAD";
                        case JOIN_FAILED -> "INTERNAL_ERROR";
                        default -> "INVALID_ROOM_STATE";
                    }, requestId);
                }
                yield roomState(roomId, requestId);
            }
            case PLAYER_READY -> {
                ProtocolV1.fields(payload, requestId, "roomId", "characterId", "ready");
                String roomId = payload.has("roomId") ? ProtocolV1.roomId(payload, requestId) : null;
                JsonNode character = payload.get("characterId");
                String characterId = null;
                if (character != null) {
                    if (!character.isTextual() || !java.util.Set.of("Alex", "Luca", "Maurice", "Leo", "Viktor")
                            .contains(character.textValue())) { throw ProtocolV1.invalid(requestId); }
                    characterId = character.textValue();
                }
                var result = ready.handle(session, new PlayerReadyHandler.ReadyRequest(roomId, characterId,
                        ProtocolV1.bool(payload, "ready", requestId)));
                if (!result.isSuccess()) {
                    throw error(switch (result) {
                        case INVALID_SESSION, PLAYER_NOT_BOUND -> "INVALID_SESSION";
                        case ROOM_NOT_FOUND -> "ROOM_NOT_FOUND";
                        case INVALID_CHARACTER, INVALID_REQUEST -> "INVALID_PAYLOAD";
                        default -> "INVALID_ROOM_STATE";
                    }, requestId);
                }
                yield roomState(session.getRoomId(), requestId);
            }
            case PLAYER_INPUT -> {
                var result = inputs.handle(session, ProtocolV1.playerInput(message, session.getPlayerId()));
                if (result != PlayerInputHandler.RoutingResult.ACCEPTED) {
                    String code = switch (result) {
                        case INVALID_SESSION, PLAYER_NOT_BOUND -> "INVALID_SESSION";
                        case ROOM_NOT_FOUND -> "ROOM_NOT_FOUND";
                        case PLAYER_NOT_IN_MATCH -> "UNAUTHORIZED_PLAYER";
                        case INVALID_INPUT -> "INVALID_PAYLOAD";
                        case DUPLICATE_OR_STALE -> "DUPLICATE_INPUT";
                        case QUEUE_FULL -> "INPUT_QUEUE_FULL";
                        default -> "INVALID_ROOM_STATE";
                    };
                    throw error(code, requestId);
                }
                yield null; // Input không ACK mỗi frame; sequence do queue authoritative kiểm tra.
            }
            case PING -> {
                ProtocolV1.fields(payload, requestId);
                yield new ProtocolMessage(MessageType.PONG, requestId, ProtocolV1.object());
            }
            case PONG -> {
                ProtocolV1.fields(payload, requestId);
                if (heartbeat == null || !heartbeat.acknowledge(session, requestId)) { throw error("INVALID_PAYLOAD", requestId); }
                yield null;
            }
            case LEAVE_ROOM -> throw error("NOT_IMPLEMENTED", requestId);
            default -> throw error("INVALID_PAYLOAD", requestId); // Client không được gửi server push.
        };
    }
    private ProtocolMessage roomState(String roomId, String requestId) throws ProtocolException {
        GameRoom room = rooms.getRoom(roomId);
        if (room == null) { throw error("ROOM_NOT_FOUND", requestId); }
        ObjectNode payload = ProtocolV1.object().put("roomId", roomId);
        var members = payload.putArray("players");
        synchronized (room) {
            if (room.isClosed() || rooms.getRoom(roomId) != room) { throw error("ROOM_NOT_FOUND", requestId); }
            payload.put("phase", room.getPhase().name());
            for (var slot : room.getPlayerSlots()) {
                var member = members.addObject().put("playerId", slot.playerId().value()).put("slotIndex", slot.slotIndex())
                        .put("ready", room.isPlayerReady(slot.playerId()));
                String character = room.getSelectedCharacter(slot.playerId());
                if (character == null) { member.putNull("characterId"); } else { member.put("characterId", character); }
            }
        }
        return new ProtocolMessage(MessageType.ROOM_STATE, requestId, payload);
    }
    private static ProtocolException error(String code, String requestId) {
        return new ProtocolException(code, switch (code) {
            case "INVALID_SESSION" -> "CONNECT is required or session is closed";
            case "UNAUTHORIZED_PLAYER" -> "Player is not authorized for this operation";
            case "ROOM_NOT_FOUND" -> "Room does not exist";
            case "ROOM_FULL" -> "Room has four players";
            case "INVALID_ROOM_STATE" -> "Operation is not allowed in current room state";
            case "DUPLICATE_INPUT" -> "Input sequence is duplicate or stale";
            case "INPUT_QUEUE_FULL" -> "Input queue is full";
            case "NOT_IMPLEMENTED" -> "Message contract exists but handler is not implemented";
            case "INTERNAL_ERROR" -> "Request could not be processed";
            default -> "Invalid message payload";
        }, requestId);
    }
}
