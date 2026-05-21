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
import com.exai.mysql.MySQL;
import com.exai.service.LLMService;
import com.exai.command.Commands;
import com.exai.utils.DataUtils;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class Config {
    public static FileConfiguration config;
    public static GameKnowledgeBase knowledgeBase;
    public static AnswerGenerator generator;
    public static String address;
    public static String database;
    public static String username;
    public static String password;
    public static String llmBaseUrl;
    public static String llmModel;
    public static double minSimilarity;
    public static String assistantName;
    public static int maxPendingKnowledgePerPlayer;
    public static String currencyName;
    public static boolean chatResponseEnabled;
    public static String chatKeywords;
    public static int chatResponseCD;
    public static String chatResponseSuffix;
    public static String language;

    public static void loadAll() {
        try {
            loadConfig();
            config = ExAI.getInstance().getConfig();
            language = config.getString("language", "zh_CN");
            Lang.load(language);
            address = config.getString("storage-data.address");
            database = config.getString("storage-data.database");
            username = config.getString("storage-data.username");
            password = config.getString("storage-data.password");
            DataContainer.sql = new MySQL(address, database, username, password);
            DataUtils.createTable();
            DataUtils.loadAllPendingKnowledge();

            ExAI.getInstance().getCommand("exai").setExecutor(new Commands());
            ExAI.getInstance().getCommand("exai").setTabCompleter(new Commands());
            String apiKey = config.getString("llm.apiKey");
            llmBaseUrl = config.getString("llm.baseUrl");
            llmModel = config.getString("llm.model");
            chatKeywords = config.getString("llm.chatKeywords", "吗,呢,什么,怎么,如何,为什么,？,?");
            chatResponseCD = config.getInt("llm.chatResponseCD", 60);
            chatResponseEnabled = config.getBoolean("llm.chatResponseEnabled", true);
            chatResponseSuffix = config.getString("llm.chatResponseSuffix", "");
            minSimilarity = config.getDouble("knowledge.minSimilarity", 0.35);
            maxPendingKnowledgePerPlayer = config.getInt("knowledge.maxPendingKnowledgePerPlayer", 30);
            currencyName = config.getString("knowledge.knowledgeReview.rewards.vault.currencyName", "Coin");
            assistantName = config.getString("assistant.name", "ExAI");
            DashScopeEmbedding embeddingService = new DashScopeEmbedding(apiKey);
            VectorStore vectorStore = new VectorStore(embeddingService);
            GameDataLoader dataLoader = new GameDataLoader();
            knowledgeBase = new GameKnowledgeBase(vectorStore, embeddingService, dataLoader, minSimilarity);
            knowledgeBase.initializeKnowledgeBase();
            LLMService llm = new LLMService(apiKey, llmBaseUrl, llmModel);
            generator = new AnswerGenerator(llm);
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
            knowledgeBase = new GameKnowledgeBase(vectorStore, embeddingService, dataLoader, minSimilarity);
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
