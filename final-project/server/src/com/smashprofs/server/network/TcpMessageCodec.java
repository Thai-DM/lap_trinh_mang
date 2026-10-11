package com.smashprofs.server.network;

import com.smashprofs.common.protocol.MessageType;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Boundary nội bộ; JSON wire v1 nằm ở common và JsonTcpMessageCodec là adapter chuẩn.
 * Implementation khác phải theo contract được duyệt, validate length trước allocation.
 * Factory tạo instance riêng cho mỗi reader và encoder; không chia sẻ parser buffer giữa connections.
 */
public interface TcpMessageCodec {
    /** Đọc một message hoàn chỉnh, giữ thứ tự stream; null chỉ khi EOF sạch. Không coi read = frame. */
    DecodedMessage readNext(InputStream input, int maxMessageBytes) throws IOException;

    /** Trả toàn bộ bytes đã encode VÀ frame, không viết trực tiếp socket; phải tuân thủ byte limit. */
    byte[] encode(Object message, int maxMessageBytes) throws IOException;

    /** Giá trị trong bộ nhớ sau decode, không phải envelope/DTO hoặc schema wire mới. */
    record DecodedMessage(MessageType type, Object payload) {
        public DecodedMessage { Objects.requireNonNull(type, "type không được null"); }
    }
}
