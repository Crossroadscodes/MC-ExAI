package com.exai.config;

import com.exai.ExAI;
import com.exai.data.DataContainer;
import com.exai.embedding.DashScopeEmbedding;
import com.exai.embedding.VectorStore;
import com.exai.generators.AnswerGenerator;
import com.exai.i18n.Lang;
import com.exai.listener.PlayerListener;
import com.exai.managers.GameDataLoader;
import com.exai.managers.GameKnowledgeBase;
import com.exai.service.LLMService;
import com.exai.service.KnowledgeReviewService;
import com.exai.command.Commands;
import com.exai.storage.MysqlStorage;
import com.exai.storage.YamlStorage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class Config {
    public static FileConfiguration config;
    public static GameKnowledgeBase knowledgeBase;
    public static AnswerGenerator generator;
    public static String storageType;
    public static String address;
    public static String database;
    public static String username;
    public static String password;
    public static String llmBaseUrl;
    public static String llmModel;
    public static double llmTemperature;
    public static double minSimilarity;
    public static int maxDocs;
    public static String assistantName;
    public static int maxPendingKnowledgePerPlayer;
    public static String currencyName;
    public static String opPermission;
    public static boolean chatResponseEnabled;
    public static String chatKeywords;
    public static int chatResponseCD;
    public static String chatResponseSuffix;
    public static String language;
    // 公屏问答自动采集
    public static KnowledgeReviewService reviewer;
    public static boolean autoCollectEnabled;
    public static int autoCollectAnswerWindow;
    public static int autoCollectThanksWindow;
    public static int autoCollectMinAnswerLength;
    public static String autoCollectThanksKeywords;
    public static boolean autoCollectNotifyReviewers;
    // 玩家书本提交的 AI 初审
    public static boolean playerSubmitReviewEnabled;
    // 文档导入
    public static LLMService llm;
    public static int documentImportChunkSize;
    public static int documentImportMaxTokens;
    public static double documentImportTemperature;
    // 图片导入用的视觉模型（为空则不支持图片导入）；复用 llm 的 apiKey/baseUrl
    public static String documentImportVisionModel;
    public static LLMService visionLlm;

    public static void loadAll() {
        try {
            loadConfig();
            config = ExAI.getInstance().getConfig();
            language = config.getString("language", "zh_CN");
            Lang.load(language);

            storageType = config.getString("storage.type", "mysql").toLowerCase();
            address = config.getString("storage-data.address");
            database = config.getString("storage-data.database");
            username = config.getString("storage-data.username");
            password = config.getString("storage-data.password");

            if (DataContainer.storage != null) {
                DataContainer.storage.shutdown();
                DataContainer.storage = null;
                DataContainer.sql = null;
            }

            if ("yml".equals(storageType)) {
                DataContainer.storage = new YamlStorage();
                ExAI.getInstance().getLogger().info(Lang.get("log.storage-mode-yml"));
            } else {
                DataContainer.storage = new MysqlStorage();
                ExAI.getInstance().getLogger().info(Lang.get("log.storage-mode-mysql"));
            }
            DataContainer.storage.initialize();

            ExAI.getInstance().getCommand("exai").setExecutor(new Commands());
            ExAI.getInstance().getCommand("exai").setTabCompleter(new Commands());
            String apiKey = config.getString("llm.apiKey");
            llmBaseUrl = config.getString("llm.baseUrl");
            llmModel = config.getString("llm.model");
            llmTemperature = config.getDouble("llm.temperature", 0.3);
            chatKeywords = config.getString("llm.chatKeywords", "吗,呢,什么,怎么,如何,为什么,？,?");
            chatResponseCD = config.getInt("llm.chatResponseCD", 60);
            chatResponseEnabled = config.getBoolean("llm.chatResponseEnabled", true);
            chatResponseSuffix = config.getString("llm.chatResponseSuffix", "");
            minSimilarity = config.getDouble("knowledge.minSimilarity", 0.35);
            maxDocs = config.getInt("knowledge.maxDocs", 3);
            maxPendingKnowledgePerPlayer = config.getInt("knowledge.maxPendingKnowledgePerPlayer", 30);
            currencyName = config.getString("knowledge.knowledgeReview.rewards.vault.currencyName", "Coin");
            opPermission = config.getString("knowledge.knowledgeReview.opPermission", "exai.op");
            autoCollectEnabled = config.getBoolean("knowledge.autoCollect.enabled", true);
            autoCollectAnswerWindow = config.getInt("knowledge.autoCollect.answerWindowSeconds", 60);
            autoCollectThanksWindow = config.getInt("knowledge.autoCollect.thanksWindowSeconds", 20);
            autoCollectMinAnswerLength = config.getInt("knowledge.autoCollect.minAnswerLength", 4);
            autoCollectThanksKeywords = config.getString("knowledge.autoCollect.thanksKeywords",
                    "谢谢,感谢,thx,thanks,3q,谢了,多谢,懂了,明白了,学到了,解决了,有用");
            autoCollectNotifyReviewers = config.getBoolean("knowledge.autoCollect.notifyReviewers", true);
            playerSubmitReviewEnabled = config.getBoolean("knowledge.playerSubmitReview.enabled", true);
            documentImportChunkSize = config.getInt("knowledge.documentImport.chunkSize", 1500);
            documentImportMaxTokens = config.getInt("knowledge.documentImport.maxTokens", 1500);
            documentImportTemperature = config.getDouble("knowledge.documentImport.temperature", 0.3);
            documentImportVisionModel = config.getString("knowledge.documentImport.visionModel", "").trim();
            assistantName = config.getString("assistant.name", "ExAI");
            DashScopeEmbedding embeddingService = new DashScopeEmbedding(apiKey);
            VectorStore vectorStore = new VectorStore(embeddingService);
            GameDataLoader dataLoader = new GameDataLoader();
            knowledgeBase = new GameKnowledgeBase(vectorStore, embeddingService, dataLoader, minSimilarity, maxDocs);
            knowledgeBase.initializeKnowledgeBase();
            llm = new LLMService(apiKey, llmBaseUrl, llmModel, llmTemperature);
            visionLlm = documentImportVisionModel.isEmpty()
                    ? null
                    : new LLMService(apiKey, llmBaseUrl, documentImportVisionModel, documentImportTemperature);
            generator = new AnswerGenerator(llm);
            reviewer = new KnowledgeReviewService(llm);
            PlayerListener.registerIfNeeded(ExAI.getInstance());
            ExAI.getInstance().getLogger().info(Lang.get("log.enable-success"));
        } catch (Exception e) {
            ExAI.getInstance().getLogger().info(Lang.get("log.enable-fail"));
            e.printStackTrace();
        }
    }

    public static void loadConfig() {
        ExAI.getInstance().saveDefaultConfig();
        ExAI.getInstance().reloadConfig();
    }

    public static void reloadKnowledgeBaseOnly() {
        try {
            String apiKey = config.getString("llm.apiKey");
            DashScopeEmbedding embeddingService = new DashScopeEmbedding(apiKey);
            VectorStore vectorStore = new VectorStore(embeddingService);
            GameDataLoader dataLoader = new GameDataLoader();
            knowledgeBase = new GameKnowledgeBase(vectorStore, embeddingService, dataLoader, minSimilarity, maxDocs);
            knowledgeBase.initializeKnowledgeBase();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static YamlConfiguration loadConfigYaml(JavaPlugin plugin, String name) {
        File file = new File(plugin.getDataFolder(), name + ".yml");

        if (!file.exists()) {
            plugin.saveResource(name + ".yml", false);
        }

        return YamlConfiguration.loadConfiguration(file);
    }
}
