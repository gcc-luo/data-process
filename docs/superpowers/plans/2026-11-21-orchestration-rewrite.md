# 数据处理模块重写：DataCraft 任务编排（2026-11-21）

依据：《DataCraft 数据处理任务编排平台 — 功能需求与开发规格 V1.0》（用户提供）+ 原型截图。
用户决策：**彻底重建**（开发阶段，旧处理任务/工作流数据全部丢弃，不做旧配置兼容）；**尽量覆盖 P1**（条件分支、数据清洗、节点自动重试、显式汇聚、HTTP 推送、定时调度）。

## 0. 仓库盘点结论（Phase 0）

可复用资产：
- 后端 Spring Boot 3.5.8 / Java 21 / Camel 4.18.5 / Flyway（MySQL 生产脚本 `db/mysql`，H2 测试脚本 `db/migration`）。
- `workflow` 模块：不可变任务版本 `processing_task_versions`、运行 `task_runs`/`task_run_attempts`、日志 `execution_logs`（含 node_key/attempt_no/line_no）、并发限额 `TaskExecutionLimiter`、停止/重试/重启恢复 `TaskRunService`、目录树 `task_directories`。
- `SqlTaskExecutor`：Camel `direct:` 路由执行参数化 DML（保留，作为 SQL 脚本节点 DML 模式的执行器）。
- `DatabaseSyncExecutor`：实时同步模块在用，保留；其 `verifyUniqueKey`/标识符守卫被新 sink 复用。
- `FlowExpression`：受限表达式语言（无任意代码执行），复用于过滤/计算/条件分支。
- `assets`：数据源凭据加密、隔离驱动（MySQL/PG/Oracle）、`ExecutionDataSourceProvider`、元数据 API `/api/data-sources/{id}/tables|columns`。
- 权限目录 `permission_catalog`（workflow.task.read/create/update/delete/execute/retry）；审计 `AuditLog`。
- 前端 Vue 3.5 + Element Plus 2.14 + Vue Flow 1.48（core/controls/background），Vite 7，`node --test` 单测。
- 工作流模块（TaskStudio「任务与工作流」菜单）：DAG 依赖 + 定时/补跑，引用任务版本 —— 保留，改为引用新编排任务。

差距（本次实现）：组件注册表驱动、草稿 revision 乐观锁、校验 API（错误定位 nodeId）、发布冻结、DATA/CONTROL 边语义、逐节点执行状态落库、命名参数绑定、HTTP 来源/推送（SSRF 守卫）、条件分支/清洗/汇聚/节点重试、增量水位 checkpoint、底部运行控制台、Monaco SQL 编辑器、撤销重做/自动布局/小地图、原型三栏视觉。

明确不做（注册表标「即将支持」并禁用，不展示假功能）：Kafka（本机无 Broker、无 camel-kafka 依赖）、Python Worker（无沙箱）、存储过程、Flink、MinIO/S3、FTP/SFTP、SQL Server（无驱动）、异常处理/DLQ、JOIN/WAIT_ALL 汇聚、SSE（用短轮询）、大数据对象存储交接（内存交接带行数上限，超限明确失败）。

## 1. 架构决策（ADR 摘要）

- **执行模型**：DAG 拓扑序逐节点独立执行，节点间以**有界内存 artifact**（`List<Map<String,Object>>`，上限平台配置 `governance.orch.max-artifact-rows`，默认 50000）交接。保留 `engine/mode` 字段供后续 Camel segment 优化。理由：节点状态/重试/分支/汇聚/预览语义清晰，内存有界；符合规格 §5.4「首版每节点独立执行」与 §6.3 小批上限。超限失败并提示，不静默截断、不 OOM。
- **两层编排**：DATA 边携带行批；CONTROL 边仅依赖（来源完成 → SQL 脚本等数据库侧步骤）。SQL 脚本 SELECT 模式是数据库侧生产者（CONTROL 入、DATA 出）；DML 模式是终止步骤（Camel SqlTaskExecutor 执行）。
- **草稿/发布**：`task_drafts`（revision 乐观锁，PUT 带 expectedRevision，冲突 409）；发布 = 全量校验通过 → 写 `processing_task_versions`（不可变，含 comment/published_by/published_at）并推进 `current_version`。运行固定版本；「运行测试」= dry-run（不写目标，预览采样）。
- **重试**：节点级 retry{enabled,maxAttempts(含首次),intervalSeconds,backoff FIXED/EXPONENTIAL,maxIntervalSeconds}；写节点（sink.*、transform.sql DML、sink.http）开启重试必须 `acknowledgeDuplicateRisk=true`（UI 二次确认），否则校验错误。重试从上游 artifact 重放，至少一次语义，日志记录「可能部分提交」。
- **增量水位**：`task_watermarks`(task_id,node_key,watermark_value,tie_breaker_value,committed_run_id)。来源 INCREMENTAL 模式要求 SQL 命名变量（valueSource=WATERMARK）；运行成功才推进；同时间戳用 tieBreaker 变量续读。
- **HTTP 安全**：JDK HttpClient，redirect NEVER；`HttpTargetGuard` 解析目标 IP，拒绝环回/链路本地/站点本地/元数据地址，除非主机在平台配置 `governance.http.allowed-hosts` 白名单（测试环境显式放行）。
- **SQL 安全**：命名占位符 `:name` → PreparedStatement 绑定（`SqlParameters` 解析，尊重引号/注释）；表/列标识符白名单正则；DDL 默认禁止（SQL 脚本 DML 模式仅 INSERT/UPDATE/DELETE，首语句分类校验）。
- **前端**：沿用现有壳/导航/权限；数据处理菜单子项更名「任务编排」。新增 monaco-editor、@vue-flow/minimap 依赖。编辑器三栏 + 底部控制台，组件目录/表单 schema 来自 `GET /api/orch/components`。
- **旧模块**：TaskStudio 删除「任务」页签（任务 CRUD 归新模块），保留工作流/定时计划；任务拓扑预览改读 ORCH 定义。Flyway V35 清空旧任务/工作流/日志数据（用户确认丢弃）。

## 2. 后端 API（沿用项目 /api 前缀与包装惯例）

- `GET /api/orch/components` 组件注册表（category/engine/icon/color/ports/fields/enabled/capabilities）
- `POST /api/tasks` 创建（name/directoryId/description/template: BLANK|SQL|SYNC）→ 草稿 rev1
- `GET /api/tasks` 列表（+draftRevision/publishedVersion/status/lastRun）
- `GET /api/tasks/{id}/draft`、`PUT /api/tasks/{id}/draft`（expectedRevision，409 冲突）
- `POST /api/tasks/{id}/validate` → {valid, errors[], warnings[]}（code/targetType/targetId/field/message）
- `POST /api/tasks/{id}/publish`（expectedRevision/comment）→ 新版本；权限 workflow.task.publish（新增）
- `GET /api/tasks/{id}/versions`、`GET /api/tasks/{id}/versions/{v}`
- `POST /api/tasks/{id}/runs`（version/businessDate/variables）→ 202 {id}；`GET /api/tasks/{id}/runs`
- `GET /api/task-runs/{runId}`（汇总+节点状态）、`GET /api/task-runs/{runId}/logs`（nodeKey/level/分页）
- `POST /api/tasks/{id}/stop`、`POST /api/tasks/{id}/runs/{runId}/retry`（沿用）
- `POST /api/orch/test-run`（definition/businessDate/variables，30s 上限，dry）→ {logs,nodeStates,previews,stats}
- `GET|PUT|DELETE /api/tasks/{id}/schedule`（cron/zone/enabled；Spring CronExpression；misfire=skip）
- 数据源选择/测试/元数据：沿用 `/api/data-sources*`

## 3. 数据库迁移 V35（mysql + h2 双份）

清空：workflow_node_attempts/workflow_runs/workflow_nodes/workflow_definitions/workflow_schedules、task_run_attempts/task_runs、execution_logs、processing_task_versions/processing_tasks/task_directories（按 FK 序）。
新增：task_drafts、task_node_runs、task_watermarks、task_schedules；processing_task_versions + comment/published_by/published_at；permission_catalog + workflow.task.publish 并授予拥有 workflow.task.update 的角色；菜单 31 更名「任务编排」。

## 4. 组件清单（注册表）

启用：source.mysql/postgresql/oracle（TABLE/SQL、FULL/INCREMENTAL）、source.http、transform.sql（SELECT/DML）、transform.field-map（类型转换/默认值/onError）、transform.filter、transform.mask、transform.calc、transform.clean（TRIM/DROP_NULL/DEDUP/REGEX_REPLACE）、control.branch（规则+唯一 default）、control.merge（UNION）、sink.mysql/postgresql/oracle（APPEND/UPSERT）、sink.http。
禁用（即将支持）：source.kafka/sqlserver/ftp/minio、transform.python/procedure/error-handler、sink.kafka/file/minio、job.flink。

## 5. 前端结构

- `ProcessingTasks.vue`：目录树+列表（AGENTS.md 工具栏/分页规范），状态列（草稿/已发布 vN）、新增模板对话框、定时、运行记录（节点钻取）。
- `orch/OrchestratorEditor.vue` + `OrchPalette/OrchNode/OrchInspector/OrchConsole/SqlMonaco/FieldMappingTable/ParameterTable/RetryForm`：原型三栏+指标卡+画布工具栏（撤销重做/缩放/适应/网格/自动布局/小地图/全屏）+ 底部控制台（运行日志/节点状态/数据预览，轮询 2s）+ 顶栏（保存草稿 Ctrl+S/校验/发布/运行测试）。
- `orch-graph.js`（连接校验/自动布局/历史栈/适配器）、`orch-api.js`；删除旧 FlowEditor/flow-*/TaskFormDialog 等及其测试，新增对应单测。
- TaskStudio：去任务页签，拓扑预览适配 ORCH。

## 6. 阶段与验收

A. 模型+注册表+草稿/校验/发布 API+迁移+单测（H2）。
B. 执行器+节点状态/重试/分支/汇聚/水位+HTTP+调度+运行 API+集成测试（H2/本地 HTTP stub）。
C. 前端编辑器+列表+控制台+单测+build。
D. 全量验证（mvn test / node --test / npm build）+ 本地 MySQL 3307 真实端到端（建表→编排→发布→运行→核对落地数据与日志）+ 文档（EXISTING_ASSETS/ARCHITECTURE_DECISIONS/README/进度清单）。

每阶段先构建+测试再进入下一阶段；未实现能力一律禁用标示，不伪造成功数据。
## 7. 实施结果（2026-11-21 完成）

- **阶段 A/B/C/D 全部落地**。后端 318 测试通过（10 个 MySQL 守卫测试默认跳过），前端 68 测试通过，`npm run build` 成功。
- **真实端到端验证（本地 MySQL 3307）**：新建任务 → 草稿(revision 乐观锁) → 校验(valid) → 发布(v1) → 运行 → 逐节点状态/日志 → 目标表 `orch_demo.orch_dst` 落地 3 行映射数据 → 重跑幂等（UPSERT 不重复）→ 节点行数指标正确。
- 新增自动化测试：OrchDefinitionTest(18)、OrchTaskExecutorTest(10：UPSERT 幂等/分支/汇聚/清洗/重试/水位成功推进与失败不推进/内存上限/dry-run/注入防护)、OrchHttpTest(4：HTTP 来源/推送/SSRF)、OrchScheduleTest(3)、OrchDraftApiTest(5：409 冲突/发布权限/版本不可变/运行节点日志/注册表)、OrchTestRunApiTest(2)、orch-graph.test.js(16)。
- 迁移 V35 在 MySQL 与 H2 均验证；MySQL 清理用 `SET FOREIGN_KEY_CHECKS=0` 处理自引用 FK。
- 交付文档：docs/workflow/EXISTING_ASSETS.md、docs/workflow/ARCHITECTURE_DECISIONS.md；README 更新功能说明/演示/配置/限制。
- **已知限制**：见 EXISTING_ASSETS §5（Kafka/Python/存储过程/Flink 等禁用；内存交接上限；轮询非 SSE；预览仅 dry-run）。
