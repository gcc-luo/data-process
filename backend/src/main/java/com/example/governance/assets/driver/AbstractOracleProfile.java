package com.example.governance.assets.driver;

import com.example.governance.assets.DataSourceDefinition;
import java.util.List;
import java.util.Map;

abstract class AbstractOracleProfile implements DatabaseProfile {
    @Override public String type() { return "ORACLE"; }
    @Override public int defaultPort() { return 1521; }
    @Override public String driverClassName() { return "oracle.jdbc.OracleDriver"; }
    @Override public Map<String, String> defaultProperties() { return Map.of("oracle.net.CONNECT_TIMEOUT", "5000", "oracle.jdbc.ReadTimeout", "10000"); }
    @Override public List<ExtraField> extraFields() { return List.of(new ExtraField("connectType", "连接方式", List.of("SID", "SERVICE"), defaultConnectType())); }
    abstract String defaultConnectType();
    @Override public String jdbcUrl(DataSourceDefinition d) {
        String mode = d.options().getOrDefault("connectType", defaultConnectType());
        return "SERVICE".equals(mode)
            ? "jdbc:oracle:thin:@//" + d.host() + ":" + d.port() + "/" + d.database()
            : "jdbc:oracle:thin:@" + d.host() + ":" + d.port() + ":" + d.database();
    }
    @Override public void validate(DataSourceDefinition d) {
        String mode = d.options().getOrDefault("connectType", defaultConnectType());
        if (!"SID".equals(mode) && !"SERVICE".equals(mode)) throw new IllegalArgumentException("Invalid Oracle connect type");
    }
    @Override public Map<String, ParamSpec> extraParams() {
        return Map.of(
            "oracle.net.CONNECT_TIMEOUT", ParamSpec.millis("oracle.net.CONNECT_TIMEOUT", "连接超时(ms)", "0..3600000"),
            "oracle.jdbc.ReadTimeout", ParamSpec.millis("oracle.jdbc.ReadTimeout", "读取超时(ms)", "0..3600000"),
            "oracle.jdbc.defaultRowPrefetch", ParamSpec.prefetch("oracle.jdbc.defaultRowPrefetch", "行预取", "1..10000"));
    }
}
