package com.smashprofs.server.network;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.server.room.GameRoom;
import com.smashprofs.server.room.PlayerSlot;
import com.smashprofs.server.room.RoomManager;
import com.smashprofs.server.room.RoomPhase;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Join theo PlayerId đã bind phía server; chưa có authentication, socket hoặc DTO JOIN_ROOM trong common. */
public final class JoinRoomHandler {
    private static final Logger LOGGER = Logger.getLogger(JoinRoomHandler.class.getName());
    private static final Pattern ROOM_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private final ConnectionManager connections;
    private final RoomManager rooms;
    private final BiConsumer<ClientSession, String> roomBinder;

    public JoinRoomHandler(ConnectionManager connections, RoomManager rooms) {
        this(connections, rooms, ClientSession::joinRoom);
    }

    /** Điểm inject nội bộ để test bind lỗi; binder chỉ được cập nhật roomId của session, không sửa room. */
    JoinRoomHandler(ConnectionManager connections, RoomManager rooms, BiConsumer<ClientSession, String> roomBinder) {
        this.connections = Objects.requireNonNull(connections, "connections không được null");
        this.rooms = Objects.requireNonNull(rooms, "rooms không được null");
        this.roomBinder = Objects.requireNonNull(roomBinder, "roomBinder không được null");
    }

    /**
     * Payload tạm là String roomId, không tự thêm shared DTO hoặc nhận PlayerId từ client.
     * Giữ khóa session qua cả kiểm tra, join và bind để hai request không đưa session vào hai phòng.
     * Thứ tự khóa session -> room giống PlayerInputHandler; không giữ khóa registry khi chờ room.
     * WAITING/READY cho phép join lặp idempotent; COUNTDOWN/PLAYING/FINISHED đều bị từ chối.
     * Các thao tác registry remove/close và API metadata trực tiếp chưa phải command queue hoàn chỉnh.
     */
    public JoinResult handle(ClientSession session, String roomId) {
        if (session == null) {
            return rejected(Status.INVALID_SESSION, roomId, null);
        }
        synchronized (session) {
            if (!isRegisteredAndOpen(session)) {
                return rejected(Status.INVALID_SESSION, roomId, null);
            }
            PlayerId playerId = session.getPlayerId();
            if (playerId == null || playerId.value() == null || playerId.value().isBlank()) {
                return rejected(Status.INVALID_SESSION, roomId, null);
            }
            if (roomId == null || roomId.length() > 64 || !ROOM_ID.matcher(roomId).matches()) {
                return rejected(Status.INVALID_ROOM_ID, roomId, null);
            }
            String previousRoomId = session.getRoomId();
            if (previousRoomId != null && !previousRoomId.equals(roomId)) {
                return rejected(Status.ALREADY_IN_ANOTHER_ROOM, roomId, null);
            }
            try {
                GameRoom room = rooms.getOrCreateRoom(roomId);
                synchronized (room) {
                    RoomPhase phase = room.getPhase();
                    if (room.isClosed() || rooms.getRoom(roomId) != room
                            || !isJoinablePhase(phase)) {
                        return rejected(Status.ROOM_NOT_JOINABLE, roomId, phase);
                    }
                    if (!isRegisteredAndOpen(session)) {
                        return rejected(Status.INVALID_SESSION, roomId, phase);
                    }
                    GameRoom.JoinResult joined = room.joinPlayer(playerId);
                    if (joined == GameRoom.JoinResult.ROOM_FULL) {
                        return rejected(Status.ROOM_FULL, roomId, room.getPhase());
                    }
                    if (joined != GameRoom.JoinResult.JOINED && joined != GameRoom.JoinResult.ALREADY_IN_ROOM) {
                        return rejected(Status.ROOM_NOT_JOINABLE, roomId, room.getPhase());
                    }
                    boolean added = joined == GameRoom.JoinResult.JOINED;
                    room.setDisplayName(playerId, session.getDisplayName());
                    try {
                        if (!isRegisteredAndOpen(session) || rooms.getRoom(roomId) != room) {
                            rollback(session, previousRoomId, room, playerId, added);
                            return rejected(Status.JOIN_FAILED, roomId, room.getPhase());
                        }
                        PlayerSlot slot = Objects.requireNonNull(room.getPlayerSlot(playerId), "Player join thiếu slot");
                        Status status = added ? Status.JOINED : Status.ALREADY_IN_ROOM;
                        JoinResult result = new JoinResult(status, roomId, slot, room.getPhase());
                        // Chỉ bind sau khi membership và slot đã hợp lệ; chưa nhả khóa room/session.
                        roomBinder.accept(session, roomId);
                        if (!roomId.equals(session.getRoomId())) {
                            throw new IllegalStateException("Bind không cập nhật roomId của session");
                        }
                        return result;
                    } catch (RuntimeException failure) {
                        // Kể cả binder đã đổi metadata rồi mới throw, khôi phục binding cũ và slot mới.
                        rollback(session, previousRoomId, room, playerId, added);
                        LOGGER.log(Level.WARNING, "Join/bind thất bại tại phòng " + roomId, failure);
                        return rejected(Status.JOIN_FAILED, roomId, room.getPhase());
                    }
                }
            } catch (RuntimeException failure) {
                LOGGER.log(Level.WARNING, "Không thể join phòng " + roomId, failure);
                return rejected(Status.JOIN_FAILED, roomId, null);
            }
        }
    }

    private boolean isRegisteredAndOpen(ClientSession session) {
        return !session.getSessionId().isBlank() && session.isOpen()
                && connections.get(session.getSessionId()) == session;
    }

    /** Chính sách phase độc lập, không giả lập GAME_END để kiểm thử FINISHED. */
    static boolean isJoinablePhase(RoomPhase phase) {
        return phase == RoomPhase.WAITING || phase == RoomPhase.READY;
    }

    /** Chỉ gọi dưới khóa session và room; không xóa player đã tồn tại từ trước. */
    private void rollback(ClientSession session, String previousRoomId, GameRoom room, PlayerId playerId, boolean added) {
        if (added && !room.leavePlayer(playerId)) {
            throw new IllegalStateException("Không thể rollback slot vừa thêm");
        }
        if (previousRoomId == null) {
            session.leaveRoom();
        } else {
            session.joinRoom(previousRoomId);
        }
    }

    private static JoinResult rejected(Status status, String roomId, RoomPhase phase) {
        return new JoinResult(status, roomId, null, phase);
    }

    public enum Status {
        JOINED,
        ALREADY_IN_ROOM,
        INVALID_SESSION,
        INVALID_ROOM_ID,
        ROOM_FULL,
        ROOM_NOT_JOINABLE,
        ALREADY_IN_ANOTHER_ROOM,
        JOIN_FAILED
    }

    /** Snapshot kết quả nội bộ server, không phải protocol response hay packet. */
    public record JoinResult(Status status, String roomId, PlayerSlot slot, RoomPhase phase) {
        public JoinResult {
            Objects.requireNonNull(status, "status không được null");
            if (status == Status.JOINED || status == Status.ALREADY_IN_ROOM) {
                Objects.requireNonNull(roomId, "Join thành công cần roomId");
                Objects.requireNonNull(slot, "Join thành công cần slot");
                Objects.requireNonNull(phase, "Join thành công cần phase");
            }
        }

        public boolean isSuccess() {
            return status == Status.JOINED || status == Status.ALREADY_IN_ROOM;
        }
    }
}
