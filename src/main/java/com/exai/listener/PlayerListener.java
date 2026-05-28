package com.exai.listener;

import com.exai.ExAI;
import com.exai.config.Config;
import com.exai.entity.Answer;
import com.exai.entity.PlayerQuestion;
import com.exai.i18n.Lang;
import com.exai.manager.ChatKnowledgeCollector;
import com.exai.utils.CDUtils;
import com.exai.utils.DataUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class PlayerListener implements Listener {
    private static boolean isRegistered = false;

    public static void registerIfNeeded(JavaPlugin plugin) {
        if (!isRegistered) {
            Bukkit.getPluginManager().registerEvents(new PlayerListener(), plugin);
            isRegistered = true;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();
        String message = event.getMessage();

        // 公屏问答自动采集独立于 AI 广播开关运行
        ChatKnowledgeCollector.handle(player, message);

        if (!Config.chatResponseEnabled) {
            return;
        }

        if (!CDUtils.isPlayerChatCDEnd()) {
            return;
        }
        if (!isQuestion(message)) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), () -> {
            try {
                PlayerQuestion pQuestion = new PlayerQuestion(message);
                Answer answer = Config.generator.generateBrodcastAnswer(pQuestion, false);
                // AI 无法作答（知识库无依据）→ 不在公屏广播，避免刷屏「抱歉，找不到相关信息」
                if (answer.isUnknown()) {
                    return;
                }
                String documents = String.join(", ", answer.getSources());
                Bukkit.broadcastMessage(Lang.get("chat.broadcast",
                        Config.assistantName, playerName, answer.getAnswer(),
                        Config.chatResponseSuffix == null ? "" : Config.chatResponseSuffix));
                DataUtils.insertLog(playerName, message, answer.getAnswer(), documents, Lang.get("log.source-broadcast"));

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private boolean isQuestion(String message) {
        String[] keywords = Config.chatKeywords.split(",");
        for (String keyword : keywords) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
