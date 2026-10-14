package util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 本地操作审计日志（JSON Lines）。
 *
 * <p>记录所有对目标数据库执行的关键操作（SQL / 命令 / 模块加载 / 反弹等），
 * 供企业安全合规审计留痕。日志按天滚动写入 {@code ~/.turacos/audit/audit-YYYY-MM-DD.log}，
 * 单条格式为一行 JSON，便于导入 SIEM / ELK 等平台。
 *
 * <p>审计日志本身只记录：操作者(本机用户)、目标连接信息(host/port/db/user)、
 * 执行语句摘要与结果摘要；不记录明文口令。
 */
public final class AuditLogger {

    private static final Logger LOG = Logger.getLogger(AuditLogger.class.getName());
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    private static final String OPERATOR = System.getProperty("user.name", "unknown");

    /** SQL 内容截断长度（控制日志体积）。 */
    private static final int DETAIL_MAX = 2000;
    /** 结果摘要截断长度。 */
    private static final int RESULT_MAX = 300;

    private AuditLogger() {
    }

    /** 记录一次数据库操作。 */
    public static void log(String action, String dbType, String host, String port,
                           String database, String dbUser, String detail, String result,
                           long elapsedMs) {
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        sb.append("\"ts\":\"").append(OffsetDateTime.now().format(TS)).append('"');
        sb.append(",\"operator\":\"").append(esc(OPERATOR)).append('"');
        sb.append(",\"action\":\"").append(esc(action)).append('"');
        sb.append(",\"dbType\":\"").append(esc(dbType)).append('"');
        sb.append(",\"host\":\"").append(esc(host)).append('"');
        sb.append(",\"port\":\"").append(esc(port)).append('"');
        sb.append(",\"database\":\"").append(esc(database)).append('"');
        sb.append(",\"dbUser\":\"").append(esc(dbUser)).append('"');
        sb.append(",\"detail\":\"").append(esc(truncate(detail, DETAIL_MAX))).append('"');
        sb.append(",\"result\":\"").append(esc(truncate(result, RESULT_MAX))).append('"');
        sb.append(",\"elapsedMs\":").append(elapsedMs);
        sb.append('}');
        appendLine(sb.toString());
    }

    /** 记录一条纯事件（无 SQL 内容），如连接尝试、检查任务开始等。 */
    public static void logEvent(String action, String dbType, String host, String port,
                                String database, String dbUser, String note) {
        log(action, dbType, host, port, database, dbUser, note, "", 0L);
    }

    /** 审计目录（每次解析，兼容运行时自定义 user.home 的场景/测试隔离）。 */
    public static Path auditDir() {
        return dir();
    }

    private static Path dir() {
        return Paths.get(System.getProperty("user.home", "."),
                ".turacos", "audit").toAbsolutePath();
    }

    private static synchronized void appendLine(String line) {
        try {
            Path auditDir = dir();
            if (!Files.exists(auditDir)) {
                Files.createDirectories(auditDir);
            }
            Path file = auditDir.resolve("audit-" + LocalDate.now() + ".log");
            Files.write(file, (line + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException e) {
            // 审计失败不能中断业务，仅记录
            LOG.log(Level.WARNING, "[!] 写入审计日志失败: " + e.getMessage(), e);
        }
    }

    /** JSON 字符串转义。 */
    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...[截断]";
    }
}
