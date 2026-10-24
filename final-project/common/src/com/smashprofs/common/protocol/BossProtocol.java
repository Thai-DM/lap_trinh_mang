package com.smashprofs.common.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smashprofs.common.model.BossSnapshot;
import java.util.ArrayList;

public final class BossProtocol {
    private BossProtocol() { }
    public static ObjectNode snapshot(BossSnapshot b) {
        var n=ProtocolV1.object().put("x",b.x()).put("y",b.y()).put("facingRight",b.facingRight())
                .put("hp",b.hp()).put("maxHp",b.maxHp()).put("phase",b.phase()).put("action",b.action())
                .put("attack",b.attack()).put("actionTick",b.actionTick()).put("attackId",b.attackId()).put("hitSerial",b.hitSerial());
        var a=n.putArray("hazards");
        for(var h:b.hazards()) { a.addObject().put("id",h.id()).put("type",h.type()).put("x",h.x()).put("y",h.y())
                .put("vx",h.vx()).put("vy",h.vy()).put("radius",h.radius()).put("remainingTicks",h.remainingTicks()).put("active",h.active()); }
        return n;
    }
    public static BossSnapshot snapshot(JsonNode n) {
        if(n==null||!n.isObject()||n.size()!=12||!n.path("hazards").isArray()||n.path("hazards").size()>BossSnapshot.MAX_HAZARDS) { throw new IllegalArgumentException("Boss fields"); }
        var hazards=new ArrayList<BossSnapshot.Hazard>();
        for(var h:n.path("hazards")) {
            if(!h.isObject()||h.size()!=9) { throw new IllegalArgumentException("Boss hazard fields"); }
            hazards.add(new BossSnapshot.Hazard(integer(h,"id"),text(h,"type"),number(h,"x"),number(h,"y"),number(h,"vx"),number(h,"vy"),number(h,"radius"),integer(h,"remainingTicks"),bool(h,"active")));
        }
        long phase=integer(n,"phase");if(phase>2) { throw new IllegalArgumentException("Boss phase"); }
        return new BossSnapshot(number(n,"x"),number(n,"y"),bool(n,"facingRight"),number(n,"hp"),number(n,"maxHp"),(int)phase,
                text(n,"action"),text(n,"attack"),integer(n,"actionTick"),integer(n,"attackId"),integer(n,"hitSerial"),hazards);
    }
    private static String text(JsonNode n,String k) { if(!n.path(k).isTextual()) { throw new IllegalArgumentException(k); }return n.path(k).textValue(); }
    private static boolean bool(JsonNode n,String k) { if(!n.path(k).isBoolean()) { throw new IllegalArgumentException(k); }return n.path(k).booleanValue(); }
    private static long integer(JsonNode n,String k) { var v=n.path(k);if(!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<0) { throw new IllegalArgumentException(k); }return v.longValue(); }
    private static float number(JsonNode n,String k) { var v=n.path(k);if(!v.isNumber()||!Double.isFinite(v.doubleValue())||!Float.isFinite(v.floatValue())) { throw new IllegalArgumentException(k); }return v.floatValue(); }
}
