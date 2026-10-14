package database;

import java.util.logging.Logger;

/**
 * 数据库适配器工厂：按数据库类型创建统一的 SQL 适配器。
 *
 * <p>支持：PostgreSQL / MySQL / MSSQL(SqlServer)。
 * Redis 请直接使用 {@link database.redis}（能力模型不同）。
 */
public final class DatabaseFactory {

    private static final Logger LOG = Logger.getLogger(DatabaseFactory.class.getName());

    private DatabaseFactory() {
    }

    /** 创建 SQL 型数据库适配器；类型不支持时返回 null。 */
    public static DatabaseAdapter createSqlAdapter(String dbType, String host, String port,
                                                   String database, String username, String password) {
        if (dbType == null) {
            return null;
        }
        switch (dbType.trim().toLowerCase()) {
            case "postgresql":
                return new psql(host, port, database, username, password);
            case "mysql":
                return new mysql(host, port, database, username, password);
            case "mssql":
            case "sqlserver":
                return new mssql(host, port, database, username, password);
            default:
                LOG.fine("不支持的 SQL 数据库类型: " + dbType + "（Redis 请用独立类）");
                return null;
        }
    }
}
