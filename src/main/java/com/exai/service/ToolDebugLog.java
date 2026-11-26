package com.exai.service;

import com.exai.ExAI;
import com.exai.config.Config;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

final class ToolDebugLog {
    private static File rootOverride;

    private ToolDebugLog() {}

    static synchronized void useDirectoryForTest(File root) { rootOverride = root; }

    static Call start(String model, ToolConversation state, JsonElement call, boolean recovering) {
        if (!Config.webAiDebug) return null;
        Call trace = new Call(model, state, call, recovering);
        write(trace.event("tool_start"));
        return trace;
    }

    static void taskFailed(String model, ToolConversation state, Exception error) {
        if (!Config.webAiDebug) return;
        JsonObject event = context(model, state);
        event.addProperty("event", "task_error");
        event.addProperty("errorType", error.getClass().getSimpleName());
        write(event);
    }

    private static JsonObject context(String model, ToolConversation state) {
        JsonObject event = new JsonObject();
        event.addProperty("model", model);
        event.addProperty("taskId", state.taskId);
        event.addProperty("turnId", state.turnId);
        event.addProperty("cwd", state.cwd);
        event.addProperty("iteration", state.iterations);
        event.addProperty("totalIterations", state.totalIterations);
        return event;
    }

    private static synchronized void write(JsonObject event) {
        if (!Config.webAiDebug) return;
        try {
            Date now = new Date();
            event.addProperty("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").format(now));
            File root = rootOverride != null ? rootOverride : ExAI.getInstance().getDataFolder();
            File directory = new File(root, "logs/tool-debug");
            Files.createDirectories(directory.toPath());
            File file = new File(directory, new SimpleDateFormat("yyyy-MM-dd").format(now) + ".jsonl");
            Files.write(file.toPath(), (event.toString() + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception error) {
            System.err.println("Unable to write tool debug log: " + error.getClass().getSimpleName());
        }
    }

    private static JsonObject arguments(JsonObject function) {
        JsonObject safe = new JsonObject();
        try {
            JsonObject args = JsonParser.parseString(function.get("arguments").getAsString()).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : args.entrySet()) {
                String key = entry.getKey();
                JsonElement value = entry.getValue();
                if ("file".equals(key) || "path".equals(key) || "plugin".equals(key) || "directory".equals(key)) {
                    if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                        String text = value.getAsString();
                        safe.addProperty(key, text.length() > 500 ? text.substring(0, 500) + "..." : text);
                    } else safe.addProperty(key, "[redacted]");
                } else if (("context_lines".equals(key) || "yaml_only".equals(key) || "delete".equals(key)
                        || "start_line".equals(key) || "line_count".equals(key))
                        && value.isJsonPrimitive() && (value.getAsJsonPrimitive().isNumber()
                        || value.getAsJsonPrimitive().isBoolean())) {
                    safe.add(key, value.deepCopy());
                } else if (("file_offset".equals(key) || "file_limit".equals(key) || "offset".equals(key)
                        || "limit".equals(key) || "per_file_limit".equals(key)) && value.isJsonPrimitive()
                        && value.getAsJsonPrimitive().isNumber()) {
                    safe.add(key, value.deepCopy());
                } else if ("include_comments".equals(key) && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
                    safe.add(key, value.deepCopy());
                } else safe.addProperty(key, "[redacted]");
            }
        } catch (RuntimeException error) {
            safe.addProperty("parseError", error.getClass().getSimpleName());
        }
        return safe;
    }

    static final class Call {
        private final JsonObject metadata;
        private final long started = System.nanoTime();

        private Call(String model, ToolConversation state, JsonElement call, boolean recovering) {
            metadata = context(model, state);
            metadata.addProperty("executionId", UUID.randomUUID().toString());
            metadata.addProperty("recovering", recovering);
            try {
                JsonObject object = call.getAsJsonObject();
                metadata.addProperty("callId", object.get("id").getAsString());
                JsonObject function = object.getAsJsonObject("function");
                metadata.addProperty("tool", function.get("name").getAsString());
                metadata.add("arguments", arguments(function));
            } catch (RuntimeException error) {
                metadata.addProperty("parseError", error.getClass().getSimpleName());
            }
        }

        private JsonObject event(String name) {
            JsonObject event = metadata.deepCopy();
            event.addProperty("event", name);
            return event;
        }

        void failed(Exception error) {
            metadata.addProperty("errorType", error.getClass().getSimpleName());
        }

        void finish(JsonObject message) {
            JsonObject event = event("tool_finish");
            event.addProperty("durationMs", (System.nanoTime() - started) / 1_000_000L);
            try {
                JsonObject result = JsonParser.parseString(message.get("content").getAsString()).getAsJsonObject();
                JsonElement ok = result.get("ok");
                if (ok != null && ok.isJsonPrimitive() && ok.getAsJsonPrimitive().isBoolean()) event.add("ok", ok);
                event.addProperty("hasError", result.has("error"));
                JsonElement code = result.get("error_code");
                if (code != null && code.isJsonPrimitive() && code.getAsJsonPrimitive().isString()
                        && code.getAsString().matches("PATH_DENIED|NOT_FOUND|INVALID_ARGUMENT|EXECUTION_ERROR|TOOL_NOT_FOUND")) {
                    event.add("errorCode", code);
                }
                for (String key : new String[]{"results", "files", "snippets", "lines", "plugins", "keys"}) {
                    JsonElement value = result.get(key);
                    if (value != null && value.isJsonArray()) event.addProperty(key + "Count", value.getAsJsonArray().size());
                }
                JsonElement truncated = result.get("truncated");
                if (truncated != null && truncated.isJsonPrimitive() && truncated.getAsJsonPrimitive().isBoolean()) {
                    event.add("truncated", truncated);
                }
                for (String key : new String[]{"scanned_files", "processed_files", "total_files", "skipped_files", "cache_hits", "matched_files", "total_matches", "next_file_offset", "next_offset"}) {
                    JsonElement value = result.get(key);
                    if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) event.add(key, value);
                }
                for (String key : new String[]{"scan_complete", "results_complete", "complete"}) {
                    JsonElement value = result.get(key);
                    if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) event.add(key, value);
                }
            } catch (RuntimeException error) {
                event.addProperty("parseError", error.getClass().getSimpleName());
            }
            write(event);
        }
    }
}
