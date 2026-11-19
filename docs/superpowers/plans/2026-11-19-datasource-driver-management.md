# 数据源连接层重构（版本档案 + 驱动管理）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 `docs/superpowers/specs/2026-11-19-datasource-driver-management-design.md` 交付连接层重构：版本档案策略、内置/上传 JDBC 驱动隔离加载、白名单参数、前端表单与驱动管理页，最终重启服务交付。

**Architecture:** `DatabaseProfile` 策略（类型+大版本）→ `DatabaseProfileRegistry` 注册表 → `DriverStore`（磁盘 jar + 元数据）+ `IsolatedDriverLoader`（URLClassLoader，parent=平台加载器，引用计数）→ `ConnectionFactory` 统一建连；Inspector/执行 DataSource 全部委托工厂。

**Tech Stack:** Spring Boot 3.5.8 / Java 21 / Flyway（MySQL + H2 双目录）/ JUnit5 + MockMvc / Vue3 + Element Plus / node --test。

**执行约定：** 内联执行（executing-plans）。用户已授权全程免确认。直接在 main 分支工作（仓库惯例，用户要求原地更新并重启服务）。工作区现有未提交改动是已完成的 V29 资产菜单功能，先单独提交（Task 0）。每个任务 TDD：先红后绿再提交。后端命令在 `backend/`，前端在 `frontend/`。

---

### Task 0: 提交遗留 V29 改动 + 修复基线测试（仅测试侧）

**Files:**
- Commit only: 现有工作区全部改动（PermissionCatalog.java、OrganizationNavigationTest.java、App.vue、DataSources.vue、Organization.vue、menu-icons.js、V29 两个 SQL、spec 文档）
- Modify(test): `src/test/java/com/example/governance/assets/DataSourceApiTest.java`、`DataSourceDeletionApiTest.java`、`DatasetDeletionApiTest.java`、`DatasetRefreshApiTest.java`、`security/DatasetAccessTest.java`、`security/AccessPolicyTest.java`、`query/DatasetPolicyStoreTest.java`、`realtime/RealtimeSettingsApiTest.java`、`workflow/StandaloneTaskRetryApiTest.java`、`workflow/WorkflowValidationApiTest.java`、`organization/OrganizationNavigationTest.java`、`mysql/MySqlPolicySnapshotTest.java`
- Modify(test): `frontend/tests/auth.test.js`

- [ ] **Step 1: 提交遗留改动**

```bash
cd ~/Documents/project/data_process
git add -A
git commit -m "chore(assets): 提交进行中的资产菜单调整（V29）与连接层设计文档"
```

- [ ] **Step 2: 修复后端旧权限码（机械替换，不改生产代码）**

旧码 → 新码集合（按测试实际用到的端点插入 `role_permissions`）：
- `datasource.manage` → 按测试需要插入：`datasource.source.read/create/update/delete/test`、`datasource.dataset.read/create/delete/refresh/manage_policy`、`datasource.query.read`。断言"删除权限后 403"的地方改为删除对应的具体码（如 `datasource.dataset.read`）。
- `dataset.read` → `datasource.query.read`（AccessPolicy/DatasetAccess 的函数权限）；列表端点另需 `datasource.dataset.read`。
- `workflow.manage` → `workflow.task.read/create/update/execute/retry` + `workflow.workflow.read/create/update/execute/retry/validate` + `workflow.schedule.read/create/update/backfill`（按测试用到的端点）。
- `organization.manage` → `system.department.create/read`、`system.user.create/read`、`system.audit.read` 等（按端点）。
- `service.manage` → `service.service.read/create/submit/approve` + `service.caller.read/create/manage_grant`。
- `report.manage` → `report.report.read/create/execute`。

逐文件 grep 旧码并替换；`AccessPolicyTest`/`DatasetAccessTest`/`DatasetPolicyStoreTest` 中构造 `AccessPolicy` 的 `Set.of("dataset.read")` 改 `Set.of("datasource.query.read")`。

- [ ] **Step 3: 修复前端 auth 测试**

`frontend/tests/auth.test.js` 第一个测试：`login('alice', 'p&ss', fetcher)` → `login('alice', 'p&ss', null, fetcher)`（auth.js 增加了 captcha 参数，fetcher 落错位导致走了真实 fetch）。

- [ ] **Step 4: 全量验证基线绿**

```bash
cd backend && mvn -q test 2>&1 | grep -E "Tests run:" | tail -3
cd ../frontend && node --test tests/*.test.js 2>&1 | grep -E "^ℹ (tests|pass|fail)"
```
预期：后端 Failures: 0, Errors: 0（Skipped 为 MySQL 环境用例，允许）；前端 fail 0。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "test: 修复细粒度 RBAC 遗留的旧权限码测试与登录测试参数"
```

---

### Task 1: DatabaseProfile 策略接口 + 五个档案 + 注册表 + 参数白名单

**Files:**
- Create: `backend/src/main/java/com/example/governance/assets/driver/DatabaseProfile.java`
- Create: `.../driver/ProfileParams.java`
- Create: `.../driver/AbstractMySqlProfile.java`、`MySql5Profile.java`、`MySql8Profile.java`
- Create: `.../driver/PostgreSqlProfile.java`
- Create: `.../driver/AbstractOracleProfile.java`、`Oracle11Profile.java`、`Oracle12Profile.java`
- Create: `.../driver/DatabaseProfileRegistry.java`
- Test: `backend/src/test/java/com/example/governance/assets/driver/DatabaseProfileTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.example.governance.assets.driver;

import com.example.governance.assets.DataSourceDefinition;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseProfileTest {
    private final DatabaseProfileRegistry registry = new DatabaseProfileRegistry(List.of(
        new MySql5Profile(), new MySql8Profile(), new PostgreSqlProfile(), new Oracle11Profile(), new Oracle12Profile()));
    private DataSourceDefinition def(String profileId, String db) { return new DataSourceDefinition("n", profileId, "db.example", 1521, db, "reader"); }

    @Test void buildsVersionSpecificUrls() {
        assertEquals("jdbc:mysql://db.example:1521/sales", registry.resolve("MYSQL:5.x").jdbcUrl(def("MYSQL:5.x", "sales")));
        assertEquals("jdbc:mysql://db.example:1521/sales", registry.resolve("MYSQL:8.x").jdbcUrl(def("MYSQL:8.x", "sales")));
        assertEquals("jdbc:postgresql://db.example:1521/sales", registry.resolve("POSTGRESQL:general").jdbcUrl(def("POSTGRESQL:general", "sales")));
        assertEquals("jdbc:oracle:thin:@db.example:1521:ORCL", registry.resolve("ORACLE:11g").jdbcUrl(def("ORACLE:11g", "ORCL")));
        assertEquals("jdbc:oracle:thin:@//db.example:1521/ORCL", registry.resolve("ORACLE:12c+").jdbcUrl(def("ORACLE:12c+", "ORCL")));
    }
    @Test void oracleConnectTypeSwitchesUrlForm() {
        var sid = new DataSourceDefinition("n", "ORACLE:12c+", "db.example", 1521, "ORCL", "reader", null, Map.of("connectType", "SID"));
        assertEquals("jdbc:oracle:thin:@db.example:1521:ORCL", registry.resolve("ORACLE:12c+").jdbcUrl(sid));
        var service = new DataSourceDefinition("n", "ORACLE:11g", "db.example", 1521, "ORCL", "reader", null, Map.of("connectType", "SERVICE"));
        assertEquals("jdbc:oracle:thin:@//db.example:1521/ORCL", registry.resolve("ORACLE:11g").jdbcUrl(service));
    }
    @Test void defaultsPreserveExistingTimeouts() {
        assertEquals("5000", registry.resolve("MYSQL:8.x").defaultProperties().get("connectTimeout"));
        assertEquals("5", registry.resolve("POSTGRESQL:general").defaultProperties().get("connectTimeout"));
        assertEquals("5000", registry.resolve("ORACLE:11g").defaultProperties().get("oracle.net.CONNECT_TIMEOUT"));
    }
    @Test void whitelistAcceptsKnownParamsAndRejectsDangerousOnes() {
        var mysql8 = registry.resolve("MYSQL:8.x");
        assertDoesNotThrow(() -> ProfileParams.validate(mysql8, Map.of("serverTimezone", "Asia/Shanghai", "allowPublicKeyRetrieval", "true")));
        assertThrows(IllegalArgumentException.class, () -> ProfileParams.validate(mysql8, Map.of("allowLoadLocalInfile", "true")));
        assertThrows(IllegalArgumentException.class, () -> ProfileParams.validate(mysql8, Map.of("autoDeserialize", "true")));
        assertThrows(IllegalArgumentException.class, () -> ProfileParams.validate(mysql8, Map.of("queryInterceptors", "x")));
        assertThrows(IllegalArgumentException.class, () -> ProfileParams.validate(mysql8, Map.of("user", "evil")));
        assertThrows(IllegalArgumentException.class, () -> ProfileParams.validate(mysql8, Map.of("serverTimezone", "Not/AZone")));
        assertThrows(IllegalArgumentException.class, () -> ProfileParams.validate(mysql8, Map.of("connectTimeout", "-1")));
        var pg = registry.resolve("POSTGRESQL:general");
        assertThrows(IllegalArgumentException.class, () -> ProfileParams.validate(pg, Map.of("socketFactory", "x")));
        assertThrows(IllegalArgumentException.class, () -> ProfileParams.validate(pg, Map.of("sslmode", "bogus")));
        assertDoesNotThrow(() -> ProfileParams.validate(pg, Map.of("sslmode", "require", "socketTimeout", "10")));
    }
    @Test void registryRejectsUnknownProfileAndListsTypes() {
        assertThrows(IllegalArgumentException.class, () -> registry.resolve("DB2:1"));
        assertEquals(java.util.Set.of("MYSQL", "POSTGRESQL", "ORACLE"), registry.types());
        assertEquals(5, registry.all().size());
    }
    @Test void oracleProfileRejectsInvalidConnectType() {
        var bad = new DataSourceDefinition("n", "ORACLE:11g", "h", 1521, "ORCL", "u", null, Map.of("connectType", "BOGUS"));
        assertThrows(IllegalArgumentException.class, () -> registry.resolve("ORACLE:11g").validate(bad));
    }
}
```

注意：此测试依赖 Task 2 的新 `DataSourceDefinition`（profileId 字符串 + 8 参构造）。**先做 Task 2 Step 1 的 record 改造**（旧测试同步改，见 Task 2），再回到本任务。执行顺序：Task 2 的 record 部分先行。

- [ ] **Step 2: 运行确认失败**

`cd backend && mvn -q test -Dtest=DatabaseProfileTest` → 编译失败（类不存在）即红。

- [ ] **Step 3: 实现接口与工具**

`DatabaseProfile.java`：

```java
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
```

`ProfileParams.java`：

```java
package com.example.governance.assets.driver;

import java.time.ZoneId;
import java.util.Map;

/** Whitelist enforcement for user-supplied driver parameters; backend is authoritative. */
public final class ProfileParams {
    private ProfileParams() {}
    public static void validate(DatabaseProfile profile, Map<String, String> options) {
        var specs = profile.extraParams();
        for (var entry : options.entrySet()) {
            String key = entry.getKey(); String value = entry.getValue();
            if ("user".equals(key) || "password".equals(key)) throw new IllegalArgumentException("Parameter not allowed: " + key);
            var spec = specs.get(key);
            if (spec == null) throw new IllegalArgumentException("Parameter not allowed: " + key);
            if (value == null || !valid(spec, value)) throw new IllegalArgumentException("Invalid value for parameter: " + key);
        }
    }
    static boolean valid(DatabaseProfile.ParamSpec spec, String value) {
        return switch (spec.kind()) {
            case BOOLEAN -> "true".equals(value) || "false".equals(value);
            case MILLIS -> integer(value, 0, 3_600_000);
            case SECONDS -> integer(value, 0, 3600);
            case PREFETCH -> integer(value, 1, 10_000);
            case ENUM -> spec.values() != null && spec.values().contains(value);
            case ZONE -> ZoneId.getAvailableZoneIds().contains(value) || value.matches("[+-]\\d{2}:\\d{2}");
            case IDENTIFIER -> value.matches("[A-Za-z0-9_$]{1,128}");
            case TEXT -> !value.isBlank() && value.length() <= 128 && value.chars().noneMatch(c -> c < 32);
        };
    }
    private static boolean integer(String value, int min, int max) {
        try { int n = Integer.parseInt(value); return n >= min && n <= max; } catch (NumberFormatException ex) { return false; }
    }
}
```

- [ ] **Step 4: 实现五个档案**

`AbstractMySqlProfile.java`（包私有）：

```java
package com.example.governance.assets.driver;

import com.example.governance.assets.DataSourceDefinition;
import java.util.List;
import java.util.Map;

abstract class AbstractMySqlProfile implements DatabaseProfile {
    @Override public String type() { return "MYSQL"; }
    @Override public int defaultPort() { return 3306; }
    @Override public String jdbcUrl(DataSourceDefinition d) { return "jdbc:mysql://" + d.host() + ":" + d.port() + "/" + d.database(); }
    @Override public Map<String, String> defaultProperties() { return Map.of("connectTimeout", "5000", "socketTimeout", "10000"); }
    @Override public List<ExtraField> extraFields() { return List.of(); }
    Map<String, ParamSpec> commonParams() {
        return Map.of(
            "useSSL", ParamSpec.bool("useSSL", "使用 SSL", "true/false"),
            "useUnicode", ParamSpec.bool("useUnicode", "使用 Unicode", "true/false"),
            "characterEncoding", ParamSpec.enumOf("characterEncoding", "字符编码", List.of("UTF-8", "GBK", "GB18030", "latin1"), "编码名"),
            "serverTimezone", ParamSpec.zone("serverTimezone", "服务器时区", "如 Asia/Shanghai 或 +08:00"),
            "connectTimeout", ParamSpec.millis("connectTimeout", "连接超时(ms)", "0..3600000"),
            "socketTimeout", ParamSpec.millis("socketTimeout", "读取超时(ms)", "0..3600000"));
    }
}
```

`MySql5Profile.java`：`@org.springframework.stereotype.Component`；id `MYSQL:5.x`；displayName `MySQL 5.x`；driverClassName `com.mysql.jdbc.Driver`；defaultDriverKey `builtin-mysql-5`；extraParams = commonParams + `zeroDateTimeBehavior` ENUM `exception/round/convertToNull`（用 `java.util.stream.Stream.concat` 或新建 HashMap 合并）。

`MySql8Profile.java`：id `MYSQL:8.x`；displayName `MySQL 8.x`；`com.mysql.cj.jdbc.Driver`；`builtin-mysql-8`；extraParams = commonParams + `allowPublicKeyRetrieval` bool + `zeroDateTimeBehavior` ENUM `EXCEPTION/ROUND/CONVERT_TO_NULL`。

`PostgreSqlProfile.java`：`@Component`；id `POSTGRESQL:general`；displayName `PostgreSQL`；port 5432；`org.postgresql.Driver`；`builtin-postgresql`；URL `jdbc:postgresql://host:port/db`；defaults `connectTimeout=5, socketTimeout=10`；extraFields 空；extraParams：`connectTimeout` seconds、`socketTimeout` seconds、`ssl` bool、`sslmode` ENUM `disable/allow/prefer/require/verify-ca/verify-full`、`currentSchema` identifier、`ApplicationName` text。

`AbstractOracleProfile.java`（包私有）：type ORACLE；port 1521；driverClassName `oracle.jdbc.OracleDriver`；defaults `oracle.net.CONNECT_TIMEOUT=5000, oracle.jdbc.ReadTimeout=10000`；extraFields `List.of(new ExtraField("connectType", "连接方式", List.of("SID", "SERVICE"), defaultConnectType()))`；抽象 `String defaultConnectType()`；

```java
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
```

`Oracle11Profile.java`：`@Component`；id `ORACLE:11g`；displayName `Oracle 11g`；defaultConnectType `SID`；defaultDriverKey `builtin-ojdbc8`。
`Oracle12Profile.java`：`@Component`；id `ORACLE:12c+`；displayName `Oracle 12c 及以上`；defaultConnectType `SERVICE`；defaultDriverKey `builtin-ojdbc11`。

- [ ] **Step 5: 实现注册表**

```java
package com.example.governance.assets.driver;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Collects all profile strategies; lookup by id and catalog for the form API. */
public final class DatabaseProfileRegistry {
    public record ParamView(String key, String label, String kind, List<String> values, String hint) {}
    public record ProfileView(String id, String type, String displayName, int defaultPort, String driverClassName, String defaultDriverKey, List<DatabaseProfile.ExtraField> extraFields, List<ParamView> extraParams) {}
    private final Map<String, DatabaseProfile> byId;
    public DatabaseProfileRegistry(List<DatabaseProfile> profiles) {
        byId = profiles.stream().collect(Collectors.toUnmodifiableMap(DatabaseProfile::id, Function.identity()));
        if (byId.size() != profiles.size()) throw new IllegalArgumentException("Duplicate database profile id");
    }
    public DatabaseProfile resolve(String id) {
        DatabaseProfile profile = byId.get(id);
        if (profile == null) throw new IllegalArgumentException("Unknown database profile: " + id);
        return profile;
    }
    public List<DatabaseProfile> all() { return byId.values().stream().sorted(Comparator.comparing(DatabaseProfile::id)).toList(); }
    public Set<String> types() { return all().stream().map(DatabaseProfile::type).collect(Collectors.toCollection(LinkedHashSet::new)); }
    public List<ProfileView> views() {
        return all().stream().map(p -> new ProfileView(p.id(), p.type(), p.displayName(), p.defaultPort(), p.driverClassName(), p.defaultDriverKey(), p.extraFields(),
            p.extraParams().values().stream().sorted(Comparator.comparing(DatabaseProfile.ParamSpec::key))
                .map(s -> new ParamView(s.key(), s.label(), s.kind().name(), s.values(), s.hint())).toList())).toList();
    }
}
```

- [ ] **Step 6: 跑测试绿 + Commit**

`mvn -q test -Dtest=DatabaseProfileTest` → PASS。
`git add -A && git commit -m "feat(datasource): 数据库版本档案策略与参数白名单"`

---

### Task 2: DataSourceDefinition 重塑 + Store/Inspector/执行 Provider 迁移（先 record，后接线）

**Files:**
- Modify: `backend/src/main/java/com/example/governance/assets/DataSourceDefinition.java`（整体重写）
- Modify: `.../assets/DataSourceStore.java`（SQL 与行映射）
- Modify: `.../assets/DatabaseInspector.java`（删除 switch，委托工厂）
- Modify: `.../assets/ExecutionDataSourceProvider.java`（委托工厂）
- Modify: `.../assets/DataSourceConfiguration.java`、`.../workflow/WorkflowConfiguration.java`（Bean 接线）
- Create: `.../assets/driver/ConnectionFactory.java`
- Modify(test): `DataSourceDefinitionTest.java`、`DataSourceStoreTest.java`、`DatabaseInspectorTest.java`、`ExecutionDataSourceProviderTest.java` 及所有构造旧 record 的测试
- Create(test): `.../assets/driver/ConnectionFactoryTest.java`（H2 TCP 全链路）

- [ ] **Step 1: 重写 DataSourceDefinition（先改测试再改实现）**

新 record（完整代码）：

```java
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
```

`DataSourceDefinitionTest` 重写：URL 断言移到档案测试（Task 1），这里保留注入/端口/库名校验断言（用 `"MYSQL:8.x"` 等 profileId），新增：profileId 非法拒绝、`type()` 派生、options 不可变、driverId<1 拒绝。

- [ ] **Step 2: 全仓修复旧 record 引用**

`grep -rn "DataSourceDefinition.Type\|new DataSourceDefinition(" backend/src` 逐处改为新构造（`Type.MYSQL`→`"MYSQL:8.x"`、`Type.POSTGRESQL`→`"POSTGRESQL:general"`、`Type.ORACLE`→`"ORACLE:12c+"`）。`ExecutionDataSourceProviderTest` 改为断言工厂委托（见 Step 5）。此时编译仍红（Store/Inspector 未改），继续。

- [ ] **Step 3: V30 迁移 + Store 改造**

`backend/src/main/resources/db/mysql/V30__jdbc_driver_management.sql`：

```sql
CREATE TABLE jdbc_drivers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    driver_key VARCHAR(80) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    db_type VARCHAR(40) NOT NULL,
    driver_class VARCHAR(200) NOT NULL,
    version_label VARCHAR(60),
    original_filename VARCHAR(255),
    jar_path VARCHAR(500) NOT NULL,
    sha256 CHAR(64) NOT NULL UNIQUE,
    builtin BOOLEAN NOT NULL DEFAULT FALSE,
    uploaded_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE data_sources ADD COLUMN version_profile VARCHAR(80);
ALTER TABLE data_sources ADD COLUMN driver_id BIGINT;
ALTER TABLE data_sources ADD COLUMN extra_options TEXT;
UPDATE data_sources SET version_profile = CASE source_type WHEN 'MYSQL' THEN 'MYSQL:8.x' WHEN 'POSTGRESQL' THEN 'POSTGRESQL:general' WHEN 'ORACLE' THEN 'ORACLE:12c+' END, extra_options = '{}' WHERE version_profile IS NULL;
ALTER TABLE data_sources MODIFY version_profile VARCHAR(80) NOT NULL;
ALTER TABLE data_sources ADD CONSTRAINT fk_data_sources_driver FOREIGN KEY (driver_id) REFERENCES jdbc_drivers(id);
INSERT INTO permission_catalog (permission_code,module_code,module_name,action_code,action_name,description,sort_order)
SELECT 'datasource.driver.read','datasource.driver','驱动管理','read','查看','读取 JDBC 驱动列表',COALESCE(MAX(sort_order),0)+1 FROM permission_catalog;
INSERT INTO permission_catalog (permission_code,module_code,module_name,action_code,action_name,description,sort_order)
SELECT 'datasource.driver.upload','datasource.driver','驱动管理','upload','上传','上传 JDBC 驱动 jar',COALESCE(MAX(sort_order),0)+1 FROM permission_catalog;
INSERT INTO permission_catalog (permission_code,module_code,module_name,action_code,action_name,description,sort_order)
SELECT 'datasource.driver.delete','datasource.driver','驱动管理','delete','删除','删除上传的 JDBC 驱动',COALESCE(MAX(sort_order),0)+1 FROM permission_catalog;
INSERT IGNORE INTO role_permissions (role_id, permission) SELECT 1, permission_code FROM permission_catalog;
INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order,visible,enabled)
VALUES (23,2,'驱动管理','M','/assets/drivers','assets/drivers','','driver',30,TRUE,TRUE);
```

`db/migration/V30__jdbc_driver_management.sql`（H2）：同上，差异：
- `id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY`
- `ALTER TABLE data_sources ALTER COLUMN version_profile SET NOT NULL;`（代替 MODIFY）
- 权限授予用：`INSERT INTO role_permissions (role_id, permission) SELECT 1, catalog.permission_code FROM permission_catalog catalog WHERE NOT EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id=1 AND rp.permission=catalog.permission_code);`

`DataSourceStore`：INSERT/UPDATE 增加 `version_profile,driver_id,extra_options`，`source_type` 写 `definition.type()`；find/list/credentialsForLockedRefresh 的 SELECT 与行映射读新列；options JSON 用静态 `ObjectMapper`（写：`writeValueAsString`，读：`readValue(..., new TypeReference<Map<String,String>>(){})`，null/空→`Map.of()`）；driver_id 用 `rs.getLong` + `wasNull`。
所有测试中裸 `INSERT INTO data_sources(...)` 增加 `version_profile` 列值（`'MYSQL:8.x'` 等）。

- [ ] **Step 4: ConnectionFactory + Inspector/Provider 委托（先写全链路失败测试）**

`ConnectionFactoryTest.java`（纯对象，无 Spring；H2 TCP 全链路）：

```java
package com.example.governance.assets.driver;

import com.example.governance.assets.DataSourceDefinition;
import com.example.governance.assets.DataSourceStore;
import com.example.governance.assets.CredentialCipher;
import com.example.governance.assets.DatabaseInspector;
import org.flywaydb.core.Flyway;
import org.h2.tools.Server;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionFactoryTest {
    static Server tcp;
    static Path h2Jar;
    @BeforeAll static void start() throws Exception {
        tcp = Server.createTcpServer("-tcpPort", "0", "-tcpAllowOthers", "false").start();
        h2Jar = Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
    @AfterAll static void stop() { tcp.stop(); }

    private record Fixture(DriverStore drivers, ConnectionFactory factory, JdbcTemplate jdbc) {}
    private Fixture fixture() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db);
        var loader = new IsolatedDriverLoader();
        var drivers = new DriverStore(jdbc, loader, Path.of(System.getProperty("java.io.tmpdir"), "gov-drivers-" + UUID.randomUUID()));
        var registry = new DatabaseProfileRegistry(List.of(new H2TestProfile()));
        return new Fixture(drivers, new ConnectionFactory(registry, drivers), jdbc);
    }
    /** Test-only profile: H2 over TCP, mem: prefix lives in the URL template. */
    static final class H2TestProfile implements DatabaseProfile {
        public String id() { return "H2:test"; }
        public String type() { return "H2"; }
        public String displayName() { return "H2 (test)"; }
        public int defaultPort() { return tcp.getPort(); }
        public String driverClassName() { return "org.h2.Driver"; }
        public String defaultDriverKey() { return "builtin-h2-test"; }
        public String jdbcUrl(DataSourceDefinition d) { return "jdbc:h2:tcp://" + d.host() + ":" + d.port() + "/mem:" + d.database(); }
        public Map<String, String> defaultProperties() { return Map.of(); }
        public Map<String, ParamSpec> extraParams() { return Map.of("ACCESS_MODE_DATA", ParamSpec.enumOf("ACCESS_MODE_DATA", "访问模式", List.of("r", "rw"), "r/rw")); }
        public List<ExtraField> extraFields() { return List.of(); }
    }

    @Test void uploadedDriverConnectsThroughIsolatedLoaderAndInspector() throws Exception {
        var f = fixture();
        var record = f.drivers.upload("H2 驱动", "H2", "org.h2.Driver", null, "h2.jar", Files.readAllBytes(h2Jar), null, java.util.Set.of("H2"));
        var definition = new DataSourceDefinition("h2", "H2:test", "localhost", tcp.getPort(), "chaindb", "sa", record.id(), Map.of());
        try (var connection = f.factory.open(definition, "testpwd"); var st = connection.createStatement()) {
            st.execute("CREATE TABLE T (ID INT PRIMARY KEY)");
        }
        var inspector = new DatabaseInspector(f.factory);
        assertTrue(inspector.test(definition, "testpwd"));
        assertTrue(inspector.tables(definition, "testpwd", null, "PUBLIC").stream().anyMatch(t -> t.name().equals("T")));
    }
    @Test void rejectsDriverTypeMismatchAndUnknownProfileAndBadParams() throws Exception {
        var f = fixture();
        var record = f.drivers.upload("H2 驱动", "H2", "org.h2.Driver", null, "h2.jar", Files.readAllBytes(h2Jar), null, java.util.Set.of("H2"));
        var mismatch = new DataSourceDefinition("x", "MYSQL:8.x", "localhost", 3306, "db", "u", record.id(), Map.of());
        // registry 只有 H2 档案时 MYSQL:8.x 未知档案先报错；另构造同类型不匹配场景：
        assertThrows(IllegalArgumentException.class, () -> f.factory.open(mismatch, "p"));
        var badParam = new DataSourceDefinition("x", "H2:test", "localhost", tcp.getPort(), "db2", "sa", record.id(), Map.of("allowLoadLocalInfile", "true"));
        assertThrows(IllegalArgumentException.class, () -> f.factory.open(badParam, "p"));
    }
    @Test void storeRoundTripsProfileDriverAndOptions() throws Exception {
        var f = fixture();
        var record = f.drivers.upload("H2 驱动", "H2", "org.h2.Driver", null, "h2.jar", Files.readAllBytes(h2Jar), null, java.util.Set.of("H2"));
        var store = new DataSourceStore(f.jdbc, new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32])));
        var definition = new DataSourceDefinition("rt", "H2:test", "localhost", tcp.getPort(), "rt", "sa", record.id(), Map.of("ACCESS_MODE_DATA", "rw"));
        long id = store.create(definition, "secret");
        assertEquals(definition, store.find(id).orElseThrow());
        assertEquals("H2", f.jdbc.queryForObject("SELECT source_type FROM data_sources WHERE id=?", String.class, id));
    }
}
```

- [ ] **Step 5: 实现 ConnectionFactory、IsolatedDriverLoader、DriverStore（使上测试绿）**

`ConnectionFactory.java`：

```java
package com.example.governance.assets.driver;

import com.example.governance.assets.DataSourceDefinition;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.stream.Collectors;

/** Single entry point for business connections: profile -> driver -> URL + whitelisted properties. */
public final class ConnectionFactory {
    private final DatabaseProfileRegistry registry;
    private final DriverStore drivers;
    public ConnectionFactory(DatabaseProfileRegistry registry, DriverStore drivers) { this.registry = registry; this.drivers = drivers; }
    public DatabaseProfile validate(DataSourceDefinition definition) {
        DatabaseProfile profile = registry.resolve(definition.profileId());
        profile.validate(definition);
        var fieldKeys = profile.extraFields().stream().map(DatabaseProfile.ExtraField::key).collect(Collectors.toSet());
        var params = definition.options().entrySet().stream().filter(e -> !fieldKeys.contains(e.getKey())).collect(Collectors.toMap(java.util.Map.Entry::getKey, java.util.Map.Entry::getValue));
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
```

`IsolatedDriverLoader.java` 与 `DriverStore.java`：按 spec §5 实现（完整代码见 spec 对应节；要点：URLClassLoader parent=平台加载器；`Driver.connect` 直连；Proxy 包装 Connection 在 close 时减引用；retire 引用归零关闭；probe 一次性加载校验并返回 major.minor；upload 校验顺序=名称/类型/类名正则/大小/zip 合法/sha 去重/临时落盘 probe/原子移动/入库；delete 内置拒删、引用 409 异常 `DriverInUseException(List<String> references)`；`resolveForProfile` 校验 db_type 与档案 type 一致；BUILTINS 五项常量，versionLabel 用 probe 探测）。

`DatabaseInspector`：删除 `connect` 静态方法与 switch；构造器 `public DatabaseInspector(ConnectionFactory factory) { this(factory::open); }`，保留包私有 `Connector` 构造器；删除无参构造。
`ExecutionDataSourceProvider`：构造器加 `ConnectionFactory`；`get` 返回 `AbstractDataSource` 匿名实现，`getConnection()` → `factory.open(definition, password)`，`getConnection(u,p)` → 用 u 替换 username 后 open。
`ExecutionDataSourceProviderTest` 重写：mock store + 真实 factory（H2 fixture 同上）或 mock factory 验证委托与 unknown 拒绝。

- [ ] **Step 6: Bean 接线**

`DataSourceConfiguration` 增加：

```java
@Bean IsolatedDriverLoader isolatedDriverLoader() { return new IsolatedDriverLoader(); }
@Bean DatabaseProfileRegistry databaseProfileRegistry(java.util.List<com.example.governance.assets.driver.DatabaseProfile> profiles) { return new DatabaseProfileRegistry(profiles); }
@Bean DriverStore driverStore(JdbcTemplate jdbc, IsolatedDriverLoader loader, org.flywaydb.core.Flyway flyway, @Value("${governance.driver-dir:./drivers}") String dir) {
    flyway.migrate(); // 确保建表先于内置驱动注册（幂等）
    var store = new DriverStore(jdbc, loader, java.nio.file.Path.of(dir));
    store.registerBuiltins();
    return store;
}
@Bean ConnectionFactory connectionFactory(DatabaseProfileRegistry registry, DriverStore drivers) { return new ConnectionFactory(registry, drivers); }
@Bean DatabaseInspector databaseInspector(ConnectionFactory factory) { return new DatabaseInspector(factory); }
```

`WorkflowConfiguration.executionDataSourceProvider(DataSourceStore, ConnectionFactory)`。
`backend/src/test/resources/application.properties` 追加 `governance.driver-dir=${java.io.tmpdir}/gov-drivers-${random.uuid}`。
`application.yml` 追加：

```yaml
spring.servlet.multipart.max-file-size: 50MB
spring.servlet.multipart.max-request-size: 52MB
governance.driver-dir: ${GOVERNANCE_DRIVER_DIR:./drivers}
```

- [ ] **Step 7: 内置驱动打包（pom）**

`pom.xml` build/plugins 增加 maven-dependency-plugin：process-resources 阶段 `copy` 五个 artifactItem 到 `${project.build.outputDirectory}/drivers-builtin`，destFileName 分别 `builtin-mysql-5.jar`（mysql:mysql-connector-java:5.1.49）、`builtin-mysql-8.jar`（com.mysql:mysql-connector-j:${mysql.version}）、`builtin-postgresql.jar`（org.postgresql:postgresql:${postgresql.version}）、`builtin-ojdbc8.jar`（com.oracle.database.jdbc:ojdbc8:19.3.0.0）、`builtin-ojdbc11.jar`（com.oracle.database.jdbc:ojdbc11:${oracle-database.version}）。保留现有 runtime 依赖不动。

- [ ] **Step 8: 全量测试绿 + Commit**

`mvn -q test`（此时 DataSourceController 仍用旧请求记录会编译失败——Step 2 已把 controller 的 `CreateRequest/Item` 一并改为 profileId 形态，见 Task 3 Step 1 前置；若编译红先完成 Task 3 的 controller 改造再回跑）。
`git add -A && git commit -m "feat(datasource): 连接工厂与隔离驱动加载，数据源按版本档案存储"`

---

### Task 3: 权限/审计目录 + DriverController + profiles API

**Files:**
- Modify: `backend/src/main/java/com/example/governance/security/PermissionCatalog.java`
- Modify: `.../security/AuditCatalog.java`
- Modify: `.../assets/DataSourceController.java`
- Create: `.../assets/DriverController.java`
- Test: `backend/src/test/java/com/example/governance/assets/DriverApiTest.java`
- Modify(test): `DataSourceApiTest.java`（请求体 type→profileId）

- [ ] **Step 1: DataSourceController 改造（Task 2 编译依赖，先行）**

`CreateRequest(String name, String profileId, String host, int port, String database, String username, String password, Long driverId, Map<String,String> options)`；
`Item(long id, String name, String type, String profileId, String profileName, String host, int port, String database, String username, Long driverId, Map<String,String> options)`；
构造器注入 `ConnectionFactory factory, DatabaseProfileRegistry registry`；create/update 先 `factory.validate(definition)`（IAE→400）；list 的 profileName 用 `registry.resolve(d.profileId()).displayName()`（未知档案兜底 profileId）；新增：

```java
@GetMapping("/api/data-sources/profiles")
List<DatabaseProfileRegistry.ProfileView> profiles() { return registry.views(); }
```

`DataSourceApiTest` 所有请求体 `"type":"MYSQL"` → `"profileId":"MYSQL:8.x"`（POSTGRESQL/ORACLE 同理）。

- [ ] **Step 2: 写失败 API 测试 DriverApiTest**

沿用 `DataSourceApiTest` 的 setup/login 模式（@SpringBootTest + @AutoConfigureMockMvc + @MockitoBean DatabaseInspector/CamelTaskRunner/ExecutionDataSourceProvider/IncrementalSyncExecutor）。用例：
1. `driverEndpointsRequirePermission`：无权限 GET /api/drivers → 403；插入 `datasource.driver.read` 后 → 200 且含 5 个内置驱动（`$[?(@.builtin==true)]` 长度 5）。
2. `uploadListAndDeleteUploadedDriver`：授 read/upload/delete；用 `MockMultipartFile("file","h2.jar","application/java-archive", Files.readAllBytes(h2Jar))` + param name/dbType=H2?——**注意**：生产注册表无 H2 类型，上传 dbType 用 `MYSQL` + driverClass `org.h2.Driver` 会被 probe 加载成功但类型不匹配校验在连接时；上传校验只查 knownTypes 与类可加载，故 dbType=MYSQL、driverClass=org.h2.Driver 可通过上传（类型匹配在 resolveForProfile 才校验）。断言 201；列表出现 builtin=false 项；DELETE → 204；审计 `driver.upload`/`driver.delete` 各 1 条。
3. `uploadRejectsBadInput`：非 jar 字节 → 400；缺驱动类（driverClass=com.example.Missing）→ 400；重复 sha 再传 → 400；未知 dbType=DB2 → 400。
4. `builtinDriverCannotBeDeleted`：取内置 id DELETE → 400。
5. `referencedDriverCannotBeDeleted`：上传后 INSERT data_sources(driver_id=该驱动, version_profile='MYSQL:8.x', ...)，DELETE → 409 且 `$.references[0]` 为数据源名。
6. `profilesCatalogRequiresSourceRead`：无权限 GET /api/data-sources/profiles → 403；授 `datasource.source.read` → 200，`$[0].id` 存在、MYSQL:8.x 项含 extraParams 白名单（allowPublicKeyRetrieval）。
7. `createWithProfileAndOptionsPersists`：授 source.create/read；POST profileId=MYSQL:8.x + options{serverTimezone:Asia/Shanghai} → 201；GET 列表 profileId/options 回显；非法 options（allowLoadLocalInfile）→ 400；未知 profileId → 400。

- [ ] **Step 3: 运行确认红**（404/编译失败）

- [ ] **Step 4: PermissionCatalog + AuditCatalog**

PermissionCatalog：
- `add()` switch 增 `case "上传" -> "upload";`
- buildEntries 增三行：`add(values, "datasource.driver", "驱动管理", "查看", "读取 JDBC 驱动列表"); add(values, "datasource.driver", "驱动管理", "上传", "上传 JDBC 驱动 jar"); add(values, "datasource.driver", "驱动管理", "删除", "删除上传的 JDBC 驱动");`
- moduleForComponent 增 `case "assets/drivers" -> "datasource.driver";`
- modulesForComponent `"assets"` → `Set.of("datasource.source", "datasource.dataset", "datasource.driver")`
- buildRoutes 增（data-sources 段首）：`route(routes, "GET", "/api/data-sources/profiles", "datasource.source.read");` 及 `GET /api/drivers → datasource.driver.read`、`POST /api/drivers → datasource.driver.upload`、`DELETE /api/drivers/* → datasource.driver.delete`。

AuditCatalog：ACTIONS 增 `Map.entry("driver.upload", "上传驱动"), Map.entry("driver.delete", "删除驱动")`；OBJECT_TYPES 增 `Map.entry("driver", "JDBC 驱动")`。

- [ ] **Step 5: DriverController 实现**

```java
package com.example.governance.assets;

import com.example.governance.assets.driver.DatabaseProfileRegistry;
import com.example.governance.assets.driver.DriverStore;
import com.example.governance.security.AccountPrincipal;
import com.example.governance.security.AuditLog;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class DriverController {
    private final DriverStore drivers;
    private final DatabaseProfileRegistry registry;
    private final AuditLog audit;
    public DriverController(DriverStore drivers, DatabaseProfileRegistry registry, AuditLog audit) { this.drivers = drivers; this.registry = registry; this.audit = audit; }
    record Item(long id, String name, String dbType, String driverClass, String versionLabel, boolean builtin, Long uploadedBy, String createdAt, String sha256, String originalFilename) {}
    @GetMapping("/api/drivers")
    List<Item> list() {
        return drivers.list().stream().map(d -> new Item(d.id(), d.name(), d.dbType(), d.driverClass(), d.versionLabel(), d.builtin(), d.uploadedBy(), String.valueOf(d.createdAt()), d.sha256(), d.originalFilename())).toList();
    }
    @PostMapping("/api/drivers")
    ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file, @RequestParam String name, @RequestParam String dbType, @RequestParam String driverClass, @RequestParam(required = false) String versionLabel, Authentication authentication) {
        try {
            long actorId = ((AccountPrincipal) authentication.getPrincipal()).id();
            var record = drivers.upload(name, dbType, driverClass, versionLabel, file.getOriginalFilename(), file.getBytes(), actorId, registry.types());
            audit.success(authentication, "driver.upload", "driver", record.id());
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", record.id(), "name", record.name()));
        } catch (IllegalArgumentException ex) { return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage())); }
        catch (IOException ex) { return ResponseEntity.badRequest().body(Map.of("error", "驱动文件读取失败")); }
    }
    @DeleteMapping("/api/drivers/{id}")
    ResponseEntity<Map<String, Object>> delete(@PathVariable long id, Authentication authentication) {
        try {
            drivers.delete(id);
            audit.success(authentication, "driver.delete", "driver", id);
            return ResponseEntity.noContent().build();
        } catch (DriverStore.DriverInUseException ex) { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "驱动仍被数据源引用", "references", ex.references())); }
        catch (IllegalStateException ex) { return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage())); }
        catch (IllegalArgumentException ex) { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "驱动不存在")); }
    }
}
```

- [ ] **Step 6: 全量测试绿 + Commit**

`mvn -q test` → 全绿。`git add -A && git commit -m "feat(datasource): 驱动管理 API、档案目录与权限审计接入"`

---

### Task 4: 隔离加载器专项测试（共存/引用计数/校验）

**Files:**
- Create: `backend/src/test/java/com/example/governance/assets/driver/IsolatedDriverLoaderTest.java`
- Create: `backend/src/test/java/com/example/governance/assets/driver/DriverStoreTest.java`

- [ ] **Step 1: 写失败测试**

IsolatedDriverLoaderTest：
1. `loadsSameJarInTwoIsolatedLoaders`：h2Jar 用两个 driverId（1、2）分别 connect（URL `jdbc:h2:mem:iso1/iso2`，props user=sa/password 空串），两个连接的 `org.h2.Driver` 类对象不同（`connection.getMetaData().getDriverName()` 相同但通过 unwrap 后 getClass().getClassLoader() 不同）；断言两连接都可用。
2. `loaderClosesAfterLastConnectionClosed`：connect→close 后 `loader.retire(1)`；再 connect 同 id 会重新加载（新实例）——用计数/类加载器身份断言。
3. `probeRejectsMissingClassAndReturnsVersion`：probe(h2Jar,"org.h2.Driver") 返回 "2.x" 形态；probe(h2Jar,"com.example.Missing") 抛 IAE。
4. `builtinMysql5And8Coexist`：从 classpath `/drivers-builtin/builtin-mysql-5.jar`、`builtin-mysql-8.jar` 复制到临时文件，分别 connect 失败预期（无真实 MySQL，`Driver.connect` 会抛通信异常）——改为断言两个加载器各自能加载驱动类：用 probe 分别返回 5.1 与 9.x，且两次 probe 的类加载器互不可见（probe 内部验证即可，断言版本号前缀不同）。

DriverStoreTest（H2 + Flyway，参照 ConnectionFactoryTest.fixture）：
1. `registerBuiltinsIdempotent`：new DriverStore(...).registerBuiltins() 两次 → jdbc_drivers 恒 5 行，builtin=true，version_label 非空。
2. `uploadDeduplicatesBySha`：同字节二次上传抛 IAE。
3. `deleteRemovesFileAndRow`：上传→delete→行消失、jar 文件不存在。
4. `deleteBuiltinRejected`、`deleteReferencedListsNames`（INSERT data_sources 引用）。

- [ ] **Step 2: 红 → 实现补齐（若 Task 2 实现已覆盖则直接绿；缺口补实现）→ 全量绿 → Commit**

`git add -A && git commit -m "test(datasource): 隔离加载与驱动仓库专项覆盖"`

---

### Task 5: 前端表单纯函数 + 测试

**Files:**
- Create: `frontend/src/datasource-form.js`
- Create: `frontend/tests/datasource-form.test.js`

- [ ] **Step 1: 写失败测试**（node --test，覆盖下列每个函数）

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { profileTypes, versionsForType, defaultForm, databaseLabel, driversForType, paramRows, buildPayload } from '../src/datasource-form.js'

const profiles = [
  { id: 'MYSQL:5.x', type: 'MYSQL', displayName: 'MySQL 5.x', defaultPort: 3306, extraFields: [], extraParams: [] },
  { id: 'MYSQL:8.x', type: 'MYSQL', displayName: 'MySQL 8.x', defaultPort: 3306, extraFields: [], extraParams: [{ key: 'serverTimezone' }] },
  { id: 'ORACLE:11g', type: 'ORACLE', displayName: 'Oracle 11g', defaultPort: 1521, extraFields: [{ key: 'connectType', label: '连接方式', options: ['SID', 'SERVICE'], defaultValue: 'SID' }], extraParams: [] },
  { id: 'ORACLE:12c+', type: 'ORACLE', displayName: 'Oracle 12c 及以上', defaultPort: 1521, extraFields: [{ key: 'connectType', label: '连接方式', options: ['SID', 'SERVICE'], defaultValue: 'SERVICE' }], extraParams: [] }
]
test('profileTypes deduplicates with labels', () => {
  assert.deepEqual(profileTypes(profiles), [{ value: 'MYSQL', label: 'MySQL' }, { value: 'ORACLE', label: 'Oracle' }])
})
test('versionsForType filters', () => { assert.deepEqual(versionsForType(profiles, 'MYSQL').map(p => p.id), ['MYSQL:5.x', 'MYSQL:8.x']) })
test('defaultForm carries port and extra field defaults', () => {
  assert.deepEqual(defaultForm(profiles[2]), { profileId: 'ORACLE:11g', port: 1521, options: { connectType: 'SID' } })
})
test('databaseLabel follows oracle connect type', () => {
  assert.equal(databaseLabel(profiles[2], {}), 'SID')
  assert.equal(databaseLabel(profiles[3], { connectType: 'SID' }), 'SID')
  assert.equal(databaseLabel(profiles[3], {}), '服务名')
  assert.equal(databaseLabel(profiles[0], {}), '数据库')
})
test('driversForType filters by dbType', () => {
  assert.deepEqual(driversForType([{ id: 1, dbType: 'MYSQL' }, { id: 2, dbType: 'ORACLE' }], 'MYSQL'), [{ id: 1, dbType: 'MYSQL' }])
})
test('paramRows excludes extra fields and empty values', () => {
  assert.deepEqual(paramRows(profiles[2], { connectType: 'SID', 'oracle.jdbc.ReadTimeout': '10000' }), [{ key: 'oracle.jdbc.ReadTimeout', value: '10000' }])
})
test('buildPayload nulls blank password on edit and drops empty options', () => {
  const form = { name: 'n', profileId: 'MYSQL:8.x', host: 'h', port: 3306, database: 'd', username: 'u', password: '', driverId: null, options: { connectType: 'SID', serverTimezone: '' } }
  const payload = buildPayload(form, true)
  assert.equal(payload.password, null)
  assert.deepEqual(payload.options, { connectType: 'SID' })
  assert.equal(buildPayload({ ...form, password: 'x' }, false).password, 'x')
})
```

- [ ] **Step 2: 红 → 实现 datasource-form.js**

```js
export const typeLabels = { MYSQL: 'MySQL', POSTGRESQL: 'PostgreSQL', ORACLE: 'Oracle' }
export function profileTypes(profiles) {
  const seen = new Map()
  for (const p of profiles || []) if (!seen.has(p.type)) seen.set(p.type, typeLabels[p.type] || p.type)
  return [...seen].map(([value, label]) => ({ value, label }))
}
export function versionsForType(profiles, type) { return (profiles || []).filter(p => p.type === type) }
export function defaultForm(profile) {
  return { profileId: profile?.id || '', port: profile?.defaultPort ?? 3306, options: Object.fromEntries((profile?.extraFields || []).map(f => [f.key, f.defaultValue])) }
}
export function databaseLabel(profile, options) {
  if (profile?.type !== 'ORACLE') return '数据库'
  const fallback = (profile?.extraFields || []).find(f => f.key === 'connectType')?.defaultValue
  return (options?.connectType || fallback) === 'SID' ? 'SID' : '服务名'
}
export function driversForType(drivers, type) { return (drivers || []).filter(d => d.dbType === type) }
export function paramRows(profile, options) {
  const fields = new Set((profile?.extraFields || []).map(f => f.key))
  return Object.entries(options || {}).filter(([k, v]) => !fields.has(k) && v !== '' && v != null).map(([key, value]) => ({ key, value }))
}
export function buildPayload(form, editing) {
  const options = {}
  for (const [k, v] of Object.entries(form.options || {})) if (v !== '' && v != null) options[k] = String(v)
  return { name: form.name, profileId: form.profileId, host: form.host, port: form.port, database: form.database, username: form.username, password: editing && !form.password ? null : form.password, driverId: form.driverId || null, options }
}
```

- [ ] **Step 3: 绿 + Commit**

`node --test tests/datasource-form.test.js` → PASS。`git add -A && git commit -m "feat(frontend): 数据源表单纯函数与测试"`

---

### Task 6: DataSources.vue 表单改造 + 驱动管理视图 + 图标

**Files:**
- Modify: `frontend/src/DataSources.vue`
- Modify: `frontend/src/menu-icons.js`（增 driver 图标与标签）

- [ ] **Step 1: menu-icons.js**

`menuIconPaths` 增 `driver: 'M9 3v4m6-4v4M7 7h10v3a5 5 0 0 1-10 0V7Zm5 8v6'`；labels 增 `driver: '驱动管理'`。

- [ ] **Step 2: DataSources.vue script 改造**

- import 增加 `profileTypes, versionsForType, defaultForm, databaseLabel, driversForType, paramRows, buildPayload`。
- 新状态：`profiles = ref([])`、`drivers = ref([])`、`driverSearch = ref('')`、`driverPage = ref(1)`、`driverSize = ref(10)`、`uploadOpen = ref(false)`、`uploadForm = ref({ name: '', dbType: 'MYSQL', driverClass: '', versionLabel: '', file: null })`、`uploading = ref(false)`、`advanced = ref([])`（行 {key,value}）。
- form 改：`{ name: '', type: 'MYSQL', profileId: '', host: '', port: 3306, database: '', username: '', password: '', driverId: null, connectType: 'SERVICE' }`。
- computed：`typeOptions = profileTypes(profiles)`、`versionOptions = versionsForType(profiles, form.type)`、`currentProfile = profiles.find(p => p.id === form.profileId)`、`typeDrivers = driversForType(drivers, form.type)`、`dbLabel = databaseLabel(currentProfile, { connectType: form.connectType })`、`driversPage = assetPage(drivers, driverSearch, driverPage, driverSize)`。
- `applyProfile(profile)`：form.profileId=profile.id；form.port=profile.defaultPort；form.connectType=extraFields connectType 默认值；advanced=[]；form.driverId=null。
- `selectType(type)`：form.type=type；applyProfile(versionsForType(...)[0])。
- `watch(() => form.profileId)`：切换档案时 applyProfile（避免编辑回填触发：用标志位或仅在用户交互 change 事件调用 applyProfile，不用 watch）。
- `loadProfiles()`：can('datasource.source.read') 时 GET /api/data-sources/profiles。
- `loadDrivers()`：can('datasource.driver.read') 时 GET /api/drivers。
- `createSource()`：重置 form 后 applyProfile(profiles[0] 或 MYSQL:8.x)。
- `editSource(item)`：回填 type=item.type、profileId=item.profileId、driverId=item.driverId、connectType=item.options?.connectType || 档案默认；advanced=paramRows(profile, item.options)。
- `save()`：options = { ...(currentProfile 有 connectType 字段 ? { connectType: form.connectType } : {}), ...Object.fromEntries(advanced.filter(r => r.key).map(r => [r.key, r.value])) }；payload=buildPayload({ ...form, options }, Boolean(editingId))；其余不变。
- 驱动上传：`submitUpload()` 校验 file/name/driverClass；FormData append file/name/dbType/driverClass/versionLabel；`authorizedRequest('/api/drivers', { method: 'POST', body: fd })`（不设 Content-Type）；400 读 payload.error；成功后关对话框、loadDrivers。
- `deleteDriver(item)`：DELETE；409 → `驱动被数据源引用：${payload.references.join('、')}`；400 → payload.error；成功 loadDrivers。
- `loadForView()`：sources → load()+loadProfiles()；datasets → loadDatasets()；drivers → loadDrivers()。

- [ ] **Step 3: DataSources.vue template 改造**

- sources 表格增列：`<el-table-column prop="profileName" label="版本" min-width="120"/>`（type 列保留）。
- 表单对话框：类型下拉用 typeOptions（@change=selectType）；新增"大版本"下拉 versionOptions（label=displayName，value=id，@change=applyProfile）；端口默认随档案；Oracle 时显示 connectType el-radio-group（SID/服务名）；"数据库或服务名" label 改 `:label="dbLabel"`；驱动 el-select（clearable，placeholder="内置默认驱动"，选项 typeDrivers，label=`${name} ${versionLabel||''}${builtin?'（内置）':''}`，value=id）；"高级参数" el-collapse：行=key el-select（currentProfile.extraParams，allow-create=false）+ value el-input（hint 占位）+ 删除按钮；"添加参数"按钮 push {key:'',value:''}。
- 新增 `<template v-else-if="view === 'drivers'">`：toolbar（搜索、上传按钮 can('datasource.driver.upload')、刷新）；表格列 name/dbType/driverClass/versionLabel/来源（builtin tag 内置/上传）/createdAt/sha256 前 12 位/操作（删除 popconfirm，can delete，内置禁用）；分页。
- 上传对话框：名称、类型下拉（typeOptions）、驱动类输入、版本标签输入（可空，占位"自动探测"）、el-upload（drag accept=".jar" :auto-upload="false" :limit="1" :on-change="f => uploadForm.file = f.raw" :on-remove="() => uploadForm.file = null"）、保存按钮。
- 现有 `v-else`（datasets）改 `v-else-if="view === 'datasets'"`。

- [ ] **Step 4: 前端验证 + Commit**

```bash
cd frontend && node --test tests/*.test.js && npm run build
```
预期全绿、build 成功。`git add -A && git commit -m "feat(frontend): 数据源版本档案表单与驱动管理页"`

---

### Task 7: 全量验证 + 文档更新

**Files:**
- Modify: `docs/verification/database-matrix.md`（新增待验证行）
- Modify: `README.md`（驱动管理说明 + GOVERNANCE_DRIVER_DIR）

- [ ] **Step 1: 后端全量**

`cd backend && mvn -q test` → Failures 0 / Errors 0。

- [ ] **Step 2: 前端全量**

`cd frontend && node --test tests/*.test.js && npm run build` → 全绿。

- [ ] **Step 3: database-matrix.md 增行**

MySQL 5.7（MYSQL:5.x + 5.1.49）、MySQL 8.0（MYSQL:8.x + Connector/J 9.5）、PostgreSQL（42.7.8）、Oracle 11.2.0.4（ojdbc8 19.3）、Oracle 19c（ojdbc11 23.7）：状态全部"待验证（无真实环境）"，注明 H2 全链路自动化已通过。

- [ ] **Step 4: README 增补**

数据源能力段落补充：类型+大版本档案、内置驱动清单、管理员上传 jar（驱动管理页）、白名单高级参数、`GOVERNANCE_DRIVER_DIR`（默认 ./drivers）。

- [ ] **Step 5: Commit**

`git add -A && git commit -m "docs: 驱动管理验证矩阵与运行说明"`

---

### Task 8: 重启服务并交付

- [ ] **Step 1: 停旧后端**

```bash
kill 47935 47958 2>/dev/null; sleep 3
lsof -iTCP:8080 -sTCP:LISTEN -P | tail -1   # 确认已释放
```
（PID 以实际 `ps aux | grep GovernanceApplication` 为准；前端 vite 41516 保留，HMR 自动生效。）

- [ ] **Step 2: 启动新后端**

```bash
cd ~/Documents/project/data_process/backend
set -a; source ~/.local/share/data-governance/backend.env; set +a
nohup mvn spring-boot:run >> ~/.local/share/data-governance/backend.log 2>&1 &
```

- [ ] **Step 3: 验证**

```bash
for i in $(seq 1 60); do curl -sf http://127.0.0.1:8080/api/health && break; sleep 3; done
curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8080/api/data-sources/profiles   # 预期 401/403（未登录，路由已登记）
curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:5173/                            # 预期 200
tail -30 ~/.local/share/data-governance/backend.log | grep -E "Flyway|Migrating|Successfully applied|Started"
```
确认 V30 迁移成功、内置驱动注册日志、无启动异常。若启动失败：读日志定位、修复、重跑相关测试后再启动（systematic-debugging）。

- [ ] **Step 4: 交付**

向用户报告：服务地址 http://127.0.0.1:5173（后端 API http://127.0.0.1:8080）、本次变更摘要、测试证据、真实库待验证项说明。

---

## Self-Review 结论

- Spec 覆盖：§3 五组件→Task 1/2/4；§4 档案→Task 1；§5 驱动管理→Task 2/3/4；§6 迁移→Task 2 Step 3；§7 API→Task 3；§8 前端→Task 5/6；§9 安全→白名单/权限/审计贯穿；§10 测试→各任务+Task 7；§11 扩展指南→spec 已含。
- 基线修复（Task 0）为测试侧机械修复，不改生产行为。
- 类型一致性：DataSourceDefinition 8 参 record 全文统一；DriverStore.DriverRecord/DriverInUseException、ProfileView/ParamView 命名前后一致。
- 执行顺序注意：Task 1 测试依赖 Task 2 Step 1 的 record，先改 record 再跑档案测试；Task 2 Step 8 的全量绿依赖 Task 3 Step 1 的 controller 改造。
