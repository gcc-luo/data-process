# 处理任务二期（画布编排）· 遗留 backlog 与已知设计决策

日期：2026-11-20
来源：二期八任务两阶段审查 + 修复轮复核（分支 cindy/processing-flow）。
用途：三期及后续维护据此区分"已知决策"与"漏网缺陷"；均不阻塞二期合并。

## 已知设计决策（有意为之，勿当缺陷误改）

1. **客户端校验为预检子集**：flow-graph.js validateFlow 只做结构+必填子集；key 正则/长度 64、节点≤100/边≤200、mappings 条目合法性、表达式语法、batchSize 范围值等以后端 FlowGraph 保存期校验为权威（头注释已声明）。
2. **单入边+单出边链/森林模型**：非 sink 出度≤1、sink/预处理入度=1；多来源 JOIN 不支持（W1 定案），执行器单上游拉取与之匹配。
3. **预览口径**：limit 施加在 source（前 100 行），filter 后统计为截断流口径而非全表比例；前端预览 Tab 已标注"仅基于前 100 行"。
4. **stats 无终态守卫**：attempt 钉定（创建时捕获 attempt 号）限制影响半径；stats 写失败降级 WARN 不击穿执行结果（resilientStats）。
5. **durationMs 口径**：非 sink 节点为构建期耗时、sink 含排空与写入（流式拓扑固有语义，NodeStat javadoc 已注明）。
6. **RUNNING FLOW 只读画布**：readonly prop 禁保存/试运行/拖拽/连线/删除/抽屉写回；后端 PUT/DELETE/versions 均有活跃 run 409 守卫（最终审查后补 versions 守卫）。复制草稿剔除 runState/lastRun 保证副本可编辑。
7. **试运行超时残留**：不可中断 JDBC 建连阶段（无已注册语句）超时仍受驱动连接超时约束；cancel.worker 注册后自检闭合注册前竞态。
8. **CONCAT 数字隐式转 toPlainString**（SQL CONCAT 语义）；UPPER/LOWER/TRIM/SUBSTR 严格字符串；AND/OR falsy-null、其余 null 传播。

## 遗留项（建议三期或后续清理处理）

| # | 项 | 位置 | 建议方向 |
|---|---|---|---|
| C1 | 表达式错误消息无位置信息 | FlowExpression | Token 带源偏移，报错带"位置 N" |
| C2 | 一元 + 不做数字类型检查（与 - 不对称） | FlowExpression.unary | 统一类型检查 |
| C3 | FLOW 引用组件集合双事实来源 | DataSourceStore.deleteUnused 内联集合 vs FlowGraph 常量 | FlowGraph 常量旁注释指向 deleteUnused，或抽共享常量模块 |
| C4 | currentAttempt 双查询 | WorkflowRunStore.nodeLogSink/nodeStatsSink | 三参重载接收 attempt 或复用 sink 的 attempt |
| C5 | 试运行 400 消息泄露面（SQLException 含执行账号/内网拓扑） | TaskController.flowTest ExecutionException 透传 | 按 cause 链白名单透传业务校验消息，其余归一文案 |
| C6 | FlowTestRunService 提取 + 超时注入参数 | TaskController.flowTest 35 行编排 | 解耦后可加毫秒级超时单测（保留 30s 真测作集成验证） |
| C7 | configSummary 死分支（未知抛错守卫后 default 不可达） | flow-components.js | 清理 |
| C8 | 旧测试弱断言（includes('f') 单字符） | flow-graph.test.js 旧用例 | 升级完整消息断言 |
| C9 | 编辑器 alert 分源/可关闭 | FlowEditor error 单 alert | 连线类就地提示、请求类可关闭 |
| C10 | 保存 400 透传后端节点定位 message | FlowEditor.save | 读响应 body message（后端已放行 FLOW 消息） |
| C11 | 离开确认无脏检查/无 beforeunload | FlowEditor.leave | 对比初始 graph 快照；刷新/关标签保护 |
| C12 | 抽屉确定无即时校验 | FlowEditor.saveConfig | 调 validateNodeConfig 就地提示 |
| C13 | 自连/重复边守卫多余 alert（VueFlow 默认已拦自连） | FlowEditor.onConnect | 静默或就地提示 |
| C14 | v-if 销毁列表 DOM 丢树折叠态/滚动位置 | ProcessingTasks 视图切换 | KeepAlive 或 div+v-show |
| C15 | FLOW 任务元数据（名称/目录/描述）无编辑入口 | ProcessingTasks.openEdit 直开画布 | 画布工具栏加"基础信息"入口（后端 PUT 已支持） |
| C16 | 空画布/未改动离开也弹确认 | FlowEditor.leave | 脏检查（同 C11） |
| C17 | 轮询失败横幅跨视图残留 | ProcessingTasks loadTasks catch | editor 视图下抑制父级 error 或分源 |
| C18 | 预览统计口径易误读（已标注提示） | 前端预览 Tab | 维持标注；可选加"截断流"字样 |
| C19 | schema 独立 schema 字段并入 qualified table（与规格配置模型形状不同、前后端一致功能等价） | FlowGraph/flow-components | 补记设计决策或恢复独立字段 |
| C20 | FIELD_MASK "****" 硬编码未抽共享工具（与 DatasetQueryEngine 语义一致） | FlowTaskExecutor | 抽共享脱敏工具常量 |
| C21 | FLOW 无 retry 策略 UI 入口（后端支持；与 C15 同类） | TaskFormDialog/FlowEditor | 画布基础信息入口一并补 |
| C22 | 字段校验行级触发（空来源不报"上游缺少字段"） | FlowTaskExecutor | 可选保存期 schema 预检 |
| C23 | openConfig 调 configToForm 无 try/catch（外部写入超安全整数参数致抽屉打不开） | FlowEditor.openConfig | 同 saveConfig 加防护 |
| C24 | paramsToForm 未严格校验 p.type==='VALUE'（比 task-edit 宽松，畸形参数静默归一） | flow-config.js | 对齐 fromParameters 严格校验 |

## 验收补充场景（二期手工清单之外）

- 复制运行中 FLOW 任务 → 副本画布可编辑可保存（runState 剔除回归测试已锁定）
- RUNNING FLOW 行"查看"→ 只读画布（抽屉纯查看、无保存/试运行）
- 触屏/窄屏删除未知组件节点（工具栏删除节点按钮）
- 空名/超 160 名 FLOW 表单拦截（不进画布）
