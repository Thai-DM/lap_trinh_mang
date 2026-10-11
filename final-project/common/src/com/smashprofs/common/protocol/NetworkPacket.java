package com.smashprofs.common.protocol;

import com.google.gson.Gson;
import java.io.Serializable;

/**
 * Standard protocol envelope transferring messages across client and server.
 */
public class NetworkPacket implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final Gson GSON = new Gson();

    private MessageType type;
    private String senderId;
    private long timestamp;
    private String payloadJson;

    public NetworkPacket() {
        this.timestamp = System.currentTimeMillis();
    }

    public NetworkPacket(MessageType type, String senderId, String payloadJson) {
        this.type = type;
        this.senderId = senderId;
        this.payloadJson = payloadJson;
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> NetworkPacket of(MessageType type, String senderId, T payloadObject) {
        String json = payloadObject != null ? GSON.toJson(payloadObject) : null;
        return new NetworkPacket(type, senderId, json);
    }

    public <T> T parsePayload(Class<T> clazz) {
        if (payloadJson == null || payloadJson.isEmpty()) {
            return null;
        }
        return GSON.fromJson(payloadJson, clazz);
    }

    public MessageType getType() {
        return type;
    }

    public void setType(MessageType type) {
        this.type = type;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static NetworkPacket fromJson(String json) {
        return GSON.fromJson(json, NetworkPacket.class);
    }
}
