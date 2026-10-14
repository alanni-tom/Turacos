package util;

/**
 * SQL 标识符引用工具。
 *
 * <p>表名/列名等标识符来自 information_schema 等元数据，直接拼接进
 * {@code SELECT * FROM <table>} 会因特殊字符中断语句或造成错误。
 * 统一按各数据库方言安全引用。
 */
public final class SqlHelpers {

    private SqlHelpers() {
    }

    /** PostgreSQL 标识符引用：双引号包裹，内部双引号加倍。 */
    public static String quotePg(String id) {
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }

    /** MySQL 标识符引用：反引号包裹，内部反引号加倍。 */
    public static String quoteMy(String id) {
        return "`" + id.replace("`", "``") + "`";
    }

    /** SQL Server 标识符引用：方括号包裹，内部方括号加倍。 */
    public static String quoteMs(String id) {
        return "[" + id.replace("]", "]]") + "]";
    }

    /** 限制查询行数在安全范围（1..5000），防止误操作拉取全表。 */
    public static int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, 5000));
    }

    /** 简单校验 host/port 输入，避免空值与明显非法端口。 */
    public static boolean isValidHostAndPort(String host, String port) {
        if (host == null || host.trim().isEmpty()) {
            return false;
        }
        if (port == null || port.trim().isEmpty()) {
            return false;
        }
        try {
            int p = Integer.parseInt(port.trim());
            return p >= 1 && p <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
