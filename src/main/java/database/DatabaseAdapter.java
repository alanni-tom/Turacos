package database;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * SQL 型数据库的统一适配器接口。
 *
 * <p>PostgreSQL / MySQL / MSSQL 三个实现共享同一套能力，
 * 调用方（UI/控制层/合规检查）无需关心具体方言；
 * Redis 能力模型差异较大（非 SQL），保留独立类实现。
 */
public interface DatabaseAdapter extends AutoCloseable {

    /** 测试连接是否可用（不影响内部状态）。 */
    boolean testConnection();

    /** 数据库（实例）列表。 */
    List<String> getDatabases();

    /** Schema（模式）列表。 */
    List<String> getSchemas();

    /** 指定 schema 下的表列表。 */
    List<String> getTables(String schema);

    /** 指定表的列名（按 ordinal 排序）。 */
    List<String> getColumnNames(String schema, String table);

    /** 取表前 limit 行。 */
    List<List<Object>> getTopRows(String schema, String table, int limit);

    /** 执行任意 SQL（支持多语句），返回格式化文本。 */
    String executeSQL(String sql) throws SQLException;

    /** 获取基础信息摘要（版本/用户/目录等）。 */
    String getDatabaseInfo();

    /** 结构化查询：返回 列名->值 行列表（供合规检查等模块使用）。 */
    List<Map<String, Object>> queryRows(String sql, String... params) throws SQLException;

    /** 释放资源（连接池）。 */
    @Override
    void close();
}
