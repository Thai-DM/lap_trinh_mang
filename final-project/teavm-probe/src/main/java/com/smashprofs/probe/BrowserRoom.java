package com.smashprofs.probe;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.utils.*;
import com.smashprofs.common.model.BossSnapshot;
import java.util.*;
import org.teavm.jso.*;

/** Browser-only asynchronous protocol adapter. JSON parsing uses libGDX, not Jackson/reflection. */
public final class BrowserRoom {
    @JSFunctor public interface Event extends JSObject { void accept(String text); }
    private JSObject socket;
    public String playerId="",room,mode,status="Connecting",error="",matchId="";
    public JsonValue latest;
    public boolean started,joined;
    private boolean loaded;
    public long received,sequence,clientTick;
    private float sinceSend;
    private boolean jump,light,heavy,dodge,throwing,ability,pickup,drop;
    public static String parameter(String key) { return query(key); }
    public static BrowserRoom fromUrl() {
        String mode=query("mode");if(!mode.equals("create")&&!mode.equals("join")) { return null; }
        String room=query("room");if(!room.matches("[A-Za-z0-9_-]{1,64}")) { throw new IllegalArgumentException("Invalid room code"); }
        return new BrowserRoom(room,mode,query("boss").equals("true"));
    }
    private BrowserRoom(String room,String mode,boolean boss) {
        this.room=room;this.mode=mode;
        String endpoint=query("ws");if(endpoint.isEmpty()) { endpoint="ws://127.0.0.1:8090"; }
        socket=open(endpoint,ignored->send("CONNECT","connect","{\"gameplay\":\"boss-v1\"}"),
                text->message(text,boss),text->{status="Disconnected";if(error.isEmpty()) { error=text; }});
    }
    private void message(String text,boolean boss) {
        try {
            var envelope=new JsonReader().parse(text);String type=envelope.getString("type");
            var p=envelope.get("payload");String id=envelope.getString("requestId","");
            switch(type) {
                case "PING" -> send("PONG",id,"{}");
                case "CONNECT_ACK" -> {
                    playerId=p.getString("playerId");status="Connected";
                    if(mode.equals("create")) { send("CREATE_ROOM","create","{\"roomId\":\""+room+"\",\"gameplay\":true,\"boss\":"+boss+"}"); }
                    else { join(); }
                }
                case "ROOM_CREATED" -> { if(id.equals("create")) { join(); } }
                case "ROOM_STATE" -> {
                    if(id.equals("join")) {
                        joined=true;status="Waiting for players";
                        String hero=mode.equals("create")?"Alex":"Luca";
                        send("PLAYER_READY","ready","{\"roomId\":\""+room+"\",\"characterId\":\""+hero+"\",\"ready\":true}");
                    }
                    loaded(p);
                }
                case "ROOM_SETTINGS_STATE" -> loaded(p);
                case "GAME_START" -> { started=true;matchId=p.getString("matchId");status="Playing"; }
                case "GAME_STATE" -> { latest=p;received++; }
                case "GAME_END" -> { status="Finished: "+p.getString("finishReason","");started=false; }
                case "ERROR" -> { error=p.getString("code","")+": "+p.getString("message","");status=error; }
                default -> { }
            }
        } catch(RuntimeException malformed) { error="Invalid server message: "+malformed.getMessage();status=error; }
    }
    private void loaded(JsonValue state) {
        String phase=state.getString("phase","");
        if(phase.equals("WAITING")) { loaded=false; }
        if(joined && !loaded && (phase.equals("READY") || phase.equals("COUNTDOWN"))) {
            loaded=true;status="Loading / countdown";
            send("LOAD_READY","loaded","{\"roomId\":\""+room+"\"}");
        }
    }
    private void join() { send("JOIN_ROOM","join","{\"roomId\":\""+room+"\"}"); }
    private void send(String type,String id,String payload) {
        transmit(socket,"{\"version\":1,\"type\":\""+type+"\",\"requestId\":\""+id+"\",\"payload\":"+payload+"}");
    }
    public void update(float delta) {
        jump|=pressed(Input.Keys.W);light|=pressed(Input.Keys.J);heavy|=pressed(Input.Keys.K);dodge|=pressed(Input.Keys.L);
        throwing|=pressed(Input.Keys.Q);ability|=pressed(Input.Keys.F);pickup|=pressed(Input.Keys.E);drop|=pressed(Input.Keys.G);
        sinceSend+=delta;if(!started || sinceSend<.05f) { return; }sinceSend=0;clientTick+=3;
        int axis=(held(Input.Keys.D)?1:0)-(held(Input.Keys.A)?1:0);
        String fighter="{\"downHeld\":"+held(Input.Keys.S)+",\"jumpHeld\":"+held(Input.Keys.W)+",\"upHeld\":"+held(Input.Keys.I)
                +",\"heavyPressed\":"+heavy+",\"heavyHeld\":"+held(Input.Keys.K)+",\"dodgePressed\":"+(dodge&&axis==0)
                +",\"throwPressed\":"+throwing+",\"dashPressed\":"+(dodge&&axis!=0)+"}";
        String payload="{\"matchId\":\""+matchId+"\",\"sequence\":"+(++sequence)+",\"clientTick\":"+clientTick+",\"moveAxis\":"+axis
                +",\"jumpPressed\":"+jump+",\"stompPressed\":false,\"meleePressed\":"+light+",\"blockHeld\":"+held(Input.Keys.J)
                +",\"abilityPressed\":"+ability+",\"interactPressed\":"+pickup+",\"dropPressed\":"+drop+",\"fighter\":"+fighter+"}";
        send("PLAYER_INPUT","input-"+sequence,payload);
        jump=light=heavy=dodge=throwing=ability=pickup=drop=false;
    }
    private static boolean held(int key) { return Gdx.input.isKeyPressed(key); }
    private static boolean pressed(int key) { return Gdx.input.isKeyJustPressed(key); }
    public JsonValue player() {
        if(latest!=null) { for(var p=latest.get("players").child;p!=null;p=p.next) { if(playerId.equals(p.getString("playerId"))) { return p; } } }
        return null;
    }
    public BossSnapshot boss() {
        if(latest==null || !latest.has("gameplay") || !latest.get("gameplay").has("boss")) { return null; }
        var b=latest.get("gameplay").get("boss");var hazards=new ArrayList<BossSnapshot.Hazard>();
        for(var h=b.get("hazards").child;h!=null;h=h.next) {
            hazards.add(new BossSnapshot.Hazard(h.getLong("id"),h.getString("type"),h.getFloat("x"),h.getFloat("y"),h.getFloat("vx"),h.getFloat("vy"),h.getFloat("radius"),h.getLong("remainingTicks"),h.getBoolean("active")));
        }
        return new BossSnapshot(b.getFloat("x"),b.getFloat("y"),b.getBoolean("facingRight"),b.getFloat("hp"),b.getFloat("maxHp"),b.getInt("phase"),b.getString("action"),b.getString("attack"),b.getLong("actionTick"),b.getLong("attackId"),b.getLong("hitSerial"),hazards);
    }
    public void close() { disconnect(socket); }
    @JSBody(params={"key"},script="return new URLSearchParams(window.location.search).get(key)||'';")
    private static native String query(String key);
    @JSBody(params={"url","opened","message","closed"},script="var s=new WebSocket(url);s.onopen=function(){opened('');};s.onmessage=function(e){message(e.data);};s.onclose=function(e){closed('WebSocket closed '+e.code);};s.onerror=function(){closed('WebSocket connection failed');};return s;")
    private static native JSObject open(String url,Event opened,Event message,Event closed);
    @JSBody(params={"socket","text"},script="if(socket&&socket.readyState===1){socket.send(text);}")
    private static native void transmit(JSObject socket,String text);
    @JSBody(params={"socket"},script="if(socket){socket.close();}")
    private static native void disconnect(JSObject socket);
}
