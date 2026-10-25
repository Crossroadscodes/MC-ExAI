package com.exai.storage;

import com.exai.config.Config;
import com.exai.data.DataContainer;
import com.exai.data.KnowledgeQueue;
import com.exai.entity.KnowledgeEntry;
import com.exai.entity.LogEntry;
import com.exai.entity.PendingReward;
import com.exai.entity.PluginDescriptionEntry;
import com.exai.entity.RagStatus;
import com.exai.evaluation.EvaluationCase;
import com.exai.evaluation.ExpectedBehavior;
import com.exai.i18n.Lang;
import com.exai.mysql.MySQL;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MysqlStorage implements DataStorage {

    private final MySQL sql;

    public MysqlStorage() {
        this.sql = new MySQL(Config.address, Config.database, Config.username, Config.password);
        DataContainer.sql = this.sql;
    }

    @Override
    public void initialize() {
        createTables();
        loadAllPendingKnowledge();
    }

    @Override
    public void shutdown() {
        try {
            if (sql != null && sql.getDataSource() != null && !sql.getDataSource().isClosed()) {
                sql.getDataSource().close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void createTables() {
        try (Connection connection = sql.getConnection();
             Statement statement = connection.createStatement()) {

            // 旧前缀 ex_* 平滑迁移到 exai_*（仅当旧表存在且新表不存在时改名）
            renameLegacyTable(connection, "ex_ai_log", "exai_log");
            renameLegacyTable(connection, "ex_pending_knowledge_count", "exai_pending_knowledge_count");
            renameLegacyTable(connection, "ex_pending_knowledge", "exai_pending_knowledge");
            renameLegacyTable(connection, "ex_pending_reward", "exai_pending_reward");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS exai_log (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "player_name VARCHAR(32) NOT NULL, " +
                    "player_input TEXT NOT NULL, " +
                    "ai_response TEXT NOT NULL, " +
                    "document_id TEXT, " +
                    "source VARCHAR(50), " +
                    "rag_status VARCHAR(32) DEFAULT 'UNKNOWN', " +
                    "create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
            addColumnIfMissing(connection, "exai_log", "rag_status", "VARCHAR(32) DEFAULT 'UNKNOWN'");
            widenLogDocumentId(connection);
            System.out.println(Lang.get("log.log-table-ok"));

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS exai_pending_knowledge_count (" +
                    "player_uuid VARCHAR(36) PRIMARY KEY, " +
                    "player_name VARCHAR(32) NOT NULL, " +
                    "pending_count INT DEFAULT 0, " +
                    "update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                    ")");
            System.out.println(Lang.get("log.pending-count-table-ok"));

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS exai_pending_knowledge (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "question TEXT NOT NULL, " +
                    "answer TEXT NOT NULL, " +
                    "submitter VARCHAR(32) NOT NULL, " +
                    "timestamp BIGINT NOT NULL, " +
                    "source VARCHAR(32) DEFAULT 'player', " +
                    "thanked TINYINT(1) DEFAULT 0, " +
                    "create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
            // 兼容旧版本：为已存在的表补充新列
            addColumnIfMissing(connection, "exai_pending_knowledge", "source", "VARCHAR(32) DEFAULT 'player'");
            addColumnIfMissing(connection, "exai_pending_knowledge", "thanked", "TINYINT(1) DEFAULT 0");
            System.out.println(Lang.get("log.pending-knowledge-table-ok"));

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS exai_pending_reward (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "player_name VARCHAR(32) NOT NULL, " +
                    "item VARCHAR(64), " +
                    "message TEXT, " +
                    "create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "INDEX idx_player_name (player_name)" +
                    ")");
            System.out.println(Lang.get("log.pending-reward-table-ok"));

            // 本地知识库 / 插件描述的导出目标表（不参与运行读路径）
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS exai_knowledge (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "knowledge_id VARCHAR(64), " +
                    "question TEXT NOT NULL, " +
                    "answer TEXT NOT NULL, " +
                    "submitter VARCHAR(64), " +
                    "ts BIGINT, " +
                    "source VARCHAR(32)" +
                    ")");
            addColumnIfMissing(connection, "exai_knowledge", "knowledge_id", "VARCHAR(64)");
            addUniqueIndexIfMissing(connection, "exai_knowledge", "uk_exai_knowledge_id", "knowledge_id");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS exai_plugin_desc (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "name VARCHAR(64) NOT NULL, " +
                    "version VARCHAR(32), " +
                    "enabled TINYINT(1) DEFAULT 1, " +
                    "description TEXT" +
                    ")");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS exai_evaluation_case (" +
                    "id VARCHAR(48) PRIMARY KEY, " +
                    "enabled TINYINT(1) NOT NULL DEFAULT 1, " +
                    "source_log_fingerprint VARCHAR(80) NOT NULL, " +
                    "question TEXT NOT NULL, " +
                    "expected_behavior VARCHAR(16) NOT NULL, " +
                    "reference_answer TEXT, " +
                    "expected_knowledge_ids TEXT, " +
                    "updated_at BIGINT NOT NULL, " +
                    "UNIQUE KEY uk_exai_evaluation_source (source_log_fingerprint)" +
                    ")");

        } catch (SQLException e) {
            System.err.println("Failed to create table: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void renameLegacyTable(Connection connection, String oldName, String newName) {
        try {
            if (tableExists(connection, oldName) && !tableExists(connection, newName)) {
                try (Statement st = connection.createStatement()) {
                    st.executeUpdate("RENAME TABLE " + oldName + " TO " + newName);
                }
            }
        } catch (SQLException e) {
            System.err.println("Failed to rename table " + oldName + " -> " + newName + ": " + e.getMessage());
        }
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getTables(connection.getCatalog(), null, table, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    @Override
    public List<KnowledgeEntry> readKnowledge() {
        List<KnowledgeEntry> list = new ArrayList<>();
        String querySQL = "SELECT knowledge_id, question, answer, submitter, ts, source FROM exai_knowledge ORDER BY id";
        try (Connection connection = sql.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(querySQL);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                String submitter = rs.getString("submitter");
                long ts = rs.getLong("ts");
                KnowledgeEntry entry = new KnowledgeEntry(
                        rs.getString("question"),
                        rs.getString("answer"),
                        submitter == null ? "" : submitter,
                        ts);
                entry.setKnowledgeId(rs.getString("knowledge_id"));
                String source = rs.getString("source");
                if (source != null) {
                    entry.setSource(source);
                }
                list.add(entry);
            }
        } catch (SQLException e) {
            System.err.println("Failed to read knowledge from db: " + e.getMessage());
            e.printStackTrace();
        }
        return list;
    }

    @Override
    public void writeKnowledge(List<KnowledgeEntry> entries) {
        try (Connection connection = sql.getConnection()) {
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("TRUNCATE TABLE exai_knowledge");
            }
            String insertSQL = "INSERT INTO exai_knowledge (knowledge_id, question, answer, submitter, ts, source) VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement pstmt = connection.prepareStatement(insertSQL)) {
                for (KnowledgeEntry e : entries) {
                    if (e.getKnowledgeId() == null || e.getKnowledgeId().trim().isEmpty()) pstmt.setNull(1, java.sql.Types.VARCHAR);
                    else pstmt.setString(1, e.getKnowledgeId());
                    pstmt.setString(2, e.getQuestion());
                    pstmt.setString(3, e.getAnswer());
                    pstmt.setString(4, e.getSubmitter());
                    pstmt.setLong(5, e.getTimestamp());
                    pstmt.setString(6, e.getSource());
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
            }
        } catch (SQLException e) {
            throw new RuntimeException("写入知识库到数据库失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void exportKnowledge(List<KnowledgeEntry> entries) {
        writeKnowledge(entries);
    }

    @Override
    public void exportPluginDescriptions(List<PluginDescriptionEntry> entries) {
        try (Connection connection = sql.getConnection()) {
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("TRUNCATE TABLE exai_plugin_desc");
            }
            String insertSQL = "INSERT INTO exai_plugin_desc (name, version, enabled, description) VALUES (?, ?, ?, ?)";
            try (PreparedStatement pstmt = connection.prepareStatement(insertSQL)) {
                for (PluginDescriptionEntry e : entries) {
                    pstmt.setString(1, e.getName());
                    pstmt.setString(2, e.getVersion());
                    pstmt.setBoolean(3, e.isEnabled());
                    pstmt.setString(4, e.getDescription());
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
            }
        } catch (SQLException e) {
            throw new RuntimeException("导出插件描述到数据库失败: " + e.getMessage(), e);
        }
    }

    private void addColumnIfMissing(Connection connection, String table, String column, String definition) {
        try {
            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet rs = meta.getColumns(connection.getCatalog(), null, table, column)) {
                if (rs.next()) {
                    return;
                }
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            }
        } catch (SQLException e) {
            System.err.println("Failed to add column " + column + " to " + table + ": " + e.getMessage());
        }
    }

    private void addUniqueIndexIfMissing(Connection connection, String table, String index, String column) {
        try {
            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet rs = meta.getIndexInfo(connection.getCatalog(), null, table, true, false)) {
                while (rs.next()) {
                    if (index.equalsIgnoreCase(rs.getString("INDEX_NAME"))) return;
                }
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("CREATE UNIQUE INDEX " + index + " ON " + table + " (" + column + ")");
            }
        } catch (SQLException e) {
            System.err.println("Failed to add index " + index + " to " + table + ": " + e.getMessage());
        }
    }

    private void widenLogDocumentId(Connection connection) {
        try {
            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet rs = meta.getColumns(connection.getCatalog(), null, "exai_log", "document_id")) {
                if (rs.next()) {
                    String type = rs.getString("TYPE_NAME");
                    int size = rs.getInt("COLUMN_SIZE");
                    if ((type != null && type.toUpperCase().contains("TEXT")) || size >= 512) return;
                }
            }
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("ALTER TABLE exai_log MODIFY COLUMN document_id TEXT");
            }
        } catch (SQLException e) {
            System.err.println("Failed to widen exai_log.document_id: " + e.getMessage());
        }
    }

    @Override
    public int getPendingCount(String uuid) {
        String querySQL = "SELECT pending_count FROM exai_pending_knowledge_count WHERE player_uuid = ?";
        try (Connection connection = sql.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(querySQL)) {
            pstmt.setString(1, uuid);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("pending_count");
            }
        } catch (SQLException e) {
            System.err.println("Failed to fetch pending count: " + e.getMessage());
            e.printStackTrace();
        }
        return 0;
    }

    @Override
    public void addPendingCount(String uuid, String playerName) {
        String updateSQL = "INSERT INTO exai_pending_knowledge_count (player_uuid, player_name, pending_count) " +
                "VALUES (?, ?, 1) ON DUPLICATE KEY UPDATE pending_count = pending_count + 1, player_name = ?";
        try (Connection connection = sql.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(updateSQL)) {
            pstmt.setString(1, uuid);
            pstmt.setString(2, playerName);
            pstmt.setString(3, playerName);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to increase pending count: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void subPendingCount(String uuid) {
        String updateSQL = "UPDATE exai_pending_knowledge_count SET pending_count = GREATEST(pending_count - 1, 0) WHERE player_uuid = ?";
        try (Connection connection = sql.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(updateSQL)) {
            pstmt.setString(1, uuid);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to decrease pending count: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public int insertPendingKnowledge(KnowledgeEntry entry) {
        String insertSQL = "INSERT INTO exai_pending_knowledge (question, answer, submitter, timestamp, source, thanked) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection connection = sql.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(insertSQL, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, entry.getQuestion());
            pstmt.setString(2, entry.getAnswer());
            pstmt.setString(3, entry.getSubmitter());
            pstmt.setLong(4, entry.getTimestamp());
            pstmt.setString(5, entry.getSource());
            pstmt.setBoolean(6, entry.isThanked());
            pstmt.executeUpdate();
            ResultSet rs = pstmt.getGeneratedKeys();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            System.err.println("Failed to insert pending knowledge: " + e.getMessage());
            e.printStackTrace();
        }
        return -1;
    }

    @Override
    public void deletePendingKnowledge(int id) {
        String deleteSQL = "DELETE FROM exai_pending_knowledge WHERE id = ?";
        try (Connection connection = sql.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(deleteSQL)) {
            pstmt.setInt(1, id);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to delete pending knowledge: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void loadAllPendingKnowledge() {
        KnowledgeQueue.clearAll();
        String querySQL = "SELECT id, question, answer, submitter, timestamp, source, thanked FROM exai_pending_knowledge";
        try (Connection connection = sql.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(querySQL);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                int id = rs.getInt("id");
                String question = rs.getString("question");
                String answer = rs.getString("answer");
                String submitter = rs.getString("submitter");
                long timestamp = rs.getLong("timestamp");
                KnowledgeEntry entry = new KnowledgeEntry(question, answer, submitter, timestamp);
                String source = rs.getString("source");
                entry.setSource(source == null ? "player" : source);
                entry.setThanked(rs.getBoolean("thanked"));
                KnowledgeQueue.addWithId(entry, id);
            }
            System.out.println(Lang.get("log.loaded-pending", KnowledgeQueue.getTotalCount()));
        } catch (SQLException e) {
            System.err.println("Failed to load pending knowledge: " + e.getMessage());
            e.printStackTrace();
        }

        rebuildMemoryState();
    }

    private void rebuildMemoryState() {
        DataContainer.playerPendingKnowledgeCount.clear();
        DataContainer.submitterToUuid.clear();
        String countQuerySQL = "SELECT player_uuid, player_name, pending_count FROM exai_pending_knowledge_count";
        try (Connection connection = sql.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(countQuerySQL);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                String playerUuid = rs.getString("player_uuid");
                String playerName = rs.getString("player_name");
                int pendingCount = rs.getInt("pending_count");
                DataContainer.playerPendingKnowledgeCount.put(playerUuid, pendingCount);
                DataContainer.submitterToUuid.put(playerName, playerUuid);
            }
            System.out.println(Lang.get("log.rebuilt-state"));
        } catch (SQLException e) {
            System.err.println("Failed to rebuild player pending count state: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public boolean isPendingKnowledgeDuplicate(String question) {
        String querySQL = "SELECT COUNT(*) FROM exai_pending_knowledge WHERE question = ?";
        try (Connection connection = sql.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(querySQL)) {
            pstmt.setString(1, question);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            System.err.println("Failed to check pending knowledge duplicate: " + e.getMessage());
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public void insertLog(String playerName, String playerInput, String aiResponse,
                          String documentId, String source, RagStatus ragStatus) {
        String insertSQL = "INSERT INTO exai_log (player_name, player_input, ai_response, document_id, source, rag_status) " +
                "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection connection = sql.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(insertSQL)) {
            pstmt.setString(1, playerName);
            pstmt.setString(2, playerInput);
            pstmt.setString(3, aiResponse);
            pstmt.setString(4, documentId);
            pstmt.setString(5, source);
            pstmt.setString(6, (ragStatus == null ? RagStatus.UNKNOWN : ragStatus).name());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to insert AI log: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public int getLogTotalCount() {
        String querySQL = "SELECT COUNT(*) FROM exai_log";
        try (Connection connection = sql.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(querySQL);
             ResultSet rs = pstmt.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            System.err.println("Failed to count AI log: " + e.getMessage());
            e.printStackTrace();
        }
        return 0;
    }

    @Override
    public List<LogEntry> getLogPage(int page, int pageSize) {
        List<LogEntry> list = new ArrayList<>();
        String querySQL = "SELECT id, player_name, player_input, ai_response, document_id, source, rag_status, create_time " +
                "FROM exai_log ORDER BY id DESC LIMIT ? OFFSET ?";
        try (Connection connection = sql.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(querySQL)) {
            pstmt.setInt(1, pageSize);
            pstmt.setInt(2, page * pageSize);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    LogEntry entry = new LogEntry(
                            rs.getInt("id"),
                            rs.getString("player_name"),
                            rs.getString("player_input"),
                            rs.getString("ai_response"),
                            rs.getString("document_id"),
                            rs.getString("source"),
                            RagStatus.fromString(rs.getString("rag_status")),
                            rs.getTimestamp("create_time") == null ? "" : rs.getTimestamp("create_time").toString()
                    );
                    list.add(entry);
                }
            }
        } catch (SQLException e) {
            System.err.println("Failed to load AI log page: " + e.getMessage());
            e.printStackTrace();
        }
        return list;
    }

    @Override
    public void deleteLog(int id) {
        String deleteSQL = "DELETE FROM exai_log WHERE id = ?";
        try (Connection connection = sql.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(deleteSQL)) {
            pstmt.setInt(1, id);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to delete AI log: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public List<EvaluationCase> readEvaluationCases() {
        List<EvaluationCase> cases = new ArrayList<>();
        String sqlText = "SELECT id, enabled, source_log_fingerprint, question, expected_behavior, " +
                "reference_answer, expected_knowledge_ids, updated_at " +
                "FROM exai_evaluation_case ORDER BY updated_at DESC";
        try (Connection connection = sql.getConnection();
             PreparedStatement statement = connection.prepareStatement(sqlText);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                EvaluationCase item = new EvaluationCase();
                item.setId(result.getString("id"));
                item.setEnabled(result.getBoolean("enabled"));
                item.setSourceLogFingerprint(result.getString("source_log_fingerprint"));
                item.setQuestion(result.getString("question"));
                try {
                    item.setExpectedBehavior(ExpectedBehavior.valueOf(
                            result.getString("expected_behavior")));
                } catch (Exception ignored) {
                    continue;
                }
                item.setReferenceAnswer(nullToEmpty(result.getString("reference_answer")));
                item.setExpectedKnowledgeIds(splitEvaluationIds(
                        result.getString("expected_knowledge_ids")));
                item.setUpdatedAt(result.getLong("updated_at"));
                cases.add(item);
            }
        } catch (SQLException e) {
            System.err.println("Failed to read RAG evaluation cases: " + e.getMessage());
            e.printStackTrace();
        }
        return cases;
    }

    @Override
    public EvaluationCase upsertEvaluationCase(String sourceFingerprint, EvaluationCase input) {
        String fingerprint = sourceFingerprint == null || sourceFingerprint.trim().isEmpty()
                ? "manual_" + UUID.randomUUID().toString().replace("-", "")
                : sourceFingerprint;
        String caseId = findEvaluationCaseId(fingerprint);
        if (caseId == null || caseId.isEmpty()) {
            caseId = input.getId();
        }
        if (caseId == null || caseId.trim().isEmpty()) {
            caseId = "case_" + UUID.randomUUID().toString().replace("-", "");
        }
        long updatedAt = System.currentTimeMillis();
        String sqlText = "INSERT INTO exai_evaluation_case " +
                "(id, enabled, source_log_fingerprint, question, expected_behavior, reference_answer, " +
                "expected_knowledge_ids, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE enabled=VALUES(enabled), question=VALUES(question), " +
                "expected_behavior=VALUES(expected_behavior), reference_answer=VALUES(reference_answer), " +
                "expected_knowledge_ids=VALUES(expected_knowledge_ids), updated_at=VALUES(updated_at)";
        try (Connection connection = sql.getConnection();
             PreparedStatement statement = connection.prepareStatement(sqlText)) {
            statement.setString(1, caseId);
            statement.setBoolean(2, input.isEnabled());
            statement.setString(3, fingerprint);
            statement.setString(4, input.getQuestion());
            statement.setString(5, input.getExpectedBehavior().name());
            statement.setString(6, nullToEmpty(input.getReferenceAnswer()));
            statement.setString(7, joinEvaluationIds(input.getExpectedKnowledgeIds()));
            statement.setLong(8, updatedAt);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save RAG evaluation case: " + e.getMessage(), e);
        }
        String persistedId = findEvaluationCaseId(fingerprint);
        input.setId(persistedId == null ? caseId : persistedId);
        input.setSourceLogFingerprint(fingerprint);
        input.setUpdatedAt(updatedAt);
        return input;
    }

    @Override
    public boolean deleteEvaluationCase(String caseId) {
        String sqlText = "DELETE FROM exai_evaluation_case WHERE id = ?";
        try (Connection connection = sql.getConnection();
             PreparedStatement statement = connection.prepareStatement(sqlText)) {
            statement.setString(1, caseId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete RAG evaluation case: " + e.getMessage(), e);
        }
    }

    @Override
    public int replaceEvaluationKnowledgeId(String oldId, String newId) {
        int changed = 0;
        for (EvaluationCase item : readEvaluationCases()) {
            boolean rowChanged = false;
            List<String> ids = item.getExpectedKnowledgeIds();
            for (int i = 0; i < ids.size(); i++) {
                if (oldId.equals(ids.get(i))) {
                    ids.set(i, newId);
                    changed++;
                    rowChanged = true;
                }
            }
            if (rowChanged) {
                upsertEvaluationCase(item.getSourceLogFingerprint(), item);
            }
        }
        return changed;
    }

    private String findEvaluationCaseId(String sourceFingerprint) {
        String sqlText = "SELECT id FROM exai_evaluation_case WHERE source_log_fingerprint = ?";
        try (Connection connection = sql.getConnection();
             PreparedStatement statement = connection.prepareStatement(sqlText)) {
            statement.setString(1, sourceFingerprint);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString("id") : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find RAG evaluation case: " + e.getMessage(), e);
        }
    }

    private static List<String> splitEvaluationIds(String value) {
        List<String> ids = new ArrayList<>();
        if (value == null || value.trim().isEmpty()) return ids;
        for (String part : value.split(",")) {
            String id = part.trim();
            if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    private static String joinEvaluationIds(List<String> ids) {
        return ids == null || ids.isEmpty() ? "" : String.join(",", ids);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @Override
    public void addPendingRewards(String playerName, List<String> items, List<String> messages) {
        String insertSQL = "INSERT INTO exai_pending_reward (player_name, item, message) VALUES (?, ?, ?)";
        try (Connection connection = sql.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(insertSQL)) {
            if (items != null) {
                for (String item : items) {
                    pstmt.setString(1, playerName);
                    pstmt.setString(2, item);
                    pstmt.setNull(3, java.sql.Types.VARCHAR);
                    pstmt.addBatch();
                }
            }
            if (messages != null) {
                for (String message : messages) {
                    pstmt.setString(1, playerName);
                    pstmt.setNull(2, java.sql.Types.VARCHAR);
                    pstmt.setString(3, message);
                    pstmt.addBatch();
                }
            }
            pstmt.executeBatch();
        } catch (SQLException e) {
            System.err.println("Failed to insert pending reward: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public PendingReward takePendingRewards(String playerName) {
        List<String> items = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        String querySQL = "SELECT id, item, message FROM exai_pending_reward WHERE player_name = ? ORDER BY id";
        try (Connection connection = sql.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(querySQL)) {
            pstmt.setString(1, playerName);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getInt("id"));
                    String item = rs.getString("item");
                    String message = rs.getString("message");
                    if (item != null) {
                        items.add(item);
                    }
                    if (message != null) {
                        messages.add(message);
                    }
                }
            }
            // 仅删除本次读到的行，避免删掉读取期间新插入的奖励
            if (!ids.isEmpty()) {
                StringBuilder deleteSQL = new StringBuilder("DELETE FROM exai_pending_reward WHERE id IN (");
                for (int i = 0; i < ids.size(); i++) {
                    deleteSQL.append(i == 0 ? "?" : ",?");
                }
                deleteSQL.append(")");
                try (PreparedStatement del = connection.prepareStatement(deleteSQL.toString())) {
                    for (int i = 0; i < ids.size(); i++) {
                        del.setInt(i + 1, ids.get(i));
                    }
                    del.executeUpdate();
                }
            }
        } catch (SQLException e) {
            System.err.println("Failed to take pending rewards: " + e.getMessage());
            e.printStackTrace();
        }
        return new PendingReward(items, messages);
    }
}
