package com.example.governance.assets;

import java.util.Objects;

/** Non-secret connection metadata. Credentials are stored separately in encrypted form. */
public record DataSourceDefinition(String name, Type type, String host, int port, String database, String username) {
    public enum Type { MYSQL, POSTGRESQL, ORACLE }
    public DataSourceDefinition {
        if (name == null || name.isBlank() || name.length() > 120) throw new IllegalArgumentException("Invalid data source name");
        Objects.requireNonNull(type, "type");
        if (host == null || !host.matches("[A-Za-z0-9.-]{1,253}")) throw new IllegalArgumentException("Invalid database host");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid database port");
        if (database == null || !database.matches("[A-Za-z0-9_$.-]{1,128}")) throw new IllegalArgumentException("Invalid database or service name");
        if (username == null || username.isBlank() || username.length() > 128) throw new IllegalArgumentException("Invalid username");
    }
    public String jdbcUrl() {
        return switch (type) {
            case MYSQL -> "jdbc:mysql://" + host + ":" + port + "/" + database;
            case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + port + "/" + database;
            case ORACLE -> "jdbc:oracle:thin:@//" + host + ":" + port + "/" + database;
        };
    }
}
