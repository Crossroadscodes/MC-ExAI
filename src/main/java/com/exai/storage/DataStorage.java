package com.exai.storage;

import com.exai.entity.KnowledgeEntry;
import com.exai.entity.LogEntry;
import com.exai.entity.PendingReward;

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

    /** 为离线提交者暂存待领取的物品奖励与登录提示消息（按传入顺序追加）。 */
    void addPendingRewards(String playerName, List<String> items, List<String> messages);

    /** 取出并清空该玩家的全部待领取奖励；无记录时返回空载体且不做删除。 */
    PendingReward takePendingRewards(String playerName);
}
