package com.exai.mysql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;

public abstract class Database {

    protected Connection connection;

    protected Database() {
        this.connection = null;
    }

    private boolean checkConnection() throws SQLException {
        return connection != null && !connection.isClosed();
    }

    public abstract Connection getConnection() throws SQLException;

    public void closeConnection() throws SQLException {
        connection.close();
    }

    public ResultSet query(String query) throws SQLException {
        if (!checkConnection()) {
            connection = getConnection();
        }

        PreparedStatement statement = connection.prepareStatement(query, ResultSet.TYPE_SCROLL_INSENSITIVE,ResultSet.CONCUR_READ_ONLY);

        return statement.executeQuery();
    }

    public void query(String query, SQLConsumer<ResultSet> consumer) throws SQLException {
        ResultSet resultSet = query(query);

        consumer.accept(resultSet);

        resultSet.close();
        resultSet.getStatement().close();
    }

    public CompletableFuture<ResultSet> queryAsync(String query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return query(query);
            } catch (SQLException e) {
                e.printStackTrace();
            }
            return null;
        });
    }

    public int update(String update) throws SQLException {
        if (!checkConnection()) {
            connection = getConnection();
        }

        PreparedStatement statement = connection.prepareStatement(update, ResultSet.TYPE_SCROLL_INSENSITIVE,ResultSet.CONCUR_READ_ONLY);
        int result = statement.executeUpdate();

        statement.close();

        return result;
    }

    public CompletableFuture<Integer> updateAsync(String update) {
        return CompletableFuture.supplyAsync(() -> {
            int results = 0;
            try {
                results = update(update);
            } catch (SQLException e) {
                e.printStackTrace();
            }
            return results;
        });
    }
}