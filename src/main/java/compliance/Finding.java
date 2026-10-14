package compliance;

/**
 * 安全基线检查结果项。
 *
 * @param id             稳定编号，如 MYSQL-001 / PG-003 / MSSQL-001 / REDIS-001
 * @param severity       严重级别
 * @param title          标题
 * @param description    问题说明
 * @param recommendation 整改建议
 * @param evidence       实测证据（脱敏后的配置/查询结果摘要）
 */
public record Finding(String id, Severity severity, String title,
                      String description, String recommendation, String evidence) {

    public static Finding of(String id, Severity severity, String title,
                             String description, String recommendation, String evidence) {
        return new Finding(id, severity, title, description, recommendation, evidence);
    }
}
