# 处理任务一期 · 遗留 backlog 与已知设计决策

日期：2026-11-20
来源：一期八任务两阶段审查 + 合并前整体审查（分支 cindy/processing-tasks）。
用途：二三期开发者据此区分"已知决策"与"漏网缺陷"；逐项含位置与建议方向，均不阻塞一期合并。

## 已知设计决策（有意为之，勿当缺陷误改）

1. **PUT 基本信息守卫为非原子 best-effort**：`TaskController.metadata` 的 hasActiveRun 检查在事务外；基本信息（名称/目录/描述）不影响执行语义（配置走不可变版本），接受极窄竞态。DELETE 不同：权威守卫在 `TaskVersionStore.delete` 锁内锁定读复查（`TaskDeleteApiTest.storeDeleteRechecksActiveRunInsideLock` 固化）。
2. **stop 与业务完成竞态判 INTERRUPTED**：`TaskRunService.execute` 成功路径 finish 前查 isCancelled；宁可误标中断不误标成功（`TaskStopApiTest.stopRaceAfterBusinessCompletionMarksInterrupted` 固化）。
3. **RunCancelContext.detach 生命周期**：终态 finish 后才 detach+remove；迟到 cancel 只置标志，不 interrupt 复用池线程、不 cancel 已关闭语句。
4. **start 行锁内单活跃守卫**：`processing_tasks` 行 FOR UPDATE 后 hasActiveRun；retry 自一期末轮修复起同样锁内守卫（见修复提交）。
5. **summary() 两条查询非同一快照**：展示型数据、轮询自愈，接受瞬时不一致。
6. **编辑保存总是创建新版本**（含仅改描述）：与 TaskStudio 版本语义一致；规格"配置变化走 versions"的优化留待二期（见下 B1）。
7. **stop 对不存在任务返回 409**（"任务未在运行"）：规格未要求 404，保持现状。

## 遗留项（建议二期或后续清理处理）

| # | 项 | 位置 | 建议方向 |
|---|---|---|---|
| B1 | 编辑仅改基本信息也 bump 版本 | TaskFormDialog.vue save | 配置与 originalConfiguration 深比较，无变化跳过 POST versions |
| B2 | 任务重命名无 UI 入口（PUT 支持 name） | TaskFormDialog.vue name 编辑态禁用 | 放开编辑态名称或列表行内重命名 |
| B3 | 轮询清错误提示：loadTasks 开头清 error，批量失败汇总在有 RUNNING 时 5s 后被抹掉 | ProcessingTasks.vue loadTasks | 轮询路径不清 error，或失败汇总改用独立 toast |
| B4 | SYNC UPSERT 的逐行 UPDATE 语句未注册 cancel（仅 insert 注册） | DatabaseSyncExecutor 路由 | register(update) 提升停止即时性 |
| B5 | 目录 create 父存在性检查无锁无事务，并发 delete 时 FK 违例→500 | TaskDirectoryStore.create | 检查入事务并 FOR UPDATE 父行（对齐任务侧 create） |
| B6 | execute 异常分支注释与行为不符（清标志未恢复） | TaskRunService.java 约 :96 | 修正注释或统一恢复语义 |
| B7 | 启动弹窗展示版本号可能滞后于实际执行版本 | ProcessingTasks.vue 启动弹窗正文 | 弹窗正文改用重解析后快照渲染 |
| B8 | 目录名称 >120 前端无预校验，落后端 400 笼统文案 | ProcessingTasks.vue addDirectory/renameDirectory prompt | inputValidator 长度校验+专属文案 |
| B9 | 运行记录弹窗状态/时间直出英文枚举与 ISO 串 | TaskRunsDialog.vue | 复用列表页 runStateLabels/fmtRunTime（抽共享 helper） |
| B10 | V33 权限描述"删除任务"未提目录 | V33 迁移（已提交，不改） | 二期迁移顺带 UPDATE 描述文案 |
| B11 | stop 持 runs 行锁期间 Statement.cancel 若驱动阻塞会拖长持锁 | TaskRunService.stop | cancel 挪到 afterCommit 同步器 |
| B12 | active.remove 无条件移除（极端交错误删后继 attempt ctx） | TaskRunService.execute 三处出口 | 改 active.remove(id, cancel) 双参 |
| B13 | cancel/detach 纳秒级读后动作竞态 | RunCancelContext | cancel()/detach() 加 synchronized 定序 |
| B14 | sort 审计 objectId=0 哨兵 | TaskDirectoryController.sort | 用 AuditLog String 重载记 "all" 或 id 列表 |
| B15 | sort 不校验同父 sortOrder 重复/负值 | TaskDirectoryStore.sort | 全量替换契约下可选加校验 |
| B16 | Directory record 兼职 sort 载体（name 恒 null） | TaskDirectoryController.sort → Store | 独立 SortEntry record |
| B17 | create 目录 MAX+1 并发竞态（并列排序，无害自愈） | TaskDirectoryStore.create | 可接受；若修包事务锁父行 |
| B18 | sort/delete 加锁顺序不一致的低概率死锁窗口（DB 自动裁决） | TaskDirectoryStore | 记录即可 |
| B19 | 批量编排（confirmStart/stopBatch 循环）未抽纯函数、无测试 | ProcessingTasks.vue | 抽 runBatch helper 入 processing-tasks.js 补测 |
| B20 | loadAll 自身不取序号，慢网络下旧快照可覆盖较新 poll 结果 | ProcessingTasks.vue loadAll | 开头取号、赋值前校验 |
| B21 | 弹窗期间被删除任务被 filter(Boolean) 静默跳过，汇总无体现 | ProcessingTasks.vue confirmStart/stopBatch | 记 {ok:false, error:'任务已不存在'} |
| B22 | updateMetadata 行载体 Object[] 类型安全 | TaskVersionStore.updateMetadata | 改私有 record |
| B23 | 参数编辑器模板与 TaskStudio 重复（含已漂移文案修复） | TaskFormDialog.vue / TaskStudio.vue | 抽 ParameterEditor 组件消重 |
| B24 | hasActiveRun 活跃状态谓词多处重复 | TaskRunService | 提取常量 |

## 验收补充场景（一期手工清单之外）

- 轮询期间拖拽目录排序不丢失（loadTasks 只刷 tasks 已修复，验收确认）
- 勾选任务后他人保存新版本再批量启动 → 按最新版本执行（快照重解析已修复，验收确认）
- 任务运行中从运行记录重试旧实例 → 409（retry 守卫已修复，验收确认）
- 窄屏目录为顶部下拉而非堆叠树（R2 已修复，验收确认）
