package com.exai.web;

import com.exai.ExAI;
import com.exai.service.ToolConversation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedList;
import java.util.Map;
import java.util.Collections;
import java.util.Comparator;
import java.util.UUID;
import java.util.LinkedHashMap;

/** Separate display transcripts and durable tool execution ledgers, stored per session. */
public final class WebAiSessionStore {
    private static final int MAX_CONTEXT_MESSAGES = 16; // 8 rounds injected into the LLM
    private static final int MAX_TOTAL_CHARS = 14000;
    /** 最后一轮对话距今超过 30 天的会话会被清理。 */
    private static final long CONVERSATION_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;
    /** 还没有任何对话的新会话只在内存中短暂保留，避免浏览器探测请求无限累积。 */
    private static final long EMPTY_SESSION_TTL_MS = 45L * 60L * 1000L;
    private static final String STORE_FILE = "web-ai-sessions.json";
    private static final Gson GSON = new Gson();
    private static final LinkedHashMap<String, Session> SESSIONS = new LinkedHashMap<>();
    private static boolean loaded;
    private static File rootOverride;
    private static final Map<String, Thread> BUSY = new LinkedHashMap<>();
    private static final Map<String, Integer> LOCK_DEPTH = new LinkedHashMap<>();
    private static final Set<String> BROKEN = new HashSet<>();

    static synchronized void useDirectory(File root) {
        rootOverride = root;
        loaded = false;
        SESSIONS.clear();
        BUSY.clear();
        LOCK_DEPTH.clear();
        BROKEN.clear();
    }

    public static synchronized boolean acquire(String id) {
        ensure(id);
        if (BUSY.containsKey(id)) return false;
        BUSY.put(id, Thread.currentThread());
        LOCK_DEPTH.put(id, 1);
        return true;
    }

    public static synchronized boolean isBusy(String id) { return BUSY.containsKey(id); }
    public static synchronized boolean busyElsewhere(String id) {
        return BUSY.containsKey(id) && BUSY.get(id) != Thread.currentThread();
    }
    public static synchronized boolean acquireControl(String id) {
        if (id == null || id.isEmpty()) return true;
        if (busyElsewhere(id)) return false;
        if (!BUSY.containsKey(id)) return acquire(id);
        LOCK_DEPTH.put(id, LOCK_DEPTH.get(id) + 1);
        return true;
    }

    public static synchronized void release(String id) {
        if (BUSY.get(id) != Thread.currentThread()) return;
        int depth = LOCK_DEPTH.get(id) - 1;
        if (depth > 0) LOCK_DEPTH.put(id, depth);
        else { BUSY.remove(id); LOCK_DEPTH.remove(id); }
    }

    public static synchronized void interrupted(String id, Exception error) {
        ToolConversation task = execution(id);
        if (!"RUNNING".equals(task.status)) return;
        task.status = "FAILED";
        task.reason = error.getMessage();
        restorePendingDisplay(SESSIONS.get(id));
        try { save(id); } catch (RuntimeException ignored) { /* Keep dirty state for explicit retry. */ }
    }

    public static synchronized ToolConversation execution(String id) {
        return SESSIONS.get(ensure(id)).execution;
    }

    public static synchronized void checkpoint(String id) {
        Session session = SESSIONS.get(id);
        if (session == null) throw new IllegalStateException("会话不存在");
        session.touch();
        session.lastConversation = System.currentTimeMillis();
        save(id);
    }

    public static synchronized Map<String, Object> taskMeta(String id) {
        ToolConversation task = execution(id);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("taskId", task.taskId);
        meta.put("taskStatus", task.status);
        meta.put("canContinue", task.canContinue());
        meta.put("iterations", task.iterations);
        meta.put("totalIterations", task.totalIterations);
        return meta;
    }

    public static synchronized void recordEvent(String id, String event) {
        if (id == null || id.isEmpty()) return;
        ToolConversation task = execution(id);
        com.exai.service.LLMService.closePendingCalls(task);
        task.addText("user", "[配置操作结果] " + event);
        checkpoint(id);
    }

    private WebAiSessionStore() {}

    public static synchronized String ensure(String requestedId) {
        loadIfNeeded();
        cleanup();
        String id = validId(requestedId) ? requestedId : newId();
        if (BROKEN.contains(id)) throw new IllegalStateException("会话文件及备份损坏，无法恢复: " + id);
        Session s = SESSIONS.get(id);
        if (s == null) {
            s = new Session();
            SESSIONS.put(id, s);
        }
        s.touch();
        return id;
    }

    public static synchronized JsonArray history(String id) {
        String sid = ensure(id);
        Session s = SESSIONS.get(sid);
        JsonArray arr = new JsonArray();
        if (s == null) return arr;
        s.touch();
        LinkedList<Msg> context = new LinkedList<>();
        int contextChars = 0;
        for (int i = s.messages.size() - 1; i >= 1; i -= 2) {
            Msg assistant = s.messages.get(i);
            Msg user = s.messages.get(i - 1);
            if (assistant.turnId != user.turnId) continue;
            int pairChars = assistant.content.length() + user.content.length();
            if (context.size() + 2 > MAX_CONTEXT_MESSAGES
                    || (contextChars + pairChars > MAX_TOTAL_CHARS && !context.isEmpty())) break;
            context.addFirst(assistant);
            context.addFirst(user);
            contextChars += pairChars;
        }
        for (Msg m : context) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role);
            o.addProperty("content", m.content);
            arr.add(o);
        }
        return arr;
    }

    /** Allocates a stable identifier for one user/assistant exchange. */
    public static synchronized long beginTurn(String id) {
        String sid = ensure(id);
        Session s = SESSIONS.get(sid);
        return s.nextTurnId++;
    }

    public static synchronized void appendExchange(String id, long turnId, String user, String assistant) {
        String sid = ensure(id);
        Session s = SESSIONS.get(sid);
        int count = s.messages.size();
        if (count >= 2 && s.messages.get(count - 1).turnId == turnId) {
            s.messages.set(count - 1, new Msg("assistant", clip(assistant), turnId));
        } else {
            s.messages.add(new Msg("user", clip(user), turnId));
            s.messages.add(new Msg("assistant", clip(assistant), turnId));
        }
        s.touch();
        s.lastConversation = System.currentTimeMillis();
        save(sid);
    }

    public static synchronized void reset(String id) {
        String sid = ensure(id);
        Session s = SESSIONS.get(sid);
        if (s != null) {
            s.messages.clear();
            s.execution = new ToolConversation();
            s.lastConversation = 0L;
            s.touch();
            save(sid);
            save(sid); // A recovery backup must not resurrect cleared messages.
        }
    }

    public static synchronized int size(String id) {
        String sid = ensure(id);
        Session s = SESSIONS.get(sid);
        return s == null ? 0 : s.messages.size() / 2;
    }

    /** Lists persisted conversations for the Web CLI sidebar. */
    public static synchronized List<Map<String, Object>> listSessions() {
        loadIfNeeded();
        cleanup();
        List<SessionSummary> summaries = new ArrayList<>();
        for (Map.Entry<String, Session> entry : SESSIONS.entrySet()) {
            Session session = entry.getValue();
            if (session.messages.isEmpty()) continue;
            summaries.add(new SessionSummary(entry.getKey(), session));
        }
        Collections.sort(summaries, Comparator.comparingLong(SessionSummary::sortTime).reversed());
        List<Map<String, Object>> out = new ArrayList<>();
        for (SessionSummary summary : summaries) out.add(summary.toMap());
        return out;
    }

    /** Returns the complete retained transcript of one session for Web CLI rendering. */
    public static synchronized Map<String, Object> transcript(String id) {
        String sid = ensure(id);
        Session session = SESSIONS.get(sid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("aiSessionId", sid);
        List<Map<String, Object>> messages = new ArrayList<>();
        if (session != null) {
            for (Msg message : session.messages) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("role", message.role);
                row.put("content", message.content);
                row.put("turnId", message.turnId);
                messages.add(row);
            }
        }
        out.put("messages", messages);
        out.putAll(taskMeta(sid));
        return out;
    }

    /** Returns the retained, stable turn IDs in chronological order. */
    public static synchronized List<Long> turnIds(String id) {
        String sid = ensure(id);
        Session s = SESSIONS.get(sid);
        List<Long> ids = new ArrayList<>();
        if (s == null) return ids;
        long last = -1;
        for (Msg message : s.messages) {
            if (message.turnId != last) {
                ids.add(message.turnId);
                last = message.turnId;
            }
        }
        return ids;
    }

    /** Removes the specified turn and every turn after it. */
    public static synchronized RewindResult rewindFrom(String id, long turnId) {
        String sid = ensure(id);
        Session s = SESSIONS.get(sid);
        if (s == null || !turnIds(sid).contains(turnId)) {
            return new RewindResult(false, 0, "轮次不存在或已不在当前会话记忆中: " + turnId);
        }
        int removedMessages = 0;
        Iterator<Msg> it = s.messages.iterator();
        while (it.hasNext()) {
            if (it.next().turnId >= turnId) {
                it.remove();
                removedMessages++;
            }
        }
        s.lastConversation = s.messages.isEmpty() ? 0L : System.currentTimeMillis();
        s.touch();
        JsonArray retained = new JsonArray();
        for (JsonElement element : s.execution.messages) {
            JsonObject message = element.getAsJsonObject();
            if (longValue(message, "_turnId") < turnId) retained.add(message);
        }
        s.execution = new ToolConversation();
        s.execution.messages = retained;
        s.execution.turnId = s.messages.isEmpty() ? 0 : s.messages.getLast().turnId;
        com.exai.service.LLMService.closePendingCalls(s.execution);
        save(sid);
        save(sid);
        return new RewindResult(true, removedMessages / 2, "");
    }

    public static synchronized void clearAll() {
        loadIfNeeded();
        for (String id : SESSIONS.keySet()) deleteSessionFiles(id);
        SESSIONS.clear();
    }

    private static boolean validId(String id) {
        return id != null && id.length() > 0 && id.length() <= 64 && id.matches("[A-Za-z0-9_-]+");
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s;
    }

    private static boolean cleanup() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        Iterator<Map.Entry<String, Session>> it = SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Session> entry = it.next();
            Session session = entry.getValue();
            boolean expiredConversation = session.lastConversation > 0L
                    && now - session.lastConversation > CONVERSATION_RETENTION_MS;
            boolean expiredEmpty = session.lastConversation == 0L
                    && now - session.lastAccess > EMPTY_SESSION_TTL_MS;
            if ((expiredConversation || expiredEmpty) && !BUSY.containsKey(entry.getKey())) {
                deleteSessionFiles(entry.getKey());
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    private static File dataRoot() {
        return rootOverride != null ? rootOverride : ExAI.getInstance().getDataFolder();
    }

    private static File directory() { return new File(dataRoot(), "web-ai-sessions"); }
    private static Path sessionFile(String id) { return new File(directory(), id + ".json").toPath(); }

    private static void loadIfNeeded() {
        if (loaded) return;
        loaded = true;
        File[] files = directory().listFiles();
        Set<String> ids = new HashSet<>();
        if (files != null) for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".json.bak")) name = name.substring(0, name.length() - 4);
            if (name.endsWith(".json")) {
                String id = name.substring(0, name.length() - 5);
                if (validId(id)) ids.add(id);
            }
        }
        for (String id : ids) {
            try {
                JsonObject data = AtomicJsonFile.read(sessionFile(id), d -> validSnapshot(d) && id.equals(string(d, "id")));
                Session session = decode(data);
                if ("RUNNING".equals(session.execution.status)) {
                    String completedText = finalResponse(session.execution);
                    session.execution.status = completedText == null ? "INTERRUPTED" : "COMPLETED";
                    session.execution.reason = completedText == null ? "服务器重启，等待明确继续" : "";
                }
                restorePendingDisplay(session);
                SESSIONS.put(id, session);
            } catch (Exception e) {
                BROKEN.add(id);
                System.err.println("读取 Web AI 会话失败: " + id + ": " + e.getMessage());
            }
        }
        try { migrateLegacy(); }
        catch (Exception e) { loaded = false; throw new IllegalStateException("迁移 Web AI 会话失败", e); }
        cleanup();
    }

    private static String finalResponse(ToolConversation task) {
        if (task.messages.size() == 0 || !task.pendingInput.isEmpty()) return null;
        JsonObject last = task.messages.get(task.messages.size() - 1).getAsJsonObject();
        if (!"assistant".equals(string(last, "role"))
                || (last.has("tool_calls") && last.get("tool_calls").isJsonArray()
                && last.getAsJsonArray("tool_calls").size() > 0)) return null;
        String text = string(last, "content");
        return text.trim().isEmpty() ? null : text;
    }

    private static void restorePendingDisplay(Session session) {
        if (session.messages.isEmpty()) return;
        Msg last = session.messages.getLast();
        if (!"assistant".equals(last.role) || last.turnId != session.execution.turnId
                || !"任务执行中，进度将逐步保存。".equals(last.content)) return;
        String text = "COMPLETED".equals(session.execution.status) ? finalResponse(session.execution) : null;
        if (text == null) text = "任务已停止，已保存执行进度。" + session.execution.reason
                + (session.execution.canContinue() ? "\n可点击继续任务或输入 /continue。" : "");
        session.messages.set(session.messages.size() - 1, new Msg("assistant", text, last.turnId));
    }

    private static boolean validSnapshot(JsonObject data) {
        try {
            if (data.get("formatVersion").getAsInt() != 2 || !validId(string(data, "id"))
                    || data.get("saveVersion").getAsLong() < 1 || !data.has("execution")) return false;
            decode(data);
            return true;
        } catch (Exception e) { return false; }
    }

    private static Session decode(JsonObject data) {
        Session session = new Session();
        session.createdAt = longValue(data, "createdAt");
        session.lastConversation = longValue(data, "lastConversation");
        session.lastAccess = longValue(data, "lastAccess");
        session.saveVersion = longValue(data, "saveVersion");
        session.nextTurnId = Math.max(1, longValue(data, "nextTurnId"));
        for (JsonElement element : data.getAsJsonArray("messages")) {
            JsonObject m = element.getAsJsonObject();
            String role = m.get("role").getAsString();
            if (!"user".equals(role) && !"assistant".equals(role)) throw new IllegalArgumentException("Invalid role");
            long turnId = longValue(m, "turnId");
            if (turnId <= 0) turnId = session.messages.size() / 2 + 1;
            session.messages.add(new Msg(role, m.get("content").getAsString(), turnId));
            session.nextTurnId = Math.max(session.nextTurnId, turnId + 1);
        }
        if (data.has("execution")) {
            session.execution = GSON.fromJson(data.get("execution"), ToolConversation.class);
            ToolConversation task = session.execution;
            if (task == null || task.messages == null || task.summary == null || task.status == null
                    || task.startedCalls == null || task.pendingInput == null || task.taskId == null || task.cwd == null
                    || task.summaryThrough < 0 || task.summaryThrough > task.messages.size())
                throw new IllegalArgumentException("Invalid execution ledger");
            for (JsonElement element : task.messages) {
                JsonObject m = element.getAsJsonObject();
                String role = m.get("role").getAsString();
                if (!"user".equals(role) && !"assistant".equals(role) && !"tool".equals(role))
                    throw new IllegalArgumentException("Invalid ledger role");
            }
        } else {
            for (Msg message : session.messages) {
                session.execution.turnId = message.turnId;
                session.execution.addText(message.role, message.content);
            }
        }
        if (!session.messages.isEmpty() && session.lastConversation == 0)
            session.lastConversation = session.lastAccess;
        if (session.createdAt == 0) session.createdAt = session.lastConversation;
        return session;
    }

    private static void migrateLegacy() throws IOException {
        Path marker = new File(dataRoot(), "web-ai-sessions-migration.json").toPath();
        if (AtomicJsonFile.read(marker, d -> d.has("complete") && d.get("complete").getAsBoolean()) != null) return;
        File legacy = new File(dataRoot(), STORE_FILE);
        if (legacy.isFile()) {
            JsonObject root = JsonParser.parseString(new String(Files.readAllBytes(legacy.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement element : root.getAsJsonArray("sessions")) {
                JsonObject data = element.getAsJsonObject();
                String id = string(data, "id");
                if (!validId(id) || BROKEN.contains(id)) continue;
                if (SESSIONS.containsKey(id)) {
                    // A prior migration attempt may have retained an unsaved session in memory.
                    if (!Files.isRegularFile(sessionFile(id))) save(id);
                    continue;
                }
                SESSIONS.put(id, decode(data));
                save(id);
            }
        }
        JsonObject complete = new JsonObject();
        complete.addProperty("complete", true);
        AtomicJsonFile.write(marker, complete, d -> d.has("complete"));
    }

    private static void save(String id) {
        Session session = SESSIONS.get(id);
        JsonObject data = new JsonObject();
        data.addProperty("formatVersion", 2);
        data.addProperty("saveVersion", ++session.saveVersion);
        data.addProperty("id", id);
        data.addProperty("createdAt", session.createdAt);
        data.addProperty("lastConversation", session.lastConversation);
        data.addProperty("lastAccess", session.lastAccess);
        data.addProperty("nextTurnId", session.nextTurnId);
        data.add("messages", GSON.toJsonTree(session.messages));
        data.add("execution", GSON.toJsonTree(session.execution));
        Exception failure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try { AtomicJsonFile.write(sessionFile(id), data, WebAiSessionStore::validSnapshot); return; }
            catch (IOException e) { failure = e; }
        }
        throw new IllegalStateException("会话保存失败，已停止执行；内存进度保留，请修复磁盘后继续", failure);
    }

    private static void deleteSessionFiles(String id) {
        Path file = sessionFile(id);
        try {
            // Delete primary last, so a failure cannot resurrect a stale backup.
            Files.deleteIfExists(AtomicJsonFile.backup(file));
            Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".tmp"));
            Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".bak.tmp"));
            Files.deleteIfExists(file);
        } catch (IOException e) { throw new IllegalStateException("清理会话失败: " + id, e); }
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static long longValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        try {
            return value == null || value.isJsonNull() ? 0L : value.getAsLong();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static final class Session {
        final LinkedList<Msg> messages = new LinkedList<>();
        long createdAt = System.currentTimeMillis();
        long lastAccess = System.currentTimeMillis();
        long lastConversation;
        long nextTurnId = 1L;
        long saveVersion;
        ToolConversation execution = new ToolConversation();
        void touch() { lastAccess = System.currentTimeMillis(); }
    }

    private static final class SessionSummary {
        final String id;
        final long createdAt;
        final long lastConversation;
        final int turns;
        final String topic;

        SessionSummary(String id, Session session) {
            this.id = id;
            this.createdAt = session.createdAt;
            this.lastConversation = session.lastConversation;
            this.turns = session.messages.size() / 2;
            String firstUser = "";
            for (Msg message : session.messages) {
                if ("user".equals(message.role)) {
                    firstUser = message.content;
                    break;
                }
            }
            this.topic = firstUser.length() > 48 ? firstUser.substring(0, 48) + "..." : firstUser;
        }

        long sortTime() { return lastConversation > 0L ? lastConversation : createdAt; }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("createdAt", createdAt);
            out.put("lastConversation", lastConversation);
            out.put("turns", turns);
            out.put("topic", topic);
            return out;
        }
    }

    private static final class Msg {
        final String role;
        final String content;
        final long turnId;
        Msg(String role, String content, long turnId) {
            this.role = role;
            this.content = content == null ? "" : content;
            this.turnId = turnId;
        }
    }

    public static final class RewindResult {
        private final boolean ok;
        private final int removedTurns;
        private final String error;

        RewindResult(boolean ok, int removedTurns, String error) {
            this.ok = ok;
            this.removedTurns = removedTurns;
            this.error = error;
        }

        public boolean isOk() { return ok; }
        public int getRemovedTurns() { return removedTurns; }
        public String getError() { return error; }
    }
}
