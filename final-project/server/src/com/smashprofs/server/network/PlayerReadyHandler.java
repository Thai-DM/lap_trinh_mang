package com.smashprofs.server.network;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.server.room.GameRoom;
import com.smashprofs.server.room.RoomManager;
import com.smashprofs.server.room.RoomPhase;

import java.util.Objects;

/** Xử lý ready theo danh tính/room phía server; không gửi packet hoặc khởi động trận. */
public final class PlayerReadyHandler {
    private final ConnectionManager connections;
    private final RoomManager rooms;

    public PlayerReadyHandler(ConnectionManager connections, RoomManager rooms) {
        this.connections = Objects.requireNonNull(connections, "connections không được null");
        this.rooms = Objects.requireNonNull(rooms, "rooms không được null");
    }

    /**
     * Giữ thứ tự khóa session -> room như các handler hiện có, không khóa toàn bộ registry.
     * Khóa session giữ metadata ổn định; khóa room giữ validation và cập nhật nhất quán với lifecycle.
     * Kiểm tra lại registry trước mutation; remove session không close vẫn có thể đua với request đang xử lý.
     * Đây chưa phải authentication. Sau restart cần dựng lại handler với RoomManager mới.
     */
    public Result handle(ClientSession session, ReadyRequest request) {
        if (session == null) {
            return Result.INVALID_SESSION;
        }
        synchronized (session) {
            if (!isRegisteredAndOpen(session)) {
                return Result.INVALID_SESSION;
            }
            PlayerId playerId = session.getPlayerId();
            if (playerId == null || playerId.value() == null || playerId.value().isBlank()) {
                return Result.PLAYER_NOT_BOUND;
            }
            String roomId = session.getRoomId();
            if (roomId == null || roomId.isBlank()) {
                return Result.ROOM_NOT_BOUND;
            }
            if (request == null || (request.roomId() != null && !roomId.equals(request.roomId()))) {
                return Result.INVALID_REQUEST;
            }
            // PLAYER_READY chỉ tìm phòng đang tham gia, tuyệt đối không tạo phòng mới.
            GameRoom room = rooms.getRoom(roomId);
            if (room == null) {
                return Result.ROOM_NOT_FOUND;
            }
            synchronized (room) {
                if (room.isClosed() || rooms.getRoom(roomId) != room) {
                    return Result.ROOM_NOT_FOUND;
                }
                if (!room.containsPlayer(playerId)) {
                    return Result.PLAYER_NOT_IN_ROOM;
                }
                if (!isUpdatablePhase(room.getPhase())) {
                    return Result.INVALID_ROOM_PHASE;
                }
                if (!isRegisteredAndOpen(session)) {
                    return Result.INVALID_SESSION;
                }
                boolean previousReady = room.isPlayerReady(playerId);
                String previousCharacter = room.getSelectedCharacter(playerId);
                try {
                    if (!room.updatePlayerReady(playerId, request.characterId(), request.ready())) {
                        return Result.INVALID_REQUEST;
                    }
                } catch (IllegalArgumentException invalidCharacter) {
                    // GameRoom validate trước mutation nên không cần rollback chọn nhân vật/ready.
                    return Result.INVALID_CHARACTER;
                }
                if (previousReady == request.ready()
                        && Objects.equals(previousCharacter, room.getSelectedCharacter(playerId))) {
                    return request.ready() ? Result.ALREADY_READY : Result.UNCHANGED;
                }
                return Result.UPDATED;
            }
        }
    }

    private boolean isRegisteredAndOpen(ClientSession session) {
        return !session.getSessionId().isBlank() && session.isOpen()
                && connections.get(session.getSessionId()) == session;
    }

    static boolean isUpdatablePhase(RoomPhase phase) {
        return phase == RoomPhase.WAITING || phase == RoomPhase.READY || phase == RoomPhase.COUNTDOWN;
    }

    /**
     * Request nội bộ tạm thời: common chưa có DTO PLAYER_READY, không phải contract wire mới.
     * roomId null dùng phòng trong session; characterId null giữ lựa chọn hiện tại.
     * Không chứa PlayerId: danh tính chỉ lấy từ session phía server.
     * TODO: Thay bằng DTO common khi nhóm thống nhất protocol; chưa chọn serialization/codec.
     */
    public record ReadyRequest(String roomId, String characterId, boolean ready) {
    }

    /** Kết quả nội bộ, không serialize hoặc gửi qua channel. */
    public enum Result {
        UPDATED,
        ALREADY_READY,
        UNCHANGED,
        INVALID_SESSION,
        PLAYER_NOT_BOUND,
        ROOM_NOT_BOUND,
        ROOM_NOT_FOUND,
        PLAYER_NOT_IN_ROOM,
        INVALID_CHARACTER,
        INVALID_ROOM_PHASE,
        INVALID_REQUEST;

        public boolean isSuccess() {
            return this == UPDATED || this == ALREADY_READY || this == UNCHANGED;
        }
    }
}
