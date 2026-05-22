package com.exai.manager;

import com.exai.config.Config;
import com.exai.data.DataContainer;
import com.exai.data.KnowledgeQueue;
import com.exai.entity.KnowledgeEntry;
import com.exai.i18n.Lang;
import com.exai.utils.DataUtils;
import org.bukkit.Bukkit;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KnowledgeManager {
    private static Map<String, String> submitterToUuid = new HashMap<>();

    public static String getTemplateBookContent() {
        return Lang.get("book.template");
    }

    public static String buildBookContent(String question, String answer) {
        return Lang.get("book.pattern-prefix-q") + question + "\n" +
               Lang.get("book.pattern-prefix-a") + answer;
    }

    private static Pattern buildPattern() {
        String q = Pattern.quote(Lang.get("book.pattern-prefix-q"));
        String a = Pattern.quote(Lang.get("book.pattern-prefix-a"));
        return Pattern.compile(q + "(.+?)[\\n ]+" + a + "(.*)", Pattern.DOTALL);
    }

    public static KnowledgeEntry parseAndValidate(String content) {
        Matcher matcher = buildPattern().matcher(content);
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

    public static void approveKnowledge(int page, int index, int pageSize) {
        KnowledgeEntry entry = KnowledgeQueue.getPage(page, pageSize).get(index);
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

    public static void rejectKnowledge(int page, int index, int pageSize) {
        KnowledgeEntry entry = KnowledgeQueue.getPage(page, pageSize).get(index);
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
            KnowledgeQueue.removeByIndex(page, index, pageSize);
        }
    }

    private static void writeToGameHelp(KnowledgeEntry entry) {
        KnowledgeFileManager.append(entry);
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
        return Lang.get("gui.knowledge-question", question) + "\n" + Lang.get("gui.knowledge-answer", answer);
    }
}
