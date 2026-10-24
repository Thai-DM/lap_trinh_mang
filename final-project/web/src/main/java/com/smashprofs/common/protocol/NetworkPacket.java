package com.smashprofs.common.protocol;
import com.smashprofs.common.model.PlayerInput;
import com.smashprofs.web.json.*;
/** Compatibility envelope for ConnectionListener. Browser games always use ProtocolMessage v1.
 * The old desktop-only MockServer's reflection-based DTO protocol is deliberately rejected.
 */
public final class NetworkPacket {
    private MessageType type;
    private String senderId,payloadJson;
    private long timestamp=System.currentTimeMillis();
    public NetworkPacket() { }
    public NetworkPacket(MessageType type,String sender,String payload) { this.type=type;senderId=sender;payloadJson=payload; }
    public MessageType getType() { return type; }
    public void setType(MessageType type) { this.type=type; }
    public String getSenderId() { return senderId; }
    public void setSenderId(String sender) { senderId=sender; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String text) { payloadJson=text; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long time) { timestamp=time; }
    public static <T> NetworkPacket of(MessageType type,String sender,T value) {
        if(value instanceof PlayerInput input) { return new NetworkPacket(type,sender,ProtocolV1.inputPayload(input).toString()); }
        if(value instanceof JsonNode node) { return new NetworkPacket(type,sender,node.toString()); }
        throw new UnsupportedOperationException("Legacy MockServer DTOs are desktop-only; use ProtocolMessage v1");
    }
    public <T> T parsePayload(Class<T> type) { throw new UnsupportedOperationException("Use the shared v1 snapshot validators in the browser"); }
    public String toJson() {
        return new ObjectNode().put("type",type.name()).put("senderId",senderId).put("timestamp",timestamp).put("payloadJson",payloadJson).toString();
    }
    public static NetworkPacket fromJson(String text) {
        var n=JsonNode.parse(text);var out=new NetworkPacket(MessageType.valueOf(n.path("type").asText()),n.path("senderId").asText(null),n.path("payloadJson").asText(null));out.timestamp=n.path("timestamp").asLong();return out;
    }
}
