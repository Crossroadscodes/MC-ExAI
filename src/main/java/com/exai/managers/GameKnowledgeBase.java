package com.exai.managers;

import com.exai.ExAI;
import com.exai.embedding.DashScopeEmbedding;
import com.exai.embedding.VectorStore;
import com.exai.entity.GameDocument;
import com.exai.i18n.Lang;
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
        Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), () -> {
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
        return Lang.get("category.enhanced-query", query, context);
    }

    private String predictCategory(String query) {
        if (matchesAny(query, Lang.get("category.kw-location"))) return Lang.get("category.location");
        if (matchesAny(query, Lang.get("category.kw-quest"))) return Lang.get("category.quest");
        if (matchesAny(query, Lang.get("category.kw-item"))) return Lang.get("category.item");
        if (matchesAny(query, Lang.get("category.kw-skill"))) return Lang.get("category.skill");
        if (matchesAny(query, Lang.get("category.kw-npc"))) return Lang.get("category.npc");
        return Lang.get("category.general");
    }

    private boolean matchesAny(String text, String csvKeywords) {
        if (csvKeywords == null || csvKeywords.isEmpty()) return false;
        String lower = text.toLowerCase();
        for (String kw : csvKeywords.split(",")) {
            String trimmed = kw.trim();
            if (!trimmed.isEmpty() && lower.contains(trimmed.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
