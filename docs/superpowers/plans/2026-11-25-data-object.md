# 数据对象实现计划

**目标：** 在数据资产中实现多数据源的数据对象注册、建表、结构发布、预览、导出和审计。

**架构：** 使用独立对象目录与对象快照，所有物理对象以数据源、catalog、schema、表名、类型共同标识。结构变更由后端数据库方言生成，执行记录和恢复状态持久化；数据预览通过现有数据集行列权限解析。

**技术栈：** Vue 3、Element Plus、Spring Boot、JdbcTemplate、Flyway、JDBC、JUnit、MySQL/PostgreSQL/Oracle。

## 文件职责

- `backend/src/main/resources/db/mysql/V40__data_objects.sql`、`backend/src/main/resources/db/migration/V40__data_objects.sql`：目录、对象、字段、草稿及 DDL 执行记录、菜单及权限迁移。
- `backend/src/main/java/com/example/governance/dataobject/ObjectStore.java`：目录、对象、字段快照及分页。
- `backend/src/main/java/com/example/governance/dataobject/ObjectMetadata.java`：从 JDBC 读取表身份、字段、精度、主键与注释。
- `backend/src/main/java/com/example/governance/dataobject/ObjectDialect.java`、`MySqlDialect.java`、`PostgresDialect.java`、`OracleDialect.java`：标识符、类型校验和 SQL 生成。
- `backend/src/main/java/com/example/governance/dataobject/ObjectExecutionStore.java`、`ObjectExecutionService.java`：预览、指纹、执行记录、并发控制及部分失败恢复。
- `backend/src/main/java/com/example/governance/dataobject/ObjectController.java`、`ObjectPreviewService.java`：授权接口、批量注册、导出和受控预览。
- `backend/src/main/java/com/example/governance/security/PermissionCatalog.java`、`AuditCatalog.java`：操作权限及中文审计。
- `frontend/src/DataObjects.vue`、`frontend/src/data-object-api.js`、`frontend/src/components/dataobject/`：目录列表、注册向导、字段编辑、SQL 预览与恢复界面；`frontend/src/App.vue` 接入菜单。
- `backend/src/test/java/com/example/governance/dataobject/`：存储、元数据、方言、执行、权限和接口测试；`frontend/tests/data-object.test.js`：纯前端逻辑测试。

## 任务 1：存储与迁移

- [ ] 新建 V40 双方言迁移：创建 `object_directories`、`data_objects`、`data_object_fields`、`data_object_drafts`、`data_object_operations`、`data_object_operation_steps`。目录使用父外键和非空删除限制；字段、草稿、步骤按对象或操作外键关联。
- [ ] 用归一化后的非空 catalog/schema、source ID、精确表名及对象类型建立唯一物理身份；对象记录版本号、资料、字段快照和可选的授权数据集 ID。通过独立锁行或唯一活动锁约束防止同一物理对象并发执行。
- [ ] 参照 V39 将菜单放在数据资产下，注册目录查看/新增/修改/删除及对象查看/注册/建表/修改/发布/删除/预览/导出权限；仅给默认管理员角色补齐权限。
- [ ] 在 `ObjectStoreTest` 覆盖根和子目录重名、非空删除、后代筛选、分页、物理身份去重与引用限制；运行 `mvn -f backend/pom.xml '-Dtest=ObjectStoreTest' test`，预期测试通过且 H2 完整迁移至 V40。

## 任务 2：元数据与对象注册

- [ ] 实现 `ObjectMetadata`，通过 `DatabaseInspector` 与 `ConnectionFactory` 使用数据源保存的驱动和凭据；使用 `DatabaseMetaData.getTables/getColumns/getPrimaryKeys`，按返回的 catalog/schema/table 进行精确匹配，转义元数据通配符。
- [ ] 字段结果区分未知与明确的非空、精度和主键；保留数据库原始类型、长度、精度、注释及字段顺序。保留 MySQL 数据库范围限制；PostgreSQL、Oracle 不将同名不同 schema 的表合并。
- [ ] 注册接口逐表读取元数据并分别事务提交，返回 `[{identity, success, objectId, error}]`；视图标记只读，重复物理身份返回冲突，单表失败不撤销其他表。
- [ ] 在 `ObjectMetadataTest` 和 `ObjectRegistrationApiTest` 验证同名跨 schema、未知元数据、只读视图、重复表、连接失败及批量部分成功；运行 `mvn -f backend/pom.xml '-Dtest=ObjectMetadataTest,ObjectRegistrationApiTest' test`。

## 任务 3：数据库方言

- [ ] 定义 `ObjectDialect` 的 `validateIdentifier`、`quoteIdentifier`、`supportedTypes`、`createTable`、`planChanges`；接口只接受结构化表和字段模型，不接收执行用原始 SQL。
- [ ] 实现 MySQL 5.x/8.x、PostgreSQL、Oracle 11g/12c+ 各自的标识符引用、类型和长度精度校验，以及建表、增列、改类型/可空、删列 SQL；严格按版本白名单拒绝不支持的转换和未知类型修改。
- [ ] 在 `ObjectDialectTest` 为每个 profile 固定建表、增列、变更、删列输出和风险标签；覆盖引号、保留字、非法标识符、长度边界、主键修改和未知类型的拒绝。运行 `mvn -f backend/pom.xml '-Dtest=ObjectDialectTest' test`。

## 任务 4：DDL 预览、执行和恢复

- [ ] 将预览持久化为操作 ID、目标身份、原结构指纹、目标定义、有序 SQL、风险与状态；执行请求仅携带操作 ID。再次读取实际结构并比较指纹，不一致返回 409，禁止执行客户端提交的 SQL。
- [ ] 以持久化状态和数据库锁阻止同一对象同时发布及相同操作重复执行；依序执行并记录每步结果。异常或中断时停止后续步骤、重新读取实际结构，记录部分成功或待核实；不得假设跨数据库 DDL 回滚。
- [ ] 创建物理表成功而登记失败时保留目标身份和 `NEEDS_REGISTRATION` 状态，提供只读取并登记已有表的恢复接口；不删除表、不重复执行建表。结构成功验证后才更新已发布快照，失败保留草稿。
- [ ] `ObjectExecutionServiceTest` 模拟过期预览、并发请求、重复提交、第二条 DDL 失败、连接中断和登记失败恢复；运行 `mvn -f backend/pom.xml '-Dtest=ObjectExecutionServiceTest' test`。

## 任务 5：预览、导出、授权与审计

- [ ] 预览要求对象关联已配置策略的数据集，并由 `DatasetPolicyStore`、`DatasetQueryEngine` 解析用户部门及字段权限；未关联或无策略返回拒绝，不能直接对物理表发无约束查询。沿用 `QueryLimits` 行数、超时和并发上限。
- [ ] 导出只包含对象和字段元数据，设定最大 10000 行及明确的超限错误；对 CSV 等表格导出转义公式前缀。删除对象登记前检查任务、数据集及其他持久引用，不能删除或改动物理表。
- [ ] 在 `PermissionCatalog` 注册各接口的具体方法和路径，在 `AuditCatalog` 注册目录、注册、建表、资料修改、结构发布、删除、预览、导出及对应对象类型的中文名称。成功和失败均写入操作者、目标和结果，不记录业务数据或凭据。
- [ ] 在 `ObjectSecurityApiTest` 验证无权限 403、只读角色不可执行 DDL、未授权数据集不可预览、行列权限、审计列表/筛选/导出中文及敏感信息不泄露；运行 `mvn -f backend/pom.xml '-Dtest=ObjectSecurityApiTest' test`。

## 任务 6：前端

- [ ] `DataObjects.vue` 参照 `DataDictionary.vue` 实现目录树、后端查询分页、权限控制、对象列表和目录菜单；`App.vue` 在 `assets/object` 下加载页面。工具栏左查询、右新增/注册/导出/刷新；桌面分页贴列表底部，窄屏目录在上并限高。
- [ ] `components/dataobject/ObjectEditor.vue` 实现新增、查看、资料编辑、复制及字段草稿；`DdlPreview.vue` 显示目标数据源、结构差异、完整 SQL、逐字段风险、确认和执行结果；`RegisterWizard.vue` 选择数据源/目录、物理表、逐表信息并显示部分失败。
- [ ] `ObjectRecovery.vue` 展示建表已成功但登记失败的目标身份与重新登记操作，以及 DDL 部分成功/待核实状态。数据预览明确显示未配置数据权限的拒绝原因。
- [ ] `frontend/tests/data-object.test.js` 覆盖表单模型、SQL 预览失效、风险确认、注册部分失败；运行 `node --test frontend/tests/data-object.test.js` 和 `npm --prefix frontend run build`。

## 任务 7：真实数据库与页面验证

- [ ] 在专用测试库逐一验证 MySQL 5.x、MySQL 8.x、PostgreSQL、Oracle 11g、Oracle 12c+ 的发现、建表、注册、结构发布和失败后实际状态；记录数据库/驱动版本、命令、结果。未提供的数据库版本标记为未实测，不声称通过。
- [ ] 运行 `mvn -f backend/pom.xml test`、`node --test frontend/tests/*.test.js`、`npm --prefix frontend run build`；检查无迁移回归、权限绕过和敏感信息泄露。
- [ ] 在已登录的本地应用检查桌面/窄屏目录与分页，完成注册、预览、DDL 预览和明确确认交互；数据库写入使用专用测试库，保留验证记录。
