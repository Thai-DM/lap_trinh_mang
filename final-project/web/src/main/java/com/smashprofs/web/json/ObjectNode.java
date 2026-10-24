package com.smashprofs.web.json;
import com.badlogic.gdx.utils.JsonValue;
import java.util.*;

public final class ObjectNode extends JsonNode {
    private final Map<String,JsonNode> entries=new LinkedHashMap<>();
    public ObjectNode() { super(null); }
    public ObjectNode put(String key,String value) { return set(key,value==null?NULL:new JsonNode(value)); }
    public ObjectNode put(String key,boolean value) { return set(key,new JsonNode(value)); }
    public ObjectNode put(String key,int value) { return put(key,(long)value); }
    public ObjectNode put(String key,long value) { return set(key,new JsonNode(value)); }
    public ObjectNode put(String key,float value) { return put(key,(double)value); }
    public ObjectNode put(String key,double value) { if(!Double.isFinite(value)) { throw new IllegalArgumentException("Non-finite number"); }return set(key,new JsonNode(value)); }
    public ObjectNode putNull(String key) { return set(key,NULL); }
    public ObjectNode set(String key,JsonNode node) { entries.put(key,node==null?NULL:node);return this; }
    public JsonNode remove(String key) { return entries.remove(key); }
    public ArrayNode putArray(String key) { var a=new ArrayNode();set(key,a);return a; }
    public ObjectNode putObject(String key) { var o=new ObjectNode();set(key,o);return o; }
    @Override public JsonNode get(String key) { return entries.get(key); }
    @Override public int size() { return entries.size(); }
    @Override public Iterator<String> fieldNames() { return entries.keySet().iterator(); }
    @Override public Iterator<Map.Entry<String,JsonNode>> fields() { return entries.entrySet().iterator(); }
    @Override public Iterator<JsonNode> iterator() { return entries.values().iterator(); }
    @Override public ObjectNode deepCopy() { var out=new ObjectNode();entries.forEach((k,v)->out.set(k,v.deepCopy()));return out; }
    @Override JsonValue toValue() { var v=new JsonValue(JsonValue.ValueType.object);entries.forEach((k,n)->v.addChild(k,n.toValue()));return v; }
    @Override public boolean equals(Object other) { return other instanceof ObjectNode o && entries.equals(o.entries); }
    @Override public int hashCode() { return entries.hashCode(); }
}
