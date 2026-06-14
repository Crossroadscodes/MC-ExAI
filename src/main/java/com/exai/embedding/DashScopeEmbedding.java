package com.exai.embedding;

import com.exai.ExAI;
import com.exai.i18n.Lang;
import com.exai.utils.HttpJsonClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DashScopeEmbedding {
    /** 嵌入缓存文件魔数("EXAI")与版本，用于校验文件归属并在格式变化时失效旧缓存。 */
    private static final int CACHE_MAGIC = 0x45584149;
    private static final int CACHE_VERSION = 1;
    /** 嵌入缓存存放的专用子目录与文件名（plugins/ExAI/cache/embeddings.cache）。 */
    public static final String CACHE_DIR_NAME = "cache";
    public static final String CACHE_FILE_NAME = "embeddings.cache";

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int dimensions;
    private final Map<String, double[]> cache = new ConcurrentHashMap<>();
    /** 持久化缓存文件；为 null 时仅用内存缓存(不落盘)。 */
    private final File cacheFile;

    public DashScopeEmbedding(String apiKey) {
        this(apiKey, defaultCacheFile());
    }

    public DashScopeEmbedding(String apiKey, File cacheFile) {
        this.apiKey = apiKey;
        this.baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        this.model = "text-embedding-v3";
        this.dimensions = 1024;
        this.cacheFile = cacheFile;
        loadCache();
    }

    private static File defaultCacheFile() {
        try {
            File dir = new File(ExAI.getInstance().getDataFolder(), CACHE_DIR_NAME);
            return new File(dir, CACHE_FILE_NAME);
        } catch (Exception e) {
            return null; // 插件实例不可用(如测试环境)时退化为纯内存缓存
        }
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

    /** 启动/重载时从磁盘读取已缓存的嵌入向量到内存，避免对未变更知识重复调用嵌入接口。 */
    private synchronized void loadCache() {
        if (cacheFile == null || !cacheFile.exists()) {
            return;
        }
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(cacheFile)))) {
            if (in.readInt() != CACHE_MAGIC || in.readInt() != CACHE_VERSION) {
                return; // 非本插件文件或版本不符 → 忽略，后续重建
            }
            String fileModel = readString(in);
            int fileDim = in.readInt();
            if (!model.equals(fileModel) || fileDim != dimensions) {
                return; // 模型或维度变化 → 旧向量不可用，忽略后重建
            }
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                String text = readString(in);
                double[] vec = new double[dimensions];
                for (int j = 0; j < dimensions; j++) {
                    vec[j] = in.readFloat();
                }
                cache.put(text, vec);
            }
            ExAI.getInstance().getLogger().info(Lang.get("log.embedding-cache-loaded", count));
        } catch (Exception e) {
            // 文件损坏/截断：忽略缓存，本次全部重新生成并覆盖写回
            ExAI.getInstance().getLogger().warning("Embedding cache load failed: " + e.getMessage());
        }
    }

    /**
     * 把当前知识库文档对应的嵌入向量写回磁盘缓存。
     * 只持久化 {@code keysToKeep} 中的文本(即活跃知识文档)，从而自动剔除一次性问句向量与已删除的旧文档。
     */
    public synchronized void persistCache(Collection<String> keysToKeep) {
        if (cacheFile == null) {
            return;
        }
        try {
            File parent = cacheFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            List<String> valid = new ArrayList<>();
            for (String key : keysToKeep) {
                double[] vec = cache.get(key);
                if (vec != null && vec.length == dimensions) {
                    valid.add(key);
                }
            }
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(new FileOutputStream(cacheFile)))) {
                out.writeInt(CACHE_MAGIC);
                out.writeInt(CACHE_VERSION);
                writeString(out, model);
                out.writeInt(dimensions);
                out.writeInt(valid.size());
                for (String key : valid) {
                    writeString(out, key);
                    double[] vec = cache.get(key);
                    for (int j = 0; j < dimensions; j++) {
                        out.writeFloat((float) vec[j]);
                    }
                }
            }
            ExAI.getInstance().getLogger().info(Lang.get("log.embedding-cache-saved", valid.size()));
        } catch (Exception e) {
            ExAI.getInstance().getLogger().warning("Embedding cache save failed: " + e.getMessage());
        }
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = in.readInt();
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }
}
