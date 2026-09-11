package com.exai.web;

import com.exai.ExAI;
import com.exai.config.Config;
import com.exai.entity.PluginDescriptionEntry;
import com.exai.manager.PluginDescriptionManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 给 Web CLI 的大模型 function calling 使用的配置工具。
 *
 * <p>所有路径都限制在 {@code plugins/} 下；读取和修改仅面向 .yml/.yaml。写工具只创建待确认修改，
 * 用户确认待确认 ID 后才真正落盘，写前备份 .bak；修改 ExAI 自身 config.yml 后触发热重载。
 */
public final class PluginConfigTool {

    private static final long MAX_READ_BYTES = 2_000_000L;
    private static final int MAX_LIST_ENTRIES = 200;
    private static final int MAX_SEARCH_FILES = 100;
    private static final int MAX_SEARCH_RESULTS = 100;
    private static final int MAX_PLUGIN_RESULTS = 20;
    private static final int MAX_SNIPPETS = 10;
    private static final int MAX_SNIPPET_CHARS = 8000;
    private static final long PENDING_TTL_MS = 10L * 60L * 1000L;
    private static final Map<String, PendingChange> PENDING = new LinkedHashMap<>();
    private static final String APPLIED_STORE_FILE = "web-cli-changes.json";
    private static final Gson GSON = new Gson();
    private static final List<AppliedChange> APPLIED = new ArrayList<>();
    private static boolean appliedLoaded;

    private PluginConfigTool() {}

    public static JsonArray toolSchemas() {
        JsonArray tools = new JsonArray();
        tools.add(tool("list_plugin_yaml",
                "List directories and YAML files under the Minecraft plugins directory. Paths are relative to plugins/.",
                objProps(
                        prop("path", "string", "Directory path relative to plugins/. Empty means plugins root or current cwd.")),
                new String[]{}));
        tools.add(tool("list_yaml_keys",
                "List immediate child keys under a YAML section. Scalar previews are masked for secret-looking keys.",
                objProps(
                        prop("file", "string", "YAML file path relative to plugins/."),
                        prop("path", "string", "YAML section path. Empty or omitted means root.")),
                new String[]{"file"}));
        tools.add(tool("get_yaml_value",
                "Get a YAML value by path. Secret-looking paths are masked.",
                objProps(
                        prop("file", "string", "YAML file path relative to plugins/."),
                        prop("path", "string", "YAML key path.")),
                new String[]{"file", "path"}));
        tools.add(tool("search_yaml_keys",
                "Search YAML key paths by keyword under plugins/. Returns matching file/path previews with secrets masked.",
                objProps(
                        prop("keyword", "string", "Keyword to search in YAML key paths, e.g. webui, port, language, mysql, reward."),
                        prop("file", "string", "Optional YAML file path relative to plugins/. If omitted, searches YAML files under plugins/ with limits.")),
                new String[]{"keyword"}));
        tools.add(tool("list_plugins",
                "List plugin JARs found directly under plugins/, including plugins that are not currently loaded. Each result includes loaded to indicate runtime state. Use this first for fuzzy Chinese plugin names so the model can choose the closest candidate.",
                objProps(prop("include_disabled", "boolean", "Whether to include disabled plugin-description entries.")),
                new String[]{}));
        tools.add(tool("search_plugins",
                "Search installed/scanned plugins by fuzzy name, description, command names/descriptions, and data folder.",
                objProps(
                        prop("query", "string", "Fuzzy plugin name or description keyword, e.g. 远征, expedition, quest."),
                        prop("include_disabled", "boolean", "Whether to include disabled plugin-description entries.")),
                new String[]{"query"}));
        tools.add(tool("get_plugin_metadata",
                "Get metadata and likely YAML config files for one installed/scanned plugin.",
                objProps(prop("name", "string", "Plugin name or close candidate name.")),
                new String[]{"name"}));
        tools.add(tool("find_plugin_files",
                "Find plugin data-folder files by plugin/folder name and optional keyword. Searches from plugins root.",
                objProps(
                        prop("plugin", "string", "Plugin name or folder name."),
                        prop("keyword", "string", "Optional file/path keyword."),
                        prop("yaml_only", "boolean", "Return only .yml/.yaml files.")),
                new String[]{}));
        tools.add(tool("search_yaml_values",
                "Search YAML key paths and scalar/list value previews by keyword.",
                objProps(
                        prop("keyword", "string", "Keyword to search in YAML paths and values, e.g. 佣兵, mercenary."),
                        prop("file", "string", "Optional YAML file path relative to plugins/."),
                        prop("path", "string", "Optional YAML section path to search under.")),
                new String[]{"keyword"}));
        tools.add(tool("read_yaml_snippet",
                "Read limited raw YAML snippets around a keyword or key path, preserving comments with secrets redacted.",
                objProps(
                        prop("file", "string", "YAML file path relative to plugins/."),
                        prop("keyword", "string", "Optional raw text keyword to search, including comments."),
                        prop("path", "string", "Optional YAML key path; last segment is used as a key hint."),
                        prop("context_lines", "integer", "Number of lines before/after each match, 0-10.")),
                new String[]{"file"}));
        JsonObject proposeProps = objProps(
                prop("file", "string", "YAML file path relative to plugins/."),
                prop("path", "string", "YAML key path to change."),
                propAny("value", "New YAML value. Use JSON null when delete is true."),
                prop("delete", "boolean", "If true, delete the key instead of setting it."),
                prop("reason", "string", "Short user-facing reason for this change."));
        tools.add(tool("propose_yaml_set",
                "Propose setting or deleting a YAML config value. This does not write immediately; it returns a confirmation id.",
                proposeProps,
                new String[]{"file", "path"}));
        return tools;
    }

    public static JsonObject executeTool(String cwd, String name, JsonObject args) {
        return executeTool(cwd, name, args, "", 0L);
    }

    public static JsonObject executeTool(String cwd, String name, JsonObject args, String sessionId, long turnId) {
        try {
            if ("list_plugin_yaml".equals(name)) return listPluginYaml(cwd, args);
            if ("list_yaml_keys".equals(name)) return listYamlKeys(cwd, args);
            if ("get_yaml_value".equals(name)) return getYamlValue(cwd, args);
            if ("search_yaml_keys".equals(name)) return searchYamlKeys(cwd, args);
            if ("list_plugins".equals(name)) return listPlugins(args);
            if ("search_plugins".equals(name)) return searchPlugins(args);
            if ("get_plugin_metadata".equals(name)) return getPluginMetadata(args);
            if ("find_plugin_files".equals(name)) return findPluginFiles(args);
            if ("search_yaml_values".equals(name)) return searchYamlValues(cwd, args);
            if ("read_yaml_snippet".equals(name)) return readYamlSnippet(cwd, args);
            if ("propose_yaml_set".equals(name)) return proposeYamlSet(cwd, args, sessionId, turnId);
            return error("未知工具: " + name);
        } catch (SecurityException se) {
            return error("路径被拒绝: " + se.getMessage());
        } catch (Exception e) {
            return error(e.getMessage());
        }
    }

    public static String buildSystemPrompt(String cwd) {
        String where = cwd == null || cwd.trim().isEmpty() ? "plugins/" : "plugins/" + cwd;
        return "你是 ExAI 网页终端里的 Minecraft 插件 YAML 配置助手。\n"
                + "你必须通过提供的工具读取 plugins/ 下的 YAML 配置，不能猜测文件内容。\n"
                + "所有路径都相对 plugins/。当前终端目录: " + where + "\n\n"
                + "当用户要求查看配置时：使用 list/search/get/snippet 等工具读取后回答。\n"
                + "遇到模糊插件名时，必须先调用 list_plugins 列出服务器所有插件，由你根据名称/描述/data_dir 判断最相似候选；不要直接把翻译词当目录名调用 list_plugin_yaml。\n"
                + "若 list_plugins 后仍不确定，再调用 search_plugins、get_plugin_metadata 和 find_plugin_files 辅助定位。\n"
                + "遇到模糊设置名时，先 search_yaml_keys，再 search_yaml_values，必要时 read_yaml_snippet 看注释。\n"
                + "中文需求可以联想到英文同义词辅助判断：佣兵=mercenary/hireling/companion/npc/soldier；远征=expedition/quest/adventure，但英文词只能用于搜索/判断，不能当作已存在目录。\n"
                + "当前目录只在用户明确说当前目录/这里/这个文件时作为主要范围；插件发现默认从 plugins 根搜索。\n"
                + "如果多个插件、文件或配置路径都可能匹配，必须询问用户选择，不能猜。\n"
                + "如果用户要求“增加几个设置”但没有具体数量/路径/值，先搜索并展示候选设置，再问用户要新增哪些或改成什么值。\n"
                + "创建新 key 只允许在目标文件和父节点明确、且注释/附近结构表明支持该设置时进行。\n"
                + "当用户要求修改配置时：\n"
                + "1. 先定位准确文件和键；\n"
                + "2. 读取当前值；\n"
                + "3. 调用 propose_yaml_set 创建待确认修改；\n"
                + "4. 最终回答必须列出 file/path/旧值/新值/原因，并提示用户输入 确认 <id>。\n\n"
                + "propose_yaml_set 不会立即写入文件。只有用户确认对应 id 才会真正保存。\n"
                + "不要声称已经修改成功，除非用户已经确认对应 id。\n"
                + "不要泄露已遮罩的 secret 值。";
    }

    public static synchronized String confirmPending(String id) {
        cleanupExpired();
        PendingChange p = PENDING.get(id);
        if (p == null) {
            return "待确认修改不存在或已过期: " + id;
        }
        try {
            File f = requireYaml("", p.fileRel, true);
            YamlConfiguration conf = YamlConfiguration.loadConfiguration(f);
            String nowComparable = comparable(conf.contains(p.path), conf.get(p.path));
            if (!nowComparable.equals(p.oldComparable)) {
                return "配置在创建确认项后已被修改，已取消执行。请重新运行 ai 生成新的修改计划。";
            }
            backup(f);
            conf.set(p.path, p.delete ? null : p.value);
            conf.save(f);
            PENDING.remove(id);
            loadAppliedChanges();
            APPLIED.add(new AppliedChange(p));
            saveAppliedChanges();
            String msg = p.delete
                    ? "已删除 " + p.fileRel + " -> " + p.path
                    : "已设置 " + p.fileRel + " -> " + p.path + " = " + p.newPreview;
            if (isExaiConfig(f)) {
                Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), Config::loadAll);
                return msg + "\n已保存并备份 .bak；这是 ExAI 自身配置，已触发即时重载";
            }
            return msg + "\n已保存并备份 .bak；如需生效请让该插件 reload 或重启服务器";
        } catch (Exception e) {
            return "确认执行失败: " + e.getMessage();
        }
    }

    public static synchronized String cancelPending(String id) {
        cleanupExpired();
        PendingChange p = PENDING.remove(id);
        return p == null ? "待确认修改不存在或已过期: " + id : "已取消待确认修改: " + id;
    }

    /** Reverts all confirmed changes in this session from the target turn onward. */
    public static synchronized RollbackResult rollbackFromTurn(String sessionId, long turnId) {
        loadAppliedChanges();
        List<AppliedChange> changes = new ArrayList<>();
        for (AppliedChange change : APPLIED) {
            if (!change.reverted && change.sessionId.equals(sessionId) && change.turnId >= turnId) {
                changes.add(change);
            }
        }
        if (changes.isEmpty()) {
            return new RollbackResult(true, 0, "");
        }

        Map<String, String> expected = new LinkedHashMap<>();
        try {
            for (int i = changes.size() - 1; i >= 0; i--) {
                AppliedChange change = changes.get(i);
                String key = change.fileRel + "\u0000" + change.path;
                String current = expected.get(key);
                if (current == null) {
                    File file = requireYaml("", change.fileRel, true);
                    YamlConfiguration conf = YamlConfiguration.loadConfiguration(file);
                    current = comparable(conf.contains(change.path), conf.get(change.path));
                }
                if (!change.newComparable.equals(current)) {
                    return new RollbackResult(false, 0,
                            "配置已在之后被修改，不能安全撤回: " + change.fileRel + " -> " + change.path);
                }
                expected.put(key, change.oldComparable);
            }

            Map<String, Boolean> backedUp = new LinkedHashMap<>();
            boolean reloadExai = false;
            for (int i = changes.size() - 1; i >= 0; i--) {
                AppliedChange change = changes.get(i);
                File file = requireYaml("", change.fileRel, true);
                String fileKey = file.getCanonicalPath();
                if (!backedUp.containsKey(fileKey)) {
                    backup(file);
                    backedUp.put(fileKey, true);
                }
                YamlConfiguration conf = YamlConfiguration.loadConfiguration(file);
                conf.set(change.path, change.oldExists ? jsonToYamlValue(change.oldValue) : null);
                conf.save(file);
                change.reverted = true;
                change.revertedAt = System.currentTimeMillis();
                reloadExai |= isExaiConfig(file);
            }
            saveAppliedChanges();
            if (reloadExai) {
                Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), Config::loadAll);
            }
            return new RollbackResult(true, changes.size(), "");
        } catch (Exception e) {
            return new RollbackResult(false, 0, "撤回配置失败: " + e.getMessage());
        }
    }

    public static synchronized String confirmPendingAll(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return "没有可确认的修改";
        }
        StringBuilder sb = new StringBuilder();
        int ok = 0;
        for (String id : ids) {
            String r = confirmPending(id);
            if (r.contains("已保存并备份")) {
                ok++;
            }
            sb.append("- ").append(id).append(": ").append(r).append('\n');
        }
        return "全部确认完成：" + ok + "/" + ids.size() + " 个已保存\n" + sb.toString().trim();
    }

    // ===== tool handlers =====

    private static JsonObject listPluginYaml(String cwd, JsonObject args) throws IOException {
        File dir = resolve(cwd, optString(args, "path"));
        if (!dir.isDirectory()) {
            return error("不是目录: " + toRel(dir));
        }
        File[] files = dir.listFiles();
        JsonObject out = ok();
        out.addProperty("path", toRel(dir));
        JsonArray entries = new JsonArray();
        if (files != null) {
            Arrays.sort(files, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            int count = 0;
            for (File f : files) {
                if (!f.isDirectory() && !isYamlName(f.getName())) continue;
                if (count++ >= MAX_LIST_ENTRIES) break;
                JsonObject e = new JsonObject();
                e.addProperty("name", f.getName());
                e.addProperty("path", toRel(f));
                e.addProperty("dir", f.isDirectory());
                e.addProperty("size", f.isDirectory() ? 0L : f.length());
                entries.add(e);
            }
            out.addProperty("truncated", count >= MAX_LIST_ENTRIES);
        }
        out.add("entries", entries);
        return out;
    }

    private static JsonObject listYamlKeys(String cwd, JsonObject args) throws IOException {
        File f = requireYaml(cwd, reqString(args, "file"), true);
        YamlConfiguration conf = YamlConfiguration.loadConfiguration(f);
        String path = optString(args, "path");
        ConfigurationSection sec = (path == null || path.trim().isEmpty()) ? conf : conf.getConfigurationSection(path);
        if (sec == null) {
            return error(conf.contains(path) ? "该路径是标量，请用 get_yaml_value" : "路径不存在: " + path);
        }
        JsonObject out = ok();
        out.addProperty("file", toRel(f));
        out.addProperty("path", path == null ? "" : path);
        JsonArray keys = new JsonArray();
        for (String k : sec.getKeys(false)) {
            String full = path == null || path.trim().isEmpty() ? k : path + "." + k;
            JsonObject item = new JsonObject();
            item.addProperty("name", k);
            item.addProperty("path", full);
            boolean section = sec.isConfigurationSection(k);
            item.addProperty("section", section);
            if (!section) {
                Object v = sec.get(k);
                item.addProperty("type", typeOf(v));
                boolean secret = isSecretPath(full);
                item.addProperty("preview", secret ? "******" : preview(v));
                if (secret) item.addProperty("masked", true);
            }
            keys.add(item);
        }
        out.add("keys", keys);
        return out;
    }

    private static JsonObject getYamlValue(String cwd, JsonObject args) throws IOException {
        File f = requireYaml(cwd, reqString(args, "file"), true);
        String path = reqString(args, "path");
        YamlConfiguration conf = YamlConfiguration.loadConfiguration(f);
        JsonObject out = ok();
        out.addProperty("file", toRel(f));
        out.addProperty("path", path);
        boolean exists = conf.contains(path);
        out.addProperty("exists", exists);
        if (!exists) return out;
        Object v = conf.isConfigurationSection(path) ? conf.getConfigurationSection(path).getValues(false) : conf.get(path);
        out.addProperty("type", typeOf(v));
        boolean secret = isSecretPath(path);
        if (secret) {
            out.addProperty("masked", true);
            out.addProperty("value", "******");
        } else {
            out.add("value", valueToJson(v, false, path));
        }
        return out;
    }

    private static JsonObject searchYamlKeys(String cwd, JsonObject args) throws IOException {
        String keyword = reqString(args, "keyword").toLowerCase();
        String file = optString(args, "file");
        List<File> files = new ArrayList<>();
        if (file != null && !file.trim().isEmpty()) {
            files.add(requireYaml(cwd, file, true));
        } else {
            collectYamlFiles(resolve(cwd, null), files, MAX_SEARCH_FILES);
        }
        JsonObject out = ok();
        out.addProperty("keyword", keyword);
        JsonArray results = new JsonArray();
        for (File f : files) {
            if (results.size() >= MAX_SEARCH_RESULTS) break;
            if (f.length() > MAX_READ_BYTES) continue;
            YamlConfiguration conf = YamlConfiguration.loadConfiguration(f);
            searchSection(toRel(f), conf, "", keyword, results);
        }
        out.add("results", results);
        out.addProperty("truncated", results.size() >= MAX_SEARCH_RESULTS || files.size() >= MAX_SEARCH_FILES);
        return out;
    }

    private static JsonObject listPlugins(JsonObject args) throws Exception {
        boolean includeDisabled = args != null && args.has("include_disabled") && !args.get("include_disabled").isJsonNull()
                && args.get("include_disabled").getAsBoolean();
        JsonObject out = ok();
        JsonArray plugins = new JsonArray();
        for (PluginInfo info : pluginInfos()) {
            if (!includeDisabled && !info.enabled) continue;
            JsonObject p = new JsonObject();
            p.addProperty("name", info.name);
            p.addProperty("version", info.version);
            p.addProperty("installed", info.installed);
            p.addProperty("enabled", info.enabled);
            p.addProperty("loaded", info.loaded);
            p.addProperty("data_dir", info.dataDir);
            p.addProperty("description_preview", preview(firstNonEmpty(info.pluginDesc, info.description)));
            p.addProperty("commands_preview", preview(info.commands));
            plugins.add(p);
            if (plugins.size() >= 200) break;
        }
        out.add("plugins", plugins);
        out.addProperty("truncated", plugins.size() >= 200);
        return out;
    }

    private static JsonObject searchPlugins(JsonObject args) throws Exception {
        String query = reqString(args, "query");
        boolean includeDisabled = args.has("include_disabled") && !args.get("include_disabled").isJsonNull()
                && args.get("include_disabled").getAsBoolean();
        JsonObject out = ok();
        out.addProperty("query", query);
        JsonArray matches = new JsonArray();
        List<PluginInfo> infos = pluginInfos();
        for (PluginInfo info : infos) {
            if (!includeDisabled && !info.enabled) continue;
            JsonArray hits = new JsonArray();
            int score = pluginScore(info, query, hits);
            if (score <= 0) continue;
            JsonObject m = new JsonObject();
            m.addProperty("name", info.name);
            m.addProperty("version", info.version);
            m.addProperty("installed", info.installed);
            m.addProperty("enabled", info.enabled);
            m.addProperty("loaded", info.loaded);
            m.addProperty("data_dir", info.dataDir);
            m.addProperty("description_preview", preview(firstNonEmpty(info.pluginDesc, info.description)));
            m.addProperty("commands_preview", preview(info.commands));
            m.addProperty("score", score);
            m.add("hit_fields", hits);
            matches.add(m);
            if (matches.size() >= MAX_PLUGIN_RESULTS) break;
        }
        out.add("matches", matches);
        return out;
    }

    private static JsonObject getPluginMetadata(JsonObject args) throws Exception {
        String name = reqString(args, "name");
        List<PluginInfo> infos = pluginInfos();
        PluginInfo exact = null;
        for (PluginInfo i : infos) {
            if (i.name.equalsIgnoreCase(name)) { exact = i; break; }
        }
        if (exact == null) {
            JsonObject fake = new JsonObject();
            fake.addProperty("query", name);
            return searchPlugins(fake);
        }
        JsonObject out = ok();
        out.addProperty("name", exact.name);
        out.addProperty("version", exact.version);
        out.addProperty("installed", exact.installed);
        out.addProperty("enabled", exact.enabled);
        out.addProperty("loaded", exact.loaded);
        out.addProperty("description", firstNonEmpty(exact.pluginDesc, exact.description));
        out.addProperty("authors", exact.authors);
        out.addProperty("commands", exact.commands);
        out.addProperty("data_dir", exact.dataDir);
        JsonArray files = new JsonArray();
        if (exact.dataDir != null && !exact.dataDir.isEmpty()) {
            collectFileEntries(resolve("", exact.dataDir), files, true, null, 80);
        }
        out.add("likely_config_files", files);
        return out;
    }

    private static JsonObject findPluginFiles(JsonObject args) throws Exception {
        String plugin = optString(args, "plugin");
        String keyword = optString(args, "keyword");
        boolean yamlOnly = !args.has("yaml_only") || args.get("yaml_only").isJsonNull() || args.get("yaml_only").getAsBoolean();
        JsonObject out = ok();
        JsonArray files = new JsonArray();
        List<File> roots = new ArrayList<>();
        if (plugin != null && !plugin.trim().isEmpty()) {
            for (PluginInfo i : pluginInfos()) {
                if ((normMatch(i.name).contains(normMatch(plugin)) || normMatch(i.dataDir).contains(normMatch(plugin)))
                        && i.dataDir != null && !i.dataDir.trim().isEmpty()) {
                    roots.add(resolve("", i.dataDir));
                }
            }
            File root = WebServer.pluginsRoot();
            File[] dirs = root.listFiles();
            if (dirs != null) {
                for (File d : dirs) {
                    if (d.isDirectory() && normMatch(d.getName()).contains(normMatch(plugin))) roots.add(d);
                }
            }
        } else {
            roots.add(WebServer.pluginsRoot());
        }
        for (File root : roots) {
            collectFileEntries(root, files, yamlOnly, keyword, 120);
            if (files.size() >= 120) break;
        }
        out.add("files", files);
        out.addProperty("truncated", files.size() >= 120);
        return out;
    }

    private static JsonObject searchYamlValues(String cwd, JsonObject args) throws IOException {
        String keyword = reqString(args, "keyword").toLowerCase();
        String file = optString(args, "file");
        String path = optString(args, "path");
        List<File> files = new ArrayList<>();
        if (file != null && !file.trim().isEmpty()) files.add(requireYaml(cwd, file, true));
        else collectYamlFiles(WebServer.pluginsRoot(), files, MAX_SEARCH_FILES);
        JsonObject out = ok();
        JsonArray results = new JsonArray();
        for (File f : files) {
            if (results.size() >= MAX_SEARCH_RESULTS) break;
            YamlConfiguration conf = YamlConfiguration.loadConfiguration(f);
            ConfigurationSection sec = (path == null || path.trim().isEmpty()) ? conf : conf.getConfigurationSection(path);
            if (sec != null) searchValues(toRel(f), sec, path == null ? "" : path, keyword, results);
        }
        out.add("results", results);
        out.addProperty("truncated", results.size() >= MAX_SEARCH_RESULTS || files.size() >= MAX_SEARCH_FILES);
        return out;
    }

    private static JsonObject readYamlSnippet(String cwd, JsonObject args) throws IOException {
        File f = requireYaml(cwd, reqString(args, "file"), true);
        String keyword = optString(args, "keyword");
        String path = optString(args, "path");
        int ctx = args.has("context_lines") && !args.get("context_lines").isJsonNull()
                ? Math.max(0, Math.min(10, args.get("context_lines").getAsInt())) : 6;
        String needle = keyword != null && !keyword.trim().isEmpty() ? keyword.trim().toLowerCase() : lastPathPart(path).toLowerCase();
        if (needle.isEmpty()) return error("keyword 或 path 至少提供一个");
        List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        JsonArray snippets = new JsonArray();
        int chars = 0;
        for (int i = 0; i < lines.size() && snippets.size() < MAX_SNIPPETS && chars < MAX_SNIPPET_CHARS; i++) {
            if (!lines.get(i).toLowerCase().contains(needle)) continue;
            int from = Math.max(0, i - ctx), to = Math.min(lines.size() - 1, i + ctx);
            StringBuilder text = new StringBuilder();
            boolean masked = false;
            for (int n = from; n <= to; n++) {
                String line = redactSecretLine(lines.get(n));
                if (!line.equals(lines.get(n))) masked = true;
                text.append(n + 1).append(": ").append(line).append('\n');
            }
            JsonObject s = new JsonObject();
            s.addProperty("start", from + 1);
            s.addProperty("end", to + 1);
            s.addProperty("text", text.toString().trim());
            if (masked) s.addProperty("masked", true);
            snippets.add(s);
            chars += text.length();
        }
        JsonObject out = ok();
        out.addProperty("file", toRel(f));
        out.add("snippets", snippets);
        out.addProperty("truncated", chars >= MAX_SNIPPET_CHARS || snippets.size() >= MAX_SNIPPETS);
        return out;
    }

    private static synchronized JsonObject proposeYamlSet(String cwd, JsonObject args, String sessionId, long turnId) throws IOException {
        cleanupExpired();
        File f = requireYaml(cwd, reqString(args, "file"), true);
        String path = reqString(args, "path");
        if (path.trim().isEmpty()) {
            return error("path 不能为空");
        }
        boolean delete = args.has("delete") && !args.get("delete").isJsonNull() && args.get("delete").getAsBoolean();
        Object value = delete ? null : jsonToYamlValue(args.has("value") ? args.get("value") : JsonNull.INSTANCE);
        String reason = optString(args, "reason");
        YamlConfiguration conf = YamlConfiguration.loadConfiguration(f);
        boolean exists = conf.contains(path);
        Object old = exists ? (conf.isConfigurationSection(path) ? conf.getConfigurationSection(path).getValues(false) : conf.get(path)) : null;
        String id = newId();
        boolean secret = isSecretPath(path);
        PendingChange p = new PendingChange(id, toRel(f), path, value, delete,
                exists, valueToJson(old, false, path), comparable(exists, old),
                comparable(!delete, delete ? null : value), secret ? "******" : preview(old),
                secret ? "******" : preview(value), reason, sessionId, turnId);
        PENDING.put(id, p);

        JsonObject out = ok();
        out.addProperty("requires_confirmation", true);
        out.addProperty("confirmation_id", id);
        out.addProperty("file", p.fileRel);
        out.addProperty("path", p.path);
        out.addProperty("exists", exists);
        out.addProperty("old_type", typeOf(old));
        out.addProperty("new_type", typeOf(value));
        if (secret) {
            out.addProperty("masked", true);
            out.addProperty("old_value", "******");
            out.addProperty("new_value", "******");
        } else {
            out.add("old_value", valueToJson(old, false, path));
            out.add("new_value", valueToJson(value, false, path));
        }
        out.addProperty("message", "已创建待确认修改。请让用户输入 确认 " + id);
        return out;
    }

    // ===== helpers =====

    private static JsonObject tool(String name, String desc, JsonObject properties, String[] required) {
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        params.add("properties", properties);
        JsonArray req = new JsonArray();
        for (String r : required) req.add(r);
        params.add("required", req);
        params.addProperty("additionalProperties", false);
        JsonObject fn = new JsonObject();
        fn.addProperty("name", name);
        fn.addProperty("description", desc);
        fn.add("parameters", params);
        JsonObject t = new JsonObject();
        t.addProperty("type", "function");
        t.add("function", fn);
        return t;
    }

    private static JsonObject objProps(JsonObject... props) {
        JsonObject o = new JsonObject();
        for (JsonObject p : props) {
            Iterator<Map.Entry<String, JsonElement>> it = p.entrySet().iterator();
            if (it.hasNext()) {
                Map.Entry<String, JsonElement> e = it.next();
                o.add(e.getKey(), e.getValue());
            }
        }
        return o;
    }

    private static JsonObject prop(String name, String type, String desc) {
        JsonObject spec = new JsonObject();
        spec.addProperty("type", type);
        spec.addProperty("description", desc);
        JsonObject o = new JsonObject();
        o.add(name, spec);
        return o;
    }

    private static JsonObject propAny(String name, String desc) {
        JsonObject spec = new JsonObject();
        spec.addProperty("description", desc);
        JsonObject o = new JsonObject();
        o.add(name, spec);
        return o;
    }

    private static JsonObject ok() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    private static JsonObject error(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", msg == null ? "unknown" : msg);
        return o;
    }

    private static File resolve(String cwd, String arg) throws IOException {
        String c = norm(cwd);
        String rel;
        if (arg == null || arg.trim().isEmpty()) {
            rel = c;
        } else {
            String a = arg.replace('\\', '/').trim();
            boolean rootAbsolute = a.startsWith("/");
            while (a.startsWith("/")) a = a.substring(1);
            rel = rootAbsolute || c.isEmpty() || a.contains("/") ? a : c + "/" + a;
        }
        return WebServer.resolveSafe(rel);
    }

    private static File requireYaml(String cwd, String path, boolean mustExist) throws IOException {
        File f = resolve(cwd, path);
        if (mustExist && !f.isFile()) throw new IOException("YAML 文件不存在: " + path);
        if (!isYamlName(f.getName())) throw new IOException("仅支持 .yml/.yaml 文件: " + path);
        if (f.exists() && f.length() > MAX_READ_BYTES) throw new IOException("文件过大(>2MB): " + path);
        return f;
    }

    private static boolean isYamlName(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".yml") || n.endsWith(".yaml");
    }

    private static String toRel(File f) throws IOException {
        return WebServer.relPath(WebServer.pluginsRoot(), f);
    }

    private static String norm(String s) {
        if (s == null) return "";
        String x = s.replace('\\', '/').trim();
        while (x.startsWith("/")) x = x.substring(1);
        while (x.endsWith("/")) x = x.substring(0, x.length() - 1);
        return x;
    }

    private static void backup(File f) throws IOException {
        if (f.exists()) {
            Files.copy(f.toPath(), new File(f.getPath() + ".bak").toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean isExaiConfig(File f) throws IOException {
        File cfg = new File(ExAI.getInstance().getDataFolder(), "config.yml").getCanonicalFile();
        return f.getCanonicalFile().equals(cfg);
    }

    private static void collectYamlFiles(File dir, List<File> out, int max) {
        if (dir == null || !dir.isDirectory() || out.size() >= max) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : files) {
            if (out.size() >= max) return;
            if (f.isDirectory()) collectYamlFiles(f, out, max);
            else if (isYamlName(f.getName()) && f.length() <= MAX_READ_BYTES) out.add(f);
        }
    }

    private static void collectFileEntries(File root, JsonArray out, boolean yamlOnly, String keyword, int max) throws IOException {
        if (root == null || !root.exists() || out.size() >= max) return;
        String kw = keyword == null ? "" : keyword.toLowerCase();
        if (root.isFile()) {
            if (yamlOnly && !isYamlName(root.getName())) return;
            String rel = toRel(root);
            if (!kw.isEmpty() && !rel.toLowerCase().contains(kw)) return;
            JsonObject e = new JsonObject();
            e.addProperty("name", root.getName());
            e.addProperty("path", rel);
            e.addProperty("dir", false);
            e.addProperty("size", root.length());
            out.add(e);
            return;
        }
        File[] files = root.listFiles();
        if (files == null) return;
        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        for (File f : files) {
            if (out.size() >= max) return;
            if (f.isDirectory()) {
                collectFileEntries(f, out, yamlOnly, keyword, max);
            } else {
                collectFileEntries(f, out, yamlOnly, keyword, max);
            }
        }
    }

    private static void searchSection(String fileRel, ConfigurationSection sec, String prefix, String keyword, JsonArray results) {
        if (results.size() >= MAX_SEARCH_RESULTS) return;
        for (String k : sec.getKeys(false)) {
            if (results.size() >= MAX_SEARCH_RESULTS) return;
            String path = prefix.isEmpty() ? k : prefix + "." + k;
            boolean hit = path.toLowerCase().contains(keyword) || fileRel.toLowerCase().contains(keyword);
            boolean section = sec.isConfigurationSection(k);
            if (hit) {
                JsonObject r = new JsonObject();
                r.addProperty("file", fileRel);
                r.addProperty("path", path);
                r.addProperty("section", section);
                if (!section) {
                    Object v = sec.get(k);
                    r.addProperty("type", typeOf(v));
                    boolean secret = isSecretPath(path);
                    r.addProperty("preview", secret ? "******" : preview(v));
                    if (secret) r.addProperty("masked", true);
                }
                results.add(r);
            }
            if (section) searchSection(fileRel, sec.getConfigurationSection(k), path, keyword, results);
        }
    }

    private static void searchValues(String fileRel, ConfigurationSection sec, String prefix, String keyword, JsonArray results) {
        if (results.size() >= MAX_SEARCH_RESULTS) return;
        for (String k : sec.getKeys(false)) {
            if (results.size() >= MAX_SEARCH_RESULTS) return;
            String path = prefix == null || prefix.isEmpty() ? k : prefix + "." + k;
            boolean section = sec.isConfigurationSection(k);
            if (section) {
                searchValues(fileRel, sec.getConfigurationSection(k), path, keyword, results);
                continue;
            }
            Object v = sec.get(k);
            String prev = preview(v);
            String lowerPath = path.toLowerCase();
            String lowerPrev = prev.toLowerCase();
            String lowerFile = fileRel.toLowerCase();
            String matched = null;
            if (lowerPath.contains(keyword)) matched = "path";
            else if (lowerPrev.contains(keyword)) matched = "value";
            else if (lowerFile.contains(keyword)) matched = "file";
            if (matched != null) {
                boolean secret = isSecretPath(path);
                JsonObject r = new JsonObject();
                r.addProperty("file", fileRel);
                r.addProperty("path", path);
                r.addProperty("type", typeOf(v));
                r.addProperty("preview", secret ? "******" : prev);
                r.addProperty("matched_in", matched);
                if (secret) r.addProperty("masked", true);
                results.add(r);
            }
        }
    }

    /**
     * Discovers plugins from JAR files in plugins/, so disabled or failed-to-load plugins are visible too.
     * Runtime Bukkit state is used only to enrich the result with the loaded flag and actual data directory.
     */
    private static List<PluginInfo> pluginInfos() throws Exception {
        final Map<String, PluginDescriptionEntry> descByName = new LinkedHashMap<>();
        for (PluginDescriptionEntry e : PluginDescriptionManager.readAll()) descByName.put(e.getName(), e);
        final Map<String, Plugin> loadedByName = new LinkedHashMap<>();
        Runnable loadedScan = () -> {
            for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                loadedByName.put(plugin.getName().toLowerCase(), plugin);
            }
        };
        if (Bukkit.isPrimaryThread()) loadedScan.run();
        else Bukkit.getScheduler().callSyncMethod(ExAI.getInstance(), () -> { loadedScan.run(); return null; }).get();

        final List<PluginInfo> out = new ArrayList<>();
        File root = WebServer.pluginsRoot();
        File[] jars = root.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".jar"));
        if (jars == null) return out;
        Arrays.sort(jars, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File jarFile : jars) {
            try (JarFile jar = new JarFile(jarFile)) {
                JarEntry entry = jar.getJarEntry("plugin.yml");
                if (entry == null) continue;
                PluginDescriptionFile d;
                try (InputStream in = jar.getInputStream(entry)) {
                    d = new PluginDescriptionFile(in);
                }
                String name = d.getName();
                Plugin p = loadedByName.get(name.toLowerCase());
                PluginDescriptionEntry saved = descByName.remove(name);
                PluginInfo info = new PluginInfo();
                info.name = name;
                info.version = d.getVersion() == null ? "" : d.getVersion();
                info.description = d.getDescription() == null ? "" : d.getDescription();
                info.pluginDesc = saved == null ? "" : saved.getDescription();
                info.installed = true;
                info.enabled = saved == null || saved.isEnabled();
                info.loaded = p != null && p.isEnabled();
                File dataDir = p == null ? new File(root, name) : p.getDataFolder();
                info.dataDir = dataDir.isDirectory() ? safeRel(dataDir) : name;
                info.authors = d.getAuthors() == null ? "" : d.getAuthors().toString();
                info.commands = commandPreview(d.getCommands());
                out.add(info);
            } catch (Exception e) {
                ExAI.getInstance().getLogger().warning("读取插件 JAR 失败 " + jarFile.getName() + ": " + e.getMessage());
            }
        }
        return out;
    }

    private static int pluginScore(PluginInfo i, String query, JsonArray hits) {
        int score = 0;
        String q = normMatch(query.replace("插件", ""));
        List<String> qs = new ArrayList<>(); qs.add(q);
        if (query.contains("远征")) { qs.add("expedition"); qs.add("quest"); qs.add("adventure"); }
        if (query.contains("佣兵")) { qs.add("mercenary"); qs.add("hireling"); qs.add("companion"); qs.add("npc"); qs.add("soldier"); }
        for (String x : qs) {
            if (x.isEmpty()) continue;
            score += hit(i.name, "name", x, hits, 10);
            score += hit(i.dataDir, "data_dir", x, hits, 8);
            score += hit(i.pluginDesc, "plugin_desc", x, hits, 7);
            score += hit(i.description, "description", x, hits, 5);
            score += hit(i.commands, "commands", x, hits, 4);
        }
        return score;
    }

    private static int hit(String text, String field, String q, JsonArray hits, int score) {
        if (text != null && normMatch(text).contains(q)) { hits.add(field); return score; }
        return 0;
    }

    private static String normMatch(String s) {
        return s == null ? "" : s.toLowerCase().replace(" ", "").replace("_", "").replace("-", "");
    }

    private static String firstNonEmpty(String a, String b) {
        return a != null && !a.trim().isEmpty() ? a : (b == null ? "" : b);
    }

    private static String safeRel(File f) {
        try { return toRel(f); } catch (Exception e) { return f == null ? "" : f.getName(); }
    }

    private static String commandPreview(Map<String, Map<String, Object>> commands) {
        if (commands == null || commands.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(); int n = 0;
        for (Map.Entry<String, Map<String, Object>> e : commands.entrySet()) {
            if (n++ >= 12) { sb.append("..."); break; }
            sb.append('/').append(e.getKey());
            Object d = e.getValue() == null ? null : e.getValue().get("description");
            if (d != null) sb.append('(').append(d).append(')');
            sb.append(' ');
        }
        return sb.toString().trim();
    }

    private static String lastPathPart(String path) {
        if (path == null) return "";
        String p = path.trim();
        int dot = p.lastIndexOf('.');
        return dot >= 0 ? p.substring(dot + 1) : p;
    }

    private static String redactSecretLine(String line) {
        String t = line.trim();
        int colon = t.indexOf(':');
        if (colon <= 0) return line;
        String key = t.substring(0, colon).trim();
        if (!isSecretPath(key)) return line;
        int prefixLen = line.indexOf(key) + key.length();
        return line.substring(0, prefixLen) + ": ******";
    }

    private static String reqString(JsonObject obj, String key) {
        String s = optString(obj, key);
        if (s == null || s.trim().isEmpty()) throw new IllegalArgumentException("缺少参数: " + key);
        return s;
    }

    private static String optString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsString();
    }

    private static Object jsonToYamlValue(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isString()) return p.getAsString();
            if (p.isNumber()) return numberFromString(p.getAsString());
            return p.getAsString();
        }
        if (e.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement child : e.getAsJsonArray()) list.add(jsonToYamlValue(child));
            return list;
        }
        if (e.isJsonObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) {
                map.put(entry.getKey(), jsonToYamlValue(entry.getValue()));
            }
            return map;
        }
        return String.valueOf(e);
    }

    private static Object numberFromString(String s) {
        if (s != null && s.matches("-?\\d+")) {
            try {
                long l = Long.parseLong(s);
                return (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) ? (int) l : l;
            } catch (NumberFormatException ignored) {}
        }
        try {
            return Double.parseDouble(s);
        } catch (Exception ignored) {
            return s;
        }
    }

    private static boolean isSecretPath(String path) {
        if (path == null) return false;
        String[] parts = path.split("\\.");
        String last = parts.length == 0 ? path : parts[parts.length - 1];
        String s = last.toLowerCase().replace("_", "").replace("-", "");
        return s.contains("password") || s.contains("passwd") || s.equals("pwd") || s.contains("secret")
                || s.contains("token") || s.contains("apikey") || s.contains("credential");
    }

    private static JsonElement valueToJson(Object v, boolean maskSecret, String path) {
        if (maskSecret || isSecretPath(path)) return new JsonPrimitive("******");
        if (v == null) return JsonNull.INSTANCE;
        if (v instanceof Boolean) return new JsonPrimitive((Boolean) v);
        if (v instanceof Number) return new JsonPrimitive((Number) v);
        if (v instanceof String) return new JsonPrimitive((String) v);
        if (v instanceof ConfigurationSection) return valueToJson(((ConfigurationSection) v).getValues(false), false, path);
        if (v instanceof Map) {
            JsonObject o = new JsonObject();
            @SuppressWarnings("unchecked") Map<Object, Object> m = (Map<Object, Object>) v;
            for (Map.Entry<Object, Object> e : m.entrySet()) {
                String child = path == null || path.isEmpty() ? String.valueOf(e.getKey()) : path + "." + e.getKey();
                o.add(String.valueOf(e.getKey()), valueToJson(e.getValue(), false, child));
            }
            return o;
        }
        if (v instanceof Iterable) {
            JsonArray arr = new JsonArray();
            for (Object x : (Iterable<?>) v) arr.add(valueToJson(x, false, path));
            return arr;
        }
        return new JsonPrimitive(String.valueOf(v));
    }

    private static String preview(Object v) {
        String s = String.valueOf(v);
        s = s.replace('\n', ' ');
        return s.length() > 80 ? s.substring(0, 77) + "..." : s;
    }

    private static String typeOf(Object v) {
        return v == null ? "null" : v.getClass().getSimpleName();
    }

    private static String comparable(boolean exists, Object v) {
        return exists + ":" + valueToJson(v, false, "").toString();
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static synchronized void cleanupExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, PendingChange>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().createdAt > PENDING_TTL_MS) {
                it.remove();
            }
        }
    }

    private static void loadAppliedChanges() {
        if (appliedLoaded) return;
        appliedLoaded = true;
        File file = appliedStoreFile();
        if (!file.isFile()) return;
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            JsonElement root = new com.google.gson.JsonParser().parse(reader);
            if (root == null || !root.isJsonArray()) return;
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                AppliedChange change = AppliedChange.fromJson(element.getAsJsonObject());
                if (change != null) APPLIED.add(change);
            }
        } catch (Exception e) {
            ExAI.getInstance().getLogger().warning("读取 CLI 修改记录失败: " + e.getMessage());
        }
    }

    private static void saveAppliedChanges() {
        JsonArray out = new JsonArray();
        for (AppliedChange change : APPLIED) out.add(change.toJson());
        try {
            File file = appliedStoreFile();
            File parent = file.getParentFile();
            if (parent != null) Files.createDirectories(parent.toPath());
            Files.write(file.toPath(), GSON.toJson(out).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            ExAI.getInstance().getLogger().warning("保存 CLI 修改记录失败: " + e.getMessage());
        }
    }

    private static File appliedStoreFile() {
        return new File(ExAI.getInstance().getDataFolder(), APPLIED_STORE_FILE);
    }

    private static final class PluginInfo {
        String name = "";
        String version = "";
        String description = "";
        String pluginDesc = "";
        String dataDir = "";
        String authors = "";
        String commands = "";
        boolean installed;
        boolean enabled = true;
        boolean loaded;
    }

    private static final class PendingChange {
        final String id;
        final String fileRel;
        final String path;
        final Object value;
        final boolean delete;
        final boolean oldExists;
        final JsonElement oldValue;
        final String oldComparable;
        final String newComparable;
        final String oldPreview;
        final String newPreview;
        final String reason;
        final String sessionId;
        final long turnId;
        final long createdAt = System.currentTimeMillis();

        PendingChange(String id, String fileRel, String path, Object value, boolean delete,
                      boolean oldExists, JsonElement oldValue, String oldComparable, String newComparable,
                      String oldPreview, String newPreview, String reason, String sessionId, long turnId) {
            this.id = id;
            this.fileRel = fileRel;
            this.path = path;
            this.value = value;
            this.delete = delete;
            this.oldExists = oldExists;
            this.oldValue = oldValue;
            this.oldComparable = oldComparable;
            this.newComparable = newComparable;
            this.oldPreview = oldPreview;
            this.newPreview = newPreview;
            this.reason = reason;
            this.sessionId = sessionId == null ? "" : sessionId;
            this.turnId = turnId;
        }
    }

    private static final class AppliedChange {
        final String id;
        final String sessionId;
        final long turnId;
        final String fileRel;
        final String path;
        final boolean oldExists;
        final JsonElement oldValue;
        final String oldComparable;
        final String newComparable;
        final long appliedAt;
        boolean reverted;
        long revertedAt;

        AppliedChange(PendingChange pending) {
            this.id = pending.id;
            this.sessionId = pending.sessionId;
            this.turnId = pending.turnId;
            this.fileRel = pending.fileRel;
            this.path = pending.path;
            this.oldExists = pending.oldExists;
            this.oldValue = pending.oldValue;
            this.oldComparable = pending.oldComparable;
            this.newComparable = pending.newComparable;
            this.appliedAt = System.currentTimeMillis();
        }

        private AppliedChange(JsonObject data) {
            this.id = string(data, "id");
            this.sessionId = string(data, "sessionId");
            this.turnId = longValue(data, "turnId");
            this.fileRel = string(data, "fileRel");
            this.path = string(data, "path");
            this.oldExists = boolValue(data, "oldExists");
            JsonElement old = data.get("oldValue");
            this.oldValue = old == null ? JsonNull.INSTANCE : old;
            this.oldComparable = string(data, "oldComparable");
            this.newComparable = string(data, "newComparable");
            this.appliedAt = longValue(data, "appliedAt");
            this.reverted = boolValue(data, "reverted");
            this.revertedAt = longValue(data, "revertedAt");
        }

        static AppliedChange fromJson(JsonObject data) {
            AppliedChange change = new AppliedChange(data);
            return change.id.isEmpty() || change.fileRel.isEmpty() || change.path.isEmpty() ? null : change;
        }

        JsonObject toJson() {
            JsonObject data = new JsonObject();
            data.addProperty("id", id);
            data.addProperty("sessionId", sessionId);
            data.addProperty("turnId", turnId);
            data.addProperty("fileRel", fileRel);
            data.addProperty("path", path);
            data.addProperty("oldExists", oldExists);
            data.add("oldValue", oldValue);
            data.addProperty("oldComparable", oldComparable);
            data.addProperty("newComparable", newComparable);
            data.addProperty("appliedAt", appliedAt);
            data.addProperty("reverted", reverted);
            data.addProperty("revertedAt", revertedAt);
            return data;
        }
    }

    public static final class RollbackResult {
        private final boolean ok;
        private final int revertedChanges;
        private final String error;

        RollbackResult(boolean ok, int revertedChanges, String error) {
            this.ok = ok;
            this.revertedChanges = revertedChanges;
            this.error = error;
        }

        public boolean isOk() { return ok; }
        public int getRevertedChanges() { return revertedChanges; }
        public String getError() { return error; }
    }

    private static boolean boolValue(JsonObject data, String key) {
        try {
            JsonElement value = data.get(key);
            return value != null && !value.isJsonNull() && value.getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String string(JsonObject data, String key) {
        JsonElement value = data.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static long longValue(JsonObject data, String key) {
        try {
            JsonElement value = data.get(key);
            return value == null || value.isJsonNull() ? 0L : value.getAsLong();
        } catch (Exception ignored) {
            return 0L;
        }
    }
}
