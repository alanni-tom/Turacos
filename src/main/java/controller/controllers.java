package controller;

import database.psql;
import database.mysql;
import database.mssql;
import database.redis;

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
     * 获取数据库列表
     */
    public List<String> loadDatabases() {
        if ("PostgreSQL".equalsIgnoreCase(this.dbType)) {
            psql db = new psql(host, port, database, username, password);
            db.close();
            return db.getDatabases();
        } else if ("MySQL".equalsIgnoreCase(this.dbType)) {
            mysql db = new mysql(host, port, database, username, password);
            db.close();
            return db.getDatabases();
        } else if ("MSSQL".equalsIgnoreCase(this.dbType)) {
            mssql db = new mssql(host, port, database, username, password);
            db.close();
            return db.getDatabases();
        } else if ("Redis".equalsIgnoreCase(this.dbType)) {
            // Redis 无真正“数据库列表”，此处展示一个占位项
            return List.of("Redis");
        }
        return List.of();
    }


}
