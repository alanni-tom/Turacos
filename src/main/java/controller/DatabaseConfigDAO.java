package controller;

import util.CredentialCipher;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 本地连接配置的持久化（SQLite）。
 *
 * 安全要点：
 * <ul>
 *   <li>密码经 {@link CredentialCipher} 加密后落盘（AES-256-GCM），
 *       兼容旧版本明文记录（读取时自动识别，保存时自动升级为密文）。</li>
 *   <li>配置文件位于运行目录 config.db（已加入 .gitignore）。</li>
 * </ul>
 */
public class DatabaseConfigDAO {

    private static final Logger LOG = Logger.getLogger(DatabaseConfigDAO.class.getName());
    private static final String DB_URL = "jdbc:sqlite:config.db";

    public DatabaseConfigDAO() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            LOG.log(Level.SEVERE, "[!] SQLite JDBC driver not found!", e);
            return;
        }
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
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "[!] Failed to create config.db or initialize table!", e);
        }
    }

    public void deleteConfig(String dbType, String host, String port, String database) {
        String deleteSql = "DELETE FROM db_configs WHERE db_type=? AND host=? AND port=? AND database=?";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(deleteSql)) {
            pstmt.setString(1, dbType);
            pstmt.setString(2, host);
            pstmt.setString(3, port);
            pstmt.setString(4, database);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "[!] 删除配置失败", e);
        }
    }

    public List<DatabaseConfig> getAllConfigs() {
        List<DatabaseConfig> configs = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM db_configs")) {

            while (rs.next()) {
                String storedPwd = rs.getString("password");
                String pwd = CredentialCipher.decrypt(storedPwd);
                if (pwd == null) {
                    // 解密失败（密钥变更/损坏）：保留原值并告警，避免静默丢密
                    LOG.warning("[!] 配置密码无法解密，保留原始值: "
                            + rs.getString("db_type") + "@" + rs.getString("host"));
                    pwd = storedPwd;
                }
                DatabaseConfig config = new DatabaseConfig(
                        rs.getString("db_type"),
                        rs.getString("host"),
                        rs.getString("port"),
                        rs.getString("username"),
                        pwd,
                        rs.getString("database")
                );
                configs.add(config);
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "[!] 读取配置列表失败", e);
        }
        return configs;
    }

    public void saveOrUpdateConfig(String dbType, String host, String port,
                                   String username, String password, String database) {
        String checkSql = "SELECT id FROM db_configs WHERE db_type=? AND host=? AND port=? AND database=?";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(checkSql)) {
            pstmt.setString(1, dbType);
            pstmt.setString(2, host);
            pstmt.setString(3, port);
            pstmt.setString(4, database);

            String encrypted = CredentialCipher.encrypt(password);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    int id = rs.getInt("id");
                    String updateSql = "UPDATE db_configs SET username=?, password=? WHERE id=?";
                    try (PreparedStatement updatePstmt = conn.prepareStatement(updateSql)) {
                        updatePstmt.setString(1, username);
                        updatePstmt.setString(2, encrypted);
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
                        insertPstmt.setString(5, encrypted);
                        insertPstmt.setString(6, database);
                        insertPstmt.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "[!] 保存配置失败", e);
        }
    }
}
