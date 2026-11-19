package com.example.governance.assets.driver;

import com.example.governance.assets.DataSourceDefinition;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** PostgreSQL via pgjdbc; one general profile covers supported server versions. */
@Component
public final class PostgreSqlProfile implements DatabaseProfile {
    @Override public String id() { return "POSTGRESQL:general"; }
    @Override public String type() { return "POSTGRESQL"; }
    @Override public String displayName() { return "PostgreSQL"; }
    @Override public int defaultPort() { return 5432; }
    @Override public String driverClassName() { return "org.postgresql.Driver"; }
    @Override public String defaultDriverKey() { return "builtin-postgresql"; }
    @Override public String jdbcUrl(DataSourceDefinition d) { return "jdbc:postgresql://" + d.host() + ":" + d.port() + "/" + d.database(); }
    @Override public Map<String, String> defaultProperties() { return Map.of("connectTimeout", "5", "socketTimeout", "10"); }
    @Override public List<ExtraField> extraFields() { return List.of(); }
    @Override public Map<String, ParamSpec> extraParams() {
        return Map.of(
            "connectTimeout", ParamSpec.seconds("connectTimeout", "连接超时(s)", "0..3600"),
            "socketTimeout", ParamSpec.seconds("socketTimeout", "读取超时(s)", "0..3600"),
            "ssl", ParamSpec.bool("ssl", "使用 SSL", "true/false"),
            "sslmode", ParamSpec.enumOf("sslmode", "SSL 模式", List.of("disable", "allow", "prefer", "require", "verify-ca", "verify-full"), "pgjdbc sslmode"),
            "currentSchema", ParamSpec.identifier("currentSchema", "默认 Schema", "schema 名"),
            "ApplicationName", ParamSpec.text("ApplicationName", "应用名", "≤128 字符"));
    }
}
