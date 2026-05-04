package com.exai.utils;

import com.exai.ExAI;
import com.exai.config.Config;
import com.exai.data.DataContainer;
import com.exai.entity.KnowledgeEntry;
import org.bukkit.Bukkit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class DataUtils {

    public static void createTable() {
        try (Connection connection = DataContainer.sql.getConnection();
             Statement statement = connection.createStatement()
        ) {
            String createTableSQL = "CREATE TABLE IF NOT EXISTS ex_ai_log (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "player_name VARCHAR(32) NOT NULL, " +
                    "player_input TEXT NOT NULL, " +
                    "ai_response TEXT NOT NULL, " +
                    "document_id VARCHAR(100), " +
                    "source VARCHAR(50), " +
                    "create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";

            statement.executeUpdate(createTableSQL);
            System.out.println("玩家问答表创建成功！");

            String createPendingTableSQL = "CREATE TABLE IF NOT EXISTS ex_pending_knowledge_count (" +
                    "player_uuid VARCHAR(36) PRIMARY KEY, " +
                    "player_name VARCHAR(32) NOT NULL, " +
                    "pending_count INT DEFAULT 0, " +
                    "update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                    ")";
            statement.executeUpdate(createPendingTableSQL);
            System.out.println("待审核知识计数表创建成功！");

            String createPendingKnowledgeSQL = "CREATE TABLE IF NOT EXISTS ex_pending_knowledge (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "question TEXT NOT NULL, " +
                    "answer TEXT NOT NULL, " +
                    "submitter VARCHAR(32) NOT NULL, " +
                    "timestamp BIGINT NOT NULL, " +
                    "create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";
            statement.executeUpdate(createPendingKnowledgeSQL);
            System.out.println("待审核知识表创建成功！");

        } catch (SQLException e) {
            System.err.println("创建表时发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void createPendingKnowledgeTable() {
        try (Connection connection = DataContainer.sql.getConnection();
             Statement statement = connection.createStatement()
        ) {
            String createPendingTableSQL = "CREATE TABLE IF NOT EXISTS ex_pending_knowledge_count (" +
                    "player_uuid VARCHAR(36) PRIMARY KEY, " +
                    "player_name VARCHAR(32) NOT NULL, " +
                    "pending_count INT DEFAULT 0, " +
                    "update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                    ")";
            statement.executeUpdate(createPendingTableSQL);
            System.out.println("待审核知识计数表创建成功！");

            String createPendingKnowledgeSQL = "CREATE TABLE IF NOT EXISTS ex_pending_knowledge (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "question TEXT NOT NULL, " +
                    "answer TEXT NOT NULL, " +
                    "submitter VARCHAR(32) NOT NULL, " +
                    "timestamp BIGINT NOT NULL, " +
                    "create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")";
            statement.executeUpdate(createPendingKnowledgeSQL);
            System.out.println("待审核知识表创建成功！");
        } catch (SQLException e) {
            System.err.println("创建待审核知识表时发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static int getPendingCount(String uuid) {
        String querySQL = "SELECT pending_count FROM ex_pending_knowledge_count WHERE player_uuid = ?";
        try (Connection connection = DataContainer.sql.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(querySQL)) {
            pstmt.setString(1, uuid);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("pending_count");
            }
        } catch (SQLException e) {
            System.err.println("获取待审核计数失败: " + e.getMessage());
            e.printStackTrace();
        }
        return 0;
    }

    public static void addPendingCount(String uuid, String playerName) {
        String updateSQL = "INSERT INTO ex_pending_knowledge_count (player_uuid, player_name, pending_count) " +
                "VALUES (?, ?, 1) ON DUPLICATE KEY UPDATE pending_count = pending_count + 1, player_name = ?";
        try (Connection connection = DataContainer.sql.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(updateSQL)) {
            pstmt.setString(1, uuid);
            pstmt.setString(2, playerName);
            pstmt.setString(3, playerName);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("增加待审核计数失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void addPendingCountAsync(String uuid, String playerName) {
        Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), () -> {
            addPendingCount(uuid, playerName);
        });
    }

    public static void subPendingCount(String uuid) {
        String updateSQL = "UPDATE ex_pending_knowledge_count SET pending_count = GREATEST(pending_count - 1, 0) WHERE player_uuid = ?";
        try (Connection connection = DataContainer.sql.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(updateSQL)) {
            pstmt.setString(1, uuid);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("减少待审核计数失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void subPendingCountAsync(String uuid) {
        Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), () -> {
            subPendingCount(uuid);
        });
    }

    public static int insertPendingKnowledge(KnowledgeEntry entry) {
        String insertSQL = "INSERT INTO ex_pending_knowledge (question, answer, submitter, timestamp) VALUES (?, ?, ?, ?)";
        try (Connection connection = DataContainer.sql.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(insertSQL, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, entry.getQuestion());
            pstmt.setString(2, entry.getAnswer());
            pstmt.setString(3, entry.getSubmitter());
            pstmt.setLong(4, entry.getTimestamp());
            pstmt.executeUpdate();
            ResultSet rs = pstmt.getGeneratedKeys();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            System.err.println("插入待审核知识失败: " + e.getMessage());
            e.printStackTrace();
        }
        return -1;
    }

    public static void deletePendingKnowledge(int id) {
        String deleteSQL = "DELETE FROM ex_pending_knowledge WHERE id = ?";
        try (Connection connection = DataContainer.sql.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(deleteSQL)) {
            pstmt.setInt(1, id);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("删除待审核知识失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void deletePendingKnowledgeAsync(int id) {
        Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), () -> {
            deletePendingKnowledge(id);
        });
    }

    public static void loadAllPendingKnowledge() {
        com.exai.data.KnowledgeQueue.clearAll();
        String querySQL = "SELECT id, question, answer, submitter, timestamp FROM ex_pending_knowledge";
        try (Connection connection = DataContainer.sql.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(querySQL);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                int id = rs.getInt("id");
                String question = rs.getString("question");
                String answer = rs.getString("answer");
                String submitter = rs.getString("submitter");
                long timestamp = rs.getLong("timestamp");
                KnowledgeEntry entry = new KnowledgeEntry(question, answer, submitter, timestamp);
                com.exai.data.KnowledgeQueue.addWithId(entry, id);
            }
            System.out.println("已从数据库加载 " + com.exai.data.KnowledgeQueue.getTotalCount() + " 条待审核知识");
        } catch (SQLException e) {
            System.err.println("加载待审核知识失败: " + e.getMessage());
            e.printStackTrace();
        }

        rebuildMemoryState();
    }

    private static void rebuildMemoryState() {
        String countQuerySQL = "SELECT player_uuid, player_name, pending_count FROM ex_pending_knowledge_count";
        try (Connection connection = DataContainer.sql.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(countQuerySQL);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                String playerUuid = rs.getString("player_uuid");
                String playerName = rs.getString("player_name");
                int pendingCount = rs.getInt("pending_count");
                DataContainer.playerPendingKnowledgeCount.put(playerUuid, pendingCount);
                DataContainer.submitterToUuid.put(playerName, playerUuid);
            }
            System.out.println("已重建玩家待审核计数状态");
        } catch (SQLException e) {
            System.err.println("重建玩家待审核计数状态失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void loadAllPendingKnowledgeAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), () -> {
            loadAllPendingKnowledge();
        });
    }

    public static boolean isPendingKnowledgeDuplicate(String question) {
        String querySQL = "SELECT COUNT(*) FROM ex_pending_knowledge WHERE question = ?";
        try (Connection connection = DataContainer.sql.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(querySQL)) {
            pstmt.setString(1, question);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            System.err.println("检查待审核知识重复失败: " + e.getMessage());
            e.printStackTrace();
        }
        return false;
    }

    public static void insertLog(String playerName, String playerInput, String aiResponse,
                              String documentId, String source) {
        String insertSQL = "INSERT INTO ex_ai_log (player_name, player_input, ai_response, document_id, source) " +
                "VALUES (?, ?, ?, ?, ?)";

        try (Connection connection = DataContainer.sql.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(insertSQL)) {

            pstmt.setString(1, playerName);
            pstmt.setString(2, playerInput);
            pstmt.setString(3, aiResponse);

            pstmt.setString(4, documentId);
            pstmt.setString(5, source);

            pstmt.executeUpdate();

        } catch (SQLException e) {
            System.err.println("插入AI日志失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void insertLogAsync(String playerName, String playerInput, String aiResponse,
                              String documentId, String source) {
        Bukkit.getScheduler().runTaskAsynchronously(ExAI.getInstance(), () -> {
            insertLog(playerName, playerInput, aiResponse, documentId, source);
        });
    }
}