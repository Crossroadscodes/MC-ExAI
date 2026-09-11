package com.exai;

import com.exai.config.Config;
import com.exai.data.DataContainer;
import com.exai.listener.ChatInputListener;
import com.exai.listener.GUIListener;
import com.exai.listener.KnowledgeListener;
import com.exai.manager.KnowledgeFileManager;
import com.exai.manager.PluginDescriptionManager;
import com.exai.service.DocumentImportService;
import com.exai.web.WebServer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class ExAI extends JavaPlugin {
    private static ExAI instance;
    public static ExAI getInstance() {
        return instance;
    }
    @Override
    public void onEnable() {
        instance = this;
        // 在知识库加载前，把旧位置 knowledge.yml 迁移到 knowledge/ 目录
        KnowledgeFileManager.migrateLegacyLocation();
        Config.loadAll();
        DocumentImportService.importFolder();
        getServer().getPluginManager().registerEvents(new ChatInputListener(), this);
        getServer().getPluginManager().registerEvents(new GUIListener(), this);
        getServer().getPluginManager().registerEvents(new KnowledgeListener(), this);
        // Web 服务的启动/停止由 Config.loadAll() -> WebServer.apply() 统一管理（reload 也生效）
        // 延迟到其它插件都已启用后扫描，刷新 pluginDesc.yml（新插件描述为空，不影响 RAG）
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                PluginDescriptionManager.scanAndMerge();
            } catch (Exception e) {
                getLogger().warning("启动扫描插件失败: " + e.getMessage());
            }
        }, 40L);
    }

    @Override
    public void onDisable() {
        WebServer.stop();
        if (DataContainer.storage != null) {
            DataContainer.storage.shutdown();
        }
    }
}
