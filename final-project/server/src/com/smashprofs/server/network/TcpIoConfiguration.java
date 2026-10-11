package com.smashprofs.server.network;

import java.util.Objects;
import java.util.function.Supplier;

/** Guards transport cấu hình được; không phải maximum frame size/protocol timeout chính thức. */
public record TcpIoConfiguration(Supplier<TcpMessageCodec> codecFactory, MessageDispatcher dispatcher,
                                 int maxMessageBytes, int incompleteMessageTimeoutMillis) {
    public TcpIoConfiguration {
        Objects.requireNonNull(codecFactory, "codecFactory không được null");
        Objects.requireNonNull(dispatcher, "dispatcher không được null");
        if (maxMessageBytes <= 0 || incompleteMessageTimeoutMillis <= 0) {
            throw new IllegalArgumentException("Byte limit và partial-message timeout phải lớn hơn 0");
        }
    }
}
