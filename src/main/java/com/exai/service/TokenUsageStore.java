package com.exai.service;

import com.exai.ExAI;
import com.google.gson.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.*;

/** Daily model-reported token totals; missing provider usage is counted as unknown. */
public final class TokenUsageStore {
    private static final Gson GSON = new Gson();
    private static final Map<String, Day> DAYS = new TreeMap<>();
    private static File override;
    private static boolean loaded;
    private TokenUsageStore() {}

    public static synchronized void record(String model, JsonObject usage) {
        record(model, usage, false, 0L, null, null);
    }

    public static synchronized void record(String model, JsonObject usage, boolean streaming,
                                           long durationMs, JsonObject response, Exception error) {
        load();
        String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        Day day = DAYS.computeIfAbsent(date, k -> new Day());
        Model total = day.models.computeIfAbsent(model == null ? "unknown" : model, k -> new Model());
        total.calls++;
        boolean unknown = error != null || !validTotal(usage);
        if (unknown) total.unknown++;
        else {
            total.prompt += number(usage, "prompt_tokens");
            total.completion += number(usage, "completion_tokens");
            total.total += number(usage, "total_tokens");
        }
        save();
        if (unknown) diagnose(model, usage, streaming, durationMs, response, error);
    }

    private static boolean validTotal(JsonObject usage) {
        if (usage == null || !usage.has("total_tokens")) return false;
        JsonElement value = usage.get("total_tokens");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return false;
        try { return value.getAsBigDecimal().longValueExact() >= 0; }
        catch (RuntimeException e) { return false; }
    }

    private static void diagnose(String model, JsonObject usage, boolean streaming, long durationMs,
                                 JsonObject response, Exception error) {
        try {
            Date now = new Date();
            JsonObject event = new JsonObject();
            event.addProperty("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").format(now));
            event.addProperty("model", model == null ? "unknown" : model);
            event.addProperty("streaming", streaming);
            event.addProperty("durationMs", Math.max(0L, durationMs));
            String reason = error != null ? "request_failed" : usage == null ? "missing_usage"
                    : !usage.has("total_tokens") ? "missing_total_tokens" : "invalid_total_tokens";
            event.addProperty("reason", reason);
            event.addProperty("responseObserved", response != null);
            event.addProperty("responseHasError", response != null && response.has("error"));
            event.addProperty("usageObserved", usage != null);
            event.addProperty("usageType", jsonType(response == null ? null : response.get("usage")));
            event.addProperty("totalTokensType", jsonType(usage == null ? null : usage.get("total_tokens")));
            JsonObject counters = new JsonObject();
            if (usage != null) for (String key : new String[]{"prompt_tokens", "completion_tokens", "total_tokens"}) {
                JsonElement value = usage.get(key);
                if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                    counters.add(key, value);
                }
            }
            event.add("reportedTokens", counters);
            if (error != null) {
                event.addProperty("errorType", error.getClass().getSimpleName());
                String message = error.getMessage();
                java.util.regex.Matcher status = java.util.regex.Pattern.compile("^HTTP ([0-9]{3}):").matcher(message == null ? "" : message);
                if (status.find()) {
                    event.addProperty("failureKind", "http_error");
                    event.addProperty("httpStatus", Integer.parseInt(status.group(1)));
                } else if (error instanceof java.net.SocketTimeoutException) {
                    event.addProperty("failureKind", "timeout");
                } else if (error instanceof java.net.UnknownHostException) {
                    event.addProperty("failureKind", "dns_error");
                } else if (error instanceof java.net.ConnectException) {
                    event.addProperty("failureKind", "connection_failed");
                } else if ("模型流式响应在结束标记前断开".equals(message)) {
                    event.addProperty("failureKind", "stream_interrupted");
                } else {
                    event.addProperty("failureKind", error instanceof java.io.IOException ? "io_error" : "invalid_response");
                }
            }
            File root = file().getAbsoluteFile().getParentFile();
            if ("data".equals(root.getName())) root = root.getParentFile();
            File directory = new File(root, "logs");
            Files.createDirectories(directory.toPath());
            File log = new File(directory, "token-usage-" + new SimpleDateFormat("yyyy-MM-dd").format(now) + ".jsonl");
            Files.write(log.toPath(), (GSON.toJson(event) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("Unable to write token usage diagnostics: " + e.getClass().getSimpleName());
        }
    }

    private static String jsonType(JsonElement value) {
        if (value == null) return "missing";
        if (value.isJsonNull()) return "null";
        if (value.isJsonObject()) return "object";
        if (value.isJsonArray()) return "array";
        if (value.getAsJsonPrimitive().isNumber()) return "number";
        return value.getAsJsonPrimitive().isBoolean() ? "boolean" : "string";
    }

    public static synchronized List<Map<String, Object>> recent(int days) {
        load();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Day> entry : DAYS.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", entry.getKey());
            long prompt = 0, completion = 0, total = 0, calls = 0, unknown = 0;
            List<Map<String, Object>> models = new ArrayList<>();
            for (Map.Entry<String, Model> item : entry.getValue().models.entrySet()) {
                Model m = item.getValue(); calls += m.calls; unknown += m.unknown;
                prompt += m.prompt; completion += m.completion; total += m.total;
                Map<String, Object> model = new LinkedHashMap<>();
                model.put("model", item.getKey()); model.put("calls", m.calls);
                model.put("promptTokens", m.prompt); model.put("completionTokens", m.completion);
                model.put("totalTokens", m.total); model.put("unknownCalls", m.unknown); models.add(model);
            }
            row.put("calls", calls); row.put("unknownCalls", unknown); row.put("promptTokens", prompt);
            row.put("completionTokens", completion); row.put("totalTokens", total); row.put("models", models);
            result.add(row);
        }
        int start = Math.max(0, result.size() - Math.max(1, days));
        return new ArrayList<>(result.subList(start, result.size()));
    }

    static synchronized void useFileForTest(File file) { override = file; loaded = false; DAYS.clear(); }

    private static int number(JsonObject object, String key) {
        try { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : 0; }
        catch (RuntimeException e) { return 0; }
    }
    private static File file() {
        return override != null ? override : new File(ExAI.getInstance().getDataFolder(), "data/token-usage.json");
    }
    private static void load() {
        if (loaded) return;
        loaded = true;
        try {
            File f = file(); if (!f.isFile()) return;
            JsonObject root = JsonParser.parseString(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject days = root.getAsJsonObject("days");
            for (Map.Entry<String, JsonElement> d : days.entrySet()) {
                Day day = new Day();
                for (Map.Entry<String, JsonElement> m : d.getValue().getAsJsonObject().entrySet()) {
                    JsonObject value = m.getValue().getAsJsonObject(); Model item = new Model();
                    item.calls = number(value, "calls"); item.unknown = number(value, "unknown");
                    item.prompt = number(value, "prompt"); item.completion = number(value, "completion"); item.total = number(value, "total");
                    day.models.put(m.getKey(), item);
                }
                DAYS.put(d.getKey(), day);
            }
        } catch (Exception e) { System.err.println("Unable to load token usage: " + e.getMessage()); }
    }
    private static void save() {
        try {
            JsonObject root = new JsonObject(), days = new JsonObject();
            for (Map.Entry<String, Day> d : DAYS.entrySet()) {
                JsonObject models = new JsonObject();
                for (Map.Entry<String, Model> m : d.getValue().models.entrySet()) {
                    JsonObject value = new JsonObject(); Model v = m.getValue();
                    value.addProperty("calls", v.calls); value.addProperty("unknown", v.unknown);
                    value.addProperty("prompt", v.prompt); value.addProperty("completion", v.completion); value.addProperty("total", v.total);
                    models.add(m.getKey(), value);
                }
                days.add(d.getKey(), models);
            }
            root.add("days", days);
            File f = file(), parent = f.getParentFile(); if (parent != null) Files.createDirectories(parent.toPath());
            File temp = new File(f.getPath() + ".tmp");
            Files.write(temp.toPath(), GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
            try { Files.move(temp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) { System.err.println("Unable to save token usage: " + e.getMessage()); }
    }
    private static final class Day { final Map<String, Model> models = new TreeMap<>(); }
    private static final class Model { long calls, unknown, prompt, completion, total; }
}
