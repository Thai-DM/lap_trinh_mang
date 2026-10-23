package com.smashprofs.web.json;
import com.badlogic.gdx.utils.JsonValue;
import java.util.*;

public final class ArrayNode extends JsonNode {
    private final List<JsonNode> entries=new ArrayList<>();
    public ArrayNode() { super(null); }
    public ArrayNode add(JsonNode value) { entries.add(value==null?NULL:value);return this; }
    public ArrayNode add(String value) { return add(value==null?NULL:new JsonNode(value)); }
    public ArrayNode add(int value) { return add(new JsonNode((long)value)); }
    public ObjectNode addObject() { var o=new ObjectNode();add(o);return o; }
    @Override public JsonNode get(int index) { return index<0||index>=size()?null:entries.get(index); }
    @Override public int size() { return entries.size(); }
    @Override public Iterator<JsonNode> iterator() { return entries.iterator(); }
    @Override public ArrayNode deepCopy() { var a=new ArrayNode();entries.forEach(v->a.add(v.deepCopy()));return a; }
    @Override JsonValue toValue() { var a=new JsonValue(JsonValue.ValueType.array);entries.forEach(v->a.addChild(v.toValue()));return a; }
    @Override public boolean equals(Object other) { return other instanceof ArrayNode a && entries.equals(a.entries); }
    @Override public int hashCode() { return entries.hashCode(); }
}
