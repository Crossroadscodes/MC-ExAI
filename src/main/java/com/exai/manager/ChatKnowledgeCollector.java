package com.exai.manager;

import com.exai.ExAI;
import com.exai.config.Config;
import com.exai.entity.ReviewResult;
import com.exai.i18n.Lang;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公屏聊天问答自动采集器。
 *
 * <p>追踪「玩家A提问 → 玩家B回答 →（提问者A感谢）」的对话流：
 * <ul>
 *   <li>命中问题关键词的消息 → 登记为该玩家的「待回答问题」。</li>
 *   <li>其它玩家随后在时间窗内发出的非问题消息 → 视为候选回答，回答出现即排程一次 AI 初审，
 *       并预留一小段「感谢时间窗」捕捉提问者的致谢。</li>
 *   <li>提问者在感谢窗内说出感谢词 → 立即触发初审并追加加分。</li>
 * </ul>
 * AI 初审通过(且评分达标)的问答会带着评分与「自动采集」来源标记进入待审核队列，交由管理员复核。
 *
 * <p>{@code AsyncPlayerChatEvent} 在异步线程触发，故状态使用 {@link ConcurrentHashMap}，
 * 真正的网络初审与定时任务都交给 Bukkit 调度器异步执行。
 */
public class ChatKnowledgeCollector {

    /** key = 提问者 UUID，仅保留每名玩家最近一条待回答问题。 */
    private static final Map<UUID, OpenQuestion> openQuestions = new ConcurrentHashMap<>();

    private static class OpenQuestion {
        final String askerName;
        final UUID askerUuid;
        final String questionText;
        final long askTime;

        volatile String answerText;
        volatile String answererName;
        volatile long answerTime;
        volatile boolean thanked;
        volatile boolean finalized;
        volatile int reviewTaskId = -1;

        OpenQuestion(String askerName, UUID askerUuid, String questionText, long askTime) {
            this.askerName = askerName;
            this.askerUuid = askerUuid;
            this.questionText = questionText;
            this.askTime = askTime;
        }
    }

    /** 由聊天监听器在异步线程调用。仅读取 player 的名字与 UUID，不持有 Player 引用。 */
    public static void handle(Player player, String rawMessage) {
        if (!Config.autoCollectEnabled || rawMessage == null) {
            return;
        }
        String message = rawMessage.trim();
        if (message.isEmpty()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        long now = System.currentTimeMillis();

        // 1) 提问者对自己问题的回答表达感谢 → 立即初审并加分
        OpenQuestion mine = openQuestions.get(uuid);
        if (mine != null && mine.answerText != null && !mine.finalized
                && isThanks(message) && now - mine.answerTime <= thanksWindowMillis()) {
            mine.thanked = true;
            triggerReview(mine);
            return;
        }

        boolean isQuestion = isQuestion(message);

        // 2) 非问题、非感谢的消息 → 尝试作为别人待回答问题的候选回答
        if (!isQuestion && !isThanks(message) && message.length() >= Config.autoCollectMinAnswerLength) {
            OpenQuestion target = findAnswerableQuestion(uuid, now);
            if (target != null) {
                synchronized (target) {
                    if (target.answerText == null && !target.finalized) {
                        target.answerText = message;
                        target.answererName = name;
                        target.answerTime = now;
                        scheduleReview(target);
                    }
                }
            }
        }

        // 3) 问题消息 → 登记/刷新该玩家的待回答问题
        if (isQuestion) {
            openQuestions.put(uuid, new OpenQuestion(name, uuid, message, now));
        }
    }

    /** 找到一条「来自他人、仍在回答时间窗内、尚无回答」的最新问题。 */
    private static OpenQuestion findAnswerableQuestion(UUID answererUuid, long now) {
        long window = Config.autoCollectAnswerWindow * 1000L;
        OpenQuestion best = null;
        for (OpenQuestion oq : openQuestions.values()) {
            if (oq.finalized || oq.answerText != null) continue;
            if (oq.askerUuid.equals(answererUuid)) continue;
            if (now - oq.askTime > window) continue;
            if (best == null || oq.askTime > best.askTime) {
                best = oq;
            }
        }
        return best;
    }

    /** 回答出现后排程一次初审，给感谢留出时间窗；窗口结束仍未感谢则照常初审。 */
    private static void scheduleReview(OpenQuestion oq) {
        long delayTicks = Math.max(1L, Config.autoCollectThanksWindow * 20L);
        int taskId = Bukkit.getScheduler().runTaskLaterAsynchronously(
                ExAI.getInstance(), () -> triggerReview(oq), delayTicks).getTaskId();
        oq.reviewTaskId = taskId;
    }

    /** 触发一次初审（幂等）：感谢路径与定时器路径都可能调用，用 finalized 防止重复处理。 */
    private static void triggerReview(OpenQuestion oq) {
        synchronized (oq) {
            if (oq.finalized) return;
            oq.finalized = true;
        }
        if (oq.reviewTaskId != -1) {
            Bukkit.getScheduler().cancelTask(oq.reviewTaskId);
        }
        openQuestions.remove(oq.askerUuid, oq);
        Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), () -> runReview(oq));
    }

    private static void runReview(OpenQuestion oq) {
        try {
            if (Config.reviewer == null || oq.answerText == null) {
                return;
            }
            ReviewResult result = Config.reviewer.review(oq.questionText, oq.answerText, oq.thanked);
            // AI 不可用(null) 或判定不通过 → 静默丢弃，不污染知识库
            if (result == null || !result.isPass()) {
                String reason = result == null ? "service unavailable" : result.getReason();
                ExAI.getInstance().getLogger().info(
                        Lang.get("log.auto-rejected", preview(oq.questionText), reason));
                return;
            }
            boolean added = KnowledgeManager.submitAutoCollected(
                    oq.questionText, oq.answerText, oq.answererName, oq.thanked);
            if (added) {
                ExAI.getInstance().getLogger().info(
                        Lang.get("log.auto-collected", preview(oq.questionText)));
                notifyReviewers();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 在主线程提示在线审核员有新采集条目待复核。 */
    private static void notifyReviewers() {
        if (!Config.autoCollectNotifyReviewers) {
            return;
        }
        String permission = Config.config.getString("knowledge.knowledgeReview.opPermission", "exai.op");
        String msg = Lang.get("chat.auto-collect-notify", Config.assistantName);
        Bukkit.getScheduler().runTask(ExAI.getInstance(), () -> {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.hasPermission(permission)) {
                    online.sendMessage(msg);
                }
            }
        });
    }

    private static long thanksWindowMillis() {
        return Config.autoCollectThanksWindow * 1000L;
    }

    private static boolean isQuestion(String message) {
        for (String keyword : Config.chatKeywords.split(",")) {
            String kw = keyword.trim();
            if (!kw.isEmpty() && message.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isThanks(String message) {
        String lower = message.toLowerCase();
        for (String keyword : Config.autoCollectThanksKeywords.split(",")) {
            String kw = keyword.trim().toLowerCase();
            if (!kw.isEmpty() && lower.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private static String preview(String text) {
        if (text == null) return "";
        return text.length() > 20 ? text.substring(0, 20) + "..." : text;
    }
}
