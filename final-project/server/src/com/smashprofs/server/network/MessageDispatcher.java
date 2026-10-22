package com.smashprofs.server.network;

import com.smashprofs.common.model.PlayerInput;
import com.smashprofs.common.protocol.MessageType;
import com.smashprofs.common.protocol.ProtocolMessage;
import com.smashprofs.server.room.RoomManager;

import java.util.Objects;

/** Validate và chuyển tiếp các message inbound thuộc phạm vi server runtime. */
public final class MessageDispatcher {
    boolean enableHeartbeat(HeartbeatManager heartbeat) {
        if (wireDispatcher == null) { return false; }
        wireDispatcher.enableHeartbeat(heartbeat); return true;
    }
    private final InboundHandler handler;
    private final PlayerInputHandler playerInputHandler;
    private final JoinRoomHandler joinRoomHandler;
    private final PlayerReadyHandler playerReadyHandler;
    private final ProtocolV1Dispatcher wireDispatcher;

    /** Boundary TCP v1; các API dispatcher/handler nội bộ cũ vẫn giữ nguyên. */
    public static MessageDispatcher protocolV1(ConnectionManager connections, RoomManager rooms) {
        return protocolV1(connections, rooms, null);
    }

    public static MessageDispatcher protocolV1(ConnectionManager connections, RoomManager rooms, SessionAdmission admission) {
        return new MessageDispatcher((session, type, payload) -> { }, new PlayerInputHandler(connections, rooms),
                new JoinRoomHandler(connections, rooms), new PlayerReadyHandler(connections, rooms),
                new ProtocolV1Dispatcher(connections, rooms, admission));
    }

    public MessageDispatcher(InboundHandler handler) {
        this(handler, null);
    }

    /** Gắn router input; null giữ boundary cũ. JOIN_ROOM/PLAYER_READY vẫn dùng handler hiện có. */
    public MessageDispatcher(InboundHandler handler, PlayerInputHandler playerInputHandler) {
        this(handler, playerInputHandler, null);
    }

    /** Gắn thêm JOIN_ROOM; payload String roomId tạm thời, PLAYER_READY vẫn dùng boundary cũ. */
    public MessageDispatcher(InboundHandler handler, PlayerInputHandler playerInputHandler, JoinRoomHandler joinRoomHandler) {
        this(handler, playerInputHandler, joinRoomHandler, null);
    }

    /** Gắn PLAYER_READY tùy chọn; các constructor cũ giữ nguyên boundary và hành vi. */
    public MessageDispatcher(InboundHandler handler, PlayerInputHandler playerInputHandler,
                             JoinRoomHandler joinRoomHandler, PlayerReadyHandler playerReadyHandler) {
        this(handler, playerInputHandler, joinRoomHandler, playerReadyHandler, null);
    }

    private MessageDispatcher(InboundHandler handler, PlayerInputHandler playerInputHandler,
                              JoinRoomHandler joinRoomHandler, PlayerReadyHandler playerReadyHandler,
                              ProtocolV1Dispatcher wireDispatcher) {
        this.handler = Objects.requireNonNull(handler, "handler không được null");
        this.playerInputHandler = playerInputHandler;
        this.joinRoomHandler = joinRoomHandler;
        this.playerReadyHandler = playerReadyHandler;
        this.wireDispatcher = wireDispatcher;
    }

    /**
     * Chuyển message hợp lệ tới handler trên thread gọi phương thức này.
     * Message bị từ chối không gọi handler và không gửi phản hồi qua channel.
     *
     * PLAYER_INPUT có router thì payload phải là PlayerInput và true chỉ khi queue chấp nhận.
     * JOIN_ROOM có handler thì payload tạm là String roomId và true khi JOINED/ALREADY_IN_ROOM.
     * PLAYER_READY có handler thì payload là ReadyRequest nội bộ và true khi cập nhật/no-op hợp lệ.
     * Các loại khác/constructor cũ vẫn chuyển payload nguyên vẹn như boundary Bước 6.
     * Factory protocolV1 nhận ProtocolMessage; Object vẫn giữ cho API/DTO nội bộ cũ.
     *
     * @return true nếu boundary cũ xử lý thành công hoặc input được chấp nhận; false nếu bị từ chối
     */
    public boolean dispatch(ClientSession session, MessageType type, Object payload) {
        if (session == null || !session.isConnected() || type == null) {
            return false;
        }
        if (payload instanceof ProtocolMessage message && wireDispatcher != null) {
            if (message.type() != type) { return false; }
            return wireDispatcher.dispatch(session, message);
        }

        switch (type) {
            case JOIN_ROOM:
                if (joinRoomHandler != null) {
                    return payload instanceof String roomId && joinRoomHandler.handle(session, roomId).isSuccess();
                }
                handler.handle(session, type, payload);
                return true;
            case PLAYER_READY:
                if (playerReadyHandler != null) {
                    return payload instanceof PlayerReadyHandler.ReadyRequest request
                            && playerReadyHandler.handle(session, request).isSuccess();
                }
                handler.handle(session, type, payload);
                return true;
            case PLAYER_INPUT:
                if (playerInputHandler != null) {
                    return payload instanceof PlayerInput input
                            && playerInputHandler.handle(session, input) == PlayerInputHandler.RoutingResult.ACCEPTED;
                }
                handler.handle(session, type, payload);
                return true;
            default:
                return false;
        }
    }

    /** Boundary để gắn xử lý inbound sau validation trong các bước tiếp theo. */
    @FunctionalInterface
    public interface InboundHandler {
        void handle(ClientSession session, MessageType type, Object payload);
    }
}
