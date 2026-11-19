package com.example.governance.assets.driver;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** MySQL 8.x servers via the modern Connector/J (com.mysql.cj) driver family. */
@Component
public final class MySql8Profile extends AbstractMySqlProfile {
    @Override public String id() { return "MYSQL:8.x"; }
    @Override public String displayName() { return "MySQL 8.x"; }
    @Override public String driverClassName() { return "com.mysql.cj.jdbc.Driver"; }
    @Override public String defaultDriverKey() { return "builtin-mysql-8"; }
    @Override public Map<String, ParamSpec> extraParams() {
        return paramsWith(
            ParamSpec.bool("allowPublicKeyRetrieval", "允许公钥检索", "caching_sha2_password 非 SSL 场景可能需要"),
            ParamSpec.enumOf("zeroDateTimeBehavior", "零日期处理", List.of("EXCEPTION", "ROUND", "CONVERT_TO_NULL"), "8.x 驱动取值"));
    }
}
