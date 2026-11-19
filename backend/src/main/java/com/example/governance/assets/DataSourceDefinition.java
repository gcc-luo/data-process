package com.example.governance.assets;

import java.util.Map;

/** Non-secret connection metadata. Credentials are stored separately in encrypted form. */
public record DataSourceDefinition(String name, String profileId, String host, int port, String database, String username, Long driverId, Map<String, String> options) {
    public DataSourceDefinition {
        if (name == null || name.isBlank() || name.length() > 120) throw new IllegalArgumentException("Invalid data source name");
        if (profileId == null || !profileId.matches("[A-Z0-9]+:[A-Za-z0-9.+_-]{1,40}")) throw new IllegalArgumentException("Invalid database profile");
        if (host == null || !host.matches("[A-Za-z0-9.-]{1,253}")) throw new IllegalArgumentException("Invalid database host");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid database port");
        if (database == null || !database.matches("[A-Za-z0-9_$.-]{1,128}")) throw new IllegalArgumentException("Invalid database or service name");
        if (username == null || username.isBlank() || username.length() > 128) throw new IllegalArgumentException("Invalid username");
        if (driverId != null && driverId < 1) throw new IllegalArgumentException("Invalid driver");
        options = options == null ? Map.of() : Map.copyOf(options);
    }
    public DataSourceDefinition(String name, String profileId, String host, int port, String database, String username) { this(name, profileId, host, port, database, username, null, Map.of()); }
    public String type() { int i = profileId.indexOf(':'); return i < 0 ? profileId : profileId.substring(0, i); }
}
