package com.exai.managers;

import com.exai.ExAI;
import com.exai.embedding.DashScopeEmbedding;
import com.exai.embedding.VectorStore;
import com.exai.entity.GameDocument;
import org.bukkit.Bukkit;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class GameKnowledgeBase {
    private VectorStore vectorStore;
    private DashScopeEmbedding embeddingService;
    private GameDataLoader dataLoader;
    private double minSimilarity;

    public GameKnowledgeBase(VectorStore vectorStore, DashScopeEmbedding embeddingService, GameDataLoader dataLoader, double minSimilarity) {
        this.vectorStore = vectorStore;
        this.embeddingService = embeddingService;
        this.dataLoader = dataLoader;
        this.minSimilarity = minSimilarity;
    }
    public void initializeKnowledgeBase() {
        Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(),()-> {
            List<GameDocument> documents = dataLoader.loadGameData();
            documents.forEach(doc -> {
                double[] embedding = embeddingService.generateEmbedding(doc.getContent());
                doc.setEmbedding(embedding);
                vectorStore.addDocument(doc);
            });
        });
    }

    public List<GameDocument> retrieveRelevantDocs(String query, String playerContext) {
        String enhancedQuery = enhanceQueryWithContext(query, playerContext);
        double[] queryEmbedding = embeddingService.generateEmbedding(enhancedQuery);

        String predictedCategory = predictCategory(query);
        List<GameDocument> results = vectorStore.searchByCategory(
                predictedCategory, queryEmbedding, 5
        );

        if (results.size() < 3) {
            List<GameDocument> globalResults = vectorStore.searchSimilar(queryEmbedding, 10);
            results.addAll(globalResults);
        }

        return results.stream()
                .distinct()
                .sorted(Comparator.comparingDouble(doc ->
                        -embeddingService.cosineSimilarity(queryEmbedding, doc.getEmbedding())))
                .limit(7)
                .filter(doc -> {
                    double similarity = embeddingService.cosineSimilarity(queryEmbedding, doc.getEmbedding());
                    return similarity >= minSimilarity;
                })
                .collect(Collectors.toList());
    }

    private String enhanceQueryWithContext(String query, String context) {
        return String.format("玩家问: %s。当前上下文: %s", query, context);
    }

    private String predictCategory(String query) {
        if (query.contains("哪里") || query.contains("位置") || query.contains("去哪")) {
            return "地点";
        } else if (query.contains("任务") || query.contains("怎么完成")) {
            return "任务";
        } else if (query.contains("怎么获得") || query.contains("装备")) {
            return "物品";
        } else if (query.contains("技能") || query.contains("怎么使用")) {
            return "技能";
        } else if (query.contains("NPC") || query.contains("谁")) {
            return "NPC";
        }
        return "通用";
    }
}