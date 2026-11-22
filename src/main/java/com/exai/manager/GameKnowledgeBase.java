package com.exai.manager;

import com.exai.ExAI;
import com.exai.embedding.DashScopeEmbedding;
import com.exai.embedding.VectorStore;
import com.exai.entity.GameDocument;
import com.exai.entity.RetrievedDocument;
import com.exai.i18n.Lang;
import com.exai.retrieval.Bm25Index;
import com.exai.retrieval.HybridRanker;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class GameKnowledgeBase {
    private VectorStore vectorStore;
    private DashScopeEmbedding embeddingService;
    private GameDataLoader dataLoader;
    private double minSimilarity;
    private int maxDocs;
    private double embeddingWeight;
    private double bm25Weight;
    private int rrfK;
    private int candidatePoolSize;
    private volatile Bm25Index bm25Index = new Bm25Index(new ArrayList<GameDocument>());
    private volatile boolean ready;

    public GameKnowledgeBase(VectorStore vectorStore, DashScopeEmbedding embeddingService, GameDataLoader dataLoader, double minSimilarity, int maxDocs) {
        this(vectorStore, embeddingService, dataLoader, minSimilarity, maxDocs,
                0.5, 0.5, 60, 10);
    }

    public GameKnowledgeBase(VectorStore vectorStore, DashScopeEmbedding embeddingService,
                             GameDataLoader dataLoader, double minSimilarity, int maxDocs,
                             double embeddingWeight, double bm25Weight, int rrfK,
                             int candidatePoolSize) {
        this.vectorStore = vectorStore;
        this.embeddingService = embeddingService;
        this.dataLoader = dataLoader;
        this.minSimilarity = minSimilarity;
        this.maxDocs = maxDocs;
        this.embeddingWeight = embeddingWeight;
        this.bm25Weight = bm25Weight;
        this.rrfK = rrfK;
        this.candidatePoolSize = Math.max(maxDocs, candidatePoolSize);
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
            bm25Index = new Bm25Index(documents);
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
        if (!ready) return Collections.emptyList();
        String enhancedQuery = enhanceQueryWithContext(query, playerContext);
        double[] queryEmbedding = embeddingService.generateEmbedding(enhancedQuery);

        String predictedCategory = predictCategory(query);
        List<GameDocument> results = vectorStore.searchByCategory(
                predictedCategory, queryEmbedding, Math.min(5, candidatePoolSize)
        );

        if (results.size() < 3) {
            List<GameDocument> globalResults = vectorStore.searchSimilar(queryEmbedding, candidatePoolSize);
            results.addAll(globalResults);
        }

        List<GameDocument> filtered = results.stream()
                .distinct()
                .filter(doc -> embeddingService.cosineSimilarity(queryEmbedding, doc.getEmbedding()) >= minSimilarity)
                .sorted(Comparator.comparingDouble(doc ->
                        -embeddingService.cosineSimilarity(queryEmbedding, doc.getEmbedding())))
                .limit(candidatePoolSize)
                .collect(Collectors.toList());

        List<HybridRanker.ScoredDocument> embeddingResults = new ArrayList<>();
        for (GameDocument doc : filtered) {
            embeddingResults.add(new HybridRanker.ScoredDocument(doc,
                    embeddingService.cosineSimilarity(queryEmbedding, doc.getEmbedding())));
        }
        String lexicalQuery = query == null ? "" : query;
        if (playerContext != null && !playerContext.trim().isEmpty()) {
            lexicalQuery += "\n" + playerContext.trim();
        }
        List<HybridRanker.ScoredDocument> bm25Results = new ArrayList<>();
        for (Bm25Index.SearchResult result : bm25Index.search(lexicalQuery, candidatePoolSize)) {
            bm25Results.add(new HybridRanker.ScoredDocument(result.getDocument(), result.getScore()));
        }
        return HybridRanker.fuse(embeddingResults, bm25Results, embeddingWeight,
                bm25Weight, rrfK, maxDocs);
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
