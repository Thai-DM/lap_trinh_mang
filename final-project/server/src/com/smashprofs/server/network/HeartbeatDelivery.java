package com.smashprofs.server.network;

import com.smashprofs.common.protocol.ProtocolMessage;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Control bất biến chờ writer; không đi qua snapshot latest-wins. */
public record HeartbeatDelivery(ProtocolMessage message, BooleanSupplier stillValid,Runnable onSending) {
    public HeartbeatDelivery(ProtocolMessage message,BooleanSupplier stillValid) { this(message,stillValid,()->{}); }
    public HeartbeatDelivery { Objects.requireNonNull(message); Objects.requireNonNull(stillValid);Objects.requireNonNull(onSending); }
}
