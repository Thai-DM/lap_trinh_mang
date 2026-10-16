package com.smashprofs.common.protocol;

import java.io.IOException;

/** Lỗi application sau khi đã đọc hết frame; reader có thể gửi ERROR và đọc frame tiếp. */
public final class ProtocolException extends IOException {
    private final String code;
    private final String requestId;
    public ProtocolException(String code, String message, String requestId) {
        super(message);
        this.code = code;
        this.requestId = requestId;
    }
    public String code() { return code; }
    public String requestId() { return requestId; }
}
