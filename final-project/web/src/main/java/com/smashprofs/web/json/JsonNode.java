package com.smashprofs.web.json;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.JsonWriter;
import java.util.*;

/** Browser JSON tree API used by the shared protocol validators. No reflection/POJO mapper. */
public class JsonNode implements Iterable<JsonNode> {
    static final JsonNode MISSING = new JsonNode(null, true);
    static final JsonNode NULL = new JsonNode(null, false);
    protected final Object value;
    private final boolean missing;
    protected JsonNode(Object value) { this(value, false); }
    private JsonNode(Object value, boolean missing) { this.value=value; this.missing=missing; }
    public static JsonNode parse(String text) {
        if(text==null || text.length()>1_048_576) { throw new IllegalArgumentException("JSON size limit"); }
        return convert(new JsonReader().parse(text),0);
    }
    private static JsonNode convert(JsonValue v,int depth) {
        if(depth>32) { throw new IllegalArgumentException("JSON nesting limit"); }
        if(v.isObject()) {
            var out=new ObjectNode();
            for(var child=v.child;child!=null;child=child.next) {
                if(child.name.length()>64 || out.has(child.name)) { throw new IllegalArgumentException("Duplicate/oversized JSON key"); }
                out.set(child.name,convert(child,depth+1));
            }
            return out;
        }
        if(v.isArray()) {
            var out=new ArrayNode();for(var child=v.child;child!=null;child=child.next) { out.add(convert(child,depth+1)); }return out;
        }
        if(v.isNull()) { return NULL; }
        if(v.isString()) {
            if(v.asString().length()>4096) { throw new IllegalArgumentException("JSON string limit"); }
            return new JsonNode(v.asString());
        }
        if(v.isBoolean()) { return new JsonNode(v.asBoolean()); }
        if(v.isLong()) { return new JsonNode(v.asLong()); }
        if(!Double.isFinite(v.asDouble())) { throw new IllegalArgumentException("Non-finite JSON number"); }
        return new JsonNode(v.asDouble());
    }
    public boolean isObject() { return this instanceof ObjectNode; }
    public boolean isArray() { return this instanceof ArrayNode; }
    public boolean isNull() { return value==null && !isContainerNode() && !missing; }
    public boolean isMissingNode() { return missing; }
    public boolean isContainerNode() { return isObject()||isArray(); }
    public boolean isTextual() { return value instanceof String; }
    public boolean isBoolean() { return value instanceof Boolean; }
    public boolean isNumber() { return value instanceof Number; }
    public boolean isIntegralNumber() { return value instanceof Long||value instanceof Integer; }
    public boolean isFloatingPointNumber() { return value instanceof Double||value instanceof Float; }
    public boolean canConvertToInt() { return isNumber() && asDouble()>=Integer.MIN_VALUE && asDouble()<=Integer.MAX_VALUE; }
    public boolean canConvertToLong() { return isNumber() && asDouble()>=Long.MIN_VALUE && asDouble()<=Long.MAX_VALUE; }
    public String textValue() { return isTextual()?(String)value:null; }
    public String asText() { return asText(missing?"":"null"); }
    public String asText(String fallback) { return value==null?fallback:isContainerNode()?"":String.valueOf(value); }
    public boolean booleanValue() { return Boolean.TRUE.equals(value); }
    public boolean asBoolean() { return asBoolean(false); }
    public boolean asBoolean(boolean fallback) { return isBoolean()?(Boolean)value:isNumber()?asDouble()!=0:isTextual()?"true".equals(value):fallback; }
    public double doubleValue() { return isNumber()?((Number)value).doubleValue():0; }
    public float floatValue() { return (float)doubleValue(); }
    public long longValue() { return isNumber()?((Number)value).longValue():0; }
    public int intValue() { return isNumber()?((Number)value).intValue():0; }
    public double asDouble() { return asDouble(0); }
    public double asDouble(double fallback) { if(isNumber()) { return doubleValue(); }try { return Double.parseDouble(asText()); }catch(Exception e) { return fallback; } }
    public long asLong() { return asLong(0); }
    public long asLong(long fallback) { if(isNumber()) { return longValue(); }try { return Long.parseLong(asText()); }catch(Exception e) { return fallback; } }
    public int asInt() { return asInt(0); }
    public int asInt(int fallback) { if(isNumber()) { return intValue(); }try { return Integer.parseInt(asText()); }catch(Exception e) { return fallback; } }
    public JsonNode get(String key) { return null; }
    public JsonNode get(int index) { return null; }
    public JsonNode path(String key) { var found=get(key);return found==null?MISSING:found; }
    public JsonNode path(int index) { var found=get(index);return found==null?MISSING:found; }
    public boolean has(String key) { return get(key)!=null; }
    public boolean hasNonNull(String key) { return has(key)&&!get(key).isNull(); }
    public int size() { return 0; }
    public boolean isEmpty() { return size()==0; }
    public Iterator<String> fieldNames() { return Collections.emptyIterator(); }
    public Iterator<Map.Entry<String,JsonNode>> fields() { return Collections.emptyIterator(); }
    public Iterator<JsonNode> elements() { return iterator(); }
    @Override public Iterator<JsonNode> iterator() { return Collections.emptyIterator(); }
    public JsonNode deepCopy() { return this; }
    JsonValue toValue() {
        if(isNull()||missing) { return new JsonValue(JsonValue.ValueType.nullValue); }
        if(isTextual()) { return new JsonValue(textValue()); }
        if(isBoolean()) { return new JsonValue(booleanValue()); }
        return isIntegralNumber()?new JsonValue(longValue()):new JsonValue(doubleValue());
    }
    @Override public String toString() { return toValue().toJson(JsonWriter.OutputType.json); }
    @Override public boolean equals(Object other) { return other instanceof JsonNode n && getClass()==n.getClass() && Objects.equals(value,n.value)&&missing==n.missing; }
    @Override public int hashCode() { return Objects.hash(value,missing); }
}
