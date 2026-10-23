package com.smashprofs.game.network.client;

import com.smashprofs.common.model.PlayerId;
import com.smashprofs.common.model.BossSnapshot;
import com.smashprofs.common.protocol.*;
import com.smashprofs.web.json.*;
import com.smashprofs.web.WebProtocol;
import org.teavm.jso.*;
import java.io.IOException;
import java.util.UUID;

/** WebSocket transport with the same contract as the desktop TCP client.
 * Callbacks run on the browser event loop. Shared LobbyClient still owns all state transitions.
 */
public final class NetworkClient {
    public static final int DEFAULT_QUEUE_CAPACITY=64, DEFAULT_CONNECT_TIMEOUT_MS=5000;
    private final String host;
    private final int port,timeout,capacity;
    private ConnectionListener listener;
    private ObjectNode connectPayload=ProtocolV1.object();
    private PlayerId playerId;
    private JSObject socket;
    private String requestId;
    private boolean fighter,connecting,connected,closed,notified;
    public NetworkClient(String host,int port,ConnectionListener listener) { this(host,port,5000,64,listener); }
    public NetworkClient(String host,int port,int timeout,int capacity,ConnectionListener listener) {
        this.host=host;this.port=port;this.timeout=timeout;this.capacity=Math.max(1,capacity);this.listener=listener;
    }
    public void enableGameplay() { negotiate(GameplayProtocol.CAPABILITY);fighter=false; }
    public void enableFighter() { negotiate(FighterProtocol.CAPABILITY);fighter=true; }
    public void enableBossSupport() { enableFighter();negotiate(BossSnapshot.CAPABILITY); }
    private void negotiate(String value) { if(connecting||closed) { throw new IllegalStateException("Negotiate before connect"); }connectPayload.put("gameplay",value); }
    public void setConnectProfile(String profileId,String name) {
        if(connecting||closed) { throw new IllegalStateException("Set profile before connect"); }
        if(profileId!=null && !profileId.isBlank()) { connectPayload.put("profileId",profileId); }
        if(name!=null && !name.isBlank()) { connectPayload.put("displayName",name); }
    }
    public boolean isFighterEnabled() { return fighter; }
    public boolean isMockProtocol() { return false; }
    public void setListener(ConnectionListener listener) { this.listener=listener; }
    public ConnectionListener getListener() { return listener; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public PlayerId getPlayerId() { return isConnected()?playerId:null; }
    public boolean isConnected() { return connected&&!closed; }
    public int getSendQueueRemainingCapacity() { return socket==null?capacity:Math.max(0,capacity-(int)(buffered(socket)/16384)); }
    public void connect() throws IOException {
        if(connecting||closed) { throw new IllegalStateException("Connection is one-shot"); }
        connecting=true;requestId=UUID.randomUUID().toString();
        try {
            socket=open(endpoint(host,port),timeout,()->{
                if(closed) { return; }connected=true;
                if(!sendMessage(new ProtocolMessage(MessageType.CONNECT,requestId,connectPayload))) { fail("Cannot send CONNECT"); }
            },this::receive,this::fail);
        } catch(RuntimeException failure) { fail(failure.getMessage());throw new IOException(failure.getMessage()); }
    }
    private void receive(String text) {
        if(closed) { return; }
        try {
            if(text==null || text.length()>ProtocolV1.MAX_PAYLOAD_BYTES) { throw new IllegalArgumentException("Frame size limit"); }
            strictJson(text);
            var message=WebProtocol.decode(text);
            var type=message.type();String id=message.requestId();
            boolean ack=false;
            if(type==MessageType.CONNECT_ACK) {
                var p=message.payload();
                if(playerId!=null || !requestId.equals(id)||p.size()!=1||!p.path("playerId").isTextual()) { throw new IllegalArgumentException("Unexpected CONNECT_ACK"); }
                playerId=new PlayerId(p.path("playerId").asText());ack=true;
            } else if(type==MessageType.PING) {
                if(playerId==null||!message.payload().isEmpty()||!sendMessage(new ProtocolMessage(MessageType.PONG,id,ProtocolV1.object()))) { throw new IllegalArgumentException("Invalid heartbeat"); }
            } else if(playerId==null && type!=MessageType.ERROR) { throw new IllegalArgumentException("Message before CONNECT_ACK"); }
            if(listener!=null) { if(ack) { listener.onConnected(); }listener.onProtocolMessageReceived(message); }
            if(type==MessageType.ERROR&&playerId==null) { fail(message.payload().path("message").asText()); }
        } catch(Exception failure) { fail("Protocol error: "+failure.getMessage()); }
    }
    public boolean sendMessage(ProtocolMessage message) {
        if(message==null||!isConnected()) { return false; }
        String text=WebProtocol.encode(message);return text.length()<=1_048_576 && send(socket,text,Math.min(1_048_576,capacity*16384));
    }
    public boolean sendPacket(NetworkPacket packet) {
        if(packet==null || packet.getType()==null) { return false; }
        try { return sendMessage(new ProtocolMessage(packet.getType(),ProtocolV1.CORRELATED_REQUESTS.contains(packet.getType())?UUID.randomUUID().toString():null,
            (ObjectNode)JsonNode.parse(packet.getPayloadJson()==null?"{}":packet.getPayloadJson()))); }
        catch(RuntimeException failure) { return false; }
    }
    private void fail(String reason) {
        if(notified) { return; }notified=true;closed=true;connected=false;playerId=null;
        if(socket!=null) { close(socket); }
        if(listener!=null) { listener.onDisconnected(reason==null?"Connection closed":reason); }
    }
    public void disconnect() { fail("Disconnected"); }
    public void disconnectAndWait(long ignored) { disconnect(); }
    public static NetworkClient forMock(String h,int p,ConnectionListener l) { throw new UnsupportedOperationException("The browser uses the real v1 game server, not MockServer"); }
    public static NetworkClient forMock(String h,int p,int t,int c,ConnectionListener l) { return forMock(h,p,l); }
    public static NetworkClient forCluster(String h,int p,String ticket,ConnectionListener l) {
        var n=new NetworkClient(h,p,l);n.connectPayload.put("capability","cluster-v1").put("assignment",ticket);return n;
    }
    @JSFunctor interface Callback extends JSObject { void run(); }
    @JSBody(params={"text"},script="JSON.parse(text);") private static native void strictJson(String text);
    @JSFunctor interface TextCallback extends JSObject { void run(String value); }
    @JSBody(params={"host","port"},script="if (/^wss?:\\/\\//.test(host)) return host; return (location.protocol==='https:'?'wss://':'ws://')+host+':'+port;")
    private static native String endpoint(String host,int port);
    @JSBody(params={"url","timeout","opened","message","failed"},script="var s=new WebSocket(url);var timer=setTimeout(function(){if(s.readyState!==1){failed('Connection timed out');s.close();}},timeout);s.onopen=function(){opened();};s.onmessage=function(e){clearTimeout(timer);if(typeof e.data==='string'){message(e.data);}else{failed('Expected a text frame');}};s.onerror=function(){failed('Cannot connect to '+url);};s.onclose=function(e){clearTimeout(timer);failed('Connection closed ('+e.code+')');};return s;")
    private static native JSObject open(String url,int timeout,Callback opened,TextCallback message,TextCallback failed);
    @JSBody(params={"s","text","limit"},script="if(!s||s.readyState!==1||s.bufferedAmount+new TextEncoder().encode(text).length>limit)return false;s.send(text);return true;")
    private static native boolean send(JSObject s,String text,int limit);
    @JSBody(params={"s"},script="return s?s.bufferedAmount:0;") private static native double buffered(JSObject s);
    @JSBody(params={"s"},script="if(s.readyState<2)s.close(1000,'Client closed');") private static native void close(JSObject s);
}
