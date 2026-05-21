package com.exai.managers;

import com.exai.ExAI;
import com.exai.entity.GameDocument;
import com.exai.i18n.Lang;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class GameDataLoader {
    private String docxPath;
    private int chunkSize;
    private boolean debugMode = false;

    public GameDataLoader() {
        this.docxPath = "gamehelp.txt";
        this.chunkSize = 5;
    }

    public GameDataLoader(String docxPath) {
        this.docxPath = docxPath;
        this.chunkSize = 5;
    }

    public GameDataLoader(String docxPath, int chunkSize) {
        this.docxPath = docxPath;
        this.chunkSize = chunkSize;
    }

    public List<GameDocument> loadGameData() {
        List<GameDocument> documents = new ArrayList<>();
        JavaPlugin plugin = ExAI.getInstance();

        saveDefaultResource(plugin);

        File txtFile = new File(plugin.getDataFolder(), "gamehelp.txt");

        if (txtFile.exists()) {
            plugin.getLogger().info(Lang.get("log.read-gamehelp"));
            documents = loadFromFile(txtFile);
        }

        plugin.getLogger().info(Lang.get("log.loaded-docs", documents.size()));
        return documents;
    }

    private void saveDefaultResource(JavaPlugin plugin) {
        try {
            plugin.saveResource("gamehelp.txt", false);
            plugin.getLogger().info(Lang.get("log.saved-default-gamehelp"));
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save resource: " + e.getMessage());
        }
    }

    private List<GameDocument> loadFromFile(File file) {
        List<GameDocument> documents = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {

            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
            documents = createChunksFromLines(lines);

        } catch (IOException e) {
            ExAI.getInstance().getLogger().severe("Failed to read file: " + e.getMessage());
            e.printStackTrace();
        }

        return documents;
    }

    private List<GameDocument> createChunksFromLines(List<String> lines) {
        List<GameDocument> chunks = new ArrayList<>();

        if (lines.isEmpty()) {
            return chunks;
        }

        if (chunkSize <= 0) {
            chunkSize = 1;
        }

        int chunkCount = (int) Math.ceil((double) lines.size() / chunkSize);

        for (int i = 0; i < chunkCount; i++) {
            int start = i * chunkSize;
            int end = Math.min(start + chunkSize, lines.size());

            List<String> chunkLines = lines.subList(start, end);
            String chunkText = mergeLinesToText(chunkLines);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("chunk_id", i + 1);
            metadata.put("chunk_size", chunkLines.size());
            metadata.put("line_range", start + "-" + (end - 1));
            metadata.put("total_lines", lines.size());

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

    private String mergeLinesToText(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > 0) {
                sb.append(". ");
            }
            sb.append(line);
        }
        return sb.toString();
    }
}
