package com.smashprofs.web;
import com.smashprofs.common.protocol.*;
import com.smashprofs.web.json.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Same v1 envelope guards as JsonFrameCodec, without the TCP length prefix or ObjectMapper. */
public final class WebProtocol {
    public static ProtocolMessage decode(String text) throws ProtocolException {
        if(text==null || text.length()>ProtocolV1.MAX_PAYLOAD_BYTES || text.getBytes(StandardCharsets.UTF_8).length>ProtocolV1.MAX_PAYLOAD_BYTES) { throw ProtocolV1.invalid(null); }
        final JsonNode root;
        try { root=JsonNode.parse(text); }catch(RuntimeException invalid) { throw ProtocolV1.invalid(null); }
        if(!root.isObject()) { throw ProtocolV1.invalid(null); }
        String request=null;
        if(root.has("requestId")) {
            var id=root.get("requestId");
            if(!id.isTextual()||id.asText().isEmpty()||id.asText().codePointCount(0,id.asText().length())>64) { throw ProtocolV1.invalid(null); }
            request=id.asText();
        }
        var names=root.fieldNames();
        while(names.hasNext()) { if(!Set.of("version","type","requestId","payload").contains(names.next())) { throw ProtocolV1.invalid(request); } }
        var version=root.path("version");
        if(!version.isIntegralNumber()||!version.canConvertToInt()) { throw ProtocolV1.invalid(request); }
        if(version.asInt()!=1) { throw new ProtocolException("UNSUPPORTED_VERSION","Only v1 is supported",request); }
        if(!root.path("type").isTextual()) { throw ProtocolV1.invalid(request); }
        final MessageType type;
        try { type=MessageType.valueOf(root.path("type").asText()); }catch(IllegalArgumentException invalid) { throw new ProtocolException("UNKNOWN_MESSAGE_TYPE","Unknown message type",request); }
        if(!ProtocolV1.MVP_TYPES.contains(type)) { throw new ProtocolException("UNKNOWN_MESSAGE_TYPE","Not a v1 type",request); }
        if(!root.path("payload").isObject() || ProtocolV1.CORRELATED_REQUESTS.contains(type)&&request==null) { throw ProtocolV1.invalid(request); }
        return new ProtocolMessage(type,request,(ObjectNode)root.get("payload"));
    }
    public static String encode(ProtocolMessage message) {
        var root=ProtocolV1.object().put("version",message.version()).put("type",message.type().name());
        if(message.requestId()!=null) { root.put("requestId",message.requestId()); }root.set("payload",message.payload());return root.toString();
    }
}
