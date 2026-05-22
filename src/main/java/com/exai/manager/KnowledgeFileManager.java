package com.exai.manager;

import com.exai.ExAI;
import com.exai.config.Config;
import com.exai.entity.KnowledgeEntry;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class KnowledgeFileManager {
    public static final String FILE_NAME = "knowledge.yml";

    public static File knowledgeFile() {
        return new File(ExAI.getInstance().getDataFolder(), FILE_NAME);
    }

    public static List<KnowledgeEntry> readAll() {
        List<KnowledgeEntry> list = new ArrayList<>();
        File file = knowledgeFile();
        if (!file.exists()) {
            return list;
        }
        YamlConfiguration conf = YamlConfiguration.loadConfiguration(file);
        List<Map<?, ?>> raw = conf.getMapList("entries");
        for (Map<?, ?> m : raw) {
            Object q = m.get("question");
            Object a = m.get("answer");
            if (q == null || a == null) continue;
            list.add(new KnowledgeEntry(q.toString(), a.toString(), "", 0L));
        }
        return list;
    }

    public static void writeAll(List<KnowledgeEntry> entries) {
        File file = knowledgeFile();
        YamlConfiguration conf = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (KnowledgeEntry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("question", e.getQuestion());
            m.put("answer", e.getAnswer());
            list.add(m);
        }
        conf.set("entries", list);
        try {
            conf.save(file);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public static void append(KnowledgeEntry entry) {
        List<KnowledgeEntry> all = readAll();
        all.add(entry);
        writeAll(all);
    }

    public static void deleteByIndex(int index) {
        List<KnowledgeEntry> all = readAll();
        if (index < 0 || index >= all.size()) return;
        all.remove(index);
        writeAll(all);
    }

    public static void updateByIndex(int index, KnowledgeEntry entry) {
        List<KnowledgeEntry> all = readAll();
        if (index < 0 || index >= all.size()) return;
        all.set(index, entry);
        writeAll(all);
    }

    public static boolean isDuplicateQuestion(String question) {
        String normalized = question.trim().toLowerCase();
        for (KnowledgeEntry entry : readAll()) {
            if (entry.getQuestion().trim().toLowerCase().equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    public static void reloadKnowledgeBaseAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), Config::reloadKnowledgeBaseOnly);
    }
}
