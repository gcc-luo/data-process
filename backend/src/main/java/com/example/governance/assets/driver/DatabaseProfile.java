package com.example.governance.assets.driver;

import com.example.governance.assets.DataSourceDefinition;
import java.util.List;
import java.util.Map;

/** Connection strategy for one database type + major version. Register a bean to support a new database. */
public interface DatabaseProfile {
    String id();
    String type();
    String displayName();
    int defaultPort();
    String driverClassName();
    String defaultDriverKey();
    String jdbcUrl(DataSourceDefinition definition);
    Map<String, String> defaultProperties();
    Map<String, ParamSpec> extraParams();
    List<ExtraField> extraFields();
    default void validate(DataSourceDefinition definition) {}

    record ExtraField(String key, String label, List<String> options, String defaultValue) {}
    record ParamSpec(String key, String label, Kind kind, List<String> values, String hint) {
        public enum Kind { BOOLEAN, MILLIS, SECONDS, ENUM, ZONE, IDENTIFIER, TEXT, PREFETCH }
        public static ParamSpec bool(String key, String label, String hint) { return new ParamSpec(key, label, Kind.BOOLEAN, null, hint); }
        public static ParamSpec millis(String key, String label, String hint) { return new ParamSpec(key, label, Kind.MILLIS, null, hint); }
        public static ParamSpec seconds(String key, String label, String hint) { return new ParamSpec(key, label, Kind.SECONDS, null, hint); }
        public static ParamSpec enumOf(String key, String label, List<String> values, String hint) { return new ParamSpec(key, label, Kind.ENUM, values, hint); }
        public static ParamSpec zone(String key, String label, String hint) { return new ParamSpec(key, label, Kind.ZONE, null, hint); }
        public static ParamSpec identifier(String key, String label, String hint) { return new ParamSpec(key, label, Kind.IDENTIFIER, null, hint); }
        public static ParamSpec text(String key, String label, String hint) { return new ParamSpec(key, label, Kind.TEXT, null, hint); }
        public static ParamSpec prefetch(String key, String label, String hint) { return new ParamSpec(key, label, Kind.PREFETCH, null, hint); }
    }
}
