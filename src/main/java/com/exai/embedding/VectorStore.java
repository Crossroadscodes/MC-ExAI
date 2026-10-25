package com.exai.embedding;

import com.exai.entity.GameDocument;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Setter
public class VectorStore {
    private List<GameDocument> documents = new CopyOnWriteArrayList<>();
    private DashScopeEmbedding embeddingService;

    public VectorStore(DashScopeEmbedding embeddingService) {
        this.embeddingService = embeddingService;
    }

    public void addDocument(GameDocument doc) {
        String text = doc.getContent();
        doc.setEmbedding(embeddingService.generateEmbedding(text));
        documents.add(doc);
    }

    public List<GameDocument> searchSimilar(double[] queryEmbedding, int topK) {
        return documents.stream()
                .sorted(Comparator.comparingDouble(doc ->
                        -embeddingService.cosineSimilarity(queryEmbedding, doc.getEmbedding())))
                .limit(topK)
                .collect(Collectors.toList());
    }

    public List<GameDocument> searchByCategory(String category, double[] queryEmbedding, int topK) {
        return documents.stream()
                .filter(doc -> category.equals(doc.getCategory()))
                .sorted(Comparator.comparingDouble(doc ->
                        -embeddingService.cosineSimilarity(queryEmbedding, doc.getEmbedding())))
                .limit(topK)
                .collect(Collectors.toList());
    }

    class SearchResult {
        GameDocument document;
        double similarity;

        SearchResult(GameDocument document, double similarity) {
            this.document = document;
            this.similarity = similarity;
        }
    }

    public List<GameDocument> getAllDocuments() {
        return new ArrayList<>(documents);
    }

    public int size() {
        return documents.size();
    }
}
