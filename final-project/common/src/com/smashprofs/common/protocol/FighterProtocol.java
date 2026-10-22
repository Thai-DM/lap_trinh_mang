package com.smashprofs.common.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smashprofs.common.model.*;
import java.util.*;

public final class FighterProtocol {
    public static final String CAPABILITY="unarmed-v1";
    private static final String[] INPUT_FIELDS={"downHeld","jumpHeld","upHeld","heavyPressed","heavyHeld","dodgePressed","throwPressed","dashPressed"};
    private FighterProtocol() { }
    public static ObjectNode input(FighterInput p) {
        return ProtocolV1.object().put("downHeld",p.downHeld()).put("jumpHeld",p.jumpHeld()).put("upHeld",p.upHeld())
                .put("heavyPressed",p.heavyPressed()).put("heavyHeld",p.heavyHeld()).put("dodgePressed",p.dodgePressed())
                .put("throwPressed",p.throwPressed()).put("dashPressed",p.dashPressed());
    }
    public static FighterInput input(JsonNode node,String request) throws ProtocolException {
        if(!(node instanceof ObjectNode p)) { throw ProtocolV1.invalid(request); }
        ProtocolV1.fields(p,request,INPUT_FIELDS);
        return new FighterInput(ProtocolV1.bool(p,"downHeld",request),ProtocolV1.bool(p,"jumpHeld",request),ProtocolV1.bool(p,"upHeld",request),
                ProtocolV1.bool(p,"heavyPressed",request),ProtocolV1.bool(p,"heavyHeld",request),ProtocolV1.bool(p,"dodgePressed",request),
                ProtocolV1.bool(p,"throwPressed",request),ProtocolV1.bool(p,"dashPressed",request));
    }
    public static ObjectNode snapshot(FighterSnapshot state) {
        var node=ProtocolV1.object();var players=node.putArray("players");
        for(var p:state.players()) {
            var player=players.addObject().put("playerId",p.playerId()).put("damagePercent",p.damagePercent()).put("action",p.action())
                    .put("attack",p.attack()).put("attackId",p.attackId()).put("attackFrame",p.attackFrame()).put("hitstunTicks",p.hitstunTicks())
                    .put("dodgeTicks",p.dodgeTicks()).put("dodgeCooldownTicks",p.dodgeCooldownTicks()).put("platformTicks",p.platformTicks())
                    .put("fastFalling",p.fastFalling()).put("recoveryAvailable",p.recoveryAvailable()).put("airJumps",p.airJumps()).put("wallJumps",p.wallJumps())
                    .put("acknowledgedSequence",p.acknowledgedSequence()).put("acknowledgedClientTick",p.acknowledgedClientTick());
            var d=p.detail();player.putObject("detail").put("state",d.state()).put("phase",d.phase()).put("dodgeKind",d.dodgeKind())
                    .put("recoveries",d.recoveries()).put("aerialActionsUsed",d.aerialActionsUsed()).put("gravityCancelTicks",d.gravityCancelTicks())
                    .put("chaseCharges",d.chaseCharges()).put("chaseTicks",d.chaseTicks()).put("bufferTicks",d.bufferTicks())
                    .put("gravityCancelled",d.gravityCancelled()).put("wallActions",d.wallActions()).put("dodgeFrames",d.dodgeFrames())
                    .put("combo",d.combo()).put("hitSerial",d.hitSerial()).put("dashTicks",d.dashTicks()).put("dashVelocity",d.dashVelocity()).put("wallLockTicks",d.wallLockTicks())
                    .put("runTicks",d.runTicks()).put("dashCooldownTicks",d.dashCooldownTicks()).put("attackDirection",d.attackDirection());
        }
        return node;
    }
    public static FighterSnapshot snapshot(JsonNode node) {
        if(node==null || !node.isObject() || node.size()!=1 || !node.path("players").isArray() || node.path("players").size()>4) { throw new IllegalArgumentException("Fighter snapshot"); }
        var values=new ArrayList<FighterSnapshot.Player>();
        for(var p:node.path("players")) {
            if((p.size()!=16 && p.size()!=17) || p.size()==17 && !p.has("detail") || !p.path("damagePercent").isNumber()) { throw new IllegalArgumentException("Fighter fields"); }
            values.add(new FighterSnapshot.Player(text(p,"playerId"),p.path("damagePercent").doubleValue(),text(p,"action"),text(p,"attack"),
                    number(p,"attackId"),number(p,"attackFrame"),number(p,"hitstunTicks"),number(p,"dodgeTicks"),number(p,"dodgeCooldownTicks"),
                    number(p,"platformTicks"),bool(p,"fastFalling"),bool(p,"recoveryAvailable"),number(p,"airJumps"),number(p,"wallJumps"),
                    nonNegativeLong(p,"acknowledgedSequence"),nonNegativeLong(p,"acknowledgedClientTick"),p.has("detail")?detail(p.path("detail")):FighterSnapshot.Detail.NONE));
        }
        return new FighterSnapshot(values);
    }
    private static FighterSnapshot.Detail detail(JsonNode d) {
        if(!d.isObject() || d.size()!=20 || !d.path("dodgeFrames").isIntegralNumber() || !d.path("dodgeFrames").canConvertToInt() || !d.path("dashVelocity").isNumber()
                || !d.path("attackDirection").isIntegralNumber() || !d.path("attackDirection").canConvertToInt()) { throw new IllegalArgumentException("Unarmed detail"); }
        return new FighterSnapshot.Detail(text(d,"state"),text(d,"phase"),text(d,"dodgeKind"),number(d,"recoveries"),number(d,"aerialActionsUsed"),number(d,"gravityCancelTicks"),
                number(d,"chaseCharges"),number(d,"chaseTicks"),number(d,"bufferTicks"),bool(d,"gravityCancelled"),number(d,"wallActions"),d.path("dodgeFrames").intValue(),
                text(d,"combo"),nonNegativeLong(d,"hitSerial"),number(d,"dashTicks"),d.path("dashVelocity").floatValue(),number(d,"wallLockTicks"),number(d,"runTicks"),number(d,"dashCooldownTicks"),d.path("attackDirection").intValue());
    }
    private static int number(JsonNode n,String field) { var p=n.path(field);if(!p.isIntegralNumber() || !p.canConvertToInt() || p.intValue()<0) { throw new IllegalArgumentException(field); }return p.intValue(); }
    private static long nonNegativeLong(JsonNode n,String field) { var p=n.path(field);if(!p.isIntegralNumber() || !p.canConvertToLong() || p.longValue()<0) { throw new IllegalArgumentException(field); }return p.longValue(); }
    private static String text(JsonNode n,String field) { if(!n.path(field).isTextual()) { throw new IllegalArgumentException(field); }return n.path(field).textValue(); }
    private static boolean bool(JsonNode n,String field) { if(!n.path(field).isBoolean()) { throw new IllegalArgumentException(field); }return n.path(field).booleanValue(); }
}
