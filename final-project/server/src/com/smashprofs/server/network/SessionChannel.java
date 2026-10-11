package com.smashprofs.server.network;

/**
 * Kênh giao tiếp đầu ra tối thiểu từ server tới một client.
 */
public interface SessionChannel {
    /** Heartbeat chỉ enqueue control; kênh không hỗ trợ không fallback sang send đồng bộ. */
    default boolean offerHeartbeat(HeartbeatDelivery delivery) { return false; }
    /** Non-blocking snapshot boundary; channel cũ không hỗ trợ thì từ chối, không gọi send đồng bộ. */
    default boolean offerGameState(GameStateDelivery delivery) { return false; }

    /** Kênh chưa hỗ trợ start từ chối; không fallback sang send đồng bộ trên simulation. */
    default boolean offerGameStart(GameStartDelivery delivery) { return false; }

    /** Kênh cũ chỉ giữ hành vi snapshot; false nghĩa là chưa hỗ trợ enqueue kết quả. Không gọi send. */
    default boolean offerGameEnd(GameEndDelivery delivery) {
        offerGameState(delivery.finalState());
        return false;
    }

    /** Chỉ xóa snapshot chưa ghi đã invalid, không đóng/cắt ngang một frame. */
    default void discardInvalidGameState() { }

    /**
     * Gửi một message tới client thông qua implementation cụ thể của kênh.
     *
     * TODO: Thay {@link Object} bằng message envelope dùng chung sau khi contract
     * trong module common được nhóm thống nhất.
     *
     * @param message message cần gửi tới client
     */
    void send(Object message);

    /**
     * Đóng kênh giao tiếp.
     */
    void close();

    /**
     * Kiểm tra kênh hiện có đang mở hay không.
     *
     * @return {@code true} nếu kênh đang mở, ngược lại trả về {@code false}
     */
    boolean isOpen();
}
