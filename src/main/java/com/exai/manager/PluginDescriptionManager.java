package com.exai.manager;

import com.exai.ExAI;
import com.exai.config.Config;
import com.exai.entity.GameDocument;
import com.exai.entity.PluginDescriptionEntry;
import com.exai.i18n.Lang;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理插件描述（plugins/ExAI/knowledge/pluginDesc.yml）：扫描服务器插件、合并保留管理员描述、
 * 并把「已启用且有描述」的插件构建成 RAG 文档参与回答。
 */
public class PluginDescriptionManager {

    public static final String FILE_NAME = "knowledge/pluginDesc.yml";

    private static File file() {
        return new File(ExAI.getInstance().getDataFolder(), FILE_NAME);
    }

    public static synchronized List<PluginDescriptionEntry> readAll() {
        List<PluginDescriptionEntry> list = new ArrayList<>();
        File f = file();
        if (!f.exists()) {
            return list;
        }
        YamlConfiguration conf = YamlConfiguration.loadConfiguration(f);
        for (Map<?, ?> m : conf.getMapList("plugins")) {
            Object name = m.get("name");
            if (name == null) {
                continue;
            }
            PluginDescriptionEntry e = new PluginDescriptionEntry();
            e.setName(name.toString());
            e.setVersion(m.get("version") == null ? "" : m.get("version").toString());
            e.setInstalled(asBool(m.get("installed"), true));
            e.setEnabled(asBool(m.get("enabled"), true));
            e.setDescription(m.get("description") == null ? "" : m.get("description").toString());
            list.add(e);
        }
        return list;
    }

    public static synchronized void writeAll(List<PluginDescriptionEntry> entries) {
        YamlConfiguration conf = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (PluginDescriptionEntry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.getName());
            m.put("version", e.getVersion());
            m.put("installed", e.isInstalled());
            m.put("enabled", e.isEnabled());
            m.put("description", e.getDescription());
            list.add(m);
        }
        conf.set("plugins", list);
        File f = file();
        try {
            conf.save(f);
        } catch (IOException ex) {
            ExAI.getInstance().getLogger().warning("保存 pluginDesc.yml 失败: " + ex.getMessage());
        }
    }

    /**
     * 枚举服务器已安装插件并与现有文件合并：保留已填描述/enabled，刷新版本与 installed 标记，
     * 当前不在线的旧条目保留并置 installed=false。必须在主线程调用（Bukkit API）。
     */
    public static synchronized List<PluginDescriptionEntry> scanAndMerge() {
        Map<String, PluginDescriptionEntry> byName = new LinkedHashMap<>();
        for (PluginDescriptionEntry e : readAll()) {
            e.setInstalled(false); // 先全部标记离线，扫描到的再置回
            byName.put(e.getName(), e);
        }
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            String name = plugin.getName();
            String version = plugin.getDescription().getVersion();
            PluginDescriptionEntry e = byName.get(name);
            if (e == null) {
                String prefill = plugin.getDescription().getDescription();
                e = new PluginDescriptionEntry(name, version, true, true, prefill == null ? "" : prefill);
                byName.put(name, e);
            } else {
                e.setVersion(version == null ? "" : version);
                e.setInstalled(true);
            }
        }
        List<PluginDescriptionEntry> merged = new ArrayList<>(byName.values());
        writeAll(merged);
        return merged;
    }

    /**
     * 把「已启用且描述非空」的插件构建成 RAG 文档。category 与知识块一致，确保能一起被检索。
     */
    public static List<GameDocument> buildDocuments() {
        List<GameDocument> docs = new ArrayList<>();
        String category = Lang.get("log.knowledge-category");
        for (PluginDescriptionEntry e : readAll()) {
            if (!e.isEnabled()) {
                continue;
            }
            String desc = e.getDescription() == null ? "" : e.getDescription().trim();
            if (desc.isEmpty()) {
                continue;
            }
            String content = Lang.get("plugin.doc-format", e.getName(), e.getVersion(), desc);
            docs.add(new GameDocument("plugin_" + e.getName(), content, category, null));
        }
        return docs;
    }

    /**
     * 把抓到的 help 原文交给大模型概括成一段插件描述。<b>会走网络，勿在主线程调用。</b>
     * LLM 未配置、原文为空或模型无返回时返回 null。
     */
    public static String summarizeHelp(String name, String version, String rawHelp) {
        if (Config.llm == null || rawHelp == null || rawHelp.trim().isEmpty()) {
            return null;
        }
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        String publicCommands = plugin == null ? "" : PluginHelpProbe.publicCommandSummary(plugin);
        String playerHelp = plugin == null ? rawHelp : PluginHelpProbe.filterForRegularPlayers(plugin, rawHelp);
        String prompt = Lang.get("plugin.help-summary-prompt",
                name, version == null ? "" : version, publicCommands, playerHelp);
        String desc = Config.llm.complete(prompt, 0.3, 300);
        return (desc == null || desc.trim().isEmpty()) ? null : desc.trim();
    }

    /** 把生成的描述写入同名条目并落盘；找不到该插件条目时返回 false。 */
    public static synchronized boolean setDescription(String name, String description) {
        List<PluginDescriptionEntry> all = readAll();
        boolean found = false;
        for (PluginDescriptionEntry e : all) {
            if (e.getName().equals(name)) {
                e.setDescription(description);
                found = true;
                break;
            }
        }
        if (found) {
            writeAll(all);
        }
        return found;
    }

    private static boolean asBool(Object o, boolean def) {
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        if (o == null) {
            return def;
        }
        return Boolean.parseBoolean(o.toString());
    }
}
