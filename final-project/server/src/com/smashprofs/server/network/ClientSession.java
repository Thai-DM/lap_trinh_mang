package com.smashprofs.server.network;

import com.smashprofs.common.model.PlayerId;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Lưu metadata của một client session phía server.
 */
public final class ClientSession {
    final HeartbeatManager.State heartbeat = new HeartbeatManager.State();
    private final String sessionId;
    private final SessionChannel channel;
    private final Consumer<ClientSession> onClosed;
    private volatile PlayerId playerId;
    private volatile String roomId;
    private volatile boolean connected = true;

    public ClientSession(String sessionId, SessionChannel channel) {
        this(sessionId, channel, session -> { });
    }

    /** Boundary transport nội bộ; callback chỉ unregister metadata, không sửa gameplay. */
    ClientSession(String sessionId, SessionChannel channel, Consumer<ClientSession> onClosed) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId không được null");
        this.channel = Objects.requireNonNull(channel, "channel không được null");
        this.onClosed = Objects.requireNonNull(onClosed, "onClosed không được null");
    }

    public String getSessionId() {
        return sessionId;
    }

    public PlayerId getPlayerId() {
        return playerId;
    }

    /** Gắn player dưới khóa dùng chung với routing; chỉ metadata, không sửa roster. */
    public synchronized void bindPlayer(PlayerId playerId) {
        this.playerId = Objects.requireNonNull(playerId, "playerId không được null");
    }

    public String getRoomId() {
        return roomId;
    }

    /** Ghi nhận room của session, không xử lý membership hay gameplay. */
    public synchronized void joinRoom(String roomId) {
        this.roomId = Objects.requireNonNull(roomId, "roomId không được null");
    }

    /** Xóa thông tin room của session. */
    public synchronized void leaveRoom() {
        roomId = null;
    }

    public boolean isConnected() {
        return connected;
    }

    /** Session nhận input chỉ khi chưa disconnect và channel còn mở; không lộ channel ra ngoài. */
    public boolean isOpen() {
        return connected && channel.isOpen();
    }

    /** Đánh dấu mất kết nối, không tự đóng channel. */
    public synchronized void markDisconnected() {
        connected = false;
        heartbeat.clear();
    }

    /** Chuyển message trực tiếp cho channel gửi đi. */
    public void send(Object message) {
        channel.send(message);
    }

    public boolean offerHeartbeat(HeartbeatDelivery delivery) { return isOpen() && channel.offerHeartbeat(delivery); }

    /** Simulation chỉ offer bản sao bất biến, không dùng send có thể block socket. */
    public boolean offerGameState(GameStateDelivery delivery) {
        return isOpen() && channel.offerGameState(delivery);
    }

    public void discardInvalidGameState() { channel.discardInvalidGameState(); }

    public boolean offerGameStart(GameStartDelivery delivery) {
        return isOpen() && channel.offerGameStart(delivery);
    }

    /** Chuyển gói terminal tới worker, không serialize hay ghi socket trên simulation thread. */
    public boolean offerGameEnd(GameEndDelivery delivery) {
        return isOpen() && channel.offerGameEnd(delivery);
    }

    /** Chặn input trước khi đóng channel; thao tác I/O đóng diễn ra ngoài khóa session. */
    public void close() {
        synchronized (this) {
            connected = false;
            heartbeat.clear();
        }
        try {
            channel.close();
        } finally {
            onClosed.accept(this);
        }
    }
}
