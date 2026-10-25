package com.exai.manager;

import com.exai.ExAI;
import com.exai.embedding.DashScopeEmbedding;
import com.exai.embedding.VectorStore;
import com.exai.entity.GameDocument;
import com.exai.entity.RetrievedDocument;
import com.exai.i18n.Lang;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class GameKnowledgeBase {
    private VectorStore vectorStore;
    private DashScopeEmbedding embeddingService;
    private GameDataLoader dataLoader;
    private double minSimilarity;
    private int maxDocs;
    private volatile boolean ready;

    public GameKnowledgeBase(VectorStore vectorStore, DashScopeEmbedding embeddingService, GameDataLoader dataLoader, double minSimilarity, int maxDocs) {
        this.vectorStore = vectorStore;
        this.embeddingService = embeddingService;
        this.dataLoader = dataLoader;
        this.minSimilarity = minSimilarity;
        this.maxDocs = maxDocs;
    }

    public void initializeKnowledgeBase() {
        initializeKnowledgeBase(null);
    }

    public void initializeKnowledgeBase(Runnable onReady) {
        Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), () -> {
            List<GameDocument> documents = dataLoader.loadGameData();
            // 把「已启用且有描述」的插件描述作为额外文档一起纳入向量库
            documents.addAll(PluginDescriptionManager.buildDocuments());
            List<String> contents = new ArrayList<>(documents.size());
            // addDocument 内部会生成并写入嵌入(命中缓存则不调接口)，此处不再重复生成
            documents.forEach(doc -> {
                vectorStore.addDocument(doc);
                contents.add(doc.getContent());
            });
            // 把本次活跃文档的嵌入落盘，下次启动/重载只需为新增或变更的片段调用接口
            embeddingService.persistCache(contents);
            ready = true;
            if (onReady != null) {
                onReady.run();
            }
        });
    }

    public boolean isReady() {
        return ready;
    }

    public List<GameDocument> retrieveRelevantDocs(String query, String playerContext) {
        return retrieveRelevantWithScores(query, playerContext).stream()
                .map(RetrievedDocument::getDocument)
                .collect(Collectors.toList());
    }

    public List<RetrievedDocument> retrieveRelevantWithScores(String query, String playerContext) {
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

        List<GameDocument> filtered = results.stream()
                .distinct()
                .filter(doc -> embeddingService.cosineSimilarity(queryEmbedding, doc.getEmbedding()) >= minSimilarity)
                .sorted(Comparator.comparingDouble(doc ->
                        -embeddingService.cosineSimilarity(queryEmbedding, doc.getEmbedding())))
                .limit(maxDocs)
                .collect(Collectors.toList());
        List<RetrievedDocument> scored = new ArrayList<>();
        for (int i = 0; i < filtered.size(); i++) {
            GameDocument doc = filtered.get(i);
            scored.add(new RetrievedDocument(doc,
                    embeddingService.cosineSimilarity(queryEmbedding, doc.getEmbedding()), i + 1));
        }
        return scored;
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
