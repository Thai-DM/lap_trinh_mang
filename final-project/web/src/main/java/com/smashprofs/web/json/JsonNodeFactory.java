package com.smashprofs.web.json;
public final class JsonNodeFactory {
    public static final JsonNodeFactory instance=new JsonNodeFactory();
    public ObjectNode objectNode() { return new ObjectNode(); }
    public ArrayNode arrayNode() { return new ArrayNode(); }
}
