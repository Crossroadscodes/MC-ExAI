package com.exai.mysql;

import com.exai.config.Config;
import com.exai.i18n.Lang;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;

import java.sql.Connection;
import java.sql.SQLException;

public class MySQL extends Database {

    private final HikariDataSource dataSource;

    public MySQL(String address, String database, String username, String password) {
        this(address, database, username, password, false);
    }

    public MySQL(String address, String database, String username, String password, boolean legacyDriver) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + address + "/" + database + "?useSSL=false&autoReconnect=true");
        config.setUsername(username);
        config.setPassword(password);

        if (legacyDriver) config.setDataSourceClassName("com.mysql.jdbc.Driver");
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("useLocalSessionState", "true");
        config.addDataSourceProperty("rewriteBatchedStatements", "true");
        config.addDataSourceProperty("cacheResultSetMetadata", "true");
        config.addDataSourceProperty("cacheServerConfiguration", "true");
        config.addDataSourceProperty("elideSetAutoCommits", "true");
        config.addDataSourceProperty("maintainTimeStats", "false");
        dataSource = new HikariDataSource(config);
        Bukkit.getServer().getConsoleSender().sendMessage(
                Lang.get("runtime.mysql-connected", Config.assistantName));
    }

    public HikariDataSource getDataSource() {
        return dataSource;
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed() || !connection.isValid(5)) {
            connection = dataSource.getConnection();
        }
        return connection;
    }
}
