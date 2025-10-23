package controller;

public class DatabaseConfig {
    private String dbType;
    private String host;
    private String port;
    private String username;
    private String password;
    private String database;

    public DatabaseConfig(String dbType, String host, String port, String username, String password, String database) {
        this.dbType = dbType;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.database = database;
    }

    // getter & setter
    public String getDbType() { return dbType; }
    public String getHost() { return host; }
    public String getPort() { return port; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public String getDatabase() { return database; }

    @Override
    public String toString() {
        return dbType + " | " + host + ":" + port + " | " + database;
    }
}
