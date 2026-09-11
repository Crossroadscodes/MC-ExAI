package com.exai.managers;

import com.exai.ExAI;
import com.exai.entity.GameDocument;
import com.exai.entity.KnowledgeEntry;
import com.exai.i18n.Lang;
import com.exai.manager.KnowledgeFileManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class GameDataLoader {
    private static final int ENTRIES_PER_CHUNK = 3;
    private boolean debugMode = false;

    public GameDataLoader() {
    }

    public List<GameDocument> loadGameData() {
        JavaPlugin plugin = ExAI.getInstance();

        File ymlFile = new File(plugin.getDataFolder(), KnowledgeFileManager.FILE_NAME);
        File legacyTxt = new File(plugin.getDataFolder(), "gamehelp.txt");

        if (!ymlFile.exists() && legacyTxt.exists()) {
            migrateTxtToYml(legacyTxt);
        }
        if (!ymlFile.exists()) {
            saveDefaultKnowledgeYml(plugin);
        }

        plugin.getLogger().info(Lang.get("log.read-knowledge-yml"));
        List<GameDocument> documents = loadFromKnowledgeFile();
        plugin.getLogger().info(Lang.get("log.loaded-docs", documents.size()));
        return documents;
    }

    private void saveDefaultKnowledgeYml(JavaPlugin plugin) {
        try {
            plugin.saveResource(KnowledgeFileManager.FILE_NAME, false);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save default knowledge.yml: " + e.getMessage());
        }
    }

    private void migrateTxtToYml(File txtFile) {
        try {
            List<KnowledgeEntry> entries = new ArrayList<>();
            String qPrefix = Lang.get("book.pattern-prefix-q");
            String aPrefix = Lang.get("book.pattern-prefix-a");
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(txtFile), StandardCharsets.UTF_8))) {
                String q = null;
                String line;
                while ((line = br.readLine()) != null) {
                    String t = line.trim();
                    if (t.isEmpty()) continue;
                    if (t.startsWith(qPrefix)) {
                        q = t.substring(qPrefix.length()).trim();
                    } else if (t.startsWith(aPrefix) && q != null) {
                        String a = t.substring(aPrefix.length()).trim();
                        entries.add(new KnowledgeEntry(q, a, "", 0L));
                        q = null;
                    }
                }
            }
            KnowledgeFileManager.writeAll(entries);
            ExAI.getInstance().getLogger().info(Lang.get("log.migrated-gamehelp-yml", entries.size()));
        } catch (IOException e) {
            ExAI.getInstance().getLogger().warning("Migrate gamehelp.txt -> knowledge.yml failed: " + e.getMessage());
        }
    }

    private List<GameDocument> loadFromKnowledgeFile() {
        List<KnowledgeEntry> entries = KnowledgeFileManager.readAll();
        return createChunksFromEntries(entries);
    }

    /**
     * Each vector document contains three complete Q&A entries, never a partial entry.
     */
    private List<GameDocument> createChunksFromEntries(List<KnowledgeEntry> entries) {
        List<GameDocument> chunks = new ArrayList<>();

        if (entries.isEmpty()) {
            return chunks;
        }

        int chunkCount = (int) Math.ceil((double) entries.size() / ENTRIES_PER_CHUNK);

        for (int i = 0; i < chunkCount; i++) {
            int start = i * ENTRIES_PER_CHUNK;
            int end = Math.min(start + ENTRIES_PER_CHUNK, entries.size());

            List<KnowledgeEntry> chunkEntries = entries.subList(start, end);
            String chunkText = mergeEntriesToText(chunkEntries);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("chunk_id", i + 1);
            metadata.put("chunk_size", chunkEntries.size());
            metadata.put("entry_range", (start + 1) + "-" + end);
            metadata.put("total_entries", entries.size());

            GameDocument chunk = new GameDocument(
                    "chunk_" + String.format("%04d", i + 1),
                    chunkText,
                    Lang.get("log.knowledge-category"),
                    metadata
            );

            chunks.add(chunk);

            if (debugMode) {
                System.out.println("Chunk " + (i + 1) + ": " +
                        chunkText.substring(0, Math.min(50, chunkText.length())) + "...");
            }
        }

        return chunks;
    }

    private String mergeEntriesToText(List<KnowledgeEntry> entries) {
        StringBuilder sb = new StringBuilder();
        String qPrefix = Lang.get("book.pattern-prefix-q");
        String aPrefix = Lang.get("book.pattern-prefix-a");
        for (KnowledgeEntry entry : entries) {
            if (sb.length() > 0) {
                sb.append(". ");
            }
            sb.append(qPrefix).append(entry.getQuestion());
            sb.append(". ");
            sb.append(aPrefix).append(entry.getAnswer());
        }
        return sb.toString();
    }
}
