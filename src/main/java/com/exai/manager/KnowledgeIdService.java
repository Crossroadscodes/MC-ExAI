package com.exai.manager;

import com.exai.ExAI;
import com.exai.config.Config;
import com.exai.entity.KnowledgeEntry;
import com.exai.i18n.Lang;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 负责生成、校验并持久化知识条目的稳定语义 ID。所有公开生成方法都应在异步线程调用。 */
public final class KnowledgeIdService {
    private static final Object TASK_LOCK = new Object();
    private static final Pattern VALID_ID = Pattern.compile("^[a-z][a-z0-9_]{2,63}$");
    private static final int BATCH_SIZE = 20;
    private static volatile boolean running;
    private static volatile int total;
    private static volatile int completed;
    private static volatile int lastGenerated;
    private static volatile String lastError = "";

    private KnowledgeIdService() {}

    public static List<KnowledgeEntry> ensureMissingIds(List<KnowledgeEntry> initialEntries) {
        synchronized (TASK_LOCK) {
            running = true;
            completed = 0;
            lastGenerated = 0;
            lastError = "";
            try {
                List<KnowledgeEntry> entries = KnowledgeFileManager.readAll();
                List<KnowledgeEntry> missing = new ArrayList<>();
                Set<String> used = new HashSet<>();
                for (KnowledgeEntry entry : entries) {
                    String id = clean(entry.getKnowledgeId());
                    if (id == null) missing.add(entry);
                    else used.add(id);
                }
                total = missing.size();
                if (missing.isEmpty() || Config.llm == null || !Config.llm.isConfigured()) {
                    if (!missing.isEmpty()) lastError = Lang.get("rag.id-llm-unavailable");
                    return entries;
                }

                Map<String, String> generated = new HashMap<>();
                for (int start = 0; start < missing.size(); start += BATCH_SIZE) {
                    int end = Math.min(start + BATCH_SIZE, missing.size());
                    List<KnowledgeEntry> batch = missing.subList(start, end);
                    Map<String, String> candidates = generateBatch(batch);
                    for (KnowledgeEntry entry : batch) {
                        String fingerprint = fingerprint(entry);
                        String candidate = candidates.get(fingerprint);
                        if (!isValid(candidate)) {
                            candidate = generateSingle(entry);
                        }
                        if (isValid(candidate)) {
                            String unique = unique(candidate, used);
                            used.add(unique);
                            generated.put(fingerprint, unique);
                        }
                        completed++;
                    }
                }
                lastGenerated = KnowledgeFileManager.mergeMissingKnowledgeIds(generated);
                return KnowledgeFileManager.readAll();
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                ExAI.getInstance().getLogger().warning("知识 ID 自动补齐失败: " + lastError);
                return initialEntries == null ? new ArrayList<>() : initialEntries;
            } finally {
                running = false;
            }
        }
    }

    public static String regenerate(String currentId) {
        synchronized (TASK_LOCK) {
            running = true;
            total = 1;
            completed = 0;
            lastGenerated = 0;
            lastError = "";
            try {
                if (Config.llm == null || !Config.llm.isConfigured()) {
                    lastError = Lang.get("rag.id-llm-unavailable");
                    return null;
                }
                KnowledgeEntry target = null;
                Set<String> used = new HashSet<>();
                for (KnowledgeEntry entry : KnowledgeFileManager.readAll()) {
                    String id = clean(entry.getKnowledgeId());
                    if (currentId.equals(id)) target = entry;
                    else if (id != null) used.add(id);
                }
                if (target == null) {
                    lastError = Lang.get("rag.id-not-found", currentId);
                    return null;
                }
                String candidate = generateSingle(target);
                completed = 1;
                if (!isValid(candidate)) {
                    lastError = Lang.get("rag.id-invalid-response");
                    return null;
                }
                String newId = unique(candidate, used);
                if (!KnowledgeFileManager.replaceKnowledgeId(currentId, newId)) return null;
                lastGenerated = 1;
                return newId;
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                ExAI.getInstance().getLogger().warning("重新生成知识 ID 失败: " + lastError);
                return null;
            } finally {
                running = false;
            }
        }
    }

    private static Map<String, String> generateBatch(List<KnowledgeEntry> entries) {
        JsonArray input = new JsonArray();
        for (KnowledgeEntry entry : entries) {
            JsonObject row = new JsonObject();
            row.addProperty("key", fingerprint(entry));
            row.addProperty("question", truncate(entry.getQuestion(), 500));
            row.addProperty("answer", truncate(entry.getAnswer(), 700));
            input.add(row);
        }
        String raw = Config.llm.complete(Lang.get("prompt.knowledge-id") + "\n" + input, 0.0, 1200);
        return parseIds(raw);
    }

    private static String generateSingle(KnowledgeEntry entry) {
        Map<String, String> parsed = generateBatch(java.util.Collections.singletonList(entry));
        return parsed.get(fingerprint(entry));
    }

    private static Map<String, String> parseIds(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null) return result;
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end < start) return result;
        try {
            JsonElement root = new JsonParser().parse(raw.substring(start, end + 1));
            if (!root.isJsonArray()) return result;
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject object = element.getAsJsonObject();
                if (!object.has("key") || !object.has("id")) continue;
                result.put(object.get("key").getAsString(), normalize(object.get("id").getAsString()));
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_')
                .replaceAll("_+", "_");
        return normalized;
    }

    private static boolean isValid(String id) {
        return id != null && VALID_ID.matcher(id).matches()
                && !id.startsWith("plugin_") && !id.startsWith("legacy_");
    }

    private static String unique(String candidate, Set<String> used) {
        if (!used.contains(candidate)) return candidate;
        int suffix = 2;
        while (true) {
            String tail = "_" + suffix;
            String base = candidate.length() + tail.length() <= 64
                    ? candidate : candidate.substring(0, 64 - tail.length());
            String value = base + tail;
            if (!used.contains(value)) return value;
            suffix++;
        }
    }

    public static String documentId(KnowledgeEntry entry) {
        String id = clean(entry == null ? null : entry.getKnowledgeId());
        return id == null ? "legacy_" + fingerprint(entry).substring(0, 12) : id;
    }

    public static String fingerprint(KnowledgeEntry entry) {
        String text = entry == null ? "" : safe(entry.getQuestion()).trim().toLowerCase(Locale.ROOT)
                + "\u0000" + safe(entry.getAnswer()).trim();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format("%02x", b & 0xff));
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode()) + Integer.toHexString(text.length());
        }
    }

    private static String clean(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value, int max) {
        String safe = safe(value);
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    public static boolean isRunning() { return running; }
    public static int getTotal() { return total; }
    public static int getCompleted() { return completed; }
    public static int getLastGenerated() { return lastGenerated; }
    public static String getLastError() { return lastError; }
}
