# 数据源连接层重构：版本档案 + 驱动管理 设计文档

日期：2026-11-19
状态：已与创作者逐节确认（范围、内置驱动、版本选择、表单形态、架构、安全、前端与测试）

## 1. 背景与目标

现状问题：

- 数据库类型是写死的枚举 `DataSourceDefinition.Type { MYSQL, POSTGRESQL, ORACLE }`，JDBC URL 模板硬编码。
- 驱动连接参数用 `switch(type)` 散落在 `DatabaseInspector` 与 `ExecutionDataSourceProvider` 两处，重复且新增类型需改多处。
- 驱动固定为 Maven runtime 依赖（mysql-connector-j 8.x、ojdbc11、postgresql），全部在应用 classpath：不支持上传 jar、不能选版本、同包名多版本无法共存。
- Oracle 仅支持服务名 URL（`@//host:port/service`），不支持 11g 常用的 SID 写法。

目标：

1. 用策略 + 注册表 + 工厂重构连接层，新增数据库类型 = 新增一个档案类 + 一个驱动 jar，现有代码零改动（开闭原则）。
2. 驱动管理：内置一批常用驱动开箱即用；支持管理员上传 JDBC 驱动 jar；驱动隔离加载，任意类型任意版本共存。
3. 支持"类型 + 大版本"两级选择（如 MySQL 5.x / 8.x、Oracle 11g / 12c+），不同版本档案对应不同驱动、URL 写法与默认参数。
4. 连接表单保持结构化 + 白名单高级参数，不允许自由 JDBC URL，保留现有安全校验。

## 2. 非目标

- 不做连接池（维持现有按次建连语义，池化另立项）。
- 不实现 SQL 方言层：查询/同步/任务执行逻辑不动（现有实现已是通用 JDBC 写法）；仅在档案接口上为未来方言方法留扩展余地。
- 本期不新增 MySQL/PG/Oracle 之外的数据库类型，只交付扩展机制与首批五个档案。
- 不做驱动代码沙箱（Java 21 无 SecurityManager）；信任边界 = 仅管理员可上传，隔离仅指类可见性。
- 元数据库（平台自身 MySQL）的连接方式不变，仍走应用 classpath 驱动与 Spring/Flyway。

## 3. 总体架构

新增子包 `com.example.governance.assets.driver`，五个组件：

| 组件 | 职责 | 依赖 |
|---|---|---|
| `DatabaseProfile`（接口） | 一个"类型+大版本"档案：URL 模板、默认驱动类名与内置驱动 key、默认连接参数、高级参数白名单、额外表单字段、默认端口、库名等字段校验 | 无 |
| `DatabaseProfileRegistry` | Spring 收集全部 `DatabaseProfile` Bean；按档案 ID 查找；输出前端表单目录 | Profile Beans |
| `DriverStore` | 驱动元数据表 `jdbc_drivers` + 磁盘 jar 管理；启动时幂等注册内置驱动；上传校验与落盘；删除保护 | JdbcTemplate、DriverLoader |
| `IsolatedDriverLoader` | 每个驱动一个独立 `URLClassLoader`（parent = 平台类加载器）；缓存 `java.sql.Driver` 实例；引用计数关闭 | 无 |
| `ConnectionFactory` | 唯一建连接入口：档案 → 驱动 → URL + 参数 → `Driver.connect()` | Registry、DriverStore、Loader |

改造点：

- `DatabaseInspector`：删除内部 `switch(type)` 建连逻辑，默认 `Connector` 委托 `ConnectionFactory`（保留 `Connector` 函数接口作为测试缝）。`test/tables/columns` 行为不变。
- `ExecutionDataSourceProvider`：返回的 `DataSource` 改为委托 `ConnectionFactory` 的轻量实现（继承 Spring `AbstractDataSource`），删除重复 switch。`CamelTaskRunner`、`DatabaseSyncExecutor`、`IncrementalSyncExecutor` 经此自动受益，无需改动。
- 业务连接不再使用 `DriverManager`（其类加载器检查机制不适合隔离加载），直接调用 `Driver.connect(url, props)`；返回 null 视为连接失败。

## 4. 版本档案

### 4.1 首批档案

档案 ID 格式 `类型:版本`（ASCII），展示名中文，由 profiles API 下发：

| 档案 ID | 展示名 | 默认端口 | URL 模板 | 默认驱动 |
|---|---|---|---|---|
| `MYSQL:5.x` | MySQL 5.x | 3306 | `jdbc:mysql://host:port/db` | builtin-mysql-5 |
| `MYSQL:8.x` | MySQL 8.x | 3306 | `jdbc:mysql://host:port/db` | builtin-mysql-8 |
| `POSTGRESQL:general` | PostgreSQL（通用） | 5432 | `jdbc:postgresql://host:port/db` | builtin-postgresql |
| `ORACLE:11g` | Oracle 11g | 1521 | 默认 SID：`jdbc:oracle:thin:@host:port:SID`；可切服务名：`jdbc:oracle:thin:@//host:port/service` | builtin-ojdbc8 |
| `ORACLE:12c+` | Oracle 12c 及以上 | 1521 | 默认服务名：`jdbc:oracle:thin:@//host:port/service`；可切 SID | builtin-ojdbc11 |

Oracle 两个档案均提供额外表单字段 `connectType`（`SID` / `SERVICE`），默认值如上；`database` 字段相应展示为"SID"或"服务名"。

### 4.2 默认连接参数（沿用现值，迁入档案）

- MySQL 两档案：`connectTimeout=5000`、`socketTimeout=10000`（毫秒）。
- PostgreSQL：`connectTimeout=5`、`socketTimeout=10`（秒）。
- Oracle 两档案：`oracle.net.CONNECT_TIMEOUT=5000`、`oracle.jdbc.ReadTimeout=10000`（毫秒）。

### 4.3 高级参数白名单（每档案声明 key + 值域，前后端双校验，后端为准）

- `MYSQL:5.x`：`useSSL`、`useUnicode`、`characterEncoding`、`serverTimezone`、`connectTimeout`、`socketTimeout`、`zeroDateTimeBehavior`。
- `MYSQL:8.x`：在 5.x 基础上增加 `allowPublicKeyRetrieval`。
- `POSTGRESQL:general`：`connectTimeout`、`socketTimeout`、`ssl`、`sslmode`、`currentSchema`、`ApplicationName`。
- Oracle 两档案：`oracle.net.CONNECT_TIMEOUT`、`oracle.jdbc.ReadTimeout`、`oracle.jdbc.defaultRowPrefetch`。

值域规则：布尔键 ∈ {true,false}；MySQL 超时为 0..3600000 整数（毫秒），PG 超时为 0..3600 整数（秒）；`sslmode` ∈ {disable,allow,prefer,require,verify-ca,verify-full}；`characterEncoding` ∈ {UTF-8,GBK,GB18030,latin1}；`serverTimezone` 为合法 ZoneId 或 ±HH:MM 偏移；`currentSchema` 匹配标识符正则 `[A-Za-z0-9_$]{1,128}`；`ApplicationName` ≤128 字符；`defaultRowPrefetch` 为 1..10000 整数；`zeroDateTimeBehavior` ∈ {EXCEPTION,ROUND,CONVERT_TO_NULL}（MySQL 8 驱动取值，5.x 档案映射为小写旧值 convertToNull/round/exception）。

白名单之外一律拒绝（400），危险参数如 `allowLoadLocalInfile`、`autoDeserialize`、`queryInterceptors`、`socketFactory`、`logger` 等天然被排除。用户白名单参数覆盖档案默认值，但不得覆盖 `user`/`password`。

### 4.4 档案接口职责（规格级）

`id()`、`type()`、`displayName()`、`defaultPort()`、`driverClassName()`、`defaultDriverKey()`、`jdbcUrl(连接字段)`、`defaultProperties()`、`extraParamWhitelist()`、`extraFieldSchema()`（额外表单字段及默认值）、`validate(连接字段)`（库名等细节校验；主机/端口/名称全局正则校验保留在 `DataSourceDefinition`）。

## 5. 驱动管理

### 5.1 内置驱动清单（构建期从 Maven 中央仓拉取）

| driver_key | 坐标 | 驱动类 | 服务档案 |
|---|---|---|---|
| builtin-mysql-5 | `mysql:mysql-connector-java:5.1.49` | `com.mysql.jdbc.Driver` | MYSQL:5.x |
| builtin-mysql-8 | `com.mysql:mysql-connector-j`（BOM 管理，当前 9.5.0，面向 MySQL 8.x 服务端） | `com.mysql.cj.jdbc.Driver` | MYSQL:8.x |
| builtin-postgresql | `org.postgresql:postgresql`（BOM 管理，当前 42.7.8） | `org.postgresql.Driver` | POSTGRESQL:general |
| builtin-ojdbc8 | `com.oracle.database.jdbc:ojdbc8:19.3.0.0` | `oracle.jdbc.OracleDriver` | ORACLE:11g |
| builtin-ojdbc11 | `com.oracle.database.jdbc:ojdbc11`（BOM 管理，当前 23.7.0.25.01） | `oracle.jdbc.OracleDriver` | ORACLE:12c+ |

说明：ojdbc8 19.3 官方互操作矩阵支持 11.2.0.4+；更老的 11.2.0.1~3 或特殊场景由用户上传 ojdbc6 等驱动解决——这正是上传能力的价值。MySQL 5/8 驱动类名与包名不同，天然可共存；PG/Oracle 同包名多版本靠隔离加载共存。内置版本标签在注册时通过驱动实例 `getMajorVersion()/getMinorVersion()` 探测，不硬编码。另：实施前基线测试存在 RBAC 改造遗留的旧权限码失败（后端 34 个、前端 1 个），随本次一并做测试侧修复，不改生产行为。

打包方式：`maven-dependency-plugin` 在构建期把五个 jar 复制为 classpath 资源（`target/classes/drivers-builtin/`，随 boot jar 分发）；启动时 `DriverStore` 解压到驱动目录并按 `driver_key` 幂等注册。应用升级时若内置 jar SHA-256 变化则覆盖文件并更新元数据，引用不受影响。元数据库自身的 mysql-connector-j 仍保留为 runtime 依赖，与业务隔离加载互不干扰。

### 5.2 存储

- 目录：`${GOVERNANCE_DRIVER_DIR:./drivers}`，下分 `builtin/` 与 `uploaded/`。
- 上传 jar 以 UUID 文件名落盘，原始文件名仅存元数据。
- 元数据表 `jdbc_drivers`：`id`、`driver_key`（唯一；内置为稳定 key，上传为 UUID）、`name`、`db_type`、`driver_class`、`version_label`（可空）、`original_filename`、`jar_path`、`sha256`（唯一）、`builtin`（布尔）、`uploaded_by`（可空）、`created_at`。

### 5.3 上传校验（顺序执行，失败返回明确 400 错误）

1. 大小 ≤ 50MB（Spring multipart 限制同步配置）。
2. `db_type` 必须是注册表中已有类型；`name`、`driver_class` 非空且长度受限。
3. 合法 zip/jar，可读取条目。
4. SHA-256 去重：已存在相同摘要则拒绝并提示已有驱动。
5. 用临时隔离类加载器加载：声明的驱动类存在、实现 `java.sql.Driver`、可无参实例化；读取 `getMajorVersion()/getMinorVersion()` 自动建议 `version_label`（用户可改）。`acceptsURL` 仅作参考信息，不作硬性拒绝。
6. 落盘 + 入库 + 审计。

### 5.4 隔离加载与生命周期

- `URLClassLoader(jarUrl, ClassLoader.getPlatformClassLoader())`：驱动可见 JDK（含 `java.sql`），不可见应用类与 Spring 上下文。
- 按驱动 ID 缓存 `Driver` 实例；`ConnectionFactory` 建连时引用计数 +1，包装 `Connection` 在 `close()` 时 -1。
- 删除驱动：先删元数据并移出缓存；引用计数归零后关闭类加载器。在途连接不受影响。
- 内置驱动不可删除；被数据源引用的上传驱动拒删（409，返回引用数据源清单）。

## 6. 数据模型与迁移

Flyway 新脚本 `V30__jdbc_driver_management.sql`（`db/mysql/` 与 `db/migration/` 两套目录各一份，语义一致）：

1. 建 `jdbc_drivers` 表（见 5.2）。
2. `data_sources` 增加三列：
   - `version_profile VARCHAR(80)`：档案 ID；
   - `driver_id BIGINT NULL`：外键 → `jdbc_drivers.id`，空 = 用档案默认内置驱动；
   - `extra_options TEXT`：JSON 对象，存白名单参数与 Oracle `connectType` 等额外字段。
3. 存量回填：`MYSQL → MYSQL:8.x`、`POSTGRESQL → POSTGRESQL:general`、`ORACLE → ORACLE:12c+`（现有 URL 即服务名写法），`driver_id=NULL`、`extra_options='{}'`，行为不变；回填后 `version_profile` 置 NOT NULL。
4. `source_type` 列保留，值 = 档案类型，由后端写入（列表展示/过滤沿用）。
5. `permission_catalog` 新增三个权限点（沿用 V25 模式，sort_order 顺延当前最大值）：
   - `datasource.driver.read`（驱动管理/查看）
   - `datasource.driver.upload`（驱动管理/上传）
   - `datasource.driver.delete`（驱动管理/删除）
   默认授予内置管理员角色（沿用 V25 的授权写法）。
6. 菜单：沿用 V29 `asset_menus` 模式新增"驱动管理"菜单项，绑定 `datasource.driver.read`。

`DataSourceDefinition` 改为 `record(name, profileId, host, port, database, username, driverId 可空, options Map)`；删除 `Type` 枚举；名称/主机/端口全局正则校验保留，库名细节校验下沉档案。`DataSourceStore` 的 SQL 与行映射同步更新。

## 7. API 设计

| 端点 | 权限 | 说明 |
|---|---|---|
| `GET /api/datasources/profiles` | datasource.source.read | 档案目录：id、type、展示名、默认端口、驱动类、默认驱动摘要、额外字段 schema、白名单参数及值域提示 |
| `GET /api/drivers` | datasource.driver.read | 驱动列表（含内置/上传标记、上传人、时间、SHA-256 短码） |
| `POST /api/drivers`（multipart：file、name、dbType、driverClass、versionLabel?） | datasource.driver.upload | 上传，校验见 5.3；审计 |
| `DELETE /api/drivers/{id}` | datasource.driver.delete | 内置 → 400；被引用 → 409 + 引用清单；成功 → 审计 |

数据源创建/更新请求体改为 `{name, profileId, host, port, database, username, password, driverId?, options?}`；显式指定 `driverId` 时，其 `db_type` 必须与档案类型一致，且驱动必须存在，否则 400；测试连接、表/字段发现端点形状不变，内部走 `ConnectionFactory`。前后端一体发布，API 直接要求 `profileId`，不兼容旧请求格式。存量任务/数据集/同步按 `source_id` 引用，不受影响。

## 8. 前端设计（Vue3 + Element Plus）

数据源表单（`DataSources.vue`）：

- 类型下拉 ← profiles API；选类型后出现大版本下拉（仅一个版本时自动选中）；默认端口随档案带出。
- Oracle 档案显示"连接方式"单选（SID/服务名），默认值随档案；"数据库或服务名"标签随连接方式切换。
- 驱动选择：默认"内置默认驱动"（driverId 置空），可展开选择同类型的其他驱动（内置或上传均可，如给 ORACLE:11g 换用上传的 ojdbc6）。
- "高级参数"折叠区：按档案白名单渲染 key-value 输入（key 下拉 + 值输入 + 值域提示）。
- 编辑行为不变：密码留空保留原密码；修改影响引用任务的警告保留。
- 表单逻辑抽到 `datasource-form.js` 纯函数模块（默认端口/版本自动选择/连接方式默认值/白名单渲染列表），供 node --test 测试，沿用项目现有模式。

驱动管理界面：数据源页新增 `drivers` 视图标签（与 sources/datasets 并列），菜单项按新权限点显隐。列表 + 上传对话框（类型、名称、驱动类、jar 文件、版本标签）+ 删除确认（被引用时展示后端返回的引用清单）。

## 9. 安全边界

- jar 即可执行代码：上传/删除仅管理员（新权限点 + 审计日志）；隔离是类可见性隔离，不是沙箱。
- 不允许自由 JDBC URL；主机/端口/库名正则校验保留；参数白名单后端强制。
- 密码加密（`CredentialCipher`）与执行身份隔离（`ExecutionDataSourceProvider` 不外泄）机制不变。
- 连接失败对外返回通用错误（沿用 `InspectionException` 风格），详细原因进日志；驱动加载失败、档案不存在、白名单拒绝返回明确 400 级错误。

## 10. 测试与验证

后端单测（H2 体系，沿用现有测试风格）：

- 档案：五个档案可解析；未知档案报错；各档案 URL 生成（含 Oracle SID/服务名切换）；默认参数正确。
- 白名单：危险 key 拒绝（allowLoadLocalInfile、autoDeserialize、socketFactory、queryInterceptors 等）；非法值拒绝；user/password 不可覆盖。
- 隔离加载：用测试资源中的 H2 jar 验证加载与建连；两个不同 jar 共存；驱动类跨加载器不可见；引用计数归零后加载器关闭。
- 全链路：注册仅测试用的 H2 档案（URL 模板 `jdbc:h2:tcp://host:port/db`）+ 启动 H2 TCP Server，验证 ConnectionFactory → Inspector → 执行 DataSource 全链路。
- 上传校验：坏 jar、缺类、非 Driver 实现、超大小、重复 SHA-256。
- 删除保护：内置拒删；被引用上传驱动 409 + 清单；未引用删除成功。
- 迁移：H2 上验证 V30 建表与存量回填。

接口测试：新权限点（非管理员 403）、审计记录、驱动 CRUD、带档案/选项的数据源 CRUD 与测试连接。

前端：`node --test frontend/tests/*.test.js` 覆盖 datasource-form.js；`npm run build` 通过。

真实库验证：更新 `docs/verification/database-matrix.md`，新增 MySQL 5.7、MySQL 8.0、PostgreSQL、Oracle 11.2.0.4、Oracle 19c 行；当前无真实环境，全部标注**待验证**，不假装通过。

## 11. 扩展指南（未来新增数据库类型）

1. 新增一个 `DatabaseProfile` 实现（`@Component`），声明 URL 模板、驱动类、白名单、额外字段。
2. 驱动 jar 加入内置清单（或交由用户上传）。
3. 补档案与白名单测试。
4. 前端经 profiles API 自动渲染，无需改前端代码。
