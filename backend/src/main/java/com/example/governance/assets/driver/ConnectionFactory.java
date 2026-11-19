package com.example.governance.assets.driver;

import com.example.governance.assets.DataSourceDefinition;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

/** Single entry point for business connections: profile -> driver -> URL + whitelisted properties. */
public final class ConnectionFactory {
    private final DatabaseProfileRegistry registry;
    private final DriverStore drivers;
    public ConnectionFactory(DatabaseProfileRegistry registry, DriverStore drivers) { this.registry = registry; this.drivers = drivers; }

    /** Resolves and validates everything needed to connect; called on save and on connect. */
    public DatabaseProfile validate(DataSourceDefinition definition) {
        DatabaseProfile profile = registry.resolve(definition.profileId());
        profile.validate(definition);
        var fieldKeys = profile.extraFields().stream().map(DatabaseProfile.ExtraField::key).collect(Collectors.toSet());
        var params = definition.options().entrySet().stream().filter(e -> !fieldKeys.contains(e.getKey())).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        ProfileParams.validate(profile, params);
        drivers.resolveForProfile(profile, definition.driverId());
        return profile;
    }

    public Connection open(DataSourceDefinition definition, String password) throws SQLException {
        DatabaseProfile profile = validate(definition);
        var driver = drivers.resolveForProfile(profile, definition.driverId());
        Properties props = new Properties();
        props.putAll(profile.defaultProperties());
        for (String key : profile.extraParams().keySet()) { String value = definition.options().get(key); if (value != null) props.setProperty(key, value); }
        props.setProperty("user", definition.username());
        props.setProperty("password", password);
        return drivers.connect(driver, profile.jdbcUrl(definition), props);
    }
}
