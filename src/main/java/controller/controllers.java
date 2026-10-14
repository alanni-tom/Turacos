package controller;

import database.DatabaseAdapter;
import database.DatabaseFactory;

import java.util.List;

/**
 * 控制层：封装数据库连接与常用操作逻辑
 */
public class controllers {

    private String host;
    private String username;
    private String password;
    private String database;
    private String dbType;
    private String port;

    /**
     * 设置数据库连接信息
     */
    public void setDatabaseInfo(String dbType, String host, String port,
                                String username, String password, String database) {
        this.dbType = dbType;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.database = database;
    }

    public String getHost() {
        return host;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getDatabase() {
        return database;
    }

    public String getDbType() {
        return dbType;
    }

    public String getPort() {
        return port;
    }

    /**
     * 获取数据库列表（通过 {@link DatabaseFactory} 统一创建适配器）。
     * Redis 无数据库列表概念，返回占位项。
     */
    public List<String> loadDatabases() {
        if ("Redis".equalsIgnoreCase(this.dbType)) {
            return List.of("Redis");
        }
        DatabaseAdapter db = DatabaseFactory.createSqlAdapter(
                dbType, host, port, database, username, password);
        if (db == null) {
            return List.of();
        }
        try {
            return db.getDatabases();
        } finally {
            db.close();
        }
    }
}
