package controller;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class DatabaseConfigDAO {

    private static final String DB_URL = "jdbc:sqlite:config.db";

    public DatabaseConfigDAO() {
        try {
            Class.forName("org.sqlite.JDBC");

            try (Connection conn = DriverManager.getConnection(DB_URL)) {
                if (conn != null) {
                    String sql = "CREATE TABLE IF NOT EXISTS db_configs (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                            "db_type TEXT NOT NULL," +
                            "host TEXT," +
                            "port TEXT," +
                            "username TEXT," +
                            "password TEXT," +
                            "database TEXT" +
                            ");";
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute(sql);
                    }
                }
            }

        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            System.err.println("SQLite JDBC driver not found!");
        } catch (SQLException e) {
            e.printStackTrace();
            System.err.println("Failed to create config.db or initialize table!");
        }
    }

    public void deleteConfig(String dbType, String host, String port, String database) {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            String deleteSql = "DELETE FROM db_configs WHERE db_type=? AND host=? AND port=? AND database=?";
            try (PreparedStatement pstmt = conn.prepareStatement(deleteSql)) {
                pstmt.setString(1, dbType);
                pstmt.setString(2, host);
                pstmt.setString(3, port);
                pstmt.setString(4, database);
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public List<DatabaseConfig> getAllConfigs() {
        List<DatabaseConfig> configs = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM db_configs")) {

            while (rs.next()) {
                DatabaseConfig config = new DatabaseConfig(
                        rs.getString("db_type"),
                        rs.getString("host"),
                        rs.getString("port"),
                        rs.getString("username"),
                        rs.getString("password"),
                        rs.getString("database")
                );
                configs.add(config);
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return configs;
    }


    public void saveOrUpdateConfig(String dbType, String host, String port, String username, String password, String database) {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            String checkSql = "SELECT id FROM db_configs WHERE db_type=? AND host=? AND port=? AND database=?";
            try (PreparedStatement pstmt = conn.prepareStatement(checkSql)) {
                pstmt.setString(1, dbType);
                pstmt.setString(2, host);
                pstmt.setString(3, port);
                pstmt.setString(4, database);

                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) {
                    int id = rs.getInt("id");
                    String updateSql = "UPDATE db_configs SET username=?, password=? WHERE id=?";
                    try (PreparedStatement updatePstmt = conn.prepareStatement(updateSql)) {
                        updatePstmt.setString(1, username);
                        updatePstmt.setString(2, password);
                        updatePstmt.setInt(3, id);
                        updatePstmt.executeUpdate();
                    }
                } else {
                    String insertSql = "INSERT INTO db_configs(db_type, host, port, username, password, database) VALUES(?,?,?,?,?,?)";
                    try (PreparedStatement insertPstmt = conn.prepareStatement(insertSql)) {
                        insertPstmt.setString(1, dbType);
                        insertPstmt.setString(2, host);
                        insertPstmt.setString(3, port);
                        insertPstmt.setString(4, username);
                        insertPstmt.setString(5, password);
                        insertPstmt.setString(6, database);
                        insertPstmt.executeUpdate();
                    }
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
