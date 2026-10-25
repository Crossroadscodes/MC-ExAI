package com.exai.web.tool;

import com.google.gson.JsonObject;

import java.util.Objects;

/** Associates an OpenAI-compatible schema with its implementation. */
public final class ToolDefinition {
    private final String name;
    private final JsonObject schema;
    private final ToolHandler handler;

    public ToolDefinition(JsonObject schema, ToolHandler handler) {
        this.schema = Objects.requireNonNull(schema, "schema").deepCopy();
        this.name = this.schema.getAsJsonObject("function").get("name").getAsString();
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public String getName() { return name; }

    public JsonObject getSchema() { return schema.deepCopy(); }

    public JsonObject execute(ToolContext context, JsonObject arguments) throws Exception {
        return handler.execute(context, arguments);
    }
}
