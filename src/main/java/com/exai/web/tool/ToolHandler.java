package com.exai.web.tool;

import com.google.gson.JsonObject;

@FunctionalInterface
public interface ToolHandler {
    JsonObject execute(ToolContext context, JsonObject arguments) throws Exception;
}
