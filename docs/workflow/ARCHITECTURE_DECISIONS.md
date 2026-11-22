# 架构决策记录（ADR）— 数据处理任务编排重写

日期：2026-11-21 · 状态：已实施（P0 + 大部分 P1）

## ADR-1 执行模型：DAG 逐节点 + 有界内存 artifact
- **决策**：拓扑序逐节点独立执行，节点间以 `List<Map<String,Object>>` artifact 交接，行数上限 `governance.orch.max-artifact-rows`（默认 50000），超限明确失败。保留 `engine`/`mode` 字段供后续 Camel segment 优化。
- **理由**：节点状态/重试/分支/汇聚/预览语义清晰、内存有界；符合规格 §5.4「首版每节点独立执行」与 §6.3 小批上限。
- **后果**：超大表同步需后续对象存储/临时表交接；当前不静默截断、不 OOM。

## ADR-2 两层编排：DATA 边与 CONTROL 边
- **决策**：DATA 边携带行批；CONTROL 边仅完成依赖。SQL 脚本 SELECT 模式 = 数据库侧生产者（CONTROL 入 `dep`、DATA 出 `out`）；DML 模式 = 终止步骤（Camel `SqlTaskExecutor`）。
- **理由**：避免把任务依赖当数据流（规格 §5.4/§6.1）。

## ADR-3 草稿/发布与乐观锁
- **决策**：`task_drafts`（revision）；PUT draft 带 expectedRevision，冲突 409 + currentRevision。发布 = 全量校验通过 → `processing_task_versions` 不可变快照 + 推进 current_version（同一事务，`@Transactional` + 行锁复查 revision）。运行固定已发布版本；「运行测试」= dry-run（不写目标）。
- **理由**：规格 §5.1/§10.1；编辑草稿不影响线上版本与运行中实例。

## ADR-4 组件注册表驱动
- **决策**：`OrchComponents` 单一注册表输出 category/engine/icon/color/ports/fields(JSON Schema 风格)/capabilities/enabled；前端_palette_与_inspector_动态渲染。新增组件 = 注册项 + 执行适配器 + 测试，不改画布核心。
- **理由**：规格 §3.5/§7.1；未实现组件 enabled=false 标“即将支持”，禁止假功能。

## ADR-5 SQL 安全
- **决策**：命名占位符 `:name` → `SqlParameters` 解析（尊重引号/注释、跳过 `::`）→ PreparedStatement 绑定，禁止字符串拼接；DML 模式仅 INSERT/UPDATE/DELETE（首关键字分类），禁止 DDL/多语句；表/列标识符白名单正则。
- **理由**：规格 §15.1；防注入。

## ADR-6 HTTP 安全（SSRF）
- **决策**：JDK HttpClient，redirect NEVER；`HttpTargetGuard` 解析目标 IP，拒绝环回/链路本地/站点本地/元数据地址，除非主机在 `governance.http.allowed-hosts` 白名单。
- **理由**：规格 §3.2/§15.1；测试环境显式放行本地 stub。

## ADR-7 节点重试与幂等
- **决策**：节点 retry{enabled,maxAttempts(含首次,1–5),intervalSeconds,backoff FIXED/EXPONENTIAL,maxIntervalSeconds}；写节点开启重试必须 acknowledgeDuplicateRisk=true（否则校验错误 NON_IDEMPOTENT_RETRY）。重试从上游 artifact 重放，至少一次语义，日志记录“可能部分提交”。
- **理由**：规格 §2.5/§6.4；不承诺 Exactly Once。

## ADR-8 增量水位
- **决策**：`task_watermarks`(task_id,node_key,value,tie_breaker,committed_run_id)。来源 INCREMENTAL 要求 SQL 命名变量（valueSource=WATERMARK）+ watermarkColumn/tieBreakerColumn；内置 `last_tie_breaker` 变量供同时间戳续读；运行成功才推进。
- **理由**：规格 §3.2/§6.4/TC-SQL-07/08。

## ADR-9 条件分支与显式汇聚
- **决策**：control.branch 按规则逐行路由到动态出口 + 唯一 default（校验每个出口须连下游）；control.merge 仅 UNION（按列名合并补空值），多 DATA 入边必须经 merge。
- **理由**：规格 §3.3/§6.2；避免隐式合并歧义。

## ADR-10 运行观测
- **决策**：`task_node_runs` 逐节点状态/行数/耗时/尝试/错误；`execution_logs` 按节点日志；运行详情/日志 API；前端短轮询 2s（未做 SSE）。真实运行不持久化业务样本，预览仅 dry-run 采样 100 行。
- **理由**：规格 §14；隐私与存储成本。

## ADR-11 定时调度
- **决策**：`task_schedules` + Spring `CronExpression`（6 段）+ 单节点 poller；行锁原子推进 next_fire 并创建运行；固定已发布版本；错过触发不补跑（任务级）；工作流级补跑沿用既有 WorkflowScheduleStore。
- **理由**：规格 §5.3 P1；复用既有调度模式。

## ADR-12 前端技术选型
- **决策**：沿用 Vue 3 + Element Plus + Vue Flow（仓库事实，JS 非 TS）；新增 monaco-editor（SQL 编辑，懒加载独立 chunk）、@vue-flow/minimap。编辑器三栏 + 指标卡 + 底部控制台，组件/表单来自注册表。
- **理由**：规格 §12；优先复用既有 UI 与画布。

## ADR-13 旧模块与数据
- **决策**：Flyway V35 清空旧任务/工作流/日志数据（用户授权丢弃；MySQL 用 `SET FOREIGN_KEY_CHECKS=0` 包裹清理以处理自引用 FK）。TaskStudio 移除重复“任务”页签，保留工作流/定时/补跑，引用新任务已发布版本。
- **理由**：开发阶段彻底重建，避免双套编辑器。

## 版本约束
- Camel 4.18.5（与现有 Spring Boot 3.5.8 / Java 21 一致），未引入 camel-kafka/http（HTTP 用 JDK HttpClient）。设计图“Camel v3.20”为视觉占位，非实际版本。