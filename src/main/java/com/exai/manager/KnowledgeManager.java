package com.exai.manager;

import com.exai.ExAI;
import com.exai.config.Config;
import com.exai.data.DataContainer;
import com.exai.data.KnowledgeQueue;
import com.exai.entity.KnowledgeEntry;
import com.exai.utils.DataUtils;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KnowledgeManager {
    private static final Pattern KNOWLEDGE_PATTERN = Pattern.compile("问：(.+?)[\\n ]+答：(.*)");
    private static Map<String, String> submitterToUuid = new HashMap<>();

    public static String getTemplateBookContent() {
        return "问：请在这里输入您的问题\n答：请在这里输入答案";
    }

    public static KnowledgeEntry parseAndValidate(String content) {
        Matcher matcher = KNOWLEDGE_PATTERN.matcher(content);
        if (matcher.find()) {
            String question = matcher.group(1).trim();
            String answer = matcher.group(2).trim();
            if (!question.isEmpty() && !answer.isEmpty()) {
                return new KnowledgeEntry(question, answer, "", System.currentTimeMillis());
            }
        }
        return null;
    }

    public static boolean submitKnowledge(KnowledgeEntry entry, String playerName) {
        entry.setSubmitter(playerName);

        if (KnowledgeQueue.isDuplicate(entry.getQuestion()) || DataUtils.isPendingKnowledgeDuplicate(entry.getQuestion())) {
            return false;
        }

        String playerUuid = Bukkit.getPlayer(playerName).getUniqueId().toString();
        if (DataContainer.playerPendingKnowledgeCount.getOrDefault(playerUuid, 0) >= Config.maxPendingKnowledgePerPlayer) {
            return false;
        }

        int dbId = DataUtils.insertPendingKnowledge(entry);
        if (dbId == -1) {
            return false;
        }

        submitterToUuid.put(playerName, playerUuid);
        DataContainer.playerPendingKnowledgeCount.merge(playerUuid, 1, Integer::sum);
        DataUtils.addPendingCountAsync(playerUuid, playerName);

        KnowledgeQueue.addWithId(entry, dbId);
        return true;
    }

    public static void approveKnowledge(int page, int index) {
        KnowledgeEntry entry = KnowledgeQueue.getPage(page, 18).get(index);
        if (entry != null) {
            int dbId = KnowledgeQueue.getDbId(entry);
            DataUtils.deletePendingKnowledgeAsync(dbId);
            String submitterName = entry.getSubmitter();
            String submitterUuid = submitterToUuid.get(submitterName);
            if (submitterUuid != null) {
                DataContainer.playerPendingKnowledgeCount.merge(submitterUuid, -1, (a, b) -> Math.max(0, a + b));
                DataUtils.subPendingCountAsync(submitterUuid);
                submitterToUuid.remove(submitterName);
            }
            writeToGameHelp(entry);
            KnowledgeQueue.remove(entry);
        }
    }

    public static void rejectKnowledge(int page, int index) {
        KnowledgeEntry entry = KnowledgeQueue.getPage(page, 18).get(index);
        if (entry != null) {
            int dbId = KnowledgeQueue.getDbId(entry);
            DataUtils.deletePendingKnowledgeAsync(dbId);
            String submitterName = entry.getSubmitter();
            String submitterUuid = submitterToUuid.get(submitterName);
            if (submitterUuid != null) {
                DataContainer.playerPendingKnowledgeCount.merge(submitterUuid, -1, (a, b) -> Math.max(0, a + b));
                DataUtils.subPendingCountAsync(submitterUuid);
                submitterToUuid.remove(submitterName);
            }
            KnowledgeQueue.removeByIndex(page, index, 18);
        }
    }

    private static void writeToGameHelp(KnowledgeEntry entry) {
        File gameHelpFile = new File(ExAI.getInstance().getDataFolder(), "gamehelp.txt");
        try (FileWriter writer = new FileWriter(gameHelpFile, true)) {
            writer.write("问：" + entry.getQuestion() + "\n");
            writer.write("答：" + entry.getAnswer() + "\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static String formatKnowledgeForDisplay(KnowledgeEntry entry) {
        String question = entry.getQuestion();
        String answer = entry.getAnswer();
        if (question.length() > 15) {
            question = question.substring(0, 15) + "...";
        }
        if (answer.length() > 15) {
            answer = answer.substring(0, 15) + "...";
        }
        return "§e问：§f" + question + "\n§e答：§f" + answer;
    }
}