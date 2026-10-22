package com.smashprofs.common.protocol;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/** Envelope TCP v1. Sao chép payload để caller không sửa message đang gửi/đã nhận. */
public record ProtocolMessage(int version, MessageType type, String requestId, ObjectNode payload) {
    public ProtocolMessage {
        Objects.requireNonNull(type, "type không được null");
        payload = Objects.requireNonNull(payload, "payload không được null").deepCopy();
    }
    public ProtocolMessage(MessageType type, String requestId, ObjectNode payload) {
        this(1, type, requestId, payload);
    }
    @Override public ObjectNode payload() { return payload.deepCopy(); }
}
