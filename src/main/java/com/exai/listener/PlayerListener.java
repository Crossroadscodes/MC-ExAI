package com.exai.listener;

import com.exai.ExAI;
import com.exai.config.Config;
import com.exai.data.KnowledgeQueue;
import com.exai.entity.Answer;
import com.exai.entity.PlayerQuestion;
import com.exai.gui.GUIManager;
import com.exai.i18n.Lang;
import com.exai.manager.ChatKnowledgeCollector;
import com.exai.manager.RewardManager;
import com.exai.service.QuestionClassifier;
import com.exai.utils.CDUtils;
import com.exai.utils.DataUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class PlayerListener implements Listener {
    private static boolean isRegistered = false;

    public static void registerIfNeeded(JavaPlugin plugin) {
        if (!isRegistered) {
            Bukkit.getPluginManager().registerEvents(new PlayerListener(), plugin);
            isRegistered = true;
            plugin.getLogger().info("[ReviewerNotify] PlayerJoin listener registered; permission="
                    + Config.opPermission + ", delayTicks=40");
        } else {
            plugin.getLogger().info("[ReviewerNotify] PlayerJoin listener already registered; skip duplicate registration");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();
        String message = event.getMessage();

        // GUI 私聊消息由 ChatInputListener 独占处理，不能再次进入公屏回答或自动采集。
        if (GUIManager.isInDialogueMode(player)) {
            return;
        }

        // 公屏问答自动采集独立于 AI 广播开关运行
        ChatKnowledgeCollector.handle(player, message);

        if (!Config.chatResponseEnabled) {
            return;
        }

        if (!Config.isLlmConfigured()) {
            return;
        }

        // 关键词只做低成本初筛，不能直接据此认定为问题。
        if (!containsQuestionKeyword(message)) {
            return;
        }
        // 普通聊天不占用公屏回答冷却；这里只读检查，真正回答成功后才启动冷却。
        if (!CDUtils.isPlayerChatCDEnd()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), () -> {
            try {
                QuestionClassifier.Result classification = new QuestionClassifier(Config.llm).classify(message);
                if (classification != QuestionClassifier.Result.QUESTION) {
                    return;
                }
                // 分类期间可能已有其它问题成功广播，再次检查可避免无意义的 RAG 调用。
                if (!CDUtils.isPlayerChatCDEnd()) {
                    return;
                }
                PlayerQuestion pQuestion = new PlayerQuestion(message);
                Answer answer = Config.generator.generateBrodcastAnswer(pQuestion, false);
                // AI 无法作答（知识库无依据）→ 不在公屏广播，避免刷屏「抱歉，找不到相关信息」
                if (answer.isUnknown()) {
                    return;
                }
                // 多个问题可能并发生成答案；只有第一个有效答案获得广播资格并启动全服冷却。
                if (!CDUtils.tryStartPlayerChatCD()) {
                    return;
                }
                String documents = String.join(", ", answer.getSources());
                Bukkit.broadcastMessage(Lang.get("chat.broadcast",
                        Config.assistantName, playerName, answer.getAnswer(),
                        Config.chatResponseSuffix == null ? "" : Config.chatResponseSuffix));
                DataUtils.insertLog(playerName, message, answer.getAnswer(), documents,
                        Lang.get("log.source-broadcast"), answer.getStatus());

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        ExAI.getInstance().getLogger().info("[ReviewerNotify] Join event received: player="
                + player.getName() + ", uuid=" + player.getUniqueId()
                + ", isOp=" + player.isOp() + ", permission=" + Config.opPermission
                + ", hasPermission=" + player.hasPermission(Config.opPermission));
        // 延迟发放，确保提示在登录刷屏之后才出现
        Bukkit.getScheduler().runTaskLater(ExAI.getInstance(), () -> {
            // 玩家若已离线则跳过，避免奖励被取出却发放失败而丢失
            if (!player.isOnline()) {
                ExAI.getInstance().getLogger().info("[ReviewerNotify] Skip notification: player="
                        + player.getName() + " is offline after the 40-tick delay");
                return;
            }
            // 发放离线期间累计的知识采纳奖励
            try {
                RewardManager.deliverQueued(player);
            } catch (Exception e) {
                ExAI.getInstance().getLogger().warning("[ReviewerNotify] Queued reward delivery failed for "
                        + player.getName() + "; reviewer notification will continue: " + e.getMessage());
                e.printStackTrace();
            }
            // 提醒审核员有多少待审核知识
            boolean isOp = player.isOp();
            boolean hasNode = player.hasPermission(Config.opPermission);
            boolean reviewer = Config.hasOpPermission(player);
            int pending = KnowledgeQueue.getTotalCount();
            ExAI.getInstance().getLogger().info("[ReviewerNotify] Delayed check: player="
                    + player.getName() + ", online=true, isOp=" + isOp
                    + ", permission=" + Config.opPermission + ", hasPermission=" + hasNode
                    + ", reviewer=" + reviewer + ", pending=" + pending);

            if (!reviewer) {
                ExAI.getInstance().getLogger().info("[ReviewerNotify] Skip notification: player="
                        + player.getName() + " is neither OP nor granted " + Config.opPermission);
                return;
            }
            if (pending <= 0) {
                ExAI.getInstance().getLogger().info("[ReviewerNotify] Skip notification: pending count is "
                        + pending + " for player=" + player.getName());
                return;
            }

            String notification = Lang.get("chat.reviewer-login-notify", Config.assistantName, pending);
            player.sendMessage(notification);
            ExAI.getInstance().getLogger().info("[ReviewerNotify] Notification sent: player="
                    + player.getName() + ", pending=" + pending + ", message="
                    + ChatColor.stripColor(notification));
        }, 40L);
    }

    private boolean containsQuestionKeyword(String message) {
        if (message == null || Config.chatKeywords == null) {
            return false;
        }
        String[] keywords = Config.chatKeywords.split(",");
        for (String rawKeyword : keywords) {
            String keyword = rawKeyword.trim();
            if (!keyword.isEmpty() && message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
