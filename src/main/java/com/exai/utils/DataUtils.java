package com.exai.utils;

import com.exai.ExAI;
import com.exai.data.DataContainer;
import com.exai.entity.KnowledgeEntry;
import com.exai.entity.LogEntry;
import com.exai.entity.PendingReward;
import org.bukkit.Bukkit;

import java.util.Collections;
import java.util.List;

public class DataUtils {

    public static void initialize() {
        if (DataContainer.storage != null) {
            DataContainer.storage.initialize();
        }
    }

    public static void createTable() {
        initialize();
    }

    public static void loadAllPendingKnowledge() {
        if (DataContainer.storage != null) {
            DataContainer.storage.loadAllPendingKnowledge();
        }
    }

    public static void loadAllPendingKnowledgeAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), DataUtils::loadAllPendingKnowledge);
    }

    public static int getPendingCount(String uuid) {
        return DataContainer.storage == null ? 0 : DataContainer.storage.getPendingCount(uuid);
    }

    public static void addPendingCount(String uuid, String playerName) {
        if (DataContainer.storage != null) {
            DataContainer.storage.addPendingCount(uuid, playerName);
        }
    }

    public static void addPendingCountAsync(String uuid, String playerName) {
        Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(),
                () -> addPendingCount(uuid, playerName));
    }

    public static void subPendingCount(String uuid) {
        if (DataContainer.storage != null) {
            DataContainer.storage.subPendingCount(uuid);
        }
    }

    public static void subPendingCountAsync(String uuid) {
        Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), () -> subPendingCount(uuid));
    }

    public static int insertPendingKnowledge(KnowledgeEntry entry) {
        return DataContainer.storage == null ? -1 : DataContainer.storage.insertPendingKnowledge(entry);
    }

    public static void deletePendingKnowledge(int id) {
        if (DataContainer.storage != null) {
            DataContainer.storage.deletePendingKnowledge(id);
        }
    }

    public static void deletePendingKnowledgeAsync(int id) {
        Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), () -> deletePendingKnowledge(id));
    }

    public static boolean isPendingKnowledgeDuplicate(String question) {
        return DataContainer.storage != null && DataContainer.storage.isPendingKnowledgeDuplicate(question);
    }

    public static void insertLog(String playerName, String playerInput, String aiResponse,
                                 String documentId, String source) {
        if (DataContainer.storage != null) {
            DataContainer.storage.insertLog(playerName, playerInput, aiResponse, documentId, source);
        }
    }

    public static void insertLogAsync(String playerName, String playerInput, String aiResponse,
                                      String documentId, String source) {
        Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(),
                () -> insertLog(playerName, playerInput, aiResponse, documentId, source));
    }

    public static int getLogTotalCount() {
        return DataContainer.storage == null ? 0 : DataContainer.storage.getLogTotalCount();
    }

    public static List<LogEntry> getLogPage(int page, int pageSize) {
        return DataContainer.storage == null ? Collections.emptyList()
                : DataContainer.storage.getLogPage(page, pageSize);
    }

    public static void deleteLog(int id) {
        if (DataContainer.storage != null) {
            DataContainer.storage.deleteLog(id);
        }
    }

    public static void deleteLogAsync(int id) {
        Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), () -> deleteLog(id));
    }

    public static void addPendingRewards(String playerName, List<String> items, List<String> messages) {
        if (DataContainer.storage != null) {
            DataContainer.storage.addPendingRewards(playerName, items, messages);
        }
    }

    public static void addPendingRewardsAsync(String playerName, List<String> items, List<String> messages) {
        Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(),
                () -> addPendingRewards(playerName, items, messages));
    }

    public static PendingReward takePendingRewards(String playerName) {
        return DataContainer.storage == null
                ? new PendingReward(Collections.emptyList(), Collections.emptyList())
                : DataContainer.storage.takePendingRewards(playerName);
    }
}
