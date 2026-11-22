package com.exai.service;

import com.exai.ExAI;
import com.google.gson.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
        load();
        String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        Day day = DAYS.computeIfAbsent(date, k -> new Day());
        Model total = day.models.computeIfAbsent(model == null ? "unknown" : model, k -> new Model());
        total.calls++;
        if (usage == null || !usage.has("total_tokens")) total.unknown++;
        else {
            total.prompt += number(usage, "prompt_tokens");
            total.completion += number(usage, "completion_tokens");
            total.total += number(usage, "total_tokens");
        }
        save();
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
