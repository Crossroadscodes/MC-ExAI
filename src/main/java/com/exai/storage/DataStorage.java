package com.exai.storage;

import com.exai.entity.KnowledgeEntry;
import com.exai.entity.LogEntry;
import com.exai.entity.PendingReward;
import com.exai.entity.PluginDescriptionEntry;

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

    /** 读取运行时知识库（mysql 模式下从 exai_knowledge 表读取）。仅 MySQL 支持。 */
    List<KnowledgeEntry> readKnowledge();

    /** 整体覆盖写入运行时知识库（清表+批量插入）。仅 MySQL 支持。 */
    void writeKnowledge(List<KnowledgeEntry> entries);

    /** 把本地知识库整体导出到数据库表（清表+批量插入，单向）。仅 MySQL 支持。 */
    void exportKnowledge(List<KnowledgeEntry> entries);

    /** 把插件描述整体导出到数据库表（清表+批量插入，单向）。仅 MySQL 支持。 */
    void exportPluginDescriptions(List<PluginDescriptionEntry> entries);
}
