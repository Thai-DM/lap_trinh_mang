package com.smashprofs.server.network;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.RankingEntry;
import com.smashprofs.common.protocol.*;
import com.smashprofs.server.ranking.LeaderboardBroadcaster;
import com.smashprofs.server.ranking.RankingService;
import com.smashprofs.server.room.GameRoom;
import com.smashprofs.server.room.RoomManager;
import java.util.Collections;
import java.util.List;
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
    private final SessionAdmission admission;
    private final RankingService rankingService;
    private final LeaderboardBroadcaster leaderboardBroadcaster;
    ProtocolV1Dispatcher(ConnectionManager connections, RoomManager rooms) {
        this(connections, rooms, null, null, null);
    }
    ProtocolV1Dispatcher(ConnectionManager connections, RoomManager rooms, SessionAdmission admission) {
        this(connections, rooms, admission, null, null);
    }
    ProtocolV1Dispatcher(ConnectionManager connections, RoomManager rooms, SessionAdmission admission,
                         RankingService rankingService) {
        this(connections, rooms, admission, rankingService, null);
    }
    ProtocolV1Dispatcher(ConnectionManager connections, RoomManager rooms, SessionAdmission admission,
                         RankingService rankingService, LeaderboardBroadcaster leaderboardBroadcaster) {
        this.admission = admission;
        this.connections = connections;
        this.rooms = rooms;
        this.rankingService = rankingService;
        this.leaderboardBroadcaster = leaderboardBroadcaster;
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
            if(session.rateLimitExceededRepeatedly()) { session.close(); }
            if (response.type() == MessageType.CONNECT_ACK && heartbeat != null) { heartbeat.connected(session); }
        }
        return accepted;
    }
    private ProtocolMessage route(ClientSession session, ProtocolMessage message) throws ProtocolException {
        String requestId = message.requestId();
        if (!session.isOpen() || connections.get(session.getSessionId()) != session) {
            throw error("INVALID_SESSION", requestId);
        }
        if(!session.allowRequest(message.type())) {
            connections.performance.rateLimitRejections.increment(); throw new ProtocolException("RATE_LIMITED","Request rate exceeded",requestId);
        }
        ObjectNode payload = message.payload();
        if (message.type() == MessageType.CONNECT) {
            if (admission == null) { ProtocolV1.fields(payload, requestId,"gameplay"); }
            if(payload.has("gameplay")) {
                String cap = payload.path("gameplay").asText();
                if(com.smashprofs.common.protocol.FighterProtocol.CAPABILITY.equals(cap) || "fighter-v1".equals(cap)) { session.enableFighter(); }
                else if(GameplayProtocol.CAPABILITY.equals(cap)) { session.enableGameplay(); }
                else { throw ProtocolV1.invalid(requestId); }
            }
            // CONNECT lặp giữ nguyên ID; không lấy PlayerId/IP/port từ client.
            if (admission != null) { session.bindPlayer(admission.connect(session, message)); }
            else if (session.getPlayerId() == null) { session.bindPlayer(new PlayerId(UUID.randomUUID().toString())); }
            return new ProtocolMessage(MessageType.CONNECT_ACK, requestId,
                    ProtocolV1.object().put("playerId", session.getPlayerId().value()));
        }
        if (session.getPlayerId() == null) { throw error("INVALID_SESSION", requestId); }
        if (admission != null) { admission.authorize(session, message); }
        return switch (message.type()) {
            case LOAD_READY -> {
                ProtocolV1.fields(payload,requestId,"roomId");String id=ProtocolV1.roomId(payload,requestId);
                if(!session.supportsFighter() || !id.equals(session.getRoomId()) || rooms.getRoom(id)==null) { throw error("UNAUTHORIZED_PLAYER",requestId); }
                try { rooms.getRoom(id).markLoaded(session.getPlayerId()); }catch(IllegalStateException invalid) { throw error("INVALID_ROOM_STATE",requestId); }
                yield null;
            }
            case REMATCH_REQUEST -> {
                ProtocolV1.fields(payload,requestId,"roomId","matchId");String id=ProtocolV1.roomId(payload,requestId);
                if(!session.supportsFighter() || !id.equals(session.getRoomId()) || rooms.getRoom(id)==null || !payload.path("matchId").isTextual()) { throw error("UNAUTHORIZED_PLAYER",requestId); }
                var room=rooms.getRoom(id);var recipients=connections.getSessions();var failed=new java.util.ArrayList<ClientSession>();
                synchronized(room) {
                    com.smashprofs.server.room.GameRoom.Rematch vote;
                    var connected=recipients.stream().filter(peer->peer.isOpen() && peer.supportsFighter() && id.equals(peer.getRoomId()))
                            .map(ClientSession::getPlayerId).collect(java.util.stream.Collectors.toSet());
                    try { vote=room.requestRematch(session.getPlayerId(),payload.path("matchId").asText(),connected); }
                    catch(IllegalStateException invalid) { throw error("INVALID_ROOM_STATE",requestId); }
                    var state=new ReliableDelivery.Message(new ProtocolMessage(MessageType.REMATCH_STATE,null,ProtocolV1.object().put("roomId",id).put("matchId",payload.path("matchId").asText())
                            .put("votes",vote.votes()).put("required",vote.required()).put("started",vote.started())));
                    for(var peer:recipients) {
                        if(peer.supportsFighter() && id.equals(peer.getRoomId())
                                && !peer.offerReliable(new ReliableDelivery(state,()->peer.isOpen() && id.equals(peer.getRoomId())))) { failed.add(peer); }
                    }
                }
                for(var peer:failed) { peer.close(); }
                yield null;
            }
            case CREATE_ROOM -> {
                ProtocolV1.fields(payload, requestId, "roomId", "gameplay");
                String roomId = ProtocolV1.roomId(payload, requestId);
                boolean enhanced=payload.has("gameplay") && ProtocolV1.bool(payload,"gameplay",requestId);
                if(enhanced && !session.supportsGameplay()) { throw error("GAMEPLAY_CAPABILITY_REQUIRED",requestId); }
                final GameRoom created;
                try { created = rooms.createRoom(roomId); }
                catch (IllegalArgumentException duplicate) { throw error("INVALID_ROOM_STATE", requestId); }
                if(enhanced) { created.configureGameplay(session.getPlayerId(),session.supportsFighter()); }
                // Chỉ create: không sửa session binding, không join/ready/start; send nằm ngoài khóa routing.
                yield new ProtocolMessage(MessageType.ROOM_CREATED, requestId,
                        ProtocolV1.object().put("roomId", created.getRoomId()));
            }
            case JOIN_ROOM -> {
                ProtocolV1.fields(payload, requestId, "roomId");
                String roomId = ProtocolV1.roomId(payload, requestId);
                var target=rooms.getRoom(roomId);
                if(target!=null && target.getRules().gameplay() && !session.supportsGameplay()) { throw error("GAMEPLAY_CAPABILITY_REQUIRED",requestId); }
                if(target!=null && target.getRules().fighter() && !session.supportsFighter()) { throw error("FIGHTER_CAPABILITY_REQUIRED",requestId); }
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
            case ROOM_SETTINGS_UPDATE -> {
                if(!session.supportsGameplay() || session.getRoomId()==null) { throw error("INVALID_ROOM_STATE",requestId); }
                ProtocolV1.fields(payload,requestId,"roomId","rules");
                String roomId=ProtocolV1.roomId(payload,requestId);
                if(!roomId.equals(session.getRoomId())) { throw error("UNAUTHORIZED_PLAYER",requestId); }
                var room=rooms.getRoom(roomId); if(room==null) { throw error("ROOM_NOT_FOUND",requestId); }
                try { room.updateSettings(session.getPlayerId(),GameplayProtocol.rules(payload.get("rules"))); }
                catch(SecurityException denied) { throw error("UNAUTHORIZED_PLAYER",requestId); }
                catch(IllegalArgumentException invalid) { throw ProtocolV1.invalid(requestId); }
                catch(IllegalStateException locked) { throw error("INVALID_ROOM_STATE",requestId); }
                yield settingsState(room,requestId);
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
                if(payload.has("matchId") && !session.supportsFighter()) { throw ProtocolV1.invalid(requestId); }
                if(payload.has("fighter") && !session.supportsFighter()) { throw ProtocolV1.invalid(requestId); }
                if (!session.supportsGameplay() && (payload.has("interactPressed") || payload.has("dropPressed"))) {
                    throw ProtocolV1.invalid(requestId);
                }
                var input=ProtocolV1.playerInput(message,session.getPlayerId());
                var room=session.getRoomId()==null?null:rooms.getRoom(session.getRoomId());
                if(room!=null && room.getRules().fighter()) {
                    if(!payload.has("matchId")) { throw ProtocolV1.invalid(requestId); }
                    var runtime=room.getMatchRuntime();
                    if(runtime==null || runtime.isFinished() || !runtime.getMatchId().equals(payload.path("matchId").asText())) { yield null; }
                }
                var result = inputs.handle(session,input);
                // Valid in-flight input can arrive before a client has received its death/respawn snapshot.
                // Its session/identity/room are still validated; discard it without a per-frame ERROR storm.
                if(result==PlayerInputHandler.RoutingResult.PLAYER_INACTIVE) { yield null; }
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
            case GET_LEADERBOARD -> {
                RankingProtocol.validateGetLeaderboard(message);
                Integer limit = RankingProtocol.parseLimit(payload, requestId);
                List<RankingEntry> entries = Collections.emptyList();
                if (rankingService != null) {
                    entries = rankingService.getLeaderboardEntries();
                }
                if (limit != null && limit < entries.size()) {
                    entries = entries.subList(0, limit);
                }
                if (leaderboardBroadcaster != null) {
                    leaderboardBroadcaster.subscribe(session);
                }
                yield RankingProtocol.createSnapshotMessage(requestId, entries);
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
            if(room.getRules().gameplay()) { payload.set("settings",settingsState(room,null).payload()); }
            for (var slot : room.getPlayerSlots()) {
                var member = members.addObject().put("playerId", slot.playerId().value()).put("slotIndex", slot.slotIndex())
                        .put("ready", room.isPlayerReady(slot.playerId()));
                String character = room.getSelectedCharacter(slot.playerId());
                if (character == null) { member.putNull("characterId"); } else { member.put("characterId", character); }
            }
        }
        return new ProtocolMessage(MessageType.ROOM_STATE, requestId, payload);
    }
    static ProtocolMessage settingsState(GameRoom room,String requestId) {
        synchronized(room) {
            var payload=ProtocolV1.object().put("roomId",room.getRoomId()).put("version",room.getSettingsVersion())
                    .put("rulesVersion",room.getRulesVersion())
                    .put("hostId",room.getHost()==null?"":room.getHost().value()).put("playerCount",room.getPlayerCount());
            payload.set("rules",GameplayProtocol.rules(room.getRules()));
            if(room.getRules().fighter()) { payload.put("phase",room.getPhase().name()).put("loadedPlayers",room.getLoadedPlayerCount()).put("countdownMs",room.getCountdownRemainingMillis()); }
            var players=payload.putArray("players");
            for(var slot:room.getPlayerSlots()) {
                var member=players.addObject().put("playerId",slot.playerId().value()).put("slotIndex",slot.slotIndex())
                        .put("ready",room.isPlayerReady(slot.playerId()));
                var character=room.getSelectedCharacter(slot.playerId());
                if(character==null) { member.putNull("characterId"); } else { member.put("characterId",character); }
            }
            return new ProtocolMessage(MessageType.ROOM_SETTINGS_STATE,requestId,payload);
        }
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
