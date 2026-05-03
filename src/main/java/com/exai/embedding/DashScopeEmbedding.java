package com.exai.embedding;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DashScopeEmbedding {
    private String apiKey;
    private String baseUrl;
    private String model;
    private int dimensions;
    private Map<String, double[]> cache = new ConcurrentHashMap<>();

    public DashScopeEmbedding(String apiKey) {
        this.apiKey = apiKey;
        this.baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        this.model = "text-embedding-v3";
        this.dimensions = 1024;
    }

    public double[] generateEmbedding(String text) {
        if (cache.containsKey(text)) {
            return cache.get(text);
        }

        String processedText = text.trim();
        if (processedText.length() > 1000) {
            processedText = processedText.substring(0, 1000);
        }

        try {
            OpenAIClient client = OpenAIOkHttpClient.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .build();

            EmbeddingCreateParams params = EmbeddingCreateParams.builder()
                    .model(model)
                    .input(EmbeddingCreateParams.Input.ofString(processedText))
                    .dimensions(dimensions)
                    .build();

            CreateEmbeddingResponse response = client.embeddings().create(params);

            if (response != null && response.data() != null && !response.data().isEmpty()) {
                List<Float> embeddingList = response.data().get(0).embedding();

                double[] embedding = new double[embeddingList.size()];
                for (int i = 0; i < embeddingList.size(); i++) {
                    embedding[i] = embeddingList.get(i);
                }

                embedding = normalize(embedding);
                cache.put(text, embedding);

                return embedding;
            }

        } catch (Exception e) {
            System.err.println("生成嵌入失败: " + e.getMessage());
            e.printStackTrace();
        }

        return new double[dimensions];
    }

    public double cosineSimilarity(double[] vec1, double[] vec2) {
        if (vec1.length != vec2.length) {
            throw new IllegalArgumentException("向量维度不同");
        }

        double dot = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < vec1.length; i++) {
            dot += vec1[i] * vec2[i];
            norm1 += vec1[i] * vec1[i];
            norm2 += vec2[i] * vec2[i];
        }

        if (norm1 == 0 || norm2 == 0) {
            return 0.0;
        }

        return dot / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    private double[] normalize(double[] vector) {
        double sum = 0.0;
        for (double v : vector) {
            sum += v * v;
        }

        double length = Math.sqrt(sum);
        if (length == 0) return vector;

        double[] normalized = new double[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = vector[i] / length;
        }

        return normalized;
    }

    public List<double[]> batchGenerateEmbeddings(List<String> texts) {
        List<double[]> results = new ArrayList<>();
        for (String text : texts) {
            results.add(generateEmbedding(text));
        }
        return results;
    }

    public void clearCache() {
        cache.clear();
    }
}