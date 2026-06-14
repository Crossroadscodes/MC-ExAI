package com.exai.storage;

import com.exai.config.Config;
import com.exai.data.DataContainer;
import com.exai.data.KnowledgeQueue;
import com.exai.entity.KnowledgeEntry;
import com.exai.entity.LogEntry;
import com.exai.entity.PendingReward;
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

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS ex_ai_log (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "player_name VARCHAR(32) NOT NULL, " +
                    "player_input TEXT NOT NULL, " +
                    "ai_response TEXT NOT NULL, " +
                    "document_id VARCHAR(100), " +
                    "source VARCHAR(50), " +
                    "create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
            System.out.println(Lang.get("log.log-table-ok"));

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS ex_pending_knowledge_count (" +
                    "player_uuid VARCHAR(36) PRIMARY KEY, " +
                    "player_name VARCHAR(32) NOT NULL, " +
                    "pending_count INT DEFAULT 0, " +
                    "update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                    ")");
            System.out.println(Lang.get("log.pending-count-table-ok"));

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS ex_pending_knowledge (" +
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
            addColumnIfMissing(connection, "ex_pending_knowledge", "source", "VARCHAR(32) DEFAULT 'player'");
            addColumnIfMissing(connection, "ex_pending_knowledge", "thanked", "TINYINT(1) DEFAULT 0");
            System.out.println(Lang.get("log.pending-knowledge-table-ok"));

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS ex_pending_reward (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "player_name VARCHAR(32) NOT NULL, " +
                    "item VARCHAR(64), " +
                    "message TEXT, " +
                    "create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "INDEX idx_player_name (player_name)" +
                    ")");
            System.out.println(Lang.get("log.pending-reward-table-ok"));

        } catch (SQLException e) {
            System.err.println("Failed to create table: " + e.getMessage());
            e.printStackTrace();
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

    @Override
    public int getPendingCount(String uuid) {
        String querySQL = "SELECT pending_count FROM ex_pending_knowledge_count WHERE player_uuid = ?";
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
        String updateSQL = "INSERT INTO ex_pending_knowledge_count (player_uuid, player_name, pending_count) " +
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
        String updateSQL = "UPDATE ex_pending_knowledge_count SET pending_count = GREATEST(pending_count - 1, 0) WHERE player_uuid = ?";
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
        String insertSQL = "INSERT INTO ex_pending_knowledge (question, answer, submitter, timestamp, source, thanked) VALUES (?, ?, ?, ?, ?, ?)";
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
        String deleteSQL = "DELETE FROM ex_pending_knowledge WHERE id = ?";
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
        String querySQL = "SELECT id, question, answer, submitter, timestamp, source, thanked FROM ex_pending_knowledge";
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
        String countQuerySQL = "SELECT player_uuid, player_name, pending_count FROM ex_pending_knowledge_count";
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
        String querySQL = "SELECT COUNT(*) FROM ex_pending_knowledge WHERE question = ?";
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
                          String documentId, String source) {
        String insertSQL = "INSERT INTO ex_ai_log (player_name, player_input, ai_response, document_id, source) " +
                "VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = sql.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(insertSQL)) {
            pstmt.setString(1, playerName);
            pstmt.setString(2, playerInput);
            pstmt.setString(3, aiResponse);
            pstmt.setString(4, documentId);
            pstmt.setString(5, source);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to insert AI log: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public int getLogTotalCount() {
        String querySQL = "SELECT COUNT(*) FROM ex_ai_log";
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
        String querySQL = "SELECT id, player_name, player_input, ai_response, document_id, source, create_time " +
                "FROM ex_ai_log ORDER BY id DESC LIMIT ? OFFSET ?";
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
        String deleteSQL = "DELETE FROM ex_ai_log WHERE id = ?";
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
    public void addPendingRewards(String playerName, List<String> items, List<String> messages) {
        String insertSQL = "INSERT INTO ex_pending_reward (player_name, item, message) VALUES (?, ?, ?)";
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
        String querySQL = "SELECT id, item, message FROM ex_pending_reward WHERE player_name = ? ORDER BY id";
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
                StringBuilder deleteSQL = new StringBuilder("DELETE FROM ex_pending_reward WHERE id IN (");
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
