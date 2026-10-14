package compliance;

import database.mssql;
import database.mysql;
import database.psql;
import database.redis;
import util.AuditLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 数据库安全基线检查器。
 *
 * <p>针对四种数据库逐一执行“合规基线”检查项（CIS/DISA 简化集），
 * 检查失败不中断，以 INFO 级别如实记录（权限不足等）。
 * 全部结果沉淀到 {@link Finding} 列表，供 UI 展示与报告导出。
 */
public final class BaselineScanner {

    private static final Logger LOG = Logger.getLogger(BaselineScanner.class.getName());

    private BaselineScanner() {
    }

    /** 执行一次完整基线检查。 */
    public static List<Finding> scan(String dbType, String host, String port,
                                     String database, String username, String password) {
        AuditLogger.logEvent("COMPLIANCE_START", dbType, host, port, database, username, "");
        List<Finding> findings;
        try {
            if ("MySQL".equalsIgnoreCase(dbType)) {
                findings = scanMysql(host, port, database, username, password);
            } else if ("PostgreSQL".equalsIgnoreCase(dbType)) {
                findings = scanPsql(host, port, database, username, password);
            } else if ("MSSQL".equalsIgnoreCase(dbType) || "SqlServer".equalsIgnoreCase(dbType)) {
                findings = scanMssql(host, port, database, username, password);
            } else if ("Redis".equalsIgnoreCase(dbType)) {
                findings = scanRedis(host, port, database, username, password);
            } else {
                findings = List.of();
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[!] 基线检查异常: " + e.getMessage(), e);
            findings = List.of(Finding.of("SCAN-000", Severity.INFO, "检查过程异常",
                    "整体扫描失败，请检查连接与权限", "重试或检查日志", String.valueOf(e.getMessage())));
        }
        AuditLogger.logEvent("COMPLIANCE_DONE", dbType, host, port, database, username,
                "findings=" + findings.size());
        return findings;
    }

    // ----------------------------------------------------------------- MySQL

    private static List<Finding> scanMysql(String host, String port, String database,
                                           String username, String password) {
        List<Finding> out = new ArrayList<>();
        mysql db = new mysql(host, port, database, username, password);
        try {
            // 版本
            try {
                String v = first(db.queryRows("SELECT VERSION() AS v"), "v");
                int major = majorVersion(v);
                if (major > 0 && major < 8) {
                    out.add(Finding.of("MYSQL-001", Severity.HIGH, "MySQL 版本处于 EOL",
                            "检测到 " + v + "，5.7 及以下已停止安全维护，高危漏洞不再修复。",
                            "升级至 8.0+；至少订阅 EOL 补丁渠道并限制暴露面。", "version=" + v));
                } else {
                    out.add(Finding.of("MYSQL-001", Severity.INFO, "MySQL 版本",
                            "当前版本仍在维护周期内。", "保持小版本更新（Patch）。", "version=" + v));
                }
            } catch (Exception e) {
                out.add(errInfo("MYSQL-001", "获取版本失败", e));
            }

            // secure_file_priv
            try {
                String sfp = myVar(db, "secure_file_priv");
                if (sfp == null) {
                    out.add(Finding.of("MYSQL-002", Severity.INFO, "secure_file_priv 无法读取",
                            "当前账户权限不足，无法读取该变量。",
                            "使用管理员账户复查。", "value=null"));
                } else if (sfp.isEmpty()) {
                    out.add(Finding.of("MYSQL-002", Severity.HIGH, "secure_file_priv 未限制",
                            "LOAD_FILE()/INTO OUTFILE 可读写服务器任意路径文件。",
                            "将 secure_file_priv 设置为指定目录或 NULL（仅限本地数据目录）。",
                            "secure_file_priv=''"));
                } else {
                    out.add(Finding.of("MYSQL-002", Severity.LOW, "secure_file_priv 已限制",
                            "文件读写被限制在指定目录。",
                            "保持现状。", "secure_file_priv=" + sfp));
                }
            } catch (Exception e) {
                out.add(errInfo("MYSQL-002", "检查 secure_file_priv 失败", e));
            }

            // local_infile
            try {
                String li = myVar(db, "local_infile");
                if ("ON".equalsIgnoreCase(li) || "1".equals(li)) {
                    out.add(Finding.of("MYSQL-003", Severity.MEDIUM, "local_infile 开启",
                            "客户端可通过 LOAD DATA LOCAL 向服务器读取本地文件。",
                            "如无必要，关闭 local_infile。", "local_infile=" + li));
                }
            } catch (Exception e) {
                out.add(errInfo("MYSQL-003", "检查 local_infile 失败", e));
            }

            // skip_grant_tables
            try {
                String sgt = myVar(db, "skip_grant_tables");
                if ("ON".equalsIgnoreCase(sgt) || "1".equals(sgt)) {
                    out.add(Finding.of("MYSQL-004", Severity.HIGH, "skip_grant_tables 开启",
                            "服务在无授权表模式下运行，任意用户可完全访问。",
                            "立即关闭并重启，排查误配置。", "skip_grant_tables=ON"));
                }
            } catch (Exception e) {
                out.add(errInfo("MYSQL-004", "检查 skip_grant_tables 失败", e));
            }

            // 空口令账户
            try {
                List<Map<String, Object>> acc;
                try {
                    acc = db.queryRows("SELECT User,Host FROM mysql.user "
                            + "WHERE authentication_string IS NULL OR authentication_string='' OR authentication_string='*'");
                } catch (Exception inner) {
                    acc = db.queryRows("SELECT User,Host FROM mysql.user "
                            + "WHERE password IS NULL OR password='' OR password='*'");
                }
                if (!acc.isEmpty()) {
                    out.add(Finding.of("MYSQL-005", Severity.HIGH, "存在空口令/通配口令账户",
                            "发现 " + acc.size() + " 个账户口令为空或不可验证。",
                            "为所有账户设置强口令；禁用不使用的账户。",
                            "sample=" + acc.get(0).get("User") + "@" + acc.get(0).get("Host")));
                }
            } catch (Exception e) {
                out.add(errInfo("MYSQL-005", "检查空口令账户失败（可能需要更高权限）", e));
            }

            // Host='%' 账户
            try {
                List<Map<String, Object>> wild = db.queryRows("SELECT User,Host FROM mysql.user WHERE Host='%'");
                if (!wild.isEmpty()) {
                    out.add(Finding.of("MYSQL-006", Severity.MEDIUM, "存在任意主机可登录账户",
                            "发现 " + wild.size() + " 个账户允许从任意主机登录。",
                            "收紧为指定网段/主机，禁用不必要的远程访问。",
                            "sample=" + wild.get(0).get("User") + "@%"));
                }
            } catch (Exception e) {
                out.add(errInfo("MYSQL-006", "检查远程账户失败（可能需要更高权限）", e));
            }

            // log_bin
            try {
                String lb = myVar(db, "log_bin");
                if ("OFF".equalsIgnoreCase(lb) || "0".equals(lb)) {
                    out.add(Finding.of("MYSQL-007", Severity.MEDIUM, "二进制日志未开启",
                            "未开启 binlog 将无法审计变更、数据恢复与主从复制。",
                            "开启 log_bin 并同步审计策略。", "log_bin=OFF"));
                }
            } catch (Exception e) {
                out.add(errInfo("MYSQL-007", "检查 log_bin 失败", e));
            }

            // 认证插件
            try {
                String plug = myVar(db, "default_authentication_plugin");
                if (plug != null && plug.toLowerCase().contains("mysql_native_password")) {
                    out.add(Finding.of("MYSQL-008", Severity.LOW, "使用旧认证插件",
                            "default_authentication_plugin=mysql_native_password，安全强度低于 caching_sha2_password。",
                            "迁移到 caching_sha2_password。", "default_authentication_plugin=" + plug));
                }
            } catch (Exception e) {
                out.add(errInfo("MYSQL-008", "检查认证插件失败", e));
            }
        } finally {
            db.close();
        }
        return out;
    }

    // ------------------------------------------------------------ PostgreSQL

    private static List<Finding> scanPsql(String host, String port, String database,
                                          String username, String password) {
        List<Finding> out = new ArrayList<>();
        psql db = new psql(host, port, database, username, password);
        try {
            // 版本
            try {
                String v = first(db.queryRows("SHOW server_version"), "server_version");
                int major = majorVersion(v);
                if (major > 0 && major < 12) {
                    out.add(Finding.of("PG-001", Severity.HIGH, "PostgreSQL 版本已停止维护",
                            "检测到 " + v + "，v12 以下已 EOL。",
                            "升级到受支持版本。", "version=" + v));
                } else {
                    out.add(Finding.of("PG-001", Severity.INFO, "PostgreSQL 版本",
                            "当前版本在维护周期内。", "保持小版本更新。", "version=" + v));
                }
            } catch (Exception e) {
                out.add(errInfo("PG-001", "获取版本失败", e));
            }

            // 额外超级用户
            try {
                List<Map<String, Object>> su = db.queryRows(
                        "SELECT rolname FROM pg_roles WHERE rolsuper AND rolcanlogin "
                                + "AND rolname NOT IN ('postgres','root') ORDER BY rolname");
                if (!su.isEmpty()) {
                    out.add(Finding.of("PG-002", Severity.MEDIUM, "存在额外超级用户",
                            "除默认超级用户外还有 " + su.size() + " 个可登录超级用户。",
                            "审查并最小化超级用户数量。", "users=" + su.get(0).get("rolname") + " ..."));
                }
            } catch (Exception e) {
                out.add(errInfo("PG-002", "检查超级用户失败", e));
            }

            // 无口令可登录角色
            try {
                List<Map<String, Object>> np = db.queryRows(
                        "SELECT rolname FROM pg_roles WHERE rolcanlogin AND passwd IS NULL AND NOT rolsuper");
                if (!np.isEmpty()) {
                    out.add(Finding.of("PG-003", Severity.MEDIUM, "存在无口令登录角色",
                            "发现 " + np.size() + " 个可登录角色未设置口令（若 pg_hba 使用 trust/md5 存在风险）。",
                            "为角色设置口令，或禁用未使用角色。",
                            "users=" + np.get(0).get("rolname") + " ..."));
                }
            } catch (Exception e) {
                out.add(errInfo("PG-003", "检查口令角色失败", e));
            }

            // pg_hba trust
            try {
                String hba = first(db.queryRows("SELECT pg_read_file(current_setting('hba_file')) AS f"), "f");
                if (hba != null && hba.toLowerCase().contains("trust")) {
                    out.add(Finding.of("PG-004", Severity.HIGH, "pg_hba.conf 存在 trust 认证",
                            "检测到 trust 认证规则，该网段任意用户免密登录。",
                            "将 trust 改为 scram-sha-256 并限定来源。", "hba_file contains 'trust'"));
                }
            } catch (Exception e) {
                out.add(errInfo("PG-004", "无法读取 pg_hba.conf（需超级用户）", e));
            }

            // 日志审计
            try {
                String lc = first(db.queryRows("SHOW log_connections"), "log_connections");
                if ("off".equalsIgnoreCase(lc) || "0".equals(lc)) {
                    out.add(Finding.of("PG-005", Severity.MEDIUM, "连接日志未开启",
                            "log_connections=off，无法审计连接行为。",
                            "开启 log_connections 并配置日志收集。", "log_connections=off"));
                }
                String ls = first(db.queryRows("SHOW log_statement"), "log_statement");
                if ("none".equalsIgnoreCase(ls)) {
                    out.add(Finding.of("PG-006", Severity.MEDIUM, "SQL 语句日志未开启",
                            "log_statement=none，无法审计语句执行。",
                            "按合规要求设置 ddl 或 mod。", "log_statement=none"));
                }
            } catch (Exception e) {
                out.add(errInfo("PG-005", "检查日志配置失败", e));
            }

            // SSL
            try {
                String ssl = first(db.queryRows("SHOW ssl"), "ssl");
                if ("off".equalsIgnoreCase(ssl)) {
                    out.add(Finding.of("PG-007", Severity.MEDIUM, "SSL 未启用",
                            "连接传输明文，存在窃听风险。",
                            "启用 SSL 并配置证书。", "ssl=off"));
                }
            } catch (Exception e) {
                out.add(errInfo("PG-007", "检查 SSL 失败", e));
            }

            // listen_addresses
            try {
                String la = first(db.queryRows("SHOW listen_addresses"), "listen_addresses");
                if ("*".equals(la) || "*".equals(String.valueOf(la))) {
                    out.add(Finding.of("PG-008", Severity.MEDIUM, "监听所有网卡",
                            "listen_addresses='*'，数据库暴露于所有接口。",
                            "按需限制监听地址（如仅内网 IP/本机）。", "listen_addresses=" + la));
                }
            } catch (Exception e) {
                out.add(errInfo("PG-008", "检查监听地址失败", e));
            }
        } finally {
            db.close();
        }
        return out;
    }

    // ------------------------------------------------------------------ MSSQL

    private static List<Finding> scanMssql(String host, String port, String database,
                                           String username, String password) {
        List<Finding> out = new ArrayList<>();
        mssql db = new mssql(host, port, database, username, password);
        try {
            // 版本
            try {
                String v = first(db.queryRows(
                        "SELECT CAST(SERVERPROPERTY('ProductVersion') AS NVARCHAR(100)) AS v"), "v");
                int major = majorVersion(v);
                if (major > 0 && major < 11) {
                    out.add(Finding.of("MSSQL-001", Severity.HIGH, "SQL Server 版本已停止维护",
                            "检测到 " + v + "，SQL Server 2012 以下已 EOL。",
                            "升级到受支持版本。", "version=" + v));
                } else {
                    out.add(Finding.of("MSSQL-001", Severity.INFO, "SQL Server 版本",
                            "当前版本在维护周期内。", "保持小版本更新。", "version=" + v));
                }
            } catch (Exception e) {
                out.add(errInfo("MSSQL-001", "获取版本失败", e));
            }

            // xp_cmdshell
            try {
                String xp = mssqlCfg(db, "xp_cmdshell");
                if ("1".equals(xp)) {
                    out.add(Finding.of("MSSQL-002", Severity.HIGH, "xp_cmdshell 已启用",
                            "可执行任意系统命令，攻击者常利用该扩展提权。",
                            "如无必要立即关闭：EXEC sp_configure 'xp_cmdshell',0。", "xp_cmdshell=1"));
                }
            } catch (Exception e) {
                out.add(errInfo("MSSQL-002", "检查 xp_cmdshell 失败", e));
            }

            // OLE Automation
            try {
                String ole = mssqlCfg(db, "Ole Automation Procedures");
                if ("1".equals(ole)) {
                    out.add(Finding.of("MSSQL-003", Severity.MEDIUM, "OLE Automation 已启用",
                            "可调用 COM 对象，扩大攻击面。",
                            "如无业务必要，保持关闭。", "Ole Automation Procedures=1"));
                }
            } catch (Exception e) {
                out.add(errInfo("MSSQL-003", "检查 OLE 自动化失败", e));
            }

            // CLR
            try {
                String clr = mssqlCfg(db, "clr enabled");
                if ("1".equals(clr)) {
                    out.add(Finding.of("MSSQL-004", Severity.MEDIUM, "CLR 已启用",
                            "允许在库内运行 .NET 程序集，需严格审计。",
                            "如无必要保持关闭；务必启用 clr strict security。", "clr enabled=1"));
                }
            } catch (Exception e) {
                out.add(errInfo("MSSQL-004", "检查 CLR 失败", e));
            }

            // Ad hoc distributed queries
            try {
                String adh = mssqlCfg(db, "Ad Hoc Distributed Queries");
                if ("1".equals(adh)) {
                    out.add(Finding.of("MSSQL-005", Severity.MEDIUM, "Ad Hoc Distributed Queries 已启用",
                            "OPENROWSET/OPENDATASOURCE 允许访问任意数据源/文件。",
                            "关闭或限制到白名单。", "Ad Hoc Distributed Queries=1"));
                }
            } catch (Exception e) {
                out.add(errInfo("MSSQL-005", "检查分布式查询失败", e));
            }

            // login auditing
            try {
                String audit = mssqlCfg(db, "login auditing");
                if ("0".equals(audit)) {
                    out.add(Finding.of("MSSQL-006", Severity.MEDIUM, "登录审计未配置",
                            "login auditing 为 none，无法审计登录失败事件。",
                            "配置为 failed logins 或 all。", "login auditing=0"));
                }
            } catch (Exception e) {
                out.add(errInfo("MSSQL-006", "检查登录审计失败", e));
            }

            // sa 账户
            try {
                Map<String, Object> sa = firstRow(db.queryRows(
                        "SELECT is_disabled FROM sys.server_principals WHERE name='sa'"));
                if (sa != null && "0".equals(String.valueOf(sa.get("is_disabled")))) {
                    out.add(Finding.of("MSSQL-007", Severity.MEDIUM, "sa 账户处于启用状态",
                            "sa 为高权限内置账户，易被暴力破解。",
                            "禁用 sa 或改为 Windows 认证；至少设置强口令并开启策略。",
                            "sa is_disabled=0"));
                }
                try {
                    String policy = String.valueOf(firstRow(db.queryRows(
                            "SELECT LOGINPROPERTY('sa','IsPolicyChecked') AS v")).get("v"));
                    if ("false".equalsIgnoreCase(policy) || "0".equals(policy)) {
                        out.add(Finding.of("MSSQL-008", Severity.MEDIUM, "sa 口令策略未启用",
                                "sa 未启用密码策略检查。",
                                "开启密码过期/复杂度策略。", "IsPolicyChecked=false"));
                    }
                } catch (Exception ignored) {
                }
            } catch (Exception e) {
                out.add(errInfo("MSSQL-007", "检查 sa 账户失败", e));
            }

            // 数据库 trustworthy
            try {
                List<Map<String, Object>> tw = db.queryRows(
                        "SELECT name FROM sys.databases WHERE is_trustworthy_on=1 AND name<>'master'");
                if (!tw.isEmpty()) {
                    out.add(Finding.of("MSSQL-009", Severity.MEDIUM, "存在 trustworthy 数据库",
                            "trustworthy=1 的库可提升权限（需与模拟/CLR 组合）。",
                            "取消非必要数据库的 trustworthy 属性。",
                            "databases=" + tw.get(0).get("name") + " ..."));
                }
            } catch (Exception e) {
                out.add(errInfo("MSSQL-009", "检查 trustworthy 失败", e));
            }
        } finally {
            db.close();
        }
        return out;
    }

    // ------------------------------------------------------------------ Redis

    private static List<Finding> scanRedis(String host, String port, String database,
                                           String username, String password) {
        List<Finding> out = new ArrayList<>();
        redis db = new redis(host, port, database, username, password);
        try {
            // 版本
            try {
                String info = db.serverInfo("server");
                String v = lineValue(info, "redis_version");
                int minor = minorVersion(v);
                if (minor > 0 && minor < 6) {
                    out.add(Finding.of("REDIS-001", Severity.LOW, "Redis 版本低于 6.0",
                            "v6 以下无 ACL 功能，整体安全能力不足。",
                            "升级到 6.0+ 并启用 ACL。", "redis_version=" + v));
                } else {
                    out.add(Finding.of("REDIS-001", Severity.INFO, "Redis 版本",
                            "当前版本具备 ACL 等现代安全特性。", "保持小版本更新。", "redis_version=" + v));
                }
            } catch (Exception e) {
                out.add(errInfo("REDIS-001", "获取版本失败", e));
            }

            // requirepass
            try {
                String req = db.configGetValue("requirepass");
                if (req == null || req.isEmpty()) {
                    out.add(Finding.of("REDIS-002", Severity.HIGH, "Redis 未设置认证口令",
                            "requirepass 为空，任何人可连接并读写数据。",
                            "设置强口令（或升级 6.0+ 使用 ACL 默认用户）。", "requirepass=''"));
                } else {
                    out.add(Finding.of("REDIS-002", Severity.INFO, "Redis 已设置认证口令",
                            "requirepass 已配置。", "建议结合 ACL 进一步限制。", "requirepass=******"));
                }
            } catch (Exception e) {
                out.add(errInfo("REDIS-002", "检查 requirepass 失败（可能无 CONFIG 权限）", e));
            }

            // protected-mode
            try {
                String pm = db.configGetValue("protected-mode");
                if ("no".equalsIgnoreCase(pm)) {
                    out.add(Finding.of("REDIS-003", Severity.HIGH, "protected-mode 关闭",
                            "未开启保护模式，暴露公网时无任何防护。",
                            "开启 protected-mode yes，并配合防火墙/绑定内网。", "protected-mode=no"));
                }
            } catch (Exception e) {
                out.add(errInfo("REDIS-003", "检查 protected-mode 失败", e));
            }

            // bind
            try {
                String bind = db.configGetValue("bind");
                if (bind == null || bind.isBlank() || bind.contains("0.0.0.0") || bind.contains("*")) {
                    out.add(Finding.of("REDIS-004", Severity.MEDIUM, "Redis 监听地址过宽",
                            "监听所有/公网地址，扩大暴露面。",
                            "绑定到本机或内网地址。", "bind=" + bind));
                }
            } catch (Exception e) {
                out.add(errInfo("REDIS-004", "检查 bind 失败", e));
            }

            // 敏感命令改名
            try {
                String rc = db.configGetValue("rename-command");
                if (rc == null || !rc.toLowerCase().contains("config")) {
                    out.add(Finding.of("REDIS-005", Severity.MEDIUM, "敏感命令未被重命名",
                            "CONFIG 等敏感命令可被任意持有口令者调用。",
                            "按需 rename-command CONFIG 等危险命令。", "rename-command=无 CONFIG 条目"));
                }
            } catch (Exception e) {
                out.add(errInfo("REDIS-005", "检查 rename-command 失败", e));
            }

            // 默认用户 ACL
            try {
                String acl = db.aclList();
                if (acl.contains("user default") && acl.contains("nopass")) {
                    out.add(Finding.of("REDIS-006", Severity.HIGH, "默认用户 ACL 免密",
                            "ACL 默认用户为 nopass，任何连接均可执行命令。",
                            "ACL SETUSER default on >强口令 ~* +@all 或创建受限用户。", "acl: default nopass"));
                }
            } catch (Exception e) {
                out.add(errInfo("REDIS-006", "检查 ACL 失败（v6+ 或需要 ACL 权限）", e));
            }
        } finally {
            db.close();
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers

    private static String myVar(mysql db, String name) {
        try {
            return first(db.queryRows("SELECT @@`" + name + "` AS v"), "v");
        } catch (Exception e) {
            // 某些变量不支持 @@ 语法，回退 SHOW
            try {
                return first(db.queryRows("SHOW VARIABLES LIKE '" + name + "'"), "Value");
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private static String mssqlCfg(mssql db, String name) {
        try {
            var rows = db.queryRows("SELECT value_in_use AS v FROM sys.configurations WHERE name=?",
                    name);
            return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("v"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String first(List<Map<String, Object>> rows, String key) {
        if (rows == null || rows.isEmpty()) return null;
        Object v = rows.get(0).get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Map<String, Object> firstRow(List<Map<String, Object>> rows) {
        return (rows == null || rows.isEmpty()) ? null : rows.get(0);
    }

    private static String lineValue(String multiLine, String key) {
        if (multiLine == null) return "";
        for (String line : multiLine.split("\r?\n")) {
            if (line.startsWith(key + ":")) {
                return line.substring(key.length() + 1).trim();
            }
        }
        return "";
    }

    private static int majorVersion(String v) {
        if (v == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\.(\\d+)").matcher(v);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static int minorVersion(String v) {
        if (v == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\.(\\d+)").matcher(v);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static Finding errInfo(String id, String title, Exception e) {
        return Finding.of(id, Severity.INFO, title,
                "该项检查未完成（权限不足或不受支持）。",
                "以更高权限账户复查。", String.valueOf(e.getMessage()));
    }
}
