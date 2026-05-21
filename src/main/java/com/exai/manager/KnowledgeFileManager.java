package com.exai.manager;

import com.exai.ExAI;
import com.exai.config.Config;
import com.exai.entity.KnowledgeEntry;
import com.exai.i18n.Lang;
import org.bukkit.Bukkit;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class KnowledgeFileManager {
    private static final String FILE_NAME = "gamehelp.txt";

    public static List<KnowledgeEntry> readAll() {
        List<KnowledgeEntry> list = new ArrayList<>();
        File file = new File(ExAI.getInstance().getDataFolder(), FILE_NAME);
        if (!file.exists()) {
            return list;
        }
        String qPrefix = Lang.get("book.pattern-prefix-q");
        String aPrefix = Lang.get("book.pattern-prefix-a");
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String q = null;
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.startsWith(qPrefix)) {
                    q = trimmed.substring(qPrefix.length()).trim();
                } else if (trimmed.startsWith(aPrefix) && q != null) {
                    String a = trimmed.substring(aPrefix.length()).trim();
                    list.add(new KnowledgeEntry(q, a, "", 0L));
                    q = null;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return list;
    }

    public static void writeAll(List<KnowledgeEntry> entries) {
        File file = new File(ExAI.getInstance().getDataFolder(), FILE_NAME);
        String qPrefix = Lang.get("book.pattern-prefix-q");
        String aPrefix = Lang.get("book.pattern-prefix-a");
        try (BufferedWriter bw = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8))) {
            for (KnowledgeEntry e : entries) {
                bw.write(qPrefix + e.getQuestion());
                bw.newLine();
                bw.write(aPrefix + e.getAnswer());
                bw.newLine();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
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

    public static void append(KnowledgeEntry entry) {
        File file = new File(ExAI.getInstance().getDataFolder(), FILE_NAME);
        String qPrefix = Lang.get("book.pattern-prefix-q");
        String aPrefix = Lang.get("book.pattern-prefix-a");
        try (BufferedWriter bw = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8))) {
            bw.write(qPrefix + entry.getQuestion());
            bw.newLine();
            bw.write(aPrefix + entry.getAnswer());
            bw.newLine();
        } catch (IOException e) {
            e.printStackTrace();
        }
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
