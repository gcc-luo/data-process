# 主数据功能实现计划

> **面向 AI 代理的工作者：** 按此计划在当前工作区内联实现。每完成一个任务，检查该任务文件范围与既有未提交改动，并更新复选框。

**目标：** 在“数据资产”下新增“主数据”，全局展示登记对象并按角色权限管理其物理记录。

**架构：** 独立主数据 API 读取全局对象目录与字段快照；JDBC 服务只操作已登记的 TABLE，按主键安全定位记录，通过现有加密数据源连接访问物理库。主数据历史存放在平台库，独立权限保护查询、增删改、导入、导出与历史；导航和审计标签使用代码注册表及 Flyway 初始化。

**技术栈：** Spring Boot 3 / JDBC / Flyway / Apache POI；Vue 3 `<script setup>` / Element Plus；现有 `ObjectStore`、`ObjectMetadata`、`ExecutionDataSourceProvider`、`AuditLog`。

---

## 文件清单

| 文件 | 职责 |
|---|---|
| `backend/src/main/java/com/example/governance/security/PermissionCatalog.java` | 注册主数据权限、菜单组件归属和 API 路由权限。 |
| `backend/src/main/java/com/example/governance/security/AuditCatalog.java` | 为主数据 action 与 object type 提供中文展示名。 |
| `backend/src/main/resources/db/mysql/V41__master_data.sql` | 初始化历史表、主数据权限、数据资产子菜单及默认管理员/角色菜单关联。 |
| `backend/src/main/java/com/example/governance/dataobject/ObjectStore.java` | 提供主数据树需要的全局对象快照读取。 |
| `backend/src/main/java/com/example/governance/dataobject/ObjectMetadata.java` | 暴露精确物理对象字段、自增/生成列、默认值和主键元数据。 |
| `backend/src/main/java/com/example/governance/masterdata/MasterDataService.java` | 动态筛选、分页查询、行新增/修改/删除、标识符与输入校验。 |
| `backend/src/main/java/com/example/governance/masterdata/MasterDataHistoryStore.java` | 保存和读取记录级操作快照/差异。 |
| `backend/src/main/java/com/example/governance/masterdata/MasterDataExcelService.java` | 生成 Excel 模板、导入记录并导出当前对象筛选结果。 |
| `backend/src/main/java/com/example/governance/masterdata/MasterDataController.java` | 主数据树、字段、行数据、导入导出和历史 API；权限与审计入口。 |
| `frontend/src/MasterDataObjectTree.vue` | 全局对象树、目录分组、对象搜索与当前对象选择。 |
| `frontend/src/MasterData.vue` | 查询工具栏、动态记录表格、增改表单、导入/导出/历史界面。 |
| `frontend/src/App.vue` | 注册页面组件并在数据资产下渲染 `assets/master-data`。 |

## 设计约定

- 颜色复用当前产品：深蓝导航 `#162b4d`、工作区底色 `#f3f6fa`、白色面板 `#ffffff`、边界 `#dce4ed`、正文 `#30465e`、主操作蓝 `#438bd0`。
- 字体继承 `App.vue` 的业务字号 token；目录标题、二级导航、输入框、按钮、表格文字不另设冲突字号。
- 采用已确认的双栏：左侧全局目录/对象树，右侧对象信息、左查询右操作的工具栏、动态表格和贴合面板底部的分页。
- 视觉区别来自对象字段的动态列与类型标记，不增加统计卡片、渐变或装饰标签；示例“状态”只在物理表实际包含该字段时显示。

```text
┌──────────────────────────────────────────────────────────────────┐
│ 数据资产 / 主数据                                  账号与状态栏  │
├────────────────────┬─────────────────────────────────────────────┤
│ 全部数据对象       │ 所选对象名称 / 表名 / 数据源 / 目录         │
│ [搜索表名或中文名] │ [筛选字段…] 查询       新增 导入 导出 历史 ↻│
│ ▾ 全部数据对象     ├─────────────────────────────────────────────┤
│   ▾ 基础数据       │ 动态业务字段表格                            │
│     客户信息       │                                             │
│     供应商信息     │                                             │
│   ▸ 业务数据       │                                             │
│                    │                            总数 / 分页       │
└────────────────────┴─────────────────────────────────────────────┘
```

## 任务 1：注册权限、审计中文名和菜单迁移

**文件：** 修改 `PermissionCatalog.java`、`AuditCatalog.java`；创建 `V41__master_data.sql`。

- [ ] **步骤 1：注册最小权限集合和路由**

在 `PermissionCatalog.buildEntries()` 新增 `datasource.master_data` 的 `read/create/update/delete/import/export/history` 七项；`moduleForComponent("assets/master-data")` 返回 `datasource.master_data`；`modulesForComponent("assets/master-data")` 返回同一模块。注册路由：

```java
route(routes, "GET", "/api/master-data/tree", "datasource.master_data.read");
route(routes, "GET", "/api/master-data/objects/*", "datasource.master_data.read");
route(routes, "POST", "/api/master-data/objects/*/query", "datasource.master_data.read");
route(routes, "POST", "/api/master-data/objects/*/records", "datasource.master_data.create");
route(routes, "PUT", "/api/master-data/objects/*/records", "datasource.master_data.update");
route(routes, "DELETE", "/api/master-data/objects/*/records", "datasource.master_data.delete");
```

同一位置把 template/import/export/history/details 路径分别映射到 `import/export/history`，并避免通配详情路由抢先匹配更具体路由。

- [ ] **步骤 2：登记审计 action 的中文展示名**

在 `AuditCatalog.ACTIONS` 增加 `master_data.create/update/delete/import/export/template/history` 标签；在 `OBJECT_TYPES` 增加 `master_data_record -> 主数据记录`。操作标签用实际接口语义，如“新增主数据记录”“导出主数据”。

- [ ] **步骤 3：创建初始化迁移**

在 MySQL Flyway V41 创建无外键依赖于可删除数据对象登记的历史表，存储对象身份、记录主键 JSON、操作类型、修改前后 JSON、操作者和时间；插入七项权限；在 `parent_id=2` 下插入 `assets/master-data` 菜单；给默认管理员角色补发七项权限，并为有 `datasource.master_data.read` 的角色写入相应 `role_menus` 关联。迁移对已存在菜单/权限采用防重条件。

**完成条件：** 新组件能按权限模块识别；迁移中权限代码与 Java 注册表完全一致；审计 action/object type 可映射为中文。

## 任务 2：增加可核验的全局对象及字段元数据

**文件：** 修改 `ObjectStore.java`、`ObjectMetadata.java`；创建 `MasterDataService.java`。

- [ ] **步骤 1：提供全量目录和对象读取**

从 `object_directories` 和 `data_objects` 读取全部目录及登记对象，包含对象 id、directory id、表名、中文名、TABLE/VIEW 类型、数据源显示名和完整物理 identity。该路径不接受 `directoryId` 过滤，避免遗漏其他目录的对象。

- [ ] **步骤 2：读取当前物理列特征**

沿用 `ObjectMetadata` 的精确 catalog/schema/table 校验，读取字段顺序、类型、长度、可空、单列/复合主键、默认值、自增和生成列。只有登记快照与当前物理列一致时允许写入；字段变化提示先刷新数据对象元数据。

- [ ] **步骤 3：定义安全 SQL 辅助方法**

使用 JDBC 元数据的 identifier quote；字段和值分开处理，值全部使用 `PreparedStatement` 参数。筛选字段必须属于已登记字段集合，筛选操作符由代码 enum 映射，任何用户输入不得直接拼进 SQL。

**完成条件：** 全局树可看到任意数据对象目录下的对象；字段元数据能区别可空、主键和数据库自动生成字段；无法识别的列状态不会被推断为安全写入。

## 任务 3：实现动态查询与安全记录 CRUD

**文件：** 修改 `MasterDataService.java`；创建/完善 `MasterDataController.java`。

- [ ] **步骤 1：实现带筛选的分页查询**

接受 `page/size/filters`；限制 size、最大页偏移、条件数量和字符串长度；计算总数并仅读取当前页。字段名经物理元数据和登记快照双重校验；排序优先使用完整主键。

- [ ] **步骤 2：实现新增及字段校验**

忽略 VIEW；只写登记且仍存在的列。必填字段在未提供值、非空且没有数据库默认值/自增/生成规则时返回字段级中文错误；缺省列从 INSERT 字段集合中省略。值按物理字段类型解析并绑定。

- [ ] **步骤 3：实现修改与删除**

要求完整主键（复合主键包含每一列）；更新集合排除主键/生成列；WHERE 对每个主键字段用参数绑定；执行后影响行数必须恰好为 1，否则返回“记录不存在或已变化”。删除也验证唯一影响一行。

- [ ] **步骤 4：由 Controller 校验权限、返回中文错误并记审计**

对象树/详情/查询使用 read，创建/修改/删除分别使用最小权限。成功与失败都使用一致 action code，审计对象类型为 `master_data_record`，消息仅记对象、键摘要和影响数量，不落单元格值。

**完成条件：** TABLE 查询/分页/增改删正常；VIEW 后端拒绝写操作；缺失/未知主键不允许修改删除；越权 API 返回 403。

## 任务 4：实现记录历史与 Excel 流程

**文件：** 创建 `MasterDataHistoryStore.java`、`MasterDataExcelService.java`；完善 `MasterDataController.java`。

- [ ] **步骤 1：记录新增/修改/删除快照**

保存物理 identity、稳定主键、操作者和操作时间；修改保存 before/after，删除保存 before，新增保存 after。历史写入失败时不能静默宣称成功；审计消息仍不含记录值。

- [ ] **步骤 2：提供受 history 权限保护的历史列表与差异详情**

按对象和主键筛选，分页读取历史；修改版本返回字段级 before/after 差异，其他操作显示新增或删除快照。历史查询和详情均检查 `datasource.master_data.history`。

- [ ] **步骤 3：生成模板并导出 Excel**

使用 Apache POI 写 XLSX；模板列头来自选中 TABLE 的字段元数据，自动生成列可保留为列头但新增时允许空白；导出只使用当前过滤条件和安全的分页上限。响应使用文件名编码和正确 MIME type。

- [ ] **步骤 4：实现有上限的逐行导入**

限制上传大小、行数和列数；按字段名映射；拒绝未知列、公式和视图导入；逐行校验并返回成功数及最多 100 条行错误，成功行各有历史记录。审计记录汇总结果，不记录原始行。

**完成条件：** history 权限不能被 read 替代；模板/导入/导出只作用于选择对象；失败行能定位并说明原因；VIEW 无法导入。

## 任务 5：实现主数据页面和全局对象树

**文件：** 创建 `MasterDataObjectTree.vue`、`MasterData.vue`；修改 `App.vue`。

- [ ] **步骤 1：接入组件和导航渲染**

在 `App.vue` 导入 `MasterData`；`section === 'assets'` 时当 `groupPages.assets === 'master-data'` 渲染此组件；菜单迁移组件名与分支条件保持一致。

- [ ] **步骤 2：实现目录树和全局搜索**

复用 `DirectoryPanelHeader.vue` 和与 `DirectoryTree.vue` 一致的文件夹 SVG；以全局 root、目录、数据对象组成树，表/视图用稳定轻量标记区分。搜索同时匹配对象英文表名和中文名；目录/树保持 `min-height:0` 并在内层滚动。

- [ ] **步骤 3：实现动态筛选与记录表**

选中对象后请求 fields 和 records；动态列使用字段名、注释与 SQL type；初始筛选展示常用可筛列，其他字段可从“更多筛选”选择。查询按钮应用条件，文本框回车查询；左右工具区按项目规范排列。

- [ ] **步骤 4：实现新增、编辑、删除弹层**

表单字段依物理类型选择 Element Plus input/select；必填标记反映数据库约束，生成列只读/隐藏；把 values 与 record key 分开提交；视图、无主键 edit/delete 动作禁用并显示原因；删除前二次确认。

- [ ] **步骤 5：实现导入/导出/版本记录反馈**

导入弹窗显示模板下载、文件选择、逐行结果；导出按钮导出当前筛选；版本记录展示操作人、动作、时间及可展开差异。所有操作依会话权限隐藏，空状态/失败状态给出下一步动作。

**完成条件：** 页面与已确认原型一致，所有对象可在任意目录筛出；当前用户选定状态不受数据对象列表页选择影响；桌面双栏、窄屏堆叠和底部分页不溢出。

## 任务 6：依赖顺序检查与构建检查

**文件：** 本任务只检查任务 1–5 已修改文件。

- [ ] **步骤 1：检查迁移/代码权限一致性**

逐项对照迁移里的权限代码与 `PermissionCatalog` 的 `CODES`；检查所有 `@GetMapping/@PostMapping/@PutMapping/@DeleteMapping` 都能被唯一最小权限覆盖；检查菜单组件值、`App.vue` 分支与 `modulesForComponent` 一致。

- [ ] **步骤 2：运行后端编译（不运行测试）**

运行：`mvn -f backend/pom.xml -DskipTests compile`

预期：`BUILD SUCCESS`。

- [ ] **步骤 3：运行前端生产构建（不运行测试）**

运行：`npm --prefix frontend run build`

预期：Vite 生成 `frontend/dist`，无 Vue 模板或模块解析错误。

## 任务进度

- [x] 任务 1：权限、审计标签、菜单迁移
- [ ] 任务 2：全局目录和物理字段元数据
- [ ] 任务 3：动态分页查询与 CRUD
- [ ] 任务 4：版本历史与 Excel
- [ ] 任务 5：Vue 页面、目录树和导航
- [ ] 任务 6：权限一致性与构建
