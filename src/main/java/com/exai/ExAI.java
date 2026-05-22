package com.exai;

import com.exai.config.Config;
import com.exai.data.DataContainer;
import com.exai.listener.ChatInputListener;
import com.exai.listener.GUIListener;
import com.exai.listener.KnowledgeListener;
import org.bukkit.plugin.java.JavaPlugin;

public final class ExAI extends JavaPlugin {
    private static ExAI instance;
    public static ExAI getInstance() {
        return instance;
    }
    @Override
    public void onEnable() {
        instance = this;
        Config.loadAll();
        getServer().getPluginManager().registerEvents(new ChatInputListener(), this);
        getServer().getPluginManager().registerEvents(new GUIListener(), this);
        getServer().getPluginManager().registerEvents(new KnowledgeListener(), this);
    }

    @Override
    public void onDisable() {
        if (DataContainer.storage != null) {
            DataContainer.storage.shutdown();
        }
    }
}
