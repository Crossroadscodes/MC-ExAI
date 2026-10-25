package com.exai.listener;

import com.exai.ExAI;
import com.exai.config.Config;
import com.exai.entity.Answer;
import com.exai.entity.PlayerQuestion;
import com.exai.entity.RagStatus;
import com.exai.gui.GUIManager;
import com.exai.gui.RagAnnotationGUI;
import com.exai.i18n.Lang;
import com.exai.service.QuestionClassifier;
import com.exai.utils.CDUtils;
import com.exai.utils.DataUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class ChatInputListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onRagAnnotationChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!RagAnnotationGUI.isAwaitingInput(player)) {
            return;
        }
        event.setCancelled(true);
        RagAnnotationGUI.acceptChatInput(player, event.getMessage());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        if (GUIManager.isInDialogueMode(player)) {
            event.setCancelled(true);

            PlayerQuestion pQuestion = new PlayerQuestion(message);
            String playerName = player.getName();
            player.sendMessage(Lang.get("chat.player-prefix", playerName, message));

            Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), () -> {
                QuestionClassifier.Result classification = new QuestionClassifier(Config.llm).classify(message);
                String documents;
                String answerText;
                RagStatus ragStatus;
                if (classification == QuestionClassifier.Result.NOT_QUESTION) {
                    answerText = Lang.get("chat.not-a-question-empty");
                    documents = "";
                    ragStatus = RagStatus.NOT_QUESTION;
                } else if (classification == QuestionClassifier.Result.UNAVAILABLE) {
                    answerText = Lang.get("chat.question-classifier-unavailable");
                    documents = "";
                    ragStatus = RagStatus.MODEL_ERROR;
                } else {
                    Answer answer = Config.generator.generateAnswer(pQuestion, false);
                    documents = String.join(", ", answer.getSources());
                    answerText = answer.getAnswer();
                    ragStatus = answer.getStatus();
                }
                Bukkit.getScheduler().runTask(ExAI.getInstance(), () -> {
                    player.sendMessage(Lang.get("chat.response", Config.assistantName, answerText));
                    DataUtils.insertLogAsync(playerName, message, answerText, documents,
                            Lang.get("log.source-private"), ragStatus);
                    if (ragStatus == RagStatus.ANSWERED) {
                        CDUtils.startDialogueCD(player);
                    }
                    GUIManager.exitDialogueMode(player);
                });
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        RagAnnotationGUI.clear(event.getPlayer());
        GUIManager.exitDialogueMode(event.getPlayer());
    }
}
