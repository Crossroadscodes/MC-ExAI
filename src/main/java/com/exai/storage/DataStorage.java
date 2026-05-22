package com.exai.storage;

import com.exai.entity.KnowledgeEntry;
import com.exai.entity.LogEntry;

import java.util.List;

public interface DataStorage {

    void initialize();

    void shutdown();

    int getPendingCount(String uuid);

    void addPendingCount(String uuid, String playerName);

    void subPendingCount(String uuid);

    int insertPendingKnowledge(KnowledgeEntry entry);

    void deletePendingKnowledge(int id);

    void loadAllPendingKnowledge();

    boolean isPendingKnowledgeDuplicate(String question);

    void insertLog(String playerName, String playerInput, String aiResponse,
                   String documentId, String source);

    int getLogTotalCount();

    List<LogEntry> getLogPage(int page, int pageSize);

    void deleteLog(int id);
}
