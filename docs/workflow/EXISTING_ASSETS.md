# 现有可复用资产与差距分析（Phase 0）

> 重写日期：2026-11-21 · 依据《DataCraft 数据处理任务编排平台功能需求与开发规格 V1.0》
> 用户决策：彻底重建（开发阶段，旧处理任务/工作流数据丢弃）；尽量覆盖 P1。

## 1. 仓库结构与技术栈（事实）

- 模块化单体：`backend/`（Spring Boot 3.5.8 / Java 21 / Apache Camel 4.18.5 / Flyway / Spring Security），`frontend/`（Vue 3.5 / Element Plus 2.14 / Vue Flow 1.48 / Vite 7，**JavaScript 非 TypeScript**）。
- 元数据库 MySQL（`db/mysql` 生产迁移），H2 测试迁移（`db/migration`）。本地运行：MySQL 3307、后端 8080（`~/.local/share/data-governance/backend.env`）。
- 构建/验证：`mvn -f backend/pom.xml test`、`node --test frontend/tests/*.test.js`、`npm --prefix frontend run build`。

## 2. 已复用资产

| 资产 | 位置 | 复用方式 |
| --- | --- | --- |
| 不可变任务版本 | `workflow/TaskVersionStore` | 改造为草稿/发布模型：create 仅建任务行（current_version=0），addVersion 冻结发布快照（+comment/published_by/published_at） |
| 运行/尝试/恢复/停止/重试 | `workflow/TaskRunService` | 扩展运行参数（variables）持久化、逐节点状态、运行详情/日志 API；保留并发限额、停止、重试、重启恢复 |
| 执行日志 | `execution_logs` + `DbRunLogSink` | 复用表结构（owner/node_key/attempt/line_no），新增按节点日志工厂 |
| 受限表达式语言 | `workflow/FlowExpression` | 直接复用于过滤/计算/条件分支（无任意代码执行） |
| Camel DML 执行 | `workflow/SqlTaskExecutor` | 复用于 SQL 脚本节点 DML 模式（`direct:` 路由 + 绑定参数） |
| 唯一键校验/标识符守卫 | `workflow/DatabaseSyncExecutor.verifyUniqueKey` | 新 JDBC sink 复用（实时同步模块仍用 DatabaseSyncExecutor 本体） |
| 数据源凭据/驱动/元数据 | `assets/*`、`ExecutionDataSourceProvider` | 完全复用：加密凭据、隔离驱动（MySQL/PG/Oracle）、`/api/data-sources/{id}/tables\|columns\|test` |
| 权限目录/审计 | `security/PermissionCatalog`、`AuditLog` | 复用路由式鉴权；新增 `workflow.task.publish` 权限并授予已有 update 角色 |
| 前端壳/导航/权限/分页 | `App.vue`、`auth.js`、`pagination.js`、`processing-tasks.js` | 复用布局、CSRF 请求、目录树筛选、分页 |
| 工作流依赖/调度/补跑 | `workflow/Workflow*`、`TaskStudio.vue` | 保留（引用新编排任务的已发布版本）；移除其重复的“任务”页签 |

## 3. 本次新建

- 后端：`OrchComponents`（组件注册表）、`OrchDefinition`（模型+校验）、`OrchDraftStore`（草稿乐观锁+发布）、`OrchTaskExecutor`（DAG 执行器）、`SqlParameters`（命名占位符）、`HttpTargetGuard`/`HttpSourceFetcher`（SSRF 守卫+HTTP 来源）、`WatermarkStore`（增量水位）、`DbNodeRunSink`（节点状态）、`TaskScheduleStore`/`TaskSchedulePoller`（任务定时）。
- 迁移：`V35__orchestration_rebuild.sql`（mysql + h2）。
- 前端：`OrchestratorEditor`/`OrchPalette`/`OrchNode`/`OrchInspector`/`OrchConsole`/`OrchRunsDialog`/`SqlMonaco`/`FieldMappingTable`/`ParameterTable`、`orch-graph.js`/`orch-api.js`/`orch-icons.js`；重写 `ProcessingTasks.vue`、精简 `TaskStudio.vue`。
- 依赖：`monaco-editor`、`@vue-flow/minimap`。

## 4. 移除（旧 FLOW/SQL/SYNC 编辑器）

`FlowEditor.vue`、`FlowNode.vue`、`flow-components.js`、`flow-config.js`、`flow-graph.js`、`task-edit.js`、`TaskFormDialog.vue`、`TaskRunsDialog.vue`、`FlowTaskExecutor.java`、`FlowGraph.java` 及对应测试。`flow-adapter.js` 保留（工作流画布仍在用）。

## 5. 差距与已知限制（诚实标示）

- **未实现并禁用（注册表 enabled=false，“即将支持”）**：Kafka 输入/输出（无 Broker、无 camel-kafka 依赖）、Python Worker（无沙箱）、存储过程、Flink、MinIO/S3、FTP/SFTP、SQL Server（无驱动）、异常处理/DLQ、JOIN/WAIT_ALL 汇聚。
- **内存交接上限**：节点间 artifact 默认 50000 行（`governance.orch.max-artifact-rows`），超限明确失败；大数据对象存储交接留待后续。
- **增量语义**：轮询水位 + tieBreaker，不捕获删除/迟到数据，非 CDC；水位仅运行成功后推进。
- **重试**：至少一次语义，写节点开启重试需确认幂等；不承诺 Exactly Once。
- **运行更新**：短轮询（2s），未实现 SSE。
- **数据预览**：仅“运行测试”采样前 100 行；真实运行不持久化业务数据样本。
- **前端语言**：沿用仓库 JavaScript（规格建议 TypeScript，按仓库事实采用 JS）。