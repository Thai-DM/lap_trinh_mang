package com.smashprofs.common.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smashprofs.common.model.MatchRules;
import com.smashprofs.common.model.GameplaySnapshot;
import java.util.ArrayList;

/** Additive capability, never inferred from untrusted player or damage fields. */
public final class GameplayProtocol {
    public static final String CAPABILITY="gameplay-v1";
    private GameplayProtocol() { }
    public static ObjectNode rules(MatchRules rules) {
        var node=ProtocolV1.object().put("maxPlayers",rules.maxPlayers()).put("matchSeconds",rules.matchSeconds())
                .put("lives",rules.lives()).put("dropRate",rules.dropRate().name());
        if(rules.fighter()) { node.put("fighter",true); }
        if(rules.keepWeaponOnThrow()) { node.put("keepWeaponOnThrow",true); }
        if(rules.boss()) { node.put("boss",true); }
        return node;
    }
    public static MatchRules rules(JsonNode node) {
        if(node==null || !node.isObject()) { throw new IllegalArgumentException("Invalid rule fields"); }
        int expectedSize=4+(node.has("fighter")?1:0)+(node.has("keepWeaponOnThrow")?1:0)+(node.has("boss")?1:0);
        if(node.size()!=expectedSize || node.has("fighter") && !node.path("fighter").isBoolean()
                || node.has("boss") && !node.path("boss").isBoolean()
                || node.has("keepWeaponOnThrow") && !node.path("keepWeaponOnThrow").isBoolean() || !node.path("maxPlayers").isIntegralNumber()
                || !node.path("maxPlayers").canConvertToInt() || !node.path("matchSeconds").isIntegralNumber()
                || !node.path("matchSeconds").canConvertToInt() || !node.path("lives").isIntegralNumber()
                || !node.path("lives").canConvertToInt() || !node.path("dropRate").isTextual()) {
            throw new IllegalArgumentException("Invalid rule fields");
        }
        return new MatchRules(node.path("maxPlayers").intValue(),node.path("matchSeconds").intValue(),
                node.path("lives").intValue(),MatchRules.DropRate.valueOf(node.path("dropRate").textValue()),
                true,node.path("fighter").asBoolean(false),node.path("keepWeaponOnThrow").asBoolean(false),node.path("boss").asBoolean(false));
    }
    public static ObjectNode snapshot(GameplaySnapshot state) {
        var node=ProtocolV1.object().put("durationTicks",state.durationTicks());node.set("rules",rules(state.rules()));
        var players=node.putArray("players");
        for(var p:state.players()) {
            var player=players.addObject().put("playerId",p.playerId()).put("lives",p.lives()).put("status",p.status())
                    .put("respawnTicks",p.respawnTicks()).put("invulnerableTicks",p.invulnerableTicks()).put("ultimateTicks",p.ultimateTicks())
                    .put("weapon",p.weapon()).put("shield",p.shield()).put("damageDealt",p.damageDealt()).put("itemsCollected",p.itemsCollected())
                    .put("weaponsCollected",p.weaponsCollected()).put("kills",p.kills()).put("deaths",p.deaths()).put("wallState",p.wallState());
            if(p.ammo()>=0) { player.put("ammo",p.ammo()); }
        }
        var items=node.putArray("items");for(var p:state.items()) { items.addObject().put("id",p.id()).put("type",p.type()).put("x",p.x()).put("y",p.y()); }
        var shots=node.putArray("shots");for(var p:state.shots()) { shots.addObject().put("id",p.id()).put("type",p.type()).put("owner",p.owner()).put("x",p.x()).put("y",p.y()).put("vx",p.vx()).put("vy",p.vy()); }
        if(state.fighter()!=null) { node.set("fighter",FighterProtocol.snapshot(state.fighter())); }if(state.boss()!=null) { node.set("boss",BossProtocol.snapshot(state.boss())); }return node;
    }
    public static GameplaySnapshot snapshot(JsonNode node) {
        if(node==null || !node.isObject() || node.size()!=5+(node.has("fighter")?1:0)+(node.has("boss")?1:0) || !node.path("players").isArray() || node.path("players").size()>4
                || !node.path("items").isArray() || node.path("items").size()>16 || !node.path("shots").isArray() || node.path("shots").size()>64) { throw new IllegalArgumentException("Invalid gameplay extension"); }
        var players=new ArrayList<GameplaySnapshot.Player>();
        for(var p:node.path("players")) {
            players.add(new GameplaySnapshot.Player(text(p,"playerId"),(int)integer(p,"lives"),text(p,"status"),integer(p,"respawnTicks"),integer(p,"invulnerableTicks"),integer(p,"ultimateTicks"),
                    text(p,"weapon"),bool(p,"shield"),number(p,"damageDealt"),(int)integer(p,"itemsCollected"),(int)integer(p,"weaponsCollected"),(int)integer(p,"kills"),(int)integer(p,"deaths"),text(p,"wallState"),p.has("ammo")?(int)integer(p,"ammo"):-1));
        }
        var items=new ArrayList<GameplaySnapshot.Item>();for(var p:node.path("items")) { items.add(new GameplaySnapshot.Item(integer(p,"id"),text(p,"type"),(float)number(p,"x"),(float)number(p,"y"))); }
        var shots=new ArrayList<GameplaySnapshot.Shot>();for(var p:node.path("shots")) { shots.add(new GameplaySnapshot.Shot(integer(p,"id"),text(p,"type"),text(p,"owner"),(float)number(p,"x"),(float)number(p,"y"),(float)number(p,"vx"),(float)number(p,"vy"))); }
        return new GameplaySnapshot(rules(node.get("rules")),integer(node,"durationTicks"),players,items,shots,node.has("fighter")?FighterProtocol.snapshot(node.get("fighter")):null,node.has("boss")?BossProtocol.snapshot(node.get("boss")):null);
    }
    private static String text(JsonNode n,String field) { if(!n.path(field).isTextual()) { throw new IllegalArgumentException(field); } return n.path(field).textValue(); }
    private static long integer(JsonNode n,String field) { var v=n.path(field); if(!v.isIntegralNumber() || !v.canConvertToLong() || v.longValue()<0 || v.longValue()>Integer.MAX_VALUE) { throw new IllegalArgumentException(field); }return v.longValue(); }
    private static double number(JsonNode n,String field) { var v=n.path(field);if(!v.isNumber() || !Double.isFinite(v.doubleValue())) { throw new IllegalArgumentException(field); } return v.doubleValue(); }
    private static boolean bool(JsonNode n,String field) { if(!n.path(field).isBoolean()) { throw new IllegalArgumentException(field); }return n.path(field).booleanValue(); }
}
