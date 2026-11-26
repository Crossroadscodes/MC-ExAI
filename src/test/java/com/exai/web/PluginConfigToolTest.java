package com.exai.web;

import com.exai.web.tool.ToolContext;
import com.exai.web.tool.ToolDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.*;

public class PluginConfigToolTest {
    @Test
    public void registryPreservesNamesOrderAndSchemas() {
        List<String> names = PluginConfigTool.toolNames();
        assertEquals(Arrays.asList("list_plugin_yaml", "list_yaml_keys", "get_yaml_value",
                "search_yaml_keys", "list_plugins", "search_plugins", "get_plugin_metadata",
                "find_plugin_files", "search_yaml_values", "read_yaml_snippet", "read_yaml_lines", "propose_yaml_set"), names);
        JsonArray schemas = PluginConfigTool.toolSchemas();
        assertEquals(12, schemas.size());
        assertEquals(names.size(), new HashSet<>(names).size());
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            assertTrue(PluginConfigTool.hasTool(name));
            JsonObject schema = schemas.get(i).getAsJsonObject();
            assertEquals(schema, PluginConfigTool.toolSchema(name));
            assertEquals("function", schema.get("type").getAsString());
            JsonObject function = schema.getAsJsonObject("function");
            assertEquals(name, function.get("name").getAsString());
            assertFalse(function.get("description").getAsString().isEmpty());
            JsonObject parameters = function.getAsJsonObject("parameters");
            assertEquals("object", parameters.get("type").getAsString());
            assertFalse(parameters.get("additionalProperties").getAsBoolean());
            for (JsonElement required : parameters.getAsJsonArray("required")) {
                assertTrue(parameters.getAsJsonObject("properties").has(required.getAsString()));
            }
        }
    }

    @Test
    public void discoveryDoesNotExposeRegistryState() {
        JsonObject expected = PluginConfigTool.toolSchema("propose_yaml_set");
        PluginConfigTool.toolSchema("propose_yaml_set").getAsJsonObject("function").remove("parameters");
        PluginConfigTool.toolSchemas().get(10).getAsJsonObject().remove("function");
        PluginConfigTool.toolNames().clear();
        assertEquals(expected, PluginConfigTool.toolSchema("propose_yaml_set"));
        assertEquals(12, PluginConfigTool.toolNames().size());
    }

    @Test
    public void searchSchemasDescribeBatchPagingScopesAndMultipleKeywords() {
        for (String name : Arrays.asList("search_yaml_keys", "search_yaml_values")) {
            JsonObject properties = PluginConfigTool.toolSchema(name).getAsJsonObject("function")
                    .getAsJsonObject("parameters").getAsJsonObject("properties");
            for (String key : Arrays.asList("directory", "keywords", "match_mode", "file_offset", "file_limit", "offset", "limit", "per_file_limit")) {
                assertTrue(properties.has(key));
            }
            assertEquals("string", properties.getAsJsonObject("keywords").getAsJsonObject("items").get("type").getAsString());
        }
        assertTrue(PluginConfigTool.toolSchema("search_yaml_values").toString().contains("include_comments"));
    }

    @Test
    public void unknownAndNullNamesAreStructuredFailures() {
        for (String name : Arrays.asList("unknown_tool", null)) {
            assertFalse(PluginConfigTool.hasTool(name));
            assertNull(PluginConfigTool.toolSchema(name));
            assertFailure(PluginConfigTool.executeTool((ToolContext) null, name, null), "TOOL_NOT_FOUND");
        }
    }

    @Test
    public void missingArgumentsFailBeforeBukkitAccess() {
        for (String name : Arrays.asList("list_yaml_keys", "get_yaml_value", "search_yaml_keys",
                "search_plugins", "get_plugin_metadata", "search_yaml_values", "read_yaml_snippet",
                "propose_yaml_set")) {
            assertFailure(PluginConfigTool.executeTool((ToolContext) null, name, null), "INVALID_ARGUMENT");
        }
    }

    @Test
    public void malformedArgumentsDoNotLeakValues() {
        JsonObject args = new JsonObject();
        JsonObject secret = new JsonObject();
        secret.addProperty("token", "private-value");
        args.add("file", secret);
        JsonObject result = PluginConfigTool.executeTool(ToolContext.anonymous(), "list_yaml_keys", args);
        assertFailure(result, "INVALID_ARGUMENT");
        assertFalse(result.toString().contains("private-value"));
    }

    @Test
    public void definitionPassesContextAndOwnsSchema() throws Exception {
        JsonObject schema = PluginConfigTool.toolSchema("propose_yaml_set");
        JsonObject expected = schema.deepCopy();
        ToolContext context = new ToolContext("Example", "session-1", 42L);
        JsonObject args = new JsonObject();
        ToolDefinition definition = new ToolDefinition(schema, (receivedContext, receivedArgs) -> {
            assertSame(context, receivedContext);
            assertSame(args, receivedArgs);
            assertEquals("Example", receivedContext.getCwd());
            assertEquals("session-1", receivedContext.getSessionId());
            assertEquals(42L, receivedContext.getTurnId());
            return receivedArgs;
        });
        schema.remove("function");
        definition.getSchema().getAsJsonObject("function").remove("parameters");
        assertEquals(expected, definition.getSchema());
        assertEquals("propose_yaml_set", definition.getName());
        assertSame(args, definition.execute(context, args));
    }

    @Test
    public void contextNormalizesNullStrings() {
        ToolContext context = new ToolContext(null, null, 7L);
        assertEquals("", context.getCwd());
        assertEquals("", context.getSessionId());
        assertEquals(7L, context.getTurnId());
        assertEquals(0L, ToolContext.anonymous().getTurnId());
    }

    @Test
    public void missingConfirmationsKeepCliTextAndStructuredStatus() {
        String id = "missing-test-id";
        JsonObject result = PluginConfigTool.confirmPendingResult(id);
        assertFailure(result, "NOT_FOUND");
        assertEquals(id, result.get("confirmation_id").getAsString());
        assertFailure(PluginConfigTool.cancelPendingResult(id), "NOT_FOUND");
        String message = "待确认修改不存在或已过期: " + id;
        assertEquals(message, PluginConfigTool.confirmPending(id));
        assertEquals(message, PluginConfigTool.cancelPending(id));
        assertEquals("全部确认完成：0/1 个已保存\n- " + id + ": " + message,
                PluginConfigTool.confirmPendingAll(Arrays.asList(id)));
        assertEquals("没有可确认的修改", PluginConfigTool.confirmPendingAll(null));
    }

    private static void assertFailure(JsonObject result, String code) {
        assertNotNull(result);
        assertFalse(result.get("ok").getAsBoolean());
        assertEquals(code, result.get("error_code").getAsString());
        assertFalse(result.get("retryable").getAsBoolean());
        assertFalse(result.get("error").getAsString().isEmpty());
    }
}
