package com.exai.web;

import com.exai.ExAI;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.Reader;
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

/** Web CLI AI 的轻量会话记忆，只保存 user/assistant 最终文本，不保存 tool 调用大段上下文。 */
public final class WebAiSessionStore {
    private static final int MAX_CONTEXT_MESSAGES = 16; // 8 rounds injected into the LLM
    private static final int MAX_MESSAGE_CHARS = 4000;
    private static final int MAX_TOTAL_CHARS = 14000;
    /** 最后一轮对话距今超过 30 天的会话会被清理。 */
    private static final long CONVERSATION_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;
    /** 还没有任何对话的新会话只在内存中短暂保留，避免浏览器探测请求无限累积。 */
    private static final long EMPTY_SESSION_TTL_MS = 45L * 60L * 1000L;
    private static final String STORE_FILE = "web-ai-sessions.json";
    private static final Gson GSON = new Gson();
    private static final LinkedHashMap<String, Session> SESSIONS = new LinkedHashMap<>();
    private static boolean loaded;

    private WebAiSessionStore() {}

    public static synchronized String ensure(String requestedId) {
        loadIfNeeded();
        if (cleanup()) {
            save();
        }
        String id = validId(requestedId) ? requestedId : newId();
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
        s.messages.add(new Msg("user", clip(user), turnId));
        s.messages.add(new Msg("assistant", clip(assistant), turnId));
        s.touch();
        s.lastConversation = System.currentTimeMillis();
        save();
    }

    public static synchronized void reset(String id) {
        String sid = ensure(id);
        Session s = SESSIONS.get(sid);
        if (s != null) {
            s.messages.clear();
            s.lastConversation = 0L;
            s.touch();
            save();
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
        if (cleanup()) save();
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
        save();
        return new RewindResult(true, removedMessages / 2, "");
    }

    public static synchronized void clearAll() {
        loadIfNeeded();
        SESSIONS.clear();
        save();
    }

    private static boolean validId(String id) {
        return id != null && id.length() > 0 && id.length() <= 64 && id.matches("[A-Za-z0-9_-]+");
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String clip(String s) {
        if (s == null) return "";
        return s.length() > MAX_MESSAGE_CHARS ? s.substring(0, MAX_MESSAGE_CHARS) + "..." : s;
    }

    private static boolean cleanup() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        Iterator<Map.Entry<String, Session>> it = SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            Session session = it.next().getValue();
            boolean expiredConversation = session.lastConversation > 0L
                    && now - session.lastConversation > CONVERSATION_RETENTION_MS;
            boolean expiredEmpty = session.lastConversation == 0L
                    && now - session.lastAccess > EMPTY_SESSION_TTL_MS;
            if (expiredConversation || expiredEmpty) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    private static void loadIfNeeded() {
        if (loaded) {
            return;
        }
        loaded = true;
        File file = storeFile();
        if (!file.isFile()) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            JsonElement root = new JsonParser().parse(reader);
            if (!root.isJsonObject()) {
                return;
            }
            JsonElement sessions = root.getAsJsonObject().get("sessions");
            if (sessions == null || !sessions.isJsonArray()) {
                return;
            }
            for (JsonElement element : sessions.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject data = element.getAsJsonObject();
                String id = string(data, "id");
                if (!validId(id)) {
                    continue;
                }
                Session session = new Session();
                session.createdAt = longValue(data, "createdAt");
                session.lastConversation = longValue(data, "lastConversation");
                session.lastAccess = longValue(data, "lastAccess");
                JsonElement messages = data.get("messages");
                long fallbackTurnId = 1L;
                if (messages != null && messages.isJsonArray()) {
                    for (JsonElement message : messages.getAsJsonArray()) {
                        if (!message.isJsonObject()) {
                            continue;
                        }
                        JsonObject m = message.getAsJsonObject();
                        String role = string(m, "role");
                        if (!"user".equals(role) && !"assistant".equals(role)) {
                            continue;
                        }
                        long turnId = longValue(m, "turnId");
                        if (turnId <= 0L) {
                            turnId = fallbackTurnId;
                            if ("assistant".equals(role)) fallbackTurnId++;
                        }
                        session.nextTurnId = Math.max(session.nextTurnId, turnId + 1L);
                        session.messages.add(new Msg(role, clip(string(m, "content")), turnId));
                    }
                }
                if (!session.messages.isEmpty() && session.lastConversation == 0L) {
                    session.lastConversation = session.lastAccess;
                }
                if (session.createdAt == 0L) {
                    session.createdAt = session.lastConversation > 0L ? session.lastConversation : session.lastAccess;
                }
                SESSIONS.put(id, session);
            }
            cleanup();
        } catch (Exception e) {
            ExAI.getInstance().getLogger().warning("读取 Web AI 会话失败: " + e.getMessage());
        }
    }

    private static void save() {
        JsonArray sessions = new JsonArray();
        for (Map.Entry<String, Session> entry : SESSIONS.entrySet()) {
            Session session = entry.getValue();
            if (session.messages.isEmpty()) {
                continue;
            }
            JsonObject data = new JsonObject();
            data.addProperty("id", entry.getKey());
            data.addProperty("createdAt", session.createdAt);
            data.addProperty("lastConversation", session.lastConversation);
            data.addProperty("lastAccess", session.lastAccess);
            JsonArray messages = new JsonArray();
            for (Msg message : session.messages) {
                JsonObject m = new JsonObject();
                m.addProperty("role", message.role);
                m.addProperty("content", message.content);
                m.addProperty("turnId", message.turnId);
                messages.add(m);
            }
            data.add("messages", messages);
            sessions.add(data);
        }
        JsonObject root = new JsonObject();
        root.add("sessions", sessions);
        File file = storeFile();
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }
            Files.write(file.toPath(), GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            ExAI.getInstance().getLogger().warning("保存 Web AI 会话失败: " + e.getMessage());
        }
    }

    private static File storeFile() {
        return new File(ExAI.getInstance().getDataFolder(), STORE_FILE);
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
