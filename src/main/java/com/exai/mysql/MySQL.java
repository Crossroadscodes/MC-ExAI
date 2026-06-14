package com.exai.mysql;

import com.exai.config.Config;
import com.exai.i18n.Lang;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;

import java.sql.Connection;
import java.sql.SQLException;

public class MySQL {

    private final HikariDataSource dataSource;

    public MySQL(String address, String database, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + address + "/" + database + "?useSSL=false&autoReconnect=true");
        config.setUsername(username);
        config.setPassword(password);

        // 连接存活/空闲/保活：配合 MySQL 服务端 wait_timeout，避免借到已被服务端关闭的连接
        config.setMaxLifetime(870000);   // 14分30秒，比 900s 少 30s
        config.setIdleTimeout(840000);   // 14分钟，小于 maxLifetime
        config.setKeepaliveTime(300000); // 5分钟探测一次

        // MySQL JDBC 预编译语句缓存（HikariCP 官方推荐项）
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");

        dataSource = new HikariDataSource(config);
        Bukkit.getServer().getConsoleSender().sendMessage(
                Lang.get("runtime.mysql-connected", Config.assistantName));
    }

    public HikariDataSource getDataSource() {
        return dataSource;
    }

    /** 从连接池借一条连接；调用方用 try-with-resources 关闭即归还连接池。 */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }
}
