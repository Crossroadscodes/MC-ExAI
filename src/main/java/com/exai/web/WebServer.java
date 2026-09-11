package com.exai.web;

import com.exai.ExAI;
import com.exai.config.Config;
import com.exai.data.DataContainer;
import com.exai.entity.KnowledgeEntry;
import com.exai.entity.PluginDescriptionEntry;
import com.exai.manager.KnowledgeFileManager;
import com.exai.manager.PluginDescriptionManager;
import com.exai.manager.PluginHelpProbe;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 内嵌的网页管理服务（仅绑定本机 127.0.0.1，无鉴权）。
 * v1 仅提供 config.yml 的可视化编辑：读取当前配置、保存并热重载。
 */
public class WebServer {

    private static final Gson GSON = new Gson();

    private static HttpServer server;
    private static ExecutorService executor;
    private static int boundPort = -1;

    // ====== 字段类型常量 ======
    private static final String T_STRING = "string";
    private static final String T_PASSWORD = "password";
    private static final String T_TEXT = "text";
    private static final String T_INT = "int";
    private static final String T_DOUBLE = "double";
    private static final String T_BOOLEAN = "boolean";
    private static final String T_ENUM = "enum";

    private static final String REWARD_ITEMS_PATH = "knowledge.knowledgeReview.rewards.items";

    /**
     * 按当前配置对账 Web 服务状态：该开未开则启动，该关未关则停止，端口变化则重启。
     * 在 onEnable 与每次 {@code Config.loadAll()}（含 /exai reload、网页保存）后调用。
     */
    public static synchronized void apply() {
        boolean shouldRun = Config.webuiEnabled;
        boolean running = server != null;
        if (running && (!shouldRun || boundPort != Config.webuiPort)) {
            stop();
            running = false;
        }
        if (shouldRun && !running) {
            start();
        }
    }

    public static synchronized void start() {
        if (server != null) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", Config.webuiPort), 0);
            executor = Executors.newFixedThreadPool(8);
            server.setExecutor(executor);
            server.createContext("/api/config", WebServer::handleConfig);
            server.createContext("/api/files/list", WebServer::handleFileList);
            server.createContext("/api/files/read", WebServer::handleFileRead);
            server.createContext("/api/files/write", WebServer::handleFileWrite);
            server.createContext("/api/cli/confirm-all", WebServer::handleCliConfirmAll);
            server.createContext("/api/cli/sessions", WebServer::handleCliSessions);
            server.createContext("/api/cli/session", WebServer::handleCliSession);
            server.createContext("/api/cli/stream", WebServer::handleCliStream);
            server.createContext("/api/cli", WebServer::handleCli);
            server.createContext("/api/plugins/scan", WebServer::handlePluginsScan);
            server.createContext("/api/plugins/gen-desc-all", WebServer::handlePluginGenDescAll);
            server.createContext("/api/plugins/gen-desc", WebServer::handlePluginGenDesc);
            server.createContext("/api/plugins", WebServer::handlePlugins);
            server.createContext("/api/knowledge/add", WebServer::handleKnowledgeAdd);
            server.createContext("/api/knowledge/update", WebServer::handleKnowledgeUpdate);
            server.createContext("/api/knowledge/delete", WebServer::handleKnowledgeDelete);
            server.createContext("/api/knowledge/migrate-db", WebServer::handleKnowledgeMigrateDb);
            server.createContext("/api/knowledge", WebServer::handleKnowledgeList);
            server.createContext("/", WebServer::handleRoot);
            server.start();
            boundPort = Config.webuiPort;
            ExAI.getInstance().getLogger().info(
                    "网页管理服务已启动: http://127.0.0.1:" + Config.webuiPort);
        } catch (IOException e) {
            ExAI.getInstance().getLogger().warning(
                    "网页管理服务启动失败(端口 " + Config.webuiPort + " 可能被占用): " + e.getMessage());
            server = null;
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        }
    }

    public static synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            boundPort = -1;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    // ====== Handlers ======

    private static void handleRoot(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                sendText(ex, 405, "Method Not Allowed");
                return;
            }
            byte[] page = readResource("web/admin.html");
            if (page == null) {
                sendText(ex, 404, "admin.html not found");
                return;
            }
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.sendResponseHeaders(200, page.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(page);
            }
        } finally {
            ex.close();
        }
    }

    private static void handleConfig(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                sendJson(ex, 200, buildConfigPayload());
            } else if ("POST".equalsIgnoreCase(method)) {
                applyConfig(readBody(ex));
                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("ok", true);
                sendJson(ex, 200, ok);
            } else {
                sendText(ex, 405, "Method Not Allowed");
            }
        } catch (Exception e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("ok", false);
            err.put("error", String.valueOf(e.getMessage()));
            sendJson(ex, 500, err);
            ExAI.getInstance().getLogger().warning("网页管理服务处理 /api/config 出错: " + e.getMessage());
        } finally {
            ex.close();
        }
    }

    // ====== 文件管理（根目录限定在 plugins/ 下，含路径越界防护） ======

    private static final long MAX_READ_BYTES = 2_000_000L; // 单文件读取上限 ~2MB

    /** plugins/ 目录的规范化绝对路径，作为允许访问的根。 */
    static File pluginsRoot() throws IOException {
        return ExAI.getInstance().getDataFolder().getParentFile().getCanonicalFile();
    }

    /** 把相对路径解析到根目录内，越界(../)则抛 SecurityException。 */
    static File resolveSafe(String rel) throws IOException {
        File base = pluginsRoot();
        File target = new File(base, rel == null ? "" : rel).getCanonicalFile();
        String bp = base.getPath();
        if (!target.getPath().equals(bp) && !target.getPath().startsWith(bp + File.separator)) {
            throw new SecurityException("路径越界，已拒绝");
        }
        return target;
    }

    /** 文件相对根目录的路径（统一用 '/' 分隔）。 */
    static String relPath(File base, File f) {
        String bp = base.getPath();
        String p = f.getPath();
        if (p.equals(bp)) {
            return "";
        }
        String r = p.substring(bp.length());
        if (r.startsWith(File.separator)) {
            r = r.substring(1);
        }
        return r.replace(File.separatorChar, '/');
    }

    private static void handleFileList(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                sendText(ex, 405, "Method Not Allowed");
                return;
            }
            File base = pluginsRoot();
            File dir = resolveSafe(query(ex).get("path"));
            if (!dir.isDirectory()) {
                sendError(ex, 404, "目录不存在");
                return;
            }
            List<Map<String, Object>> entries = new ArrayList<>();
            File[] files = dir.listFiles();
            if (files != null) {
                Arrays.sort(files, (a, b) -> {
                    if (a.isDirectory() != b.isDirectory()) {
                        return a.isDirectory() ? -1 : 1;
                    }
                    return a.getName().compareToIgnoreCase(b.getName());
                });
                for (File f : files) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("name", f.getName());
                    e.put("dir", f.isDirectory());
                    e.put("size", f.isDirectory() ? 0L : f.length());
                    e.put("path", relPath(base, f));
                    entries.add(e);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", relPath(base, dir));
            out.put("entries", entries);
            sendJson(ex, 200, out);
        } catch (SecurityException se) {
            sendError(ex, 403, se.getMessage());
        } catch (Exception e) {
            sendError(ex, 500, String.valueOf(e.getMessage()));
        } finally {
            ex.close();
        }
    }

    private static void handleFileRead(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                sendText(ex, 405, "Method Not Allowed");
                return;
            }
            File base = pluginsRoot();
            File f = resolveSafe(query(ex).get("path"));
            if (!f.isFile()) {
                sendError(ex, 404, "文件不存在");
                return;
            }
            if (f.length() > MAX_READ_BYTES) {
                sendError(ex, 413, "文件过大(>2MB)，请用其它工具编辑");
                return;
            }
            byte[] bytes;
            try (InputStream is = new FileInputStream(f)) {
                bytes = readAll(is);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", relPath(base, f));
            out.put("content", new String(bytes, StandardCharsets.UTF_8));
            sendJson(ex, 200, out);
        } catch (SecurityException se) {
            sendError(ex, 403, se.getMessage());
        } catch (Exception e) {
            sendError(ex, 500, String.valueOf(e.getMessage()));
        } finally {
            ex.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static void handleFileWrite(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendText(ex, 405, "Method Not Allowed");
                return;
            }
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> req = GSON.fromJson(readBody(ex), type);
            if (req == null || req.get("path") == null) {
                sendError(ex, 400, "缺少 path");
                return;
            }
            File f = resolveSafe(String.valueOf(req.get("path")));
            if (f.isDirectory()) {
                sendError(ex, 400, "目标是目录");
                return;
            }
            String content = req.get("content") == null ? "" : String.valueOf(req.get("content"));
            // 覆盖前留一个 .bak 备份
            if (f.exists()) {
                Files.copy(f.toPath(), new File(f.getPath() + ".bak").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            sendJson(ex, 200, ok);
        } catch (SecurityException se) {
            sendError(ex, 403, se.getMessage());
        } catch (Exception e) {
            sendError(ex, 500, String.valueOf(e.getMessage()));
        } finally {
            ex.close();
        }
    }

    /** 网页终端 CLI：接收 {ids:[...]}，一次确认当前前端展示的全部待确认修改。 */
    private static void handleCliConfirmAll(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendText(ex, 405, "Method Not Allowed");
                return;
            }
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> req = GSON.fromJson(readBody(ex), type);
            List<String> ids = new ArrayList<>();
            if (req != null && req.get("ids") instanceof List) {
                for (Object o : (List<?>) req.get("ids")) {
                    if (o != null) {
                        ids.add(String.valueOf(o));
                    }
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("output", PluginConfigTool.confirmPendingAll(ids));
            sendJson(ex, 200, out);
        } catch (Exception e) {
            sendError(ex, 500, String.valueOf(e.getMessage()));
        } finally {
            ex.close();
        }
    }

    /** Lists saved AI conversations for the Web CLI sidebar. */
    private static void handleCliSessions(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                sendText(ex, 405, "Method Not Allowed");
                return;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("sessions", WebAiSessionStore.listSessions());
            sendJson(ex, 200, out);
        } catch (Exception e) {
            sendError(ex, 500, String.valueOf(e.getMessage()));
        } finally {
            ex.close();
        }
    }

    /** Returns the full retained transcript for a selected Web CLI conversation. */
    private static void handleCliSession(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                sendText(ex, 405, "Method Not Allowed");
                return;
            }
            String id = query(ex).get("id");
            if (id == null || id.trim().isEmpty()) {
                sendError(ex, 400, "Missing session id");
                return;
            }
            sendJson(ex, 200, WebAiSessionStore.transcript(id));
        } catch (Exception e) {
            sendError(ex, 500, String.valueOf(e.getMessage()));
        } finally {
            ex.close();
        }
    }

    /** 网页终端 CLI：接收 {cmd, cwd, aiSessionId}，交给 WebCli 执行。 */
    private static void handleCliStream(HttpExchange ex) throws IOException {
        boolean streaming = false;
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendText(ex, 405, "Method Not Allowed");
                return;
            }
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> req = GSON.fromJson(readBody(ex), type);
            String cmd = req == null || req.get("cmd") == null ? "" : String.valueOf(req.get("cmd"));
            String cwd = req == null || req.get("cwd") == null ? "" : String.valueOf(req.get("cwd"));
            String aiSessionId = req == null || req.get("aiSessionId") == null ? null : String.valueOf(req.get("aiSessionId"));

            ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.getResponseHeaders().set("Connection", "keep-alive");
            ex.sendResponseHeaders(200, 0);
            streaming = true;
            final OutputStream os = ex.getResponseBody();
            WebCli.execStream(cmd, cwd, aiSessionId, new WebCli.StreamSink() {
                @Override
                public void meta(Map<String, Object> data) throws IOException { sendSse(os, "meta", data); }
                @Override
                public void status(String message) throws IOException {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("message", message);
                    sendSse(os, "status", data);
                }
                @Override
                public void delta(String text) throws IOException {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("text", text);
                    sendSse(os, "delta", data);
                }
                @Override
                public void done(Map<String, Object> data) throws IOException { sendSse(os, "done", data); }
                @Override
                public void error(Map<String, Object> data) throws IOException { sendSse(os, "error", data); }
            });
        } catch (Exception e) {
            if (streaming) {
                try {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("ok", false);
                    err.put("error", String.valueOf(e.getMessage()));
                    sendSse(ex.getResponseBody(), "error", err);
                } catch (Exception ignored) {
                }
            } else {
                sendError(ex, 500, String.valueOf(e.getMessage()));
            }
        } finally {
            ex.close();
        }
    }

    private static void sendSse(OutputStream os, String event, Object data) throws IOException {
        os.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
        os.write(("data: " + GSON.toJson(data) + "\n\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private static void handleCli(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendText(ex, 405, "Method Not Allowed");
                return;
            }
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> req = GSON.fromJson(readBody(ex), type);
            String cmd = req == null || req.get("cmd") == null ? "" : String.valueOf(req.get("cmd"));
            String cwd = req == null || req.get("cwd") == null ? "" : String.valueOf(req.get("cwd"));
            String aiSessionId = req == null || req.get("aiSessionId") == null ? null : String.valueOf(req.get("aiSessionId"));
            sendJson(ex, 200, WebCli.exec(cmd, cwd, aiSessionId));
        } catch (Exception e) {
            sendError(ex, 500, String.valueOf(e.getMessage()));
        } finally {
            ex.close();
        }
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new LinkedHashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) {
            return m;
        }
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            if (i < 0) {
                continue;
            }
            m.put(urlDecode(pair.substring(0, i)), urlDecode(pair.substring(i + 1)));
        }
        return m;
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static void sendError(HttpExchange ex, int code, String msg) throws IOException {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("ok", false);
        err.put("error", msg);
        sendJson(ex, code, err);
    }

    // ====== 插件描述 ======

    private static void handlePlugins(HttpExchange ex) throws IOException {
        try {
            if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("plugins", PluginDescriptionManager.readAll());
                sendJson(ex, 200, out);
            } else if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                savePlugins(readBody(ex));
                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("ok", true);
                sendJson(ex, 200, ok);
            } else {
                sendText(ex, 405, "Method Not Allowed");
            }
        } catch (Exception e) {
            sendError(ex, 500, String.valueOf(e.getMessage()));
        } finally {
            ex.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static void savePlugins(String body) {
        List<Map<String, Object>> incoming = GSON.fromJson(body, new TypeToken<List<Map<String, Object>>>() {}.getType());
        if (incoming == null) {
            return;
        }
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> m : incoming) {
            Object name = m.get("name");
            if (name != null) {
                byName.put(name.toString(), m);
            }
        }
        // 合并进现有条目，仅覆盖 enabled / description，保留 version / installed
        List<PluginDescriptionEntry> all = PluginDescriptionManager.readAll();
        for (PluginDescriptionEntry e : all) {
            Map<String, Object> m = byName.get(e.getName());
            if (m != null) {
                if (m.get("enabled") != null) {
                    e.setEnabled(Boolean.parseBoolean(String.valueOf(m.get("enabled"))));
                }
                if (m.get("description") != null) {
                    e.setDescription(String.valueOf(m.get("description")));
                }
            }
        }
        PluginDescriptionManager.writeAll(all);
        // 让新描述进入向量库
        Config.reloadKnowledgeBaseOnly();
    }

    private static void handlePluginsScan(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendText(ex, 405, "Method Not Allowed");
                return;
            }
            // 扫描需在主线程执行（Bukkit API）
            List<PluginDescriptionEntry> list = Bukkit.getScheduler()
                    .callSyncMethod(ExAI.getInstance(), PluginDescriptionManager::scanAndMerge)
                    .get();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("plugins", list);
            sendJson(ex, 200, out);
        } catch (Exception e) {
            sendError(ex, 500, String.valueOf(e.getMessage()));
        } finally {
            ex.close();
        }
    }

    /** 用虚拟玩家执行插件 help 并交大模型概括，为单个插件生成描述。 */
    private static void handlePluginGenDesc(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendText(ex, 405, "Method Not Allowed");
                return;
            }
            Map<String, Object> req = GSON.fromJson(readBody(ex), new TypeToken<Map<String, Object>>() {}.getType());
            String name = req == null || req.get("name") == null ? "" : String.valueOf(req.get("name")).trim();
            if (name.isEmpty()) {
                sendError(ex, 400, "name 不能为空");
                return;
            }
            org.bukkit.plugin.Plugin p = Bukkit.getPluginManager().getPlugin(name);
            if (p == null) {
                sendError(ex, 400, "插件未安装或未在线: " + name);
                return;
            }
            String version = p.getDescription().getVersion();
            // 在 Web 线程上抓取：captureHelp 内部只把每次命令分发短暂切到主线程，等待与清洗都在本线程
            String raw = PluginHelpProbe.captureHelp(p);
            if (raw == null || raw.trim().isEmpty()) {
                sendError(ex, 400, "未能抓取到 " + name + " 的 help 输出，请手动填写");
                return;
            }
            String desc = PluginDescriptionManager.summarizeHelp(name, version, raw);
            if (desc == null) {
                sendError(ex, 502, "大模型未返回描述（请检查 LLM 配置）");
                return;
            }
            PluginDescriptionManager.setDescription(name, desc);
            Config.reloadKnowledgeBaseOnly();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("name", name);
            out.put("description", desc);
            sendJson(ex, 200, out);
        } catch (Exception e) {
            sendError(ex, 500, String.valueOf(e.getMessage()));
        } finally {
            ex.close();
        }
    }

    /** 对所有「已安装且启用」的插件批量生成描述（同步阻塞返回，可能耗时）。 */
    private static void handlePluginGenDescAll(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendText(ex, 405, "Method Not Allowed");
                return;
            }
            // 在 Web 线程上逐个抓取：每个插件的命令分发各自短暂切到主线程，分摊到多个 tick，避免一次性卡主线程
            Map<String, String[]> captured = captureAllHelp();
            List<Map<String, Object>> results = new ArrayList<>();
            int okCount = 0;
            for (Map.Entry<String, String[]> entry : captured.entrySet()) {
                String name = entry.getKey();
                String version = entry.getValue()[0];
                String raw = entry.getValue()[1];
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("name", name);
                if (raw == null || raw.trim().isEmpty()) {
                    r.put("ok", false);
                    r.put("error", "未抓取到 help 输出");
                    results.add(r);
                    continue;
                }
                String desc = PluginDescriptionManager.summarizeHelp(name, version, raw);
                if (desc == null) {
                    r.put("ok", false);
                    r.put("error", "大模型未返回");
                    results.add(r);
                    continue;
                }
                PluginDescriptionManager.setDescription(name, desc);
                r.put("ok", true);
                r.put("description", desc);
                results.add(r);
                okCount++;
            }
            if (okCount > 0) {
                Config.reloadKnowledgeBaseOnly();
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("count", okCount);
            out.put("results", results);
            sendJson(ex, 200, out);
        } catch (Exception e) {
            sendError(ex, 500, String.valueOf(e.getMessage()));
        } finally {
            ex.close();
        }
    }

    /** Web 线程执行：枚举「已安装且启用」的插件，逐个抓取 help 原文（内部按需切主线程）。返回 name -> [version, rawHelp]。 */
    private static Map<String, String[]> captureAllHelp() {
        Map<String, String[]> out = new LinkedHashMap<>();
        List<PluginDescriptionEntry> entries = PluginDescriptionManager.readAll();
        for (PluginDescriptionEntry e : entries) {
            if (!e.isEnabled()) {
                continue;
            }
            org.bukkit.plugin.Plugin p = Bukkit.getPluginManager().getPlugin(e.getName());
            if (p == null) {
                continue;
            }
            String version = p.getDescription().getVersion();
            String raw = PluginHelpProbe.captureHelp(p);
            out.put(e.getName(), new String[]{version, raw});
        }
        return out;
    }

    // ====== 知识库 CRUD ======

    private static void handleKnowledgeList(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                sendText(ex, 405, "Method Not Allowed");
                return;
            }
            Map<String, String> q = query(ex);
            int page = parseInt(q.get("page"), 0);
            int size = parseInt(q.get("size"), 20);
            if (size <= 0) {
                size = 20;
            }
            List<KnowledgeEntry> all = KnowledgeFileManager.readAll();
            int total = all.size();
            int start = Math.max(0, page * size);
            int end = Math.min(start + size, total);
            List<Map<String, Object>> entries = new ArrayList<>();
            for (int i = start; i < end; i++) {
                KnowledgeEntry e = all.get(i);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("index", i);
                m.put("question", e.getQuestion());
                m.put("answer", e.getAnswer());
                entries.add(m);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("total", total);
            out.put("page", page);
            out.put("size", size);
            out.put("entries", entries);
            sendJson(ex, 200, out);
        } catch (Exception e) {
            sendError(ex, 500, String.valueOf(e.getMessage()));
        } finally {
            ex.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static void handleKnowledgeAdd(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendText(ex, 405, "Method Not Allowed");
                return;
            }
            Map<String, Object> req = GSON.fromJson(readBody(ex), new TypeToken<Map<String, Object>>() {}.getType());
            String question = req == null || req.get("question") == null ? "" : String.valueOf(req.get("question")).trim();
            String answer = req == null || req.get("answer") == null ? "" : String.valueOf(req.get("answer")).trim();
            if (question.isEmpty() || answer.isEmpty()) {
                sendError(ex, 400, "问题和答案都不能为空");
                return;
            }
            KnowledgeFileManager.append(new KnowledgeEntry(question, answer, "", System.currentTimeMillis()));
            Config.reloadKnowledgeBaseOnly();
            sendOk(ex);
        } catch (Exception e) {
            sendError(ex, 500, String.valueOf(e.getMessage()));
        } finally {
            ex.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static void handleKnowledgeUpdate(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendText(ex, 405, "Method Not Allowed");
                return;
            }
            Map<String, Object> req = GSON.fromJson(readBody(ex), new TypeToken<Map<String, Object>>() {}.getType());
            int index = toInt(req == null ? null : req.get("index"), -1);
            String question = req == null || req.get("question") == null ? "" : String.valueOf(req.get("question")).trim();
            String answer = req == null || req.get("answer") == null ? "" : String.valueOf(req.get("answer")).trim();
            if (index < 0 || question.isEmpty() || answer.isEmpty()) {
                sendError(ex, 400, "参数无效");
                return;
            }
            KnowledgeFileManager.updateByIndex(index, new KnowledgeEntry(question, answer, "", System.currentTimeMillis()));
            Config.reloadKnowledgeBaseOnly();
            sendOk(ex);
        } catch (Exception e) {
            sendError(ex, 500, String.valueOf(e.getMessage()));
        } finally {
            ex.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static void handleKnowledgeDelete(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendText(ex, 405, "Method Not Allowed");
                return;
            }
            Map<String, Object> req = GSON.fromJson(readBody(ex), new TypeToken<Map<String, Object>>() {}.getType());
            int index = toInt(req == null ? null : req.get("index"), -1);
            if (index < 0) {
                sendError(ex, 400, "index 无效");
                return;
            }
            KnowledgeFileManager.deleteByIndex(index);
            Config.reloadKnowledgeBaseOnly();
            sendOk(ex);
        } catch (Exception e) {
            sendError(ex, 500, String.valueOf(e.getMessage()));
        } finally {
            ex.close();
        }
    }

    private static void handleKnowledgeMigrateDb(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendText(ex, 405, "Method Not Allowed");
                return;
            }
            if (!"mysql".equalsIgnoreCase(Config.storageType)) {
                sendError(ex, 400, "请先在配置中把存储切到 mysql 再迁移");
                return;
            }
            if (DataContainer.storage == null) {
                sendError(ex, 500, "存储未就绪");
                return;
            }
            List<KnowledgeEntry> knowledge = KnowledgeFileManager.readAllFromFile();
            List<PluginDescriptionEntry> plugins = PluginDescriptionManager.readAll();
            DataContainer.storage.exportKnowledge(knowledge);
            DataContainer.storage.exportPluginDescriptions(plugins);
            // 导出直接写了 DB，绕过了缓存；失效后重建向量库让 RAG 读到刚导入的数据
            KnowledgeFileManager.invalidateCache();
            Config.reloadKnowledgeBaseOnly();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("knowledgeCount", knowledge.size());
            out.put("pluginCount", plugins.size());
            sendJson(ex, 200, out);
        } catch (Exception e) {
            sendError(ex, 500, String.valueOf(e.getMessage()));
        } finally {
            ex.close();
        }
    }

    private static void sendOk(HttpExchange ex) throws IOException {
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("ok", true);
        sendJson(ex, 200, ok);
    }

    private static int parseInt(String s, int def) {
        if (s == null) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ====== 配置 schema 与读写 ======

    /** 中英双语文本 {zh_CN, en_US}。 */
    private static Map<String, Object> i18n(String zh, String en) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("zh_CN", zh);
        m.put("en_US", en);
        return m;
    }

    /** 单个可编辑字段（label 双语）。 */
    private static Map<String, Object> field(String path, String labelZh, String labelEn, String type, String... options) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("path", path);
        f.put("label", i18n(labelZh, labelEn));
        f.put("type", type);
        if (options.length > 0) {
            List<String> opts = new ArrayList<>();
            for (String o : options) {
                opts.add(o);
            }
            f.put("options", opts);
        }
        return f;
    }

    private static Map<String, Object> group(String titleZh, String titleEn, Map<String, Object>... fields) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("title", i18n(titleZh, titleEn));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> f : fields) {
            list.add(f);
        }
        g.put("fields", list);
        return g;
    }

    /** 返回有序的分组 schema（每组若干字段）。 */
    private static List<Map<String, Object>> buildGroups() {
        List<Map<String, Object>> groups = new ArrayList<>();
        groups.add(group("通用", "General",
                field("language", "语言", "Language", T_ENUM, "zh_CN", "en_US"),
                field("assistant.name", "助手名称", "Assistant name", T_STRING),
                field("gui.title", "GUI 标题", "GUI title", T_STRING),
                field("gui.charNumPerLine", "GUI 每行字符数", "GUI chars per line", T_INT)));
        groups.add(group("存储", "Storage",
                field("storage.type", "存储模式", "Storage mode", T_ENUM, "yml", "mysql"),
                field("storage-data.address", "数据库地址 (仅 mysql)", "DB address (mysql only)", T_STRING),
                field("storage-data.database", "数据库名 (仅 mysql)", "DB name (mysql only)", T_STRING),
                field("storage-data.username", "数据库用户名 (仅 mysql)", "DB username (mysql only)", T_STRING),
                field("storage-data.password", "数据库密码 (仅 mysql)", "DB password (mysql only)", T_PASSWORD)));
        groups.add(group("大模型 (LLM)", "LLM",
                field("llm.baseUrl", "Base URL", "Base URL", T_STRING),
                field("llm.model", "模型名", "Model", T_STRING),
                field("llm.apiKey", "API Key", "API Key", T_PASSWORD),
                field("llm.temperature", "回答温度 (0~1)", "Answer temperature (0~1)", T_DOUBLE),
                field("llm.chatKeywords", "公屏触发关键词 (英文逗号分隔)", "Chat trigger keywords (comma-separated)", T_TEXT),
                field("llm.chatResponseCD", "公屏回应冷却(秒)", "Chat response cooldown (s)", T_INT),
                field("llm.chatResponseEnabled", "启用公屏回应", "Enable chat response", T_BOOLEAN),
                field("llm.chatResponseSuffix", "公屏回应后缀", "Chat response suffix", T_TEXT)));
        groups.add(group("向量模型(Embedding)", "Embedding",
                field("embedding.baseUrl", "Embedding Base URL", "Embedding Base URL", T_STRING),
                field("embedding.apiKey", "Embedding API Key（留空复用 LLM）", "Embedding API Key (empty = LLM key)", T_PASSWORD),
                field("embedding.model", "Embedding 模型", "Embedding model", T_STRING),
                field("embedding.dimensions", "向量维度", "Vector dimensions", T_INT)));
        groups.add(group("知识匹配", "Knowledge matching",
                field("knowledge.minSimilarity", "最低相似度", "Min similarity", T_DOUBLE),
                field("knowledge.maxDocs", "单次最多注入文档数", "Max docs per answer", T_INT),
                field("knowledge.maxPendingKnowledgePerPlayer", "每玩家最多待审核数", "Max pending per player", T_INT),
                field("knowledge.playerSubmitReview.enabled", "玩家提交 AI 初审", "AI pre-review on player submit", T_BOOLEAN)));
        groups.add(group("公屏自动采集", "Auto-collect from chat",
                field("knowledge.autoCollect.enabled", "启用自动采集", "Enable auto-collect", T_BOOLEAN),
                field("knowledge.autoCollect.answerWindowSeconds", "回答时间窗(秒)", "Answer window (s)", T_INT),
                field("knowledge.autoCollect.thanksWindowSeconds", "感谢时间窗(秒)", "Thanks window (s)", T_INT),
                field("knowledge.autoCollect.minAnswerLength", "最短回答字符数", "Min answer length", T_INT),
                field("knowledge.autoCollect.notifyReviewers", "提示在线审核员", "Notify online reviewers", T_BOOLEAN),
                field("knowledge.autoCollect.thanksKeywords", "感谢关键词 (英文逗号分隔)", "Thanks keywords (comma-separated)", T_TEXT)));
        groups.add(group("文档导入", "Document import",
                field("knowledge.documentImport.chunkSize", "分片字符数", "Chunk size (chars)", T_INT),
                field("knowledge.documentImport.maxTokens", "单次最大输出 token", "Max output tokens", T_INT),
                field("knowledge.documentImport.temperature", "抽取温度 (0~1)", "Extraction temperature (0~1)", T_DOUBLE),
                field("knowledge.documentImport.visionModel", "视觉模型 (留空不支持图片)", "Vision model (empty = no image)", T_STRING)));
        groups.add(group("审核与奖励", "Review & rewards",
                field("knowledge.knowledgeReview.opPermission", "审核员权限节点", "Reviewer permission node", T_STRING),
                field("knowledge.knowledgeReview.rewards.vault.enabled", "启用 Vault 金币奖励", "Enable Vault money reward", T_BOOLEAN),
                field("knowledge.knowledgeReview.rewards.vault.amount", "金币数量", "Money amount", T_INT),
                field("knowledge.knowledgeReview.rewards.vault.currencyName", "货币名称", "Currency name", T_STRING)));
        groups.add(group("网页管理服务", "Web admin service",
                field("webui.enabled", "启用", "Enabled", T_BOOLEAN),
                field("webui.port", "端口", "Port", T_INT)));
        return groups;
    }

    private static Map<String, Object> buildConfigPayload() {
        List<Map<String, Object>> groups = buildGroups();
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map<String, Object> g : groups) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fields = (List<Map<String, Object>>) g.get("fields");
            for (Map<String, Object> f : fields) {
                String path = (String) f.get("path");
                values.put(path, Config.config.get(path));
            }
        }
        // 奖励物品列表
        List<Map<String, Object>> rewardItems = new ArrayList<>();
        for (Map<?, ?> raw : Config.config.getMapList(REWARD_ITEMS_PATH)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("material", String.valueOf(raw.get("material")));
            Object amt = raw.get("amount");
            item.put("amount", amt instanceof Number ? ((Number) amt).intValue() : 1);
            rewardItems.add(item);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lang", Config.language);
        payload.put("groups", groups);
        payload.put("values", values);
        payload.put("rewardItems", rewardItems);
        return payload;
    }

    @SuppressWarnings("unchecked")
    private static void applyConfig(String body) {
        Type type = new TypeToken<Map<String, Object>>() {}.getType();
        Map<String, Object> req = GSON.fromJson(body, type);
        if (req == null) {
            throw new IllegalArgumentException("空请求体");
        }

        // 按 schema 已知字段，按类型写回
        Map<String, String> typeByPath = new LinkedHashMap<>();
        for (Map<String, Object> g : buildGroups()) {
            for (Map<String, Object> f : (List<Map<String, Object>>) g.get("fields")) {
                typeByPath.put((String) f.get("path"), (String) f.get("type"));
            }
        }

        Map<String, Object> values = (Map<String, Object>) req.get("values");
        if (values != null) {
            for (Map.Entry<String, Object> e : values.entrySet()) {
                String path = e.getKey();
                String t = typeByPath.get(path);
                if (t == null) {
                    continue; // 仅接受 schema 内的已知字段
                }
                Config.config.set(path, coerce(t, e.getValue()));
            }
        }

        // 奖励物品列表整体替换
        Object itemsObj = req.get("rewardItems");
        if (itemsObj instanceof List) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : (List<Object>) itemsObj) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<String, Object> raw = (Map<String, Object>) o;
                String material = String.valueOf(raw.get("material")).trim();
                if (material.isEmpty()) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("material", material);
                item.put("amount", toInt(raw.get("amount"), 1));
                out.add(item);
            }
            Config.config.set(REWARD_ITEMS_PATH, out);
        }

        // 落盘后复用 /exai reload 的异步重载路径
        ExAI.getInstance().saveConfig();
        Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), Config::loadAll);
    }

    private static Object coerce(String type, Object value) {
        switch (type) {
            case T_BOOLEAN:
                if (value instanceof Boolean) {
                    return value;
                }
                return Boolean.parseBoolean(String.valueOf(value));
            case T_INT:
                return toInt(value, 0);
            case T_DOUBLE:
                return value instanceof Number
                        ? ((Number) value).doubleValue()
                        : Double.parseDouble(String.valueOf(value));
            default:
                return value == null ? "" : String.valueOf(value);
        }
    }

    private static int toInt(Object value, int def) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(value)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ====== HTTP 工具 ======

    private static void sendJson(HttpExchange ex, int code, Object body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendText(HttpExchange ex, int code, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(readAll(is), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readResource(String name) throws IOException {
        try (InputStream is = ExAI.getInstance().getResource(name)) {
            if (is == null) {
                return null;
            }
            return readAll(is);
        }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
