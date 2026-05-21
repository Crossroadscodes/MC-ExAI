package com.exai.embedding;

import com.exai.utils.HttpJsonClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DashScopeEmbedding {
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int dimensions;
    private final Map<String, double[]> cache = new ConcurrentHashMap<>();

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
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("input", processedText);
            body.addProperty("dimensions", dimensions);
            body.addProperty("encoding_format", "float");

            JsonObject resp = HttpJsonClient.postJson(
                    baseUrl + "/embeddings", apiKey, body);

            if (resp != null && resp.has("data")) {
                JsonArray data = resp.getAsJsonArray("data");
                if (data.size() > 0) {
                    JsonArray embeddingArr = data.get(0).getAsJsonObject()
                            .getAsJsonArray("embedding");
                    double[] embedding = new double[embeddingArr.size()];
                    for (int i = 0; i < embeddingArr.size(); i++) {
                        embedding[i] = embeddingArr.get(i).getAsDouble();
                    }
                    embedding = normalize(embedding);
                    cache.put(text, embedding);
                    return embedding;
                }
            }
        } catch (Exception e) {
            System.err.println("Embedding generation failed: " + e.getMessage());
            e.printStackTrace();
        }

        return new double[dimensions];
    }

    public double cosineSimilarity(double[] vec1, double[] vec2) {
        if (vec1.length != vec2.length) {
            throw new IllegalArgumentException("Vector dimension mismatch");
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
