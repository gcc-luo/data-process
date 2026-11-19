package com.example.governance.assets.driver;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** MySQL 5.x servers via the legacy Connector/J 5.1 driver family. */
@Component
public final class MySql5Profile extends AbstractMySqlProfile {
    @Override public String id() { return "MYSQL:5.x"; }
    @Override public String displayName() { return "MySQL 5.x"; }
    @Override public String driverClassName() { return "com.mysql.jdbc.Driver"; }
    @Override public String defaultDriverKey() { return "builtin-mysql-5"; }
    @Override public Map<String, ParamSpec> extraParams() {
        return paramsWith(ParamSpec.enumOf("zeroDateTimeBehavior", "零日期处理", List.of("exception", "round", "convertToNull"), "5.1 驱动取值"));
    }
}
