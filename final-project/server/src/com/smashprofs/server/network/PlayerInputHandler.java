package com.smashprofs.server.network;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.PlayerInput;
import com.smashprofs.server.game.MatchRuntime;
import com.smashprofs.server.room.GameRoom;
import com.smashprofs.server.room.RoomManager;
import com.smashprofs.server.room.RoomPhase;

import java.util.Objects;

/** Định tuyến input theo metadata session phía server; không tick hoặc sửa MatchState. */
public final class PlayerInputHandler {
    private final ConnectionManager connections;
    private final RoomManager rooms;

    public PlayerInputHandler(ConnectionManager connections, RoomManager rooms) {
        this.connections = Objects.requireNonNull(connections, "connections không được null");
        this.rooms = Objects.requireNonNull(rooms, "rooms không được null");
    }

    /**
     * Thứ tự khóa: session -> room -> submitInput (runtime/queue dùng khóa sẵn có).
     * Khóa session giữ player/room ổn định; khóa room giữ validation và enqueue nhất quán với cleanup.
     * Không giữ khóa registry của ConnectionManager/RoomManager và không gọi channel.send/close.
     * Registry được kiểm tra lại trước submit; remove session không close vẫn có thể đua với input đang xử lý.
     * Đây chưa phải authentication hay room-command queue. Sau restart server cần dựng lại handler với registry mới.
     */
    public RoutingResult handle(ClientSession session, PlayerInput input) {
        if (session == null) {
            return RoutingResult.INVALID_SESSION;
        }
        synchronized (session) {
            String sessionId = session.getSessionId();
            if (sessionId.isBlank() || !session.isOpen() || connections.get(sessionId) != session) {
                return RoutingResult.INVALID_SESSION;
            }
            PlayerId playerId = session.getPlayerId();
            if (playerId == null || playerId.value() == null || playerId.value().isBlank()) {
                return RoutingResult.PLAYER_NOT_BOUND;
            }
            String roomId = session.getRoomId();
            if (roomId == null || roomId.isBlank()) {
                return RoutingResult.ROOM_NOT_BOUND;
            }
            // ID trong DTO chỉ là dữ liệu cần đối chiếu, không phải nguồn xác định danh tính.
            if (input == null || !playerId.equals(input.playerId())) {
                return RoutingResult.INVALID_INPUT;
            }
            GameRoom room = rooms.getRoom(roomId);
            if (room == null) {
                return RoutingResult.ROOM_NOT_FOUND;
            }
            synchronized (room) {
                // Reject reference cũ nếu room đã bị xóa/tạo lại trong lúc lookup.
                if (room.isClosed() || rooms.getRoom(roomId) != room) {
                    return RoutingResult.ROOM_NOT_FOUND;
                }
                MatchRuntime runtime = room.getMatchRuntime();
                if (room.getPhase() != RoomPhase.PLAYING || runtime == null || !runtime.isStarted()
                        || !roomId.equals(runtime.getRoomId())) {
                    return RoutingResult.ROOM_NOT_PLAYING;
                }
                if (!room.containsPlayer(playerId) || !runtime.getMatchState().containsPlayer(playerId)) {
                    return RoutingResult.PLAYER_NOT_IN_MATCH;
                }
                if (!session.isOpen() || connections.get(sessionId) != session) {
                    return RoutingResult.INVALID_SESSION;
                }
                // Sequence, axis, capacity và aggregation vẫn do InputQueue hiện có quản lý.
                return switch (runtime.submitInput(input)) {
                    case ACCEPTED -> RoutingResult.ACCEPTED;
                    case INVALID_INPUT -> RoutingResult.INVALID_INPUT;
                    case UNKNOWN_PLAYER -> RoutingResult.PLAYER_NOT_IN_MATCH;
                    case DUPLICATE_OR_STALE -> RoutingResult.DUPLICATE_OR_STALE;
                    case QUEUE_FULL -> RoutingResult.QUEUE_FULL;
                    case MATCH_FINISHED -> RoutingResult.ROOM_NOT_PLAYING;
                    case PLAYER_INACTIVE -> RoutingResult.PLAYER_INACTIVE;
                };
            }
        }
    }

    /** Kết quả nội bộ, không phải message/protocol gửi qua network. */
    public enum RoutingResult {
        ACCEPTED,
        INVALID_SESSION,
        PLAYER_NOT_BOUND,
        ROOM_NOT_BOUND,
        ROOM_NOT_FOUND,
        ROOM_NOT_PLAYING,
        PLAYER_NOT_IN_MATCH,
        INVALID_INPUT,
        DUPLICATE_OR_STALE,
        QUEUE_FULL,
        PLAYER_INACTIVE
    }
}
