package com.exai.listener;

import com.exai.ExAI;
import com.exai.config.Config;
import com.exai.entity.Answer;
import com.exai.entity.PlayerQuestion;
import com.exai.gui.GUIManager;
import com.exai.i18n.Lang;
import com.exai.manager.KnowledgeFileManager;
import com.exai.service.QuestionClassifier;
import com.exai.utils.CDUtils;
import com.exai.utils.DataUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class ChatInputListener implements Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        if (GUIManager.isInDialogueMode(player)) {
            event.setCancelled(true);

            PlayerQuestion pQuestion = new PlayerQuestion(message);
            String playerName = player.getName();
            player.sendMessage(Lang.get("chat.player-prefix", playerName, message));

            Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), () -> {
                Boolean isQuestion = new QuestionClassifier(Config.llm).isQuestion(message);
                String documents;
                String answerText;
                if (Boolean.FALSE.equals(isQuestion)) {
                    List<com.exai.entity.KnowledgeEntry> entries = KnowledgeFileManager.readAll();
                    if (entries.isEmpty()) {
                        answerText = Lang.get("chat.not-a-question-empty");
                    } else {
                        com.exai.entity.KnowledgeEntry example = entries.get(
                                ThreadLocalRandom.current().nextInt(entries.size()));
                        answerText = Lang.get("chat.not-a-question", example.getQuestion());
                    }
                    documents = "";
                } else {
                    Answer answer = Config.generator.generateAnswer(pQuestion, false);
                    documents = String.join(", ", answer.getSources());
                    answerText = answer.getAnswer();
                }
                Bukkit.getScheduler().runTask(ExAI.getInstance(), () -> {
                    player.sendMessage(Lang.get("chat.response", Config.assistantName, answerText));
                    DataUtils.insertLog(playerName, message, answerText, documents, Lang.get("log.source-private"));
                    CDUtils.startDialogueCD(player);
                    GUIManager.exitDialogueMode(player);
                });
            });
        }
    }
}
