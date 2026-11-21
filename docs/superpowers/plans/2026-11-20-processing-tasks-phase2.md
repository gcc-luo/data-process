# 数据处理 · 处理任务（二期：画布编排）实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 新增 FLOW 流程编排任务类型：VueFlow 三栏画布（组件面板/画布/配置抽屉）、7 种节点组件、受限表达式、安全试运行（不写目标表）、执行日志与节点统计落库（V34）。

**架构：** 后端新增 FlowExpression（递归下降解析+求值）、FlowGraph（模型+结构校验）、FlowTaskExecutor（流式拉取管道）、RunLogSink/ExecutionSinks/DbRunLogSink（日志与统计接线）；CamelTaskRunner 增加 FLOW 分支与四参重载；TaskRunService/WorkflowWorker 分别注入 TASK_RUN/WORKFLOW_NODE 日志 sink。前端新增 flow-components.js/flow-graph.js 纯逻辑、FlowNode.vue/FlowEditor.vue，ProcessingTasks 与 TaskFormDialog 增加 FLOW 两段式创建与编辑入口。

**技术栈：** Java 21 / Spring Boot / Flyway（H2+MySQL 双份）、Vue 3 / @vue-flow/core（已有）/ Element Plus、node:test。

**规格：** `docs/superpowers/specs/2026-11-20-processing-tasks-design.md` 二期节。一期 backlog 见 `-backlog.md`（B1-B24 不在本期范围）。

**环境注意：**
- mvn 必须带 `JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"` 与 `TMP=D:/tmp TEMP=D:/tmp`（C 盘常满，正斜杠！反斜杠会被 bash 吃掉导致 surefire 路径非法）。
- 后端测试：`cd backend && TMP=D:/tmp TEMP=D:/tmp JAVA_HOME="..." mvn test -Dtest=<类> -DfailIfNoTests=false`；全量同前缀。
- 前端：`cd frontend && node --test "tests/*.test.js"`、`npm run build`。
- 基线（一期合并后）：后端 242、前端 59。
- 建议在独立 worktree 执行（.cindy-worktrees/processing-flow，分支 cindy/processing-flow）。

**规格细化（本期落地时确认）：** execution_logs 增加 `node_key VARCHAR(128) NULL` 列——WORKFLOW_NODE 的 attempt 主键是 (run_id,node_key,attempt_no) 复合键，单一 owner_id 无法定位节点；TASK_RUN 行 node_key 为 NULL。规格原文未含此列，属必要细化。

---

## 文件结构

| 文件 | 职责 |
|---|---|
| 创建 `backend/src/main/resources/db/migration/V34__execution_logs_and_stats.sql` + `db/mysql/` 同名 | execution_logs 表（含 node_key）+ 两 attempts 表 stats 列 |
| 创建 `backend/.../workflow/RunLogSink.java` | 日志 sink 函数接口 |
| 创建 `backend/.../workflow/ExecutionSinks.java` | log+stats sink 组合 record，NOOP 常量 |
| 创建 `backend/.../workflow/DbRunLogSink.java` | 落库 sink：行号自增、2000 行截断标记 |
| 创建 `backend/.../workflow/FlowExpression.java` | 受限表达式解析+求值 |
| 创建 `backend/.../workflow/FlowGraph.java` | 图模型、结构/配置校验、拓扑序 |
| 创建 `backend/.../workflow/FlowTaskExecutor.java` | 流式管道执行/预览、统计、取消检查 |
| 修改 `backend/.../workflow/CamelTaskRunner.java` | FLOW 分支 + 四参重载 |
| 修改 `backend/.../workflow/WorkflowWorker.java` | TaskRunner 接口四参 default + executeNode 注入 node sinks |
| 修改 `backend/.../workflow/WorkflowRunStore.java` | nodeLogSink/nodeStatsSink/currentAttempt |
| 修改 `backend/.../workflow/TaskRunService.java` | execute 注入 TASK_RUN sinks |
| 修改 `backend/.../workflow/TaskDefinitionValidator.java` | FLOW 分支；sourceId 校验移入 SQL/SYNC |
| 修改 `backend/.../workflow/TaskController.java` | POST /api/tasks/flow/test-run |
| 修改 `backend/.../security/PermissionCatalog.java` | test-run 路由 |
| 修改 `backend/.../workflow/TaskVersionStore.java` | create 类型集合加 FLOW |
| 创建 `frontend/src/flow-components.js` | 组件目录+配置 schema+默认值 |
| 创建 `frontend/src/flow-graph.js` | 客户端结构校验+VueFlow 适配 |
| 创建 `frontend/src/FlowNode.vue` | 自定义节点卡片 |
| 创建 `frontend/src/FlowEditor.vue` | 三栏编辑器+试运行弹窗 |
| 修改 `frontend/src/ProcessingTasks.vue` | list/editor 视图切换、FLOW 行入口、类型列 |
| 修改 `frontend/src/TaskFormDialog.vue` | FLOW 类型选项+flow emit |
| 测试 | DbRunLogSinkTest、WorkflowNodeLogTest、FlowExpressionTest、FlowGraphTest、FlowTaskExecutorTest、FlowTestRunApiTest、flow-graph.test.js、flow-components.test.js |

---

### 任务 1：V34 迁移与日志/统计接线

**文件：**
- 创建：两份 V34 迁移
- 创建：`RunLogSink.java`、`ExecutionSinks.java`、`DbRunLogSink.java`
- 修改：`WorkflowRunStore.java`、`WorkflowWorker.java`、`TaskRunService.java`、`CamelTaskRunner.java`
- 测试：`backend/src/test/java/com/example/governance/workflow/DbRunLogSinkTest.java`、`WorkflowNodeLogTest.java`、`RealRunnerCancelPropagationTest.java`

**P0 防护（审查后补充，必做）：** TaskRunService 改调四参后，CamelTaskRunner 必须立即增加四参 override 委托三参（保 cancel 传递；sinks 暂忽略，任务 4 替换为完整实现），否则接口 default 委托两参时新建永不取消的上下文→真实任务停止回归（mock 会掩盖）。配套真实 runner 回归测试 RealRunnerCancelPropagationTest：代理 PreparedStatement 在 executeUpdate 阻塞等待 cancel，断言 Statement.cancel 被四参路径触发。

- [x] **步骤 1：编写失败的测试**

`DbRunLogSinkTest.java`：
```java
package com.example.governance.workflow;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DbRunLogSinkTest {
    private JdbcTemplate fixture() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        return new JdbcTemplate(db);
    }

    @Test void linesAreNumberedAndTruncatedAt2000() {
        var jdbc = fixture();
        var sink = new DbRunLogSink(jdbc, "TASK_RUN", 7, null, 1);
        for (int i = 1; i <= 2005; i++) sink.log("INFO", "line " + i);
        assertEquals(2001, jdbc.queryForObject("SELECT COUNT(*) FROM execution_logs WHERE owner_type='TASK_RUN' AND owner_id=7", Integer.class));
        assertEquals("line 2000", jdbc.queryForObject("SELECT message FROM execution_logs WHERE line_no=2000", String.class));
        assertEquals("WARN", jdbc.queryForObject("SELECT level FROM execution_logs WHERE line_no=2001", String.class));
        assertEquals("日志超过 2000 行，已截断", jdbc.queryForObject("SELECT message FROM execution_logs WHERE line_no=2001", String.class));
        assertNull(jdbc.queryForObject("SELECT node_key FROM execution_logs WHERE line_no=1", String.class));
    }

    @Test void longMessagesAreClippedAndNodeKeyStored() {
        var jdbc = fixture();
        var sink = new DbRunLogSink(jdbc, "WORKFLOW_NODE", 9, "n1", 2);
        sink.log("ERROR", "x".repeat(3000));
        assertEquals(2000, jdbc.queryForObject("SELECT LENGTH(message) FROM execution_logs WHERE owner_id=9", Integer.class));
        assertEquals("n1", jdbc.queryForObject("SELECT node_key FROM execution_logs WHERE owner_id=9", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT attempt_no FROM execution_logs WHERE owner_id=9", Integer.class));
    }
}
```

`WorkflowNodeLogTest.java`（验证 worker 接线与 stats 落库；mock runner 捕获 sinks）：
```java
package com.example.governance.workflow;

import com.example.governance.GovernanceApplication;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=GovernanceApplication.class)
@TestPropertySource(properties="spring.datasource.url=jdbc:h2:mem:workflow_node_log;DB_CLOSE_DELAY=-1")
class WorkflowNodeLogTest {
    @Autowired JdbcTemplate jdbc; @Autowired WorkflowRunStore runs; @Autowired WorkflowWorker worker;
    @MockitoBean CamelTaskRunner runner;

    @Test void nodeLogsAndStatsLandOnNodeAttempt() throws Exception {
        jdbc.update("INSERT INTO processing_tasks(id,name,task_type,current_version) VALUES (801,'t','SQL',1)");
        jdbc.update("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (801,1,'{}')");
        jdbc.update("INSERT INTO workflow_definitions(id,name,current_version) VALUES (801,'wf',1)");
        jdbc.update("INSERT INTO workflow_versions(workflow_id,version) VALUES (801,1)");
        jdbc.update("INSERT INTO workflow_nodes(workflow_id,workflow_version,node_key,ordinal_no,task_id,task_version) VALUES (801,1,'n1',0,801,1)");
        jdbc.update("INSERT INTO workflow_runs(id,workflow_id,workflow_version,business_date,state,created_at) VALUES (801,801,1,DATE '2026-11-20','RUNNING',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO workflow_run_nodes(run_id,node_key,state) VALUES (801,'n1','PENDING')");
        Mockito.doAnswer(inv -> {
            ExecutionSinks sinks = inv.getArgument(3);
            sinks.log().log("INFO", "节点开始");
            sinks.stats().accept("[{\"nodeKey\":\"n1\",\"rowsIn\":3,\"rowsOut\":3,\"durationMs\":5}]");
            return null;
        }).when(runner).execute(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        worker.process(801);
        assertEquals("节点开始", jdbc.queryForObject("SELECT message FROM execution_logs WHERE owner_type='WORKFLOW_NODE' AND owner_id=801 AND node_key='n1'", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT attempt_no FROM execution_logs WHERE owner_id=801", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT stats FROM workflow_node_attempts WHERE run_id=801 AND node_key='n1'", String.class).contains("\"rowsIn\":3"));
    }
}
```
（workflow_runs 列以 V9 实际 schema 为准，若列名不符按迁移调整插入语句。）

- [x] **步骤 2：运行测试验证失败**

运行：`cd backend && TMP=D:/tmp TEMP=D:/tmp JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot" mvn test -Dtest=DbRunLogSinkTest,WorkflowNodeLogTest -DfailIfNoTests=false`
预期：FAIL（类/表不存在）。

- [x] **步骤 3：编写迁移与 sink 类**

`db/migration/V34__execution_logs_and_stats.sql`：
```sql
-- Execution logs and per-attempt node stats for task and workflow runs.
CREATE TABLE execution_logs (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    owner_type VARCHAR(20) NOT NULL,
    owner_id BIGINT NOT NULL,
    node_key VARCHAR(128),
    attempt_no INTEGER NOT NULL,
    line_no INTEGER NOT NULL,
    level VARCHAR(10) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_execution_logs_owner ON execution_logs(owner_type, owner_id, node_key, attempt_no, line_no);
ALTER TABLE task_run_attempts ADD COLUMN stats TEXT;
ALTER TABLE workflow_node_attempts ADD COLUMN stats TEXT;
```
`db/mysql/V34__execution_logs_and_stats.sql`：同上，主键改 `id BIGINT AUTO_INCREMENT PRIMARY KEY`。

`RunLogSink.java`：
```java
package com.example.governance.workflow;

/** Receives execution log lines; implementations assign line numbers and persist or collect. */
@FunctionalInterface
public interface RunLogSink { void log(String level, String message); }
```

`ExecutionSinks.java`：
```java
package com.example.governance.workflow;

import java.util.function.Consumer;

/** Log sink plus a one-shot stats JSON consumer persisted onto the current attempt row. */
public record ExecutionSinks(RunLogSink log, Consumer<String> stats) {
    public static final ExecutionSinks NOOP = new ExecutionSinks((level, message) -> {}, json -> {});
}
```

`DbRunLogSink.java`：
```java
package com.example.governance.workflow;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;

/** Persists log lines with per-attempt numbering; caps at 2000 lines plus one truncation marker. */
public final class DbRunLogSink implements RunLogSink {
    private final JdbcTemplate jdbc;
    private final String ownerType;
    private final long ownerId;
    private final String nodeKey;
    private final int attempt;
    private final AtomicInteger lines = new AtomicInteger();
    public DbRunLogSink(JdbcTemplate jdbc, String ownerType, long ownerId, String nodeKey, int attempt) {
        this.jdbc = jdbc; this.ownerType = ownerType; this.ownerId = ownerId; this.nodeKey = nodeKey; this.attempt = attempt;
    }
    @Override public void log(String level, String message) {
        int line = lines.incrementAndGet();
        if (line > 2001) return;
        boolean marker = line == 2001;
        String text = marker ? "日志超过 2000 行，已截断" : message;
        if (text.length() > 2000) text = text.substring(0, 2000);
        jdbc.update("INSERT INTO execution_logs(owner_type,owner_id,node_key,attempt_no,line_no,level,message) VALUES (?,?,?,?,?,?,?)",
                ownerType, ownerId, nodeKey, attempt, line, marker ? "WARN" : level, text);
    }
}
```

- [x] **步骤 4：接线 WorkflowRunStore / WorkflowWorker / TaskRunService**

`WorkflowRunStore.java` 新增（jdbc 字段已存在）：
```java
    public int currentAttempt(long runId, String node) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(attempt_no),1) FROM workflow_node_attempts WHERE run_id=? AND node_key=?", Integer.class, runId, node);
    }
    public RunLogSink nodeLogSink(long runId, String node) { return new DbRunLogSink(jdbc, "WORKFLOW_NODE", runId, node, currentAttempt(runId, node)); }
    public java.util.function.Consumer<String> nodeStatsSink(long runId, String node) {
        int attempt = currentAttempt(runId, node);
        return json -> jdbc.update("UPDATE workflow_node_attempts SET stats=? WHERE run_id=? AND node_key=? AND attempt_no=?", json, runId, node, attempt);
    }
```

`WorkflowWorker.java`：接口增加 default 四参：
```java
    @FunctionalInterface public interface TaskRunner {
        void execute(TaskVersionStore.Version task, LocalDate businessDate) throws Exception;
        default void execute(TaskVersionStore.Version task, LocalDate businessDate, RunCancelContext cancel, ExecutionSinks sinks) throws Exception { execute(task, businessDate); }
    }
```
`executeNode` 内 `runner.execute(task, businessDate);` 改为：
```java
            runner.execute(task, businessDate, new RunCancelContext(), new ExecutionSinks(runs.nodeLogSink(runId, key), runs.nodeStatsSink(runId, key)));
```

`TaskRunService.java` execute 内 `runner.execute(tasks.findVersion(taskId, version).orElseThrow(), date, cancel);` 改为：
```java
            var sinks = new ExecutionSinks(new DbRunLogSink(jdbc, "TASK_RUN", id, null, attempt),
                    json -> jdbc.update("UPDATE task_run_attempts SET stats=? WHERE run_id=? AND attempt_no=?", json, id, attempt));
            runner.execute(tasks.findVersion(taskId, version).orElseThrow(), date, cancel, sinks);
```

- [x] **步骤 5：运行测试验证通过**

运行：同步骤 2 命令；再跑 `mvn test -Dtest=WorkflowWorkerTest,TaskStopApiTest,StandaloneTaskRetryTest -DfailIfNoTests=false`（mock 桩若因四参失效按一期惯例同步桩签名，不得削弱断言）。
预期：PASS。

- [x] **步骤 6：Commit**

```bash
git add backend/src/main/resources/db/migration/V34__execution_logs_and_stats.sql backend/src/main/resources/db/mysql/V34__execution_logs_and_stats.sql backend/src/main/java/com/example/governance/workflow/RunLogSink.java backend/src/main/java/com/example/governance/workflow/ExecutionSinks.java backend/src/main/java/com/example/governance/workflow/DbRunLogSink.java backend/src/main/java/com/example/governance/workflow/WorkflowRunStore.java backend/src/main/java/com/example/governance/workflow/WorkflowWorker.java backend/src/main/java/com/example/governance/workflow/TaskRunService.java backend/src/test/java/com/example/governance/workflow/DbRunLogSinkTest.java backend/src/test/java/com/example/governance/workflow/WorkflowNodeLogTest.java
git commit -m "feat(processing): 执行日志与节点统计存储及任务/工作流接线

V34 新增 execution_logs（含 node_key）与 attempts.stats；
DbRunLogSink 行号自增与 2000 行截断；worker/service 注入对应 sink。"
```

---

### 任务 2：受限表达式解析与求值

**文件：**
- 创建：`backend/.../workflow/FlowExpression.java`
- 测试：`backend/src/test/java/com/example/governance/workflow/FlowExpressionTest.java`

- [x] **步骤 1：编写失败的测试**

```java
package com.example.governance.workflow;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlowExpressionTest {
    private static Object eval(String expr, Map<String, Object> row) { return FlowExpression.parse(expr).eval(row); }

    @Test void arithmeticAndPrecedence() {
        assertEquals(new BigDecimal("7"), eval("1 + 2 * 3", Map.of()));
        assertEquals(new BigDecimal("9"), eval("(1 + 2) * 3", Map.of()));
        assertEquals(new BigDecimal("-2"), eval("-4 / 2", Map.of()));
    }

    @Test void fieldReferencesAreCaseInsensitive() {
        assertEquals(new BigDecimal("30"), eval("price * qty", Map.of("price", new BigDecimal("10"), "qty", new BigDecimal("3"))));
    }

    @Test void comparisonsAndLogic() {
        assertEquals(Boolean.TRUE, eval("amount > 100 AND status = 'OK'", Map.of("amount", new BigDecimal("150"), "status", "OK")));
        assertEquals(Boolean.FALSE, eval("NOT true", Map.of()));
        assertEquals(Boolean.TRUE, eval("1 != 2 OR false", Map.of()));
    }

    @Test void nullPropagates() {
        assertNull(eval("missing + 1", Map.of()));
        assertNull(eval("missing > 1", Map.of()));
        assertEquals(Boolean.FALSE, eval("missing > 1 AND true", Map.of()));
    }

    @Test void functions() {
        assertEquals("ABC", eval("UPPER(name)", Map.of("name", "abc")));
        assertEquals("abc", eval("LOWER('ABC')", Map.of()));
        assertEquals("bc", eval("SUBSTR('abc', 2)", Map.of()));
        assertEquals("b", eval("SUBSTR('abc', 2, 1)", Map.of()));
        assertEquals("ab!", eval("CONCAT('a', 'b', '!')", Map.of()));
        assertEquals(new BigDecimal("3"), eval("ABS(-3)", Map.of()));
        assertEquals(new BigDecimal("3.14"), eval("ROUND(3.14159, 2)", Map.of()));
        assertEquals("x", eval("TRIM('  x  ')", Map.of()));
    }

    @Test void stringComparisonAndConcatNull() {
        assertEquals(Boolean.TRUE, eval("'a' < 'b'", Map.of()));
        assertEquals("a", eval("CONCAT('a', missing)", Map.of()));
    }

    @Test void typeErrorsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> eval("'a' + 1", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> eval("1 AND true", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> eval("'a' > 'b' + 1", Map.of()));
    }

    @Test void syntaxErrorsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> FlowExpression.parse("1 +"));
        assertThrows(IllegalArgumentException.class, () -> FlowExpression.parse("FOO(1)"));
        assertThrows(IllegalArgumentException.class, () -> FlowExpression.parse("(1"));
        assertThrows(IllegalArgumentException.class, () -> FlowExpression.parse(""));
        assertThrows(IllegalArgumentException.class, () -> FlowExpression.parse("1; DROP TABLE x"));
    }
}
```

- [x] **步骤 2：运行测试验证失败**

运行：`cd backend && TMP=D:/tmp TEMP=D:/tmp JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot" mvn test -Dtest=FlowExpressionTest -DfailIfNoTests=false`
预期：FAIL（类不存在）。

- [x] **步骤 3：实现 FlowExpression**

创建 `FlowExpression.java`（递归下降；值域 String/BigDecimal/Boolean/null；列名统一小写）：
```java
package com.example.governance.workflow;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Restricted expression language for FIELD_FILTER / FIELD_CALC. No arbitrary code execution. */
public final class FlowExpression {
    public interface Expr { Object eval(Map<String, Object> row); }
    private static final Set<String> FUNCTIONS = Set.of("UPPER", "LOWER", "TRIM", "SUBSTR", "CONCAT", "ABS", "ROUND");
    private record Token(String kind, String text) {}
    private final List<Token> tokens;
    private int pos;
    private FlowExpression(List<Token> tokens) { this.tokens = tokens; }

    public static Expr parse(String source) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("表达式不能为空");
        var parser = new FlowExpression(tokenize(source));
        Expr expr = parser.or();
        if (parser.pos != parser.tokens.size()) throw new IllegalArgumentException("表达式语法错误: 多余内容 " + parser.tokens.get(parser.pos).text());
        return expr;
    }

    private static List<Token> tokenize(String source) {
        var tokens = new ArrayList<Token>();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '\'') {
                int end = source.indexOf('\'', i + 1);
                if (end < 0) throw new IllegalArgumentException("表达式语法错误: 字符串未闭合");
                tokens.add(new Token("STRING", source.substring(i + 1, end))); i = end + 1; continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < source.length() && Character.isDigit(source.charAt(i + 1)))) {
                int end = i;
                while (end < source.length() && (Character.isDigit(source.charAt(end)) || source.charAt(end) == '.')) end++;
                tokens.add(new Token("NUMBER", source.substring(i, end))); i = end; continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int end = i;
                while (end < source.length() && (Character.isLetterOrDigit(source.charAt(end)) || source.charAt(end) == '_')) end++;
                tokens.add(new Token("IDENT", source.substring(i, end))); i = end; continue;
            }
            if (c == '(' || c == ')' || c == ',') { tokens.add(new Token(String.valueOf(c), String.valueOf(c))); i++; continue; }
            if (c == '!' && i + 1 < source.length() && source.charAt(i + 1) == '=') { tokens.add(new Token("OP", "!=")); i += 2; continue; }
            if (c == '>' || c == '<') {
                if (i + 1 < source.length() && source.charAt(i + 1) == '=') { tokens.add(new Token("OP", c + "=")); i += 2; }
                else { tokens.add(new Token("OP", String.valueOf(c))); i++; }
                continue;
            }
            if (c == '=' || c == '+' || c == '-' || c == '*' || c == '/') { tokens.add(new Token("OP", String.valueOf(c))); i++; continue; }
            throw new IllegalArgumentException("表达式语法错误: 非法字符 " + c);
        }
        return tokens;
    }

    private Token peek() { return pos < tokens.size() ? tokens.get(pos) : null; }
    private Token take() { Token t = peek(); if (t == null) throw new IllegalArgumentException("表达式语法错误: 意外结束"); pos++; return t; }
    private boolean matchOp(String op) { Token t = peek(); if (t != null && t.kind().equals("OP") && t.text().equals(op)) { pos++; return true; } return false; }
    private boolean matchKind(String kind) { Token t = peek(); if (t != null && t.kind().equals(kind)) { pos++; return true; } return false; }
    private boolean matchIdent(String word) { Token t = peek(); if (t != null && t.kind().equals("IDENT") && t.text().equalsIgnoreCase(word)) { pos++; return true; } return false; }

    private Expr or() {
        Expr left = and();
        while (matchIdent("OR")) { Expr right = and(); Expr l = left; left = row -> { Object a = l.eval(row); Object b = right.eval(row); return boolOrFalse(a) || boolOrFalse(b); }; }
        return left;
    }
    private Expr and() {
        Expr left = not();
        while (matchIdent("AND")) { Expr right = not(); Expr l = left; left = row -> { Object a = l.eval(row); Object b = right.eval(row); return boolOrFalse(a) && boolOrFalse(b); }; }
        return left;
    }
    private Expr not() {
        if (matchIdent("NOT")) { Expr inner = not(); return row -> { Object v = inner.eval(row); return v == null ? null : !bool(v); }; }
        return comparison();
    }
    private Expr comparison() {
        Expr left = additive();
        Token t = peek();
        if (t != null && t.kind().equals("OP") && Set.of("=", "!=", ">", "<", ">=", "<=").contains(t.text())) {
            pos++;
            Expr right = additive();
            String op = t.text();
            return row -> {
                Object a = left.eval(row); Object b = right.eval(row);
                if (a == null || b == null) return null;
                int cmp;
                if (a instanceof BigDecimal x && b instanceof BigDecimal y) cmp = x.compareTo(y);
                else if (a instanceof String x && b instanceof String y) cmp = x.compareTo(y);
                else if (a instanceof Boolean x && b instanceof Boolean y && (op.equals("=") || op.equals("!="))) cmp = x.equals(y) ? 0 : 1;
                else throw new IllegalArgumentException("表达式类型错误: 无法比较 " + a.getClass().getSimpleName() + " 与 " + b.getClass().getSimpleName());
                return switch (op) { case "=" -> cmp == 0; case "!=" -> cmp != 0; case ">" -> cmp > 0; case "<" -> cmp < 0; case ">=" -> cmp >= 0; default -> cmp <= 0; };
            };
        }
        return left;
    }
    private Expr additive() {
        Expr left = multiplicative();
        while (true) {
            if (matchOp("+")) { Expr right = multiplicative(); Expr l = left; left = row -> arith(l.eval(row), right.eval(row), "+"); }
            else if (matchOp("-")) { Expr right = multiplicative(); Expr l = left; left = row -> arith(l.eval(row), right.eval(row), "-"); }
            else return left;
        }
    }
    private Expr multiplicative() {
        Expr left = unary();
        while (true) {
            if (matchOp("*")) { Expr right = unary(); Expr l = left; left = row -> arith(l.eval(row), right.eval(row), "*"); }
            else if (matchOp("/")) { Expr right = unary(); Expr l = left; left = row -> arith(l.eval(row), right.eval(row), "/"); }
            else return left;
        }
    }
    private Expr unary() {
        if (matchOp("-")) { Expr inner = unary(); return row -> { Object v = inner.eval(row); return v == null ? null : num(v).negate(); }; }
        if (matchOp("+")) return unary();
        return primary();
    }
    private Expr primary() {
        Token t = take();
        if (t.kind().equals("NUMBER")) return row -> new BigDecimal(t.text());
        if (t.kind().equals("STRING")) return row -> t.text();
        if (t.kind().equals("(")) { Expr inner = or(); expect(")"); return inner; }
        if (t.kind().equals("IDENT")) {
            String word = t.text();
            if (word.equalsIgnoreCase("true")) return row -> Boolean.TRUE;
            if (word.equalsIgnoreCase("false")) return row -> Boolean.FALSE;
            if (word.equalsIgnoreCase("null")) return row -> null;
            Token next = peek();
            if (next != null && next.kind().equals("(")) {
                if (!FUNCTIONS.contains(word.toUpperCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("表达式语法错误: 未知函数 " + word);
                pos++;
                var args = new ArrayList<Expr>();
                if (!matchKind(")")) {
                    args.add(or());
                    while (matchKind(",")) args.add(or());
                    expect(")");
                }
                return call(word.toUpperCase(java.util.Locale.ROOT), args);
            }
            String field = word.toLowerCase(java.util.Locale.ROOT);
            return row -> row.get(field);
        }
        throw new IllegalArgumentException("表达式语法错误: 意外符号 " + t.text());
    }
    private void expect(String kind) { Token t = take(); if (!t.kind().equals(kind)) throw new IllegalArgumentException("表达式语法错误: 期望 " + kind); }

    private static Expr call(String fn, List<Expr> args) {
        return row -> {
            var values = new ArrayList<Object>();
            for (Expr arg : args) values.add(arg.eval(row));
            switch (fn) {
                case "UPPER": case "LOWER": case "TRIM": {
                    requireArity(fn, values, 1);
                    Object v = values.get(0);
                    if (v == null) return null;
                    String s = str(v);
                    return fn.equals("UPPER") ? s.toUpperCase(java.util.Locale.ROOT) : fn.equals("LOWER") ? s.toLowerCase(java.util.Locale.ROOT) : s.trim();
                }
                case "SUBSTR": {
                    if (values.size() < 2 || values.size() > 3) throw new IllegalArgumentException("表达式参数错误: SUBSTR 需 2-3 参");
                    Object v = values.get(0);
                    if (v == null) return null;
                    String s = str(v);
                    int start = num(values.get(1)).intValue();
                    if (start < 1) throw new IllegalArgumentException("表达式参数错误: SUBSTR 起始需 >=1");
                    int from = Math.min(start - 1, s.length());
                    int to = values.size() == 3 ? Math.min(from + num(values.get(2)).intValue(), s.length()) : s.length();
                    return s.substring(from, Math.max(from, to));
                }
                case "CONCAT": {
                    var sb = new StringBuilder();
                    for (Object v : values) sb.append(v == null ? "" : str(v));
                    return sb.toString();
                }
                case "ABS": {
                    requireArity(fn, values, 1);
                    Object v = values.get(0);
                    return v == null ? null : num(v).abs();
                }
                case "ROUND": {
                    if (values.isEmpty() || values.size() > 2) throw new IllegalArgumentException("表达式参数错误: ROUND 需 1-2 参");
                    Object v = values.get(0);
                    if (v == null) return null;
                    int digits = values.size() == 2 ? num(values.get(1)).intValue() : 0;
                    return num(v).setScale(digits, RoundingMode.HALF_UP);
                }
                default: throw new IllegalArgumentException("表达式语法错误: 未知函数 " + fn);
            }
        };
    }
    private static void requireArity(String fn, List<Object> values, int n) {
        if (values.size() != n) throw new IllegalArgumentException("表达式参数错误: " + fn + " 需 " + n + " 参");
    }
    private static Object arith(Object a, Object b, String op) {
        if (a == null || b == null) return null;
        BigDecimal x = num(a); BigDecimal y = num(b);
        return switch (op) {
            case "+" -> x.add(y);
            case "-" -> x.subtract(y);
            case "*" -> x.multiply(y);
            default -> y.signum() == 0 ? null : x.divide(y, MathContext.DECIMAL64);
        };
    }
    private static BigDecimal num(Object v) {
        if (v instanceof BigDecimal d) return d;
        throw new IllegalArgumentException("表达式类型错误: 期望数字, 实际 " + v.getClass().getSimpleName());
    }
    private static String str(Object v) {
        if (v instanceof String s) return s;
        throw new IllegalArgumentException("表达式类型错误: 期望字符串, 实际 " + v.getClass().getSimpleName());
    }
    private static boolean bool(Object v) {
        if (v instanceof Boolean b) return b;
        throw new IllegalArgumentException("表达式类型错误: 期望布尔, 实际 " + v.getClass().getSimpleName());
    }
    /** AND/OR 的 falsy-null：null 条件不保留行（过滤语义）；非 null 非布尔仍报错。 */
    private static boolean boolOrFalse(Object v) { return v == null ? false : bool(v); }
}
```
注意：`'a' > 'b' + 1` 用例中右侧先算 `'b'+1` 抛类型错误，符合测试预期；除零返回 null（过滤视为不保留）。

**语义定稿（审查后确认）：** 算术/比较/NOT 遇 null 传播 null；**AND/OR 将 null 视为 false**（falsy-null，符合 FIELD_FILTER 过滤语义：null 条件不保留行；与 SQL 三值逻辑在过滤结果上等价）；非 null 操作数仍严格布尔校验；CONCAT 视 null 为 ""、**BigDecimal 隐式转 toPlainString**（SQL CONCAT 语义，任务 4 审查定案；Boolean 仍拒绝）；UPPER/LOWER/TRIM/SUBSTR 保持严格字符串。函数参数列表的 `)`/`,` 用独立 matchKind 匹配（tokenize 将其 kind 设为字面符号而非 OP）。

- [x] **步骤 4：运行测试验证通过**

运行：同步骤 2 命令。预期：`Tests run: 8, Failures: 0`。

- [x] **步骤 5：Commit**

```bash
git add backend/src/main/java/com/example/governance/workflow/FlowExpression.java backend/src/test/java/com/example/governance/workflow/FlowExpressionTest.java
git commit -m "feat(processing): 流程编排受限表达式解析与求值

递归下降支持算术/比较/逻辑/括号与 UPPER/LOWER/TRIM/SUBSTR/CONCAT/ABS/ROUND；
null 传播、类型错误与语法错误明确拒绝，不执行任意代码。"
```

---

### 任务 3：图模型与结构校验

**文件：**
- 创建：`backend/.../workflow/FlowGraph.java`
- 修改：`backend/.../workflow/TaskDefinitionValidator.java`、`TaskVersionStore.java`
- 测试：`backend/src/test/java/com/example/governance/workflow/FlowGraphTest.java`

- [x] **步骤 1：编写失败的测试**

```java
package com.example.governance.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlowGraphTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static FlowGraph.Graph parse(String json) throws Exception { return FlowGraph.parse(MAPPER.readTree(json)); }
    private static String graph(String nodes, String edges) { return "{\"nodes\":[" + nodes + "],\"edges\":[" + edges + "]}"; }
    private static String node(String key, String component, String config) { return "{\"key\":\"" + key + "\",\"component\":\"" + component + "\",\"config\":" + config + "}"; }
    private static final String SRC = "{\"sourceId\":1,\"table\":\"orders\"}";
    private static final String SINK = "{\"sourceId\":2,\"table\":\"orders_copy\",\"writeMode\":\"APPEND\"}";

    @Test void validChainParsesAndTopoOrders() throws Exception {
        var g = parse(graph(node("s", "SOURCE_TABLE", SRC) + "," + node("f", "FIELD_FILTER", "{\"expression\":\"amount > 0\"}") + "," + node("t", "SINK_TABLE", SINK),
                "{\"from\":\"s\",\"to\":\"f\"},{\"from\":\"f\",\"to\":\"t\"}"));
        FlowGraph.validate(g);
        assertEquals(java.util.List.of("s", "f", "t"), FlowGraph.topoOrder(g).stream().map(FlowGraph.Node::key).toList());
    }

    @Test void cyclesRejected() throws Exception {
        var g = parse(graph(node("s", "SOURCE_TABLE", SRC) + "," + node("f", "FIELD_FILTER", "{\"expression\":\"true\"}") + "," + node("t", "SINK_TABLE", SINK),
                "{\"from\":\"s\",\"to\":\"f\"},{\"from\":\"f\",\"to\":\"t\"},{\"from\":\"f\",\"to\":\"s\"}"));
        assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(g));
    }

    @Test void layerRulesRejected() throws Exception {
        // 来源有入边
        var g1 = parse(graph(node("s", "SOURCE_TABLE", SRC) + "," + node("t", "SINK_TABLE", SINK), "{\"from\":\"t\",\"to\":\"s\"}"));
        assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(g1));
        // 目标有出边
        var g2 = parse(graph(node("s", "SOURCE_TABLE", SRC) + "," + node("t", "SINK_TABLE", SINK), "{\"from\":\"s\",\"to\":\"t\"},{\"from\":\"t\",\"to\":\"s\"}"));
        assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(g2));
        // 拦截器多入边
        var g3 = parse(graph(node("s1", "SOURCE_TABLE", SRC) + "," + node("s2", "SOURCE_TABLE", SRC) + "," + node("f", "FIELD_FILTER", "{\"expression\":\"true\"}") + "," + node("t", "SINK_TABLE", SINK),
                "{\"from\":\"s1\",\"to\":\"f\"},{\"from\":\"s2\",\"to\":\"f\"},{\"from\":\"f\",\"to\":\"t\"}"));
        assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(g3));
    }

    @Test void danglingNodesAndMissingEndpointsRejected() throws Exception {
        var g1 = parse(graph(node("s", "SOURCE_TABLE", SRC) + "," + node("f", "FIELD_FILTER", "{\"expression\":\"true\"}"), "{\"from\":\"s\",\"to\":\"f\"}"));
        assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(g1)); // 无 sink
        var g2 = parse(graph(node("s", "SOURCE_TABLE", SRC) + "," + node("t", "SINK_TABLE", SINK) + "," + node("x", "FIELD_CALC", "{\"field\":\"c\",\"expression\":\"1\"}"), "{\"from\":\"s\",\"to\":\"t\"}"));
        assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(g2)); // 孤立节点
    }

    @Test void componentConfigRequired() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(parse(graph(node("s", "SOURCE_TABLE", "{\"sourceId\":1}") + "," + node("t", "SINK_TABLE", SINK), "{\"from\":\"s\",\"to\":\"t\"}"))));
        assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(parse(graph(node("s", "SOURCE_SQL", "{\"sourceId\":1,\"sql\":\"SELECT 1\"}") + "," + node("m", "FIELD_MAP", "{\"mappings\":[]}") + "," + node("t", "SINK_TABLE", SINK), "{\"from\":\"s\",\"to\":\"m\"},{\"from\":\"m\",\"to\":\"t\"}"))));
        assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(parse(graph(node("s", "SOURCE_TABLE", SRC) + "," + node("c", "FIELD_CALC", "{\"field\":\"c\",\"expression\":\"1 +\"}") + "," + node("t", "SINK_TABLE", SINK), "{\"from\":\"s\",\"to\":\"c\"},{\"from\":\"c\",\"to\":\"t\"}"))));
        assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(parse(graph(node("s", "SOURCE_TABLE", SRC) + "," + node("t", "SINK_TABLE", "{\"sourceId\":2,\"table\":\"t\",\"writeMode\":\"UPSERT\"}"), "{\"from\":\"s\",\"to\":\"t\"}"))));
    }

    @Test void duplicateKeysAndUnknownComponentsRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(parse(graph(node("s", "SOURCE_TABLE", SRC) + "," + node("s", "SINK_TABLE", SINK), "{\"from\":\"s\",\"to\":\"s\"}"))));
        assertThrows(IllegalArgumentException.class, () -> FlowGraph.validate(parse(graph(node("s", "KAFKA", SRC) + "," + node("t", "SINK_TABLE", SINK), "{\"from\":\"s\",\"to\":\"t\"}"))));
    }
}
```

- [x] **步骤 2：运行测试验证失败**

运行：`cd backend && TMP=D:/tmp TEMP=D:/tmp JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot" mvn test -Dtest=FlowGraphTest -DfailIfNoTests=false`
预期：FAIL（FlowGraph 不存在）。

- [x] **步骤 3：实现 FlowGraph**

创建 `FlowGraph.java`：
```java
package com.example.governance.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** FLOW task topology model with structural and per-component configuration validation. */
public final class FlowGraph {
    public record Node(String key, String component, JsonNode config, double x, double y) {}
    public record Edge(String from, String to) {}
    public record Graph(List<Node> nodes, List<Edge> edges) {}
    public static final Set<String> SOURCES = Set.of("SOURCE_TABLE", "SOURCE_SQL");
    public static final Set<String> SINKS = Set.of("SINK_TABLE");
    public static final Set<String> INTERCEPTORS = Set.of("FIELD_MAP", "FIELD_FILTER", "FIELD_MASK", "FIELD_CALC");
    private static final String IDENT = "[A-Za-z_][A-Za-z0-9_$]*";
    private static final String QUALIFIED = IDENT + "(\\." + IDENT + ")?";

    public static Graph parse(JsonNode root) {
        if (root == null || !root.isObject()) throw new IllegalArgumentException("Invalid flow configuration");
        JsonNode nodes = root.path("nodes"); JsonNode edges = root.path("edges");
        if (!nodes.isArray() || nodes.isEmpty() || nodes.size() > 100 || !edges.isArray() || edges.size() > 200) throw new IllegalArgumentException("Invalid flow nodes or edges");
        var nodeList = new ArrayList<Node>();
        for (JsonNode n : nodes) {
            String key = text(n, "key"); String component = text(n, "component");
            JsonNode config = n.path("config");
            if (!config.isObject()) throw new IllegalArgumentException("Invalid node config: " + key);
            nodeList.add(new Node(key, component, config, n.path("position").path("x").asDouble(0), n.path("position").path("y").asDouble(0)));
        }
        var edgeList = new ArrayList<Edge>();
        for (JsonNode e : edges) edgeList.add(new Edge(text(e, "from"), text(e, "to")));
        return new Graph(List.copyOf(nodeList), List.copyOf(edgeList));
    }

    public static void validate(Graph g) {
        var byKey = new HashMap<String, Node>();
        for (Node n : g.nodes()) {
            if (!n.key().matches(IDENT) || n.key().length() > 64 || byKey.put(n.key(), n) != null) throw new IllegalArgumentException("Invalid or duplicate node key: " + n.key());
            if (!SOURCES.contains(n.component()) && !SINKS.contains(n.component()) && !INTERCEPTORS.contains(n.component())) throw new IllegalArgumentException("Unknown component: " + n.component());
        }
        var in = new HashMap<String, Integer>(); var out = new HashMap<String, Integer>();
        var seenEdges = new HashSet<String>();
        for (Edge e : g.edges()) {
            if (!byKey.containsKey(e.from()) || !byKey.containsKey(e.to()) || e.from().equals(e.to()) || !seenEdges.add(e.from() + "->" + e.to())) throw new IllegalArgumentException("Invalid edge: " + e.from() + "->" + e.to());
            out.merge(e.from(), 1, Integer::sum); in.merge(e.to(), 1, Integer::sum);
        }
        boolean hasSource = false; boolean hasSink = false;
        for (Node n : g.nodes()) {
            int indeg = in.getOrDefault(n.key(), 0); int outdeg = out.getOrDefault(n.key(), 0);
            if (SOURCES.contains(n.component())) { hasSource = true; if (indeg != 0) throw new IllegalArgumentException("来源节点不能有入边: " + n.key()); }
            else if (SINKS.contains(n.component())) { hasSink = true; if (outdeg != 0) throw new IllegalArgumentException("目标节点不能有出边: " + n.key()); if (indeg != 1) throw new IllegalArgumentException("目标节点需恰一个入边: " + n.key()); }
            else if (indeg != 1) throw new IllegalArgumentException("预处理节点需恰一个入边: " + n.key());
            validateConfig(n);
        }
        if (!hasSource || !hasSink) throw new IllegalArgumentException("流程需至少一个来源与一个目标");
        // 每个节点必须可达某个 sink（反向可达集）
        var reverse = new HashMap<String, List<String>>();
        for (Edge e : g.edges()) reverse.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e.from());
        var reachable = new HashSet<String>();
        var queue = new ArrayDeque<String>();
        for (Node n : g.nodes()) if (SINKS.contains(n.component())) { reachable.add(n.key()); queue.add(n.key()); }
        while (!queue.isEmpty()) for (String prev : reverse.getOrDefault(queue.poll(), List.of())) if (reachable.add(prev)) queue.add(prev);
        for (Node n : g.nodes()) if (!reachable.contains(n.key())) throw new IllegalArgumentException("节点未连接到目标: " + n.key());
        topoOrder(g); // 环检测
    }

    public static List<Node> topoOrder(Graph g) {
        var byKey = new HashMap<String, Node>();
        var indeg = new HashMap<String, Integer>();
        var next = new HashMap<String, List<String>>();
        for (Node n : g.nodes()) { byKey.put(n.key(), n); indeg.put(n.key(), 0); }
        for (Edge e : g.edges()) { next.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to()); indeg.merge(e.to(), 1, Integer::sum); }
        var queue = new ArrayDeque<String>();
        for (Node n : g.nodes()) if (indeg.get(n.key()) == 0) queue.add(n.key());
        var order = new ArrayList<Node>();
        while (!queue.isEmpty()) {
            String key = queue.poll();
            order.add(byKey.get(key));
            for (String n : next.getOrDefault(key, List.of())) if (indeg.merge(n, -1, Integer::sum) == 0) queue.add(n);
        }
        if (order.size() != g.nodes().size()) throw new IllegalArgumentException("流程存在环");
        return order;
    }

    public static Map<String, String> upstream(Graph g) {
        var map = new HashMap<String, String>();
        for (Edge e : g.edges()) map.put(e.to(), e.from());
        return map;
    }

    private static void validateConfig(Node n) {
        JsonNode c = n.config();
        switch (n.component()) {
            case "SOURCE_TABLE" -> { id(c, "sourceId"); table(c, "table"); }
            case "SOURCE_SQL" -> { id(c, "sourceId"); text(c, "sql"); if (c.has("parameters")) parameters(c.path("parameters")); }
            case "FIELD_MAP" -> {
                JsonNode mappings = c.path("mappings");
                if (!mappings.isArray() || mappings.isEmpty() || mappings.size() > 1000) throw new IllegalArgumentException("Invalid mappings: " + n.key());
                var targets = new HashSet<String>();
                for (JsonNode m : mappings) {
                    String from = text(m, "from"); String to = text(m, "to");
                    if (!from.matches(IDENT) || !to.matches(IDENT) || !targets.add(to.toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("Invalid mapping: " + n.key());
                }
            }
            case "FIELD_FILTER" -> FlowExpression.parse(text(c, "expression"));
            case "FIELD_MASK" -> { if (!text(c, "field").matches(IDENT)) throw new IllegalArgumentException("Invalid mask field: " + n.key()); }
            case "FIELD_CALC" -> { if (!text(c, "field").matches(IDENT)) throw new IllegalArgumentException("Invalid calc field: " + n.key()); FlowExpression.parse(text(c, "expression")); }
            case "SINK_TABLE" -> {
                id(c, "sourceId"); table(c, "table");
                String mode = c.has("writeMode") ? text(c, "writeMode") : "APPEND";
                if (!Set.of("APPEND", "UPSERT").contains(mode)) throw new IllegalArgumentException("Invalid write mode: " + n.key());
                if ("UPSERT".equals(mode)) {
                    JsonNode keys = c.path("keyColumns");
                    if (!keys.isArray() || keys.isEmpty() || keys.size() > 1000) throw new IllegalArgumentException("Invalid key columns: " + n.key());
                    var seen = new HashSet<String>();
                    for (JsonNode k : keys) if (!k.isTextual() || !k.textValue().matches(IDENT) || !seen.add(k.textValue().toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("Invalid key column: " + n.key());
                }
                JsonNode batch = c.get("batchSize");
                if (batch != null && (!batch.isIntegralNumber() || !batch.canConvertToInt() || batch.intValue() < 1 || batch.intValue() > 10000)) throw new IllegalArgumentException("Invalid batch size: " + n.key());
            }
            default -> throw new IllegalArgumentException("Unknown component: " + n.component());
        }
    }
    private static void parameters(JsonNode params) {
        if (!params.isArray() || params.size() > 1000) throw new IllegalArgumentException("Invalid parameters");
        for (JsonNode p : params) {
            String kind = text(p, "type");
            if ("VALUE".equals(kind)) { JsonNode v = p.get("value"); if (v != null && !v.isValueNode()) throw new IllegalArgumentException("Invalid parameter"); }
            else if (!"BUSINESS_DATE".equals(kind)) throw new IllegalArgumentException("Invalid parameter type");
        }
    }
    private static void id(JsonNode c, String key) {
        JsonNode v = c.get(key);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToLong() || v.longValue() < 1) throw new IllegalArgumentException("Invalid source ID");
    }
    private static void table(JsonNode c, String key) {
        JsonNode v = c.get(key);
        if (v == null || !v.isTextual() || !v.textValue().matches(QUALIFIED)) throw new IllegalArgumentException("Invalid table");
    }
    private static String text(JsonNode c, String key) {
        JsonNode v = c.get(key);
        if (v == null || !v.isTextual() || v.textValue().isBlank()) throw new IllegalArgumentException("Invalid flow field: " + key);
        return v.textValue();
    }
}
```

- [x] **步骤 4：validator 与 create 类型集合**

`TaskDefinitionValidator.validate`：把无条件 `id(root, "sourceId");` 移入 SQL 与 SYNC 分支首行；新增分支：
```java
            } else if ("FLOW".equals(type)) {
                FlowGraph.validate(FlowGraph.parse(root));
            } else throw new IllegalArgumentException("Invalid task type");
```
（原末尾 `else throw` 保留为最终 else。）

`TaskVersionStore.create` 五参中 `Set.of("SQL", "SYNC")` 改为 `Set.of("SQL", "SYNC", "FLOW")`。

- [x] **步骤 5：运行测试验证通过**

运行：同步骤 2 命令 + `mvn test -Dtest=TaskDefinitionValidatorTest,TaskVersionStoreTest -DfailIfNoTests=false`。
预期：PASS（既有 SQL/SYNC 校验不回归）。

- [x] **步骤 6：Commit**

```bash
git add backend/src/main/java/com/example/governance/workflow/FlowGraph.java backend/src/main/java/com/example/governance/workflow/TaskDefinitionValidator.java backend/src/main/java/com/example/governance/workflow/TaskVersionStore.java backend/src/test/java/com/example/governance/workflow/FlowGraphTest.java
git commit -m "feat(processing): FLOW 图模型与结构/配置校验

层级规则（来源无入边/目标无出边/单入边）、环与悬空节点拒绝、
七组件必填配置与表达式语法保存期校验；validator/create 支持 FLOW。"
```

---

### 任务 3b：FLOW 源引用接线与校验定案（审查后新增，必做）

**K1（关键）：** `TaskController.verify` 取根级 sourceId（FLOW 为 0）→ lockSources 抛错 → FLOW 创建/加版本必 400；`DataSourceStore.deleteUnused` 遇 FLOW 版本行抛 IllegalStateException → 存在 FLOW 任务后所有数据源删除 500。修复：
- verify 按类型提取：FLOW 遍历 nodes[].config.sourceId 收集集合；SQL/SYNC 保持根级 sourceId(+targetId)。
- deleteUnused 增加 FLOW 分支：遍历 nodes[].config.sourceId，任一等于被删 id → IllegalStateException("Data source is referenced by a task version")；非法节点 sourceId → Cannot check safely；非 SQL/SYNC/FLOW 类型仍拒绝。
- FLOW 保存报错放行 ex.getMessage()：create 与 version 均保持抛 ResponseStatusException(BAD_REQUEST)（内层事务 rollback-only 下正常返回会 UnexpectedRollbackException），仅 reason 条件化（FLOW→ex.getMessage()，其他→通用文案），配合 W3 节点定位消息。

**W1 定案：** 非 sink 节点出度 ≤1（链/森林模型，与任务 4 单上游拉取执行器一致）；validate 增加 `非 sink outdeg>1 → IAE("节点只能有一条出边: key")`；前端 flow-graph.validateFlow 与 FlowEditor onConnect 同步该规则（任务 6/7 落实）。
**W2：** 补 sink 出边/孤立 sink 入度两个分支锁定用例。
**W3：** validate 内 validateConfig 调用 try/catch 重抛前置 "节点 key: "。
**N1：** upstream javadoc 注明仅对已 validate 图有意义。
**N6：** 补重复边/自环/非法 key/parse 限额/upstream 用例。

测试：FlowSourceReferenceApiTest（FLOW 创建 201 + 加版本 201；数据源被 FLOW 引用删除 409；无引用 FLOW 存在时其他数据源可删）+ FlowGraphTest 扩充。全量回归。

---

### 任务 4：FlowTaskExecutor 流式管道

**文件：**
- 创建：`backend/.../workflow/FlowTaskExecutor.java`
- 修改：`backend/.../workflow/CamelTaskRunner.java`
- 测试：`backend/src/test/java/com/example/governance/workflow/FlowTaskExecutorTest.java`

- [x] **步骤 1：编写失败的测试**

```java
package com.example.governance.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class FlowTaskExecutorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private record Fixture(JdbcTemplate jdbc, DataSource ds) {}
    private Fixture fixture() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:flow_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).load().migrate();
        var jdbc = new JdbcTemplate(ds);
        jdbc.update("CREATE TABLE orders(id INT PRIMARY KEY, name VARCHAR(50), amount DECIMAL(10,2))");
        jdbc.update("INSERT INTO orders VALUES (1,'a',10),(2,'b',-5),(3,'c',20)");
        jdbc.update("CREATE TABLE orders_copy(id INT PRIMARY KEY, name VARCHAR(50), amount DECIMAL(10,2), tag VARCHAR(10))");
        return new Fixture(jdbc, ds);
    }
    private FlowGraph.Graph graph(String json) throws Exception { return FlowGraph.parse(MAPPER.readTree(json)); }
    private static final String CHAIN = "{\"nodes\":["
            + "{\"key\":\"s\",\"component\":\"SOURCE_TABLE\",\"config\":{\"sourceId\":1,\"table\":\"orders\"}},"
            + "{\"key\":\"f\",\"component\":\"FIELD_FILTER\",\"config\":{\"expression\":\"amount > 0\"}},"
            + "{\"key\":\"m\",\"component\":\"FIELD_MASK\",\"config\":{\"field\":\"name\"}},"
            + "{\"key\":\"c\",\"component\":\"FIELD_CALC\",\"config\":{\"field\":\"tag\",\"expression\":\"CONCAT('t', id)\"}},"
            + "{\"key\":\"t\",\"component\":\"SINK_TABLE\",\"config\":{\"sourceId\":1,\"table\":\"orders_copy\",\"writeMode\":\"APPEND\"}}],"
            + "\"edges\":[{\"from\":\"s\",\"to\":\"f\"},{\"from\":\"f\",\"to\":\"m\"},{\"from\":\"m\",\"to\":\"c\"},{\"from\":\"c\",\"to\":\"t\"}]}";

    @Test void appendPipelineFiltersMasksCalculatesAndCounts() throws Exception {
        var f = fixture();
        var logs = new ArrayList<String>();
        var statsJson = new ArrayList<String>();
        var outcome = new FlowTaskExecutor().execute(graph(CHAIN), id -> f.ds(), LocalDate.of(2026, 11, 20), new RunCancelContext(),
                new ExecutionSinks((l, m) -> logs.add(l + ":" + m), statsJson::add), 0);
        assertEquals(2, f.jdbc().queryForObject("SELECT COUNT(*) FROM orders_copy", Integer.class));
        assertEquals("****", f.jdbc().queryForObject("SELECT name FROM orders_copy WHERE id=1", String.class));
        assertEquals("t1", f.jdbc().queryForObject("SELECT tag FROM orders_copy WHERE id=1", String.class));
        assertTrue(logs.stream().anyMatch(l -> l.startsWith("INFO:")));
        assertEquals(1, statsJson.size());
        assertTrue(statsJson.get(0).contains("\"nodeKey\":\"f\""));
        assertTrue(outcome.previews().isEmpty());
    }

    @Test void upsertUpdatesExistingKeys() throws Exception {
        var f = fixture();
        f.jdbc().update("INSERT INTO orders_copy VALUES (1,'old',1,'x')");
        String upsert = CHAIN.replace("\"writeMode\":\"APPEND\"", "\"writeMode\":\"UPSERT\",\"keyColumns\":[\"id\"]");
        new FlowTaskExecutor().execute(graph(upsert), id -> f.ds(), LocalDate.of(2026, 11, 20), new RunCancelContext(), ExecutionSinks.NOOP, 0);
        assertEquals(2, f.jdbc().queryForObject("SELECT COUNT(*) FROM orders_copy", Integer.class));
        assertEquals("****", f.jdbc().queryForObject("SELECT name FROM orders_copy WHERE id=1", String.class));
    }

    @Test void previewDoesNotWriteAndCapsRows() throws Exception {
        var f = fixture();
        var outcome = new FlowTaskExecutor().execute(graph(CHAIN), id -> f.ds(), LocalDate.of(2026, 11, 20), new RunCancelContext(), ExecutionSinks.NOOP, 1);
        assertEquals(0, f.jdbc().queryForObject("SELECT COUNT(*) FROM orders_copy", Integer.class));
        var preview = outcome.previews().get("t");
        assertNotNull(preview);
        assertEquals(1, preview.rows().size());
        assertEquals(List.of("id", "name", "amount", "tag"), preview.columns());
    }

    @Test void cancelMidPipelineRollsBackTarget() throws Exception {
        var f = fixture();
        var cancel = new RunCancelContext();
        // 过滤节点逐行检查：第一行处理后置取消
        String chain = CHAIN.replace("\"expression\":\"amount > 0\"", "\"expression\":\"amount > 0\"");
        var executor = new FlowTaskExecutor();
        // 包装 DataSource 代理：getConnection 时置取消标志，来源迭代器逐行检查即中断
        var proxy = (DataSource) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{DataSource.class}, (p, m, a) -> {
            if (m.getName().equals("getConnection")) { cancel.cancel(); }
            return m.invoke(f.ds(), a);
        });
        assertThrows(Exception.class, () -> executor.execute(graph(CHAIN), id -> proxy, LocalDate.of(2026, 11, 20), cancel, ExecutionSinks.NOOP, 0));
        assertEquals(0, f.jdbc().queryForObject("SELECT COUNT(*) FROM orders_copy", Integer.class));
    }

    @Test void missingUpstreamFieldNamesNode() throws Exception {
        var f = fixture();
        String bad = CHAIN.replace("\"field\":\"name\"", "\"field\":\"nope\"");
        var ex = assertThrows(IllegalArgumentException.class, () -> new FlowTaskExecutor().execute(graph(bad), id -> f.ds(), LocalDate.of(2026, 11, 20), new RunCancelContext(), ExecutionSinks.NOOP, 0));
        assertTrue(ex.getMessage().contains("m"));
    }
}
```
- [x] **步骤 2：运行测试验证失败**

运行：`cd backend && TMP=D:/tmp TEMP=D:/tmp JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot" mvn test -Dtest=FlowTaskExecutorTest -DfailIfNoTests=false`
预期：FAIL（FlowTaskExecutor 不存在）。

- [x] **步骤 3：实现 FlowTaskExecutor**

创建 `FlowTaskExecutor.java`：
```java
package com.example.governance.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import javax.sql.DataSource;

/** Streaming pull pipeline for FLOW tasks; preview mode caps sources and never writes targets. */
public final class FlowTaskExecutor {
    public record NodeStat(String nodeKey, long rowsIn, long rowsOut, long durationMs) {}
    public record Preview(List<String> columns, List<List<Object>> rows) {}
    public record Outcome(List<NodeStat> stats, Map<String, Preview> previews) {}
    private final ObjectMapper mapper = new ObjectMapper();

    public Outcome execute(FlowGraph.Graph graph, CamelTaskRunner.DataSourceProvider provider, LocalDate businessDate, RunCancelContext cancel, ExecutionSinks sinks, int previewLimit) throws Exception {
        FlowGraph.validate(graph);
        var order = FlowGraph.topoOrder(graph);
        var upstream = FlowGraph.upstream(graph);
        var outputs = new LinkedHashMap<String, Iterator<Map<String, Object>>>();
        var columns = new LinkedHashMap<String, List<String>>();
        var stats = new ArrayList<NodeStat>();
        var previews = new LinkedHashMap<String, Preview>();
        var closeables = new ArrayList<AutoCloseable>();
        try {
            for (FlowGraph.Node node : order) {
                long started = System.nanoTime();
                sinks.log().log("INFO", "节点 " + node.key() + " (" + node.component() + ") 开始");
                long[] counts = {0, 0};
                Iterator<Map<String, Object>> it;
                List<String> cols;
                switch (node.component()) {
                    case "SOURCE_TABLE", "SOURCE_SQL" -> {
                        DataSource ds = provider.get(node.config().path("sourceId").asLong());
                        Connection connection = ds.getConnection();
                        closeables.add(connection);
                        connection.setReadOnly(true);
                        String sql = node.component().equals("SOURCE_TABLE") ? "SELECT * FROM " + node.config().path("table").textValue() : node.config().path("sql").textValue();
                        PreparedStatement statement = connection.prepareStatement(sql);
                        closeables.add(statement);
                        cancel.register(statement);
                        statement.setFetchSize(500);
                        statement.setQueryTimeout(30);
                        if (node.component().equals("SOURCE_SQL")) bindParameters(statement, node.config().path("parameters"), businessDate);
                        ResultSet rows = statement.executeQuery();
                        closeables.add(rows);
                        cols = new ArrayList<>();
                        for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) cols.add(rows.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT));
                        it = sourceIterator(rows, cols, cancel, previewLimit, counts);
                    }
                    case "FIELD_MAP", "FIELD_FILTER", "FIELD_MASK", "FIELD_CALC" -> {
                        String up = upstream.get(node.key());
                        Iterator<Map<String, Object>> source = outputs.get(up);
                        cols = transformColumns(node, columns.get(up));
                        it = transformIterator(node, source, cols, cancel, counts);
                    }
                    case "SINK_TABLE" -> {
                        String up = upstream.get(node.key());
                        cols = columns.get(up);
                        Iterator<Map<String, Object>> source = outputs.get(up);
                        if (previewLimit > 0) {
                            var collected = new ArrayList<List<Object>>();
                            while (source.hasNext() && collected.size() < previewLimit) {
                                var row = source.next();
                                counts[0]++;
                                collected.add(cols.stream().map(row::get).toList());
                            }
                            counts[1] = collected.size();
                            previews.put(node.key(), new Preview(List.copyOf(cols), collected));
                            it = java.util.Collections.emptyIterator();
                        } else {
                            counts[1] = writeSink(node, provider, cols, source, cancel, counts);
                            it = java.util.Collections.emptyIterator();
                        }
                    }
                    default -> throw new IllegalArgumentException("Unknown component: " + node.component());
                }
                outputs.put(node.key(), it);
                columns.put(node.key(), cols);
                long duration = (System.nanoTime() - started) / 1_000_000;
                stats.add(new NodeStat(node.key(), counts[0], counts[1], duration));
                sinks.log().log("INFO", "节点 " + node.key() + " 完成: 入 " + counts[0] + " 出 " + counts[1]);
            }
            if (previewLimit == 0) sinks.stats().accept(mapper.writeValueAsString(stats));
            return new Outcome(List.copyOf(stats), previews);
        } finally {
            for (int i = closeables.size() - 1; i >= 0; i--) try { closeables.get(i).close(); } catch (Exception ignored) { }
        }
    }

    private Iterator<Map<String, Object>> sourceIterator(ResultSet rows, List<String> cols, RunCancelContext cancel, int previewLimit, long[] counts) {
        return new Iterator<>() {
            private Map<String, Object> next;
            private boolean done;
            private void advance() {
                if (next != null || done) return;
                try {
                    if (cancel.isCancelled()) throw new InterruptedException("手动停止");
                    if ((previewLimit > 0 && counts[0] >= previewLimit) || !rows.next()) { done = true; return; }
                    var row = new LinkedHashMap<String, Object>();
                    for (int i = 0; i < cols.size(); i++) row.put(cols.get(i), convert(rows.getObject(i + 1)));
                    counts[0]++;
                    next = row;
                } catch (Exception ex) { throw wrap(ex); }
            }
            @Override public boolean hasNext() { advance(); return next != null; }
            @Override public Map<String, Object> next() { advance(); if (next == null) throw new NoSuchElementException(); var r = next; next = null; counts[1]++; return r; }
        };
    }

    private List<String> transformColumns(FlowGraph.Node node, List<String> input) {
        return switch (node.component()) {
            case "FIELD_MAP" -> {
                var cols = new ArrayList<String>();
                for (JsonNode m : node.config().path("mappings")) cols.add(m.path("to").textValue().toLowerCase(Locale.ROOT));
                yield cols;
            }
            case "FIELD_CALC" -> {
                var cols = new ArrayList<>(input);
                String field = node.config().path("field").textValue().toLowerCase(Locale.ROOT);
                if (cols.contains(field)) throw new IllegalArgumentException("节点 " + node.key() + ": 计算字段已存在 " + field);
                cols.add(field);
                yield cols;
            }
            default -> input;
        };
    }

    private Iterator<Map<String, Object>> transformIterator(FlowGraph.Node node, Iterator<Map<String, Object>> source, List<String> cols, RunCancelContext cancel, long[] counts) {
        FlowExpression.Expr filter = node.component().equals("FIELD_FILTER") ? FlowExpression.parse(node.config().path("expression").textValue()) : null;
        FlowExpression.Expr calc = node.component().equals("FIELD_CALC") ? FlowExpression.parse(node.config().path("expression").textValue()) : null;
        return new Iterator<>() {
            private Map<String, Object> next;
            private void advance() {
                if (next != null) return;
                try {
                    while (source.hasNext()) {
                        if (cancel.isCancelled()) throw new InterruptedException("手动停止");
                        var row = source.next();
                        counts[0]++;
                        switch (node.component()) {
                            case "FIELD_MAP" -> {
                                var mapped = new LinkedHashMap<String, Object>();
                                for (JsonNode m : node.config().path("mappings")) {
                                    String from = m.path("from").textValue().toLowerCase(Locale.ROOT);
                                    if (!row.containsKey(from)) throw new IllegalArgumentException("节点 " + node.key() + ": 上游缺少字段 " + from);
                                    mapped.put(m.path("to").textValue().toLowerCase(Locale.ROOT), row.get(from));
                                }
                                next = mapped; counts[1]++; return;
                            }
                            case "FIELD_FILTER" -> {
                                if (Boolean.TRUE.equals(filter.eval(row))) { next = row; counts[1]++; return; }
                            }
                            case "FIELD_MASK" -> {
                                String field = node.config().path("field").textValue().toLowerCase(Locale.ROOT);
                                if (!row.containsKey(field)) throw new IllegalArgumentException("节点 " + node.key() + ": 上游缺少字段 " + field);
                                var masked = new LinkedHashMap<>(row);
                                masked.put(field, "****");
                                next = masked; counts[1]++; return;
                            }
                            case "FIELD_CALC" -> {
                                var extended = new LinkedHashMap<>(row);
                                extended.put(node.config().path("field").textValue().toLowerCase(Locale.ROOT), calc.eval(row));
                                next = extended; counts[1]++; return;
                            }
                            default -> throw new IllegalArgumentException("Unknown component");
                        }
                    }
                } catch (Exception ex) { throw wrap(ex); }
            }
            @Override public boolean hasNext() { advance(); return next != null; }
            @Override public Map<String, Object> next() { advance(); if (next == null) throw new NoSuchElementException(); var r = next; next = null; return r; }
        };
    }

    private long writeSink(FlowGraph.Node node, CamelTaskRunner.DataSourceProvider provider, List<String> cols, Iterator<Map<String, Object>> source, RunCancelContext cancel, long[] counts) throws Exception {
        DataSource ds = provider.get(node.config().path("sourceId").asLong());
        String table = node.config().path("table").textValue();
        String mode = node.config().path("writeMode").asText("APPEND");
        int batchSize = node.config().path("batchSize").asInt(500);
        List<String> keys = new ArrayList<>();
        for (JsonNode k : node.config().path("keyColumns")) keys.add(k.textValue().toLowerCase(Locale.ROOT));
        try (Connection target = ds.getConnection()) {
            target.setAutoCommit(false);
            try {
                var targetColumns = new java.util.HashSet<String>();
                var meta = target.getMetaData();
                String[] parts = table.split("\\.");
                String schema = parts.length == 2 ? parts[0] : null;
                String name = parts[parts.length - 1];
                try (ResultSet rc = meta.getColumns(null, schema, name, null)) {
                    while (rc.next()) targetColumns.add(rc.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
                if (targetColumns.isEmpty()) try (ResultSet rc = meta.getColumns(null, schema, name.toUpperCase(Locale.ROOT), null)) {
                    while (rc.next()) targetColumns.add(rc.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
                for (String col : cols) if (!targetColumns.contains(col)) throw new IllegalArgumentException("节点 " + node.key() + ": 目标表缺少字段 " + col);
                if ("UPSERT".equals(mode)) DatabaseSyncExecutor.verifyUniqueKey(target, table, keys);
                String placeholders = String.join(",", java.util.Collections.nCopies(cols.size(), "?"));
                try (PreparedStatement insert = target.prepareStatement("INSERT INTO " + table + " (" + String.join(",", cols) + ") VALUES (" + placeholders + ")")) {
                    cancel.register(insert);
                    insert.setQueryTimeout(30);
                    PreparedStatement update = null;
                    if ("UPSERT".equals(mode)) {
                        var nonKeys = cols.stream().filter(c -> !keys.contains(c)).toList();
                        String assignments = nonKeys.isEmpty() ? keys.get(0) + "=" + keys.get(0) : nonKeys.stream().map(c -> c + "=?").collect(java.util.stream.Collectors.joining(","));
                        String where = keys.stream().map(k -> k + "=?").collect(java.util.stream.Collectors.joining(" AND "));
                        update = target.prepareStatement("UPDATE " + table + " SET " + assignments + " WHERE " + where);
                        update.setQueryTimeout(30);
                    }
                    int pending = 0;
                    while (source.hasNext()) {
                        if (cancel.isCancelled()) throw new InterruptedException("手动停止");
                        var row = source.next();
                        counts[0]++;
                        if ("UPSERT".equals(mode)) {
                            int pos = 1;
                            for (String c : cols) if (!keys.contains(c)) update.setObject(pos++, row.get(c));
                            for (String k : keys) {
                                Object v = row.get(k);
                                if (v == null) throw new IllegalArgumentException("节点 " + node.key() + ": 主键值为空 " + k);
                                update.setObject(pos++, v);
                            }
                            int changed = update.executeUpdate();
                            if (changed > 1) throw new IllegalStateException("节点 " + node.key() + ": 主键匹配多行");
                            if (changed == 0) { bind(insert, cols, row); insert.executeUpdate(); }
                        } else {
                            bind(insert, cols, row);
                            insert.addBatch();
                            pending++;
                            if (pending == batchSize) { insert.executeBatch(); pending = 0; }
                        }
                        counts[1]++;
                    }
                    if (pending > 0) insert.executeBatch();
                    if (cancel.isCancelled()) throw new InterruptedException("手动停止");
                    target.commit();
                }
            } catch (Exception failure) {
                try { target.rollback(); } catch (Exception rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        }
        return counts[1];
    }
    private static void bind(PreparedStatement statement, List<String> cols, Map<String, Object> row) throws Exception {
        for (int i = 0; i < cols.size(); i++) statement.setObject(i + 1, row.get(cols.get(i)));
    }
    private static void bindParameters(PreparedStatement statement, JsonNode parameters, LocalDate businessDate) throws Exception {
        if (!parameters.isArray()) return;
        int pos = 1;
        for (JsonNode p : parameters) {
            String kind = p.path("type").textValue();
            if ("BUSINESS_DATE".equals(kind)) statement.setObject(pos++, java.sql.Date.valueOf(businessDate));
            else {
                JsonNode v = p.get("value");
                if (v == null || v.isNull()) statement.setObject(pos++, null);
                else if (v.isTextual()) statement.setObject(pos++, v.textValue());
                else if (v.isBoolean()) statement.setObject(pos++, v.booleanValue());
                else statement.setObject(pos++, v.decimalValue());
            }
        }
    }
    private static Object convert(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal || value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Number n) return new BigDecimal(n.toString());
        if (value instanceof java.sql.Date || value instanceof java.sql.Timestamp || value instanceof java.sql.Time) return value.toString();
        throw new IllegalArgumentException("不支持的字段类型: " + value.getClass().getSimpleName());
    }
    private static RuntimeException wrap(Exception ex) {
        if (ex instanceof RuntimeException r) return r;
        if (ex instanceof InterruptedException i) return new IllegalStateException(i.getMessage(), i);
        return new IllegalStateException(ex.getMessage(), ex);
    }
}
```
注意：sourceIterator 的 counts[1] 在 next() 自增（ emitted），sink 消费时 counts[0] 自增；统计语义 source rowsIn=rowsOut=读取数（counts[0] 读取、counts[1] 发出，二者相等）。wrap 把 InterruptedException 包成 IllegalStateException 以穿越 Iterator 接口；TaskRunService 的 catch(Exception) 按 cancel.isCancelled() 分类 INTERRUPTED/手动停止（一期已有语义）。

- [x] **步骤 4：CamelTaskRunner FLOW 分支与重载**

字段增加 `private final FlowTaskExecutor flow = new FlowTaskExecutor();`
方法重载链（四参 override 已在任务 1 存在且委托三参；本任务把四参改为完整实现、三参委托四参）：
```java
    @Override public void execute(TaskVersionStore.Version task, LocalDate businessDate) throws Exception { execute(task, businessDate, new RunCancelContext(), ExecutionSinks.NOOP); }
    public void execute(TaskVersionStore.Version task, LocalDate businessDate, RunCancelContext cancel) throws Exception { execute(task, businessDate, cancel, ExecutionSinks.NOOP); }
    @Override public void execute(TaskVersionStore.Version task, LocalDate businessDate, RunCancelContext cancel, ExecutionSinks sinks) throws Exception {
```
（原三参方法体移入四参；FLOW 分支使用 sinks。）
方法体 SYNC 分支后增加：
```java
        } else if ("FLOW".equals(task.type())) {
            flow.execute(FlowGraph.parse(config), provider, businessDate, cancel, sinks, 0);
        } else throw new IllegalArgumentException("Unsupported task type");
```

- [x] **步骤 5：运行测试验证通过**

运行：同步骤 2 命令 + `mvn test -Dtest=CamelTaskRunnerTest,DatabaseSyncExecutorTest,SqlTaskExecutorTest,WorkflowWorkerTest,TaskStopApiTest -DfailIfNoTests=false`（mock 桩按四参同步，不削弱断言）。
预期：PASS。

- [x] **步骤 6：Commit**

```bash
git add backend/src/main/java/com/example/governance/workflow/FlowTaskExecutor.java backend/src/main/java/com/example/governance/workflow/CamelTaskRunner.java backend/src/test/java/com/example/governance/workflow/FlowTaskExecutorTest.java
git commit -m "feat(processing): FLOW 流式管道执行器与预览模式

拉取式迭代器管道：来源限量/逐行取消/字段映射过滤脱敏计算/
目标 APPEND 与 UPSERT（commit 前取消检查回滚）；统计与日志经 sinks；
预览不写目标表；CamelTaskRunner 增加 FLOW 分支与四参重载。"
```

---

### 任务 5：试运行端点

**文件：**
- 修改：`backend/.../workflow/TaskController.java`、`backend/.../security/PermissionCatalog.java`
- 测试：`backend/src/test/java/com/example/governance/workflow/FlowTestRunApiTest.java`

- [x] **步骤 1：编写失败的测试**

```java
package com.example.governance.workflow;

import com.example.governance.GovernanceApplication;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=GovernanceApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties="spring.datasource.url=jdbc:h2:mem:flow_test_run_api;DB_CLOSE_DELAY=-1")
class FlowTestRunApiTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired PasswordEncoder encoder;
    @MockitoBean com.example.governance.assets.ExecutionDataSourceProvider provider;

    private MockHttpSession login() throws Exception {
        jdbc.update("INSERT INTO departments(id,name) VALUES (811,'dept')");
        jdbc.update("INSERT INTO roles(id,name) VALUES (811,'role')");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (811,'flow-user',?,811,TRUE,'v')", encoder.encode("password"));
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (811,811)");
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (811,'workflow.task.execute'),(811,'workflow.task.read')");
        return (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username", "flow-user").param("password", "password")).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }

    @Test void testRunPreviewsWithoutWritingAndRequiresPermission() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:flow_src_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var src = new JdbcTemplate(ds);
        src.update("CREATE TABLE orders(id INT PRIMARY KEY, amount DECIMAL(10,2))");
        src.update("INSERT INTO orders VALUES (1,10),(2,-1),(3,20)");
        src.update("CREATE TABLE orders_copy(id INT PRIMARY KEY, amount DECIMAL(10,2))");
        Mockito.when(provider.get(Mockito.anyLong())).thenReturn(ds);
        var session = login();
        String config = "{\"nodes\":[{\"key\":\"s\",\"component\":\"SOURCE_TABLE\",\"config\":{\"sourceId\":1,\"table\":\"orders\"}},{\"key\":\"f\",\"component\":\"FIELD_FILTER\",\"config\":{\"expression\":\"amount > 0\"}},{\"key\":\"t\",\"component\":\"SINK_TABLE\",\"config\":{\"sourceId\":1,\"table\":\"orders_copy\",\"writeMode\":\"APPEND\"}}],\"edges\":[{\"from\":\"s\",\"to\":\"f\"},{\"from\":\"f\",\"to\":\"t\"}]}";
        mvc.perform(post("/api/tasks/flow/test-run").session(session).with(csrf()).contentType("application/json").content("{\"configuration\":" + config + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previews.t.rows.length()").value(2))
                .andExpect(jsonPath("$.stats.length()").value(3));
        assertEquals(0, src.queryForObject("SELECT COUNT(*) FROM orders_copy", Integer.class));
        // 无 execute 权限 → 403
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (812,'flow-none',?,811,TRUE,'v2')", encoder.encode("password"));
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (812,811)");
        jdbc.update("DELETE FROM role_permissions WHERE role_id=811 AND permission='workflow.task.execute'");
        var none = (MockHttpSession) mvc.perform(post("/api/login").with(csrf()).param("username", "flow-none").param("password", "password")).andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
        mvc.perform(post("/api/tasks/flow/test-run").session(none).with(csrf()).contentType("application/json").content("{\"configuration\":" + config + "}"))
                .andExpect(status().isForbidden());
    }

    @Test void invalidFlowConfigurationRejected() throws Exception {
        var session = login();
        mvc.perform(post("/api/tasks/flow/test-run").session(session).with(csrf()).contentType("application/json").content("{\"configuration\":{\"nodes\":[],\"edges\":[]}}"))
                .andExpect(status().isBadRequest());
    }
}
```

- [x] **步骤 2：运行测试验证失败**

运行：`cd backend && TMP=D:/tmp TEMP=D:/tmp JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot" mvn test -Dtest=FlowTestRunApiTest -DfailIfNoTests=false`
预期：FAIL（404/403 路由未注册）。

- [x] **步骤 3：实现端点**

`TaskController` 构造器追加注入 `com.example.governance.assets.ExecutionDataSourceProvider provider`（字段+参数）；新增字段 `private final FlowTaskExecutor flowExecutor = new FlowTaskExecutor(); private final com.fasterxml.jackson.databind.ObjectMapper flowMapper = new com.fasterxml.jackson.databind.ObjectMapper();`
新增端点：
```java
    record FlowTestRequest(JsonNode configuration) {}
    @PostMapping("/api/tasks/flow/test-run")
    ResponseEntity<Map<String, Object>> flowTest(@RequestBody FlowTestRequest request) {
        String configuration;
        try { configuration = flowMapper.writeValueAsString(request.configuration()); validator.validate("FLOW", configuration); }
        catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "流程配置无效"); }
        var cancel = new RunCancelContext();
        var logs = new java.util.concurrent.CopyOnWriteArrayList<Map<String, Object>>();
        RunLogSink collect = (level, message) -> logs.add(Map.of("lineNo", logs.size() + 1, "level", level, "message", message));
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> flowExecutor.execute(FlowGraph.parse(request.configuration()), provider, java.time.LocalDate.now(), cancel, new ExecutionSinks(collect, json -> {}), 100));
            var outcome = future.get(30, java.util.concurrent.TimeUnit.SECONDS);
            return ResponseEntity.ok(Map.of("logs", logs, "previews", outcome.previews(), "stats", outcome.stats()));
        } catch (java.util.concurrent.TimeoutException ex) {
            cancel.cancel();
            throw new ResponseStatusException(HttpStatus.REQUEST_TIMEOUT, "试运行超过 30 秒");
        } catch (java.util.concurrent.ExecutionException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getCause() == null ? "试运行失败" : ex.getCause().getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "试运行被中断");
        }
    }
```
`PermissionCatalog` routes 增加：
```java
        route(routes, "POST", "/api/tasks/flow/test-run", "workflow.task.execute");
```

- [x] **步骤 4：运行测试验证通过**

运行：同步骤 2 命令 + 全量 `mvn test`（应 242+新增）。
预期：PASS。

- [x] **步骤 5：Commit**

```bash
git add backend/src/main/java/com/example/governance/workflow/TaskController.java backend/src/main/java/com/example/governance/security/PermissionCatalog.java backend/src/test/java/com/example/governance/workflow/FlowTestRunApiTest.java
git commit -m "feat(processing): 流程试运行端点（安全预览）

POST /api/tasks/flow/test-run：每来源限量 100 行、不写目标表、
30 秒超时取消；返回日志/各目标预览/统计；权限 workflow.task.execute。"
```

---

### 任务 6：前端组件目录与图纯逻辑

**文件：**
- 创建：`frontend/src/flow-components.js`、`frontend/src/flow-graph.js`
- 测试：`frontend/tests/flow-components.test.js`、`frontend/tests/flow-graph.test.js`

- [x] **步骤 1：编写失败的测试**

`frontend/tests/flow-components.test.js`：
```js
import { test } from 'node:test'
import { strict as assert } from 'node:assert'
import { COMPONENTS, componentDef, defaultConfig } from '../src/flow-components.js'

test('catalog covers seven components in three groups', () => {
  assert.equal(COMPONENTS.length, 7)
  assert.deepEqual([...new Set(COMPONENTS.map(c => c.group))].sort(), ['interceptor', 'sink', 'source'])
  for (const c of COMPONENTS) assert.ok(c.label && c.component)
})

test('default configs match backend required fields', () => {
  assert.equal(defaultConfig('SOURCE_TABLE').table, '')
  assert.equal(defaultConfig('SOURCE_SQL').sql, '')
  assert.deepEqual(defaultConfig('FIELD_MAP').mappings, [])
  assert.equal(defaultConfig('FIELD_FILTER').expression, '')
  assert.equal(defaultConfig('FIELD_MASK').field, '')
  assert.equal(defaultConfig('FIELD_CALC').expression, '')
  assert.equal(defaultConfig('SINK_TABLE').writeMode, 'APPEND')
  assert.equal(defaultConfig('SINK_TABLE').batchSize, 500)
})

test('componentDef throws on unknown', () => {
  assert.throws(() => componentDef('KAFKA'))
  assert.equal(componentDef('FIELD_MASK').group, 'interceptor')
})
```

`frontend/tests/flow-graph.test.js`：
```js
import { test } from 'node:test'
import { strict as assert } from 'node:assert'
import { validateFlow, toVueNodes, toVueEdges, newNode } from '../src/flow-graph.js'

const chain = {
  nodes: [
    { key: 's', component: 'SOURCE_TABLE', config: { sourceId: 1, table: 'orders' }, position: { x: 0, y: 0 } },
    { key: 'f', component: 'FIELD_FILTER', config: { expression: 'amount > 0' }, position: { x: 200, y: 0 } },
    { key: 't', component: 'SINK_TABLE', config: { sourceId: 2, table: 'copy', writeMode: 'APPEND' }, position: { x: 400, y: 0 } }
  ],
  edges: [{ from: 's', to: 'f' }, { from: 'f', to: 't' }]
}

test('valid chain passes client validation', () => {
  assert.deepEqual(validateFlow(chain), [])
})

test('structural errors are reported with node keys', () => {
  const cycle = { nodes: chain.nodes, edges: [...chain.edges, { from: 'f', to: 's' }] }
  assert.ok(validateFlow(cycle).some(m => m.includes('环')))
  const noSink = { nodes: chain.nodes.slice(0, 2), edges: [{ from: 's', to: 'f' }] }
  assert.ok(validateFlow(noSink).some(m => m.includes('目标')))
  const dangling = { nodes: [...chain.nodes, { key: 'x', component: 'FIELD_CALC', config: { field: 'c', expression: '1' }, position: { x: 0, y: 200 } }], edges: chain.edges }
  assert.ok(validateFlow(dangling).some(m => m.includes('x')))
  const multiIn = { nodes: [...chain.nodes, { key: 's2', component: 'SOURCE_TABLE', config: { sourceId: 1, table: 'o' }, position: { x: 0, y: 200 } }], edges: [...chain.edges, { from: 's2', to: 'f' }] }
  assert.ok(validateFlow(multiIn).some(m => m.includes('f')))
})

test('config errors are reported', () => {
  const bad = { nodes: chain.nodes.map(n => n.key === 's' ? { ...n, config: { sourceId: 1, table: '' } } : n), edges: chain.edges }
  assert.ok(validateFlow(bad).some(m => m.includes('s')))
})

test('vue adapter maps keys positions and edges', () => {
  const nodes = toVueNodes(chain)
  assert.equal(nodes.length, 3)
  assert.equal(nodes[0].id, 's')
  assert.deepEqual(nodes[0].position, { x: 0, y: 0 })
  assert.equal(nodes[0].type, 'flowNode')
  const edges = toVueEdges(chain)
  assert.equal(edges.length, 2)
  assert.equal(edges[0].source, 's')
  assert.equal(edges[0].target, 'f')
})

test('newNode generates unique keys and default config', () => {
  const a = newNode('FIELD_FILTER', chain.nodes, { x: 10, y: 20 })
  const b = newNode('FIELD_FILTER', chain.nodes, { x: 10, y: 20 })
  assert.notEqual(a.key, b.key)
  assert.equal(a.config.expression, '')
  assert.deepEqual(a.position, { x: 10, y: 20 })
})
```

- [x] **步骤 2：运行测试验证失败**

运行：`cd frontend && node --test "tests/flow-components.test.js" "tests/flow-graph.test.js"`
预期：FAIL（模块不存在）。

- [x] **步骤 3：实现两个模块**

`frontend/src/flow-components.js`：
```js
/** FLOW 组件目录：分组、文案、默认配置与配置抽屉 schema（与后端 FlowGraph 校验对应）。 */
export const COMPONENTS = [
  { component: 'SOURCE_TABLE', group: 'source', label: '数据库表', fields: [{ key: 'sourceId', label: '数据源', type: 'source' }, { key: 'table', label: '表名（可 schema.table）', type: 'text' }] },
  { component: 'SOURCE_SQL', group: 'source', label: 'SQL 查询', fields: [{ key: 'sourceId', label: '数据源', type: 'source' }, { key: 'sql', label: 'SQL 语句', type: 'textarea' }] },
  { component: 'FIELD_MAP', group: 'interceptor', label: '字段映射', fields: [{ key: 'mappings', label: '映射（上游字段 → 输出字段）', type: 'mappings' }] },
  { component: 'FIELD_FILTER', group: 'interceptor', label: '数据过滤', fields: [{ key: 'expression', label: '保留条件表达式', type: 'expr' }] },
  { component: 'FIELD_MASK', group: 'interceptor', label: '字段脱敏', fields: [{ key: 'field', label: '脱敏字段（整体替换为 ****）', type: 'text' }] },
  { component: 'FIELD_CALC', group: 'interceptor', label: '字段计算', fields: [{ key: 'field', label: '新字段名', type: 'text' }, { key: 'expression', label: '计算表达式', type: 'expr' }] },
  { component: 'SINK_TABLE', group: 'sink', label: '数据库表', fields: [{ key: 'sourceId', label: '目标数据源', type: 'source' }, { key: 'table', label: '目标表', type: 'text' }, { key: 'writeMode', label: '写入方式', type: 'select', options: [{ value: 'APPEND', label: '追加' }, { value: 'UPSERT', label: '按唯一键更新插入' }] }, { key: 'keyColumns', label: '唯一键字段（逗号分隔，UPSERT 必填）', type: 'list' }, { key: 'batchSize', label: '批次大小', type: 'number' }] }
]
export const GROUPS = [{ key: 'source', label: '来源组件' }, { key: 'interceptor', label: '预处理组件' }, { key: 'sink', label: '目标组件' }]
export function componentDef(component) {
  const def = COMPONENTS.find(c => c.component === component)
  if (!def) throw new Error('未知组件: ' + component)
  return def
}
export function defaultConfig(component) {
  switch (component) {
    case 'SOURCE_TABLE': return { sourceId: null, table: '' }
    case 'SOURCE_SQL': return { sourceId: null, sql: '' }
    case 'FIELD_MAP': return { mappings: [] }
    case 'FIELD_FILTER': return { expression: '' }
    case 'FIELD_MASK': return { field: '' }
    case 'FIELD_CALC': return { field: '', expression: '' }
    case 'SINK_TABLE': return { sourceId: null, table: '', writeMode: 'APPEND', keyColumns: [], batchSize: 500 }
    default: throw new Error('未知组件: ' + component)
  }
}
export function configSummary(component, config) {
  switch (component) {
    case 'SOURCE_TABLE': case 'SINK_TABLE': return config.table || '未配置表'
    case 'SOURCE_SQL': return (config.sql || '未配置 SQL').slice(0, 24)
    case 'FIELD_MAP': return `${(config.mappings || []).length} 条映射`
    case 'FIELD_FILTER': return (config.expression || '未配置条件').slice(0, 24)
    case 'FIELD_MASK': return config.field || '未配置字段'
    case 'FIELD_CALC': return config.field || '未配置字段'
    default: return ''
  }
}
```

`frontend/src/flow-graph.js`：
```js
/** FLOW 图客户端结构校验（后端保存期仍权威校验）与 VueFlow 适配。 */
import { defaultConfig } from './flow-components.js'
const SOURCES = ['SOURCE_TABLE', 'SOURCE_SQL']
const SINKS = ['SINK_TABLE']
const IDENT = /^[A-Za-z_][A-Za-z0-9_$]*$/
export function validateFlow(graph) {
  const errors = []
  const nodes = graph.nodes || []
  const edges = graph.edges || []
  if (!nodes.length) return ['流程至少需要一个节点']
  const keys = new Set()
  for (const n of nodes) {
    if (!n.key || keys.has(n.key)) errors.push(`节点 key 重复或缺失: ${n.key}`)
    keys.add(n.key)
  }
  const indeg = new Map(), outdeg = new Map()
  for (const e of edges) {
    if (!keys.has(e.from) || !keys.has(e.to) || e.from === e.to) { errors.push(`非法连线: ${e.from} → ${e.to}`); continue }
    outdeg.set(e.from, (outdeg.get(e.from) || 0) + 1)
    indeg.set(e.to, (indeg.get(e.to) || 0) + 1)
  }
  let hasSource = false, hasSink = false
  for (const n of nodes) {
    const i = indeg.get(n.key) || 0, o = outdeg.get(n.key) || 0
    if (SOURCES.includes(n.component)) { hasSource = true; if (i) errors.push(`来源节点不能有入边: ${n.key}`) }
    else if (SINKS.includes(n.component)) { hasSink = true; if (o) errors.push(`目标节点不能有出边: ${n.key}`); if (i !== 1) errors.push(`目标节点需恰一个入边: ${n.key}`) }
    else if (i !== 1) errors.push(`预处理节点需恰一个入边: ${n.key}`)
    if (!SINKS.includes(n.component) && o > 1) errors.push(`节点只能有一条出边: ${n.key}`)
    const c = n.config || {}
    if (SOURCES.includes(n.component) || SINKS.includes(n.component)) {
      if (!c.sourceId) errors.push(`节点 ${n.key} 未选择数据源`)
      if (!c.table || !/^[A-Za-z_][A-Za-z0-9_$]*(\.[A-Za-z_][A-Za-z0-9_$]*)?$/.test(c.table)) errors.push(`节点 ${n.key} 表名无效`)
    }
    if (n.component === 'SOURCE_SQL' && !c.sql) errors.push(`节点 ${n.key} 未配置 SQL`)
    if (n.component === 'FIELD_MAP' && !(c.mappings || []).length) errors.push(`节点 ${n.key} 未配置映射`)
    if ((n.component === 'FIELD_FILTER' || n.component === 'FIELD_CALC') && !c.expression) errors.push(`节点 ${n.key} 未配置表达式`)
    if (n.component === 'FIELD_MASK' && !c.field) errors.push(`节点 ${n.key} 未配置脱敏字段`)
    if (n.component === 'FIELD_CALC' && !IDENT.test(c.field || '')) errors.push(`节点 ${n.key} 新字段名无效`)
    if (n.component === 'SINK_TABLE' && c.writeMode === 'UPSERT' && !(c.keyColumns || []).length) errors.push(`节点 ${n.key} UPSERT 需唯一键`)
  }
  if (!hasSource) errors.push('流程需至少一个来源组件')
  if (!hasSink) errors.push('流程需至少一个目标组件')
  // 环检测（Kahn）
  const indeg2 = new Map(nodes.map(n => [n.key, 0]))
  for (const e of edges) if (indeg2.has(e.to)) indeg2.set(e.to, indeg2.get(e.to) + 1)
  const queue = nodes.filter(n => indeg2.get(n.key) === 0).map(n => n.key)
  let visited = 0
  while (queue.length) {
    const k = queue.shift(); visited++
    for (const e of edges) if (e.from === k) { indeg2.set(e.to, indeg2.get(e.to) - 1); if (indeg2.get(e.to) === 0) queue.push(e.to) }
  }
  if (visited !== nodes.length) errors.push('流程存在环')
  // 悬空：反向可达 sink
  const reach = new Set(nodes.filter(n => SINKS.includes(n.component)).map(n => n.key))
  const stack = [...reach]
  while (stack.length) { const k = stack.pop(); for (const e of edges) if (e.to === k && !reach.has(e.from)) { reach.add(e.from); stack.push(e.from) } }
  for (const n of nodes) if (!reach.has(n.key)) errors.push(`节点未连接到目标: ${n.key}`)
  return errors
}
export function toVueNodes(graph) {
  return (graph.nodes || []).map(n => ({ id: n.key, type: 'flowNode', position: n.position || { x: 0, y: 0 }, data: { key: n.key, component: n.component, config: n.config } }))
}
export function toVueEdges(graph) {
  return (graph.edges || []).map(e => ({ id: `${e.from}->${e.to}`, source: e.from, target: e.to, type: 'smoothstep', markerEnd: { type: 'arrowclosed', color: '#728a88' } }))
}
export function newNode(component, existing, position) {
  const prefix = component.toLowerCase().replace(/_/g, '')
  let i = 1
  const keys = new Set((existing || []).map(n => n.key))
  while (keys.has(`${prefix}${i}`)) i++
  return { key: `${prefix}${i}`, component, config: defaultConfig(component), position: position || { x: 40 + (existing || []).length * 40, y: 40 + (existing || []).length * 30 } }
}
```

- [x] **步骤 4：运行测试验证通过**

运行：同步骤 2 命令 + 全量 `node --test "tests/*.test.js"`（应 59+新增）。
预期：PASS。

- [x] **步骤 5：Commit**

```bash
git add frontend/src/flow-components.js frontend/src/flow-graph.js frontend/tests/flow-components.test.js frontend/tests/flow-graph.test.js
git commit -m "feat(processing): 画布组件目录与图客户端校验纯逻辑

七组件三分组目录+配置 schema+默认值；客户端结构/配置校验、
VueFlow 适配与节点 key 生成，带 node:test 覆盖。"
```

---

### 任务 7：FlowNode 与 FlowEditor 组件

**文件：**
- 创建：`frontend/src/FlowNode.vue`、`frontend/src/FlowEditor.vue`

- [x] **步骤 1：实现 FlowNode.vue**

```vue
<script setup>
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { componentDef, configSummary } from './flow-components.js'
const props = defineProps({ data: Object, selected: Boolean })
const def = computed(() => componentDef(props.data.component))
const summary = computed(() => configSummary(props.data.component, props.data.config || {}))
const isSource = computed(() => def.value.group === 'source')
const isSink = computed(() => def.value.group === 'sink')
</script>
<template>
  <div class="flow-node" :class="{ selected, source: isSource, sink: isSink }">
    <Handle v-if="!isSource" type="target" :position="Position.Left" />
    <strong>{{ def.label }}</strong>
    <small>{{ data.key }} · {{ summary }}</small>
    <Handle v-if="!isSink" type="source" :position="Position.Right" />
  </div>
</template>
<style scoped>
.flow-node{border:1px solid #79aaa1;border-left:5px solid #208b77;border-radius:7px;background:#fff;padding:8px 12px;min-width:150px;box-shadow:0 3px 12px #173e3615;display:flex;flex-direction:column;gap:2px}
.flow-node.selected{outline:2px solid #208b77}
.flow-node.source{border-left-color:#3572d3}
.flow-node.sink{border-left-color:#b26a00}
.flow-node small{color:#728582;font-size:11px;max-width:180px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
</style>
```

- [x] **步骤 2：实现 FlowEditor.vue**

```vue
<script setup>
import { computed, inject, ref, watch } from 'vue'
import { VueFlow, useVueFlow } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import { ElMessageBox } from 'element-plus'
import { authorizedRequest } from './auth.js'
import { COMPONENTS, GROUPS, componentDef, defaultConfig } from './flow-components.js'
import { validateFlow, toVueNodes, toVueEdges, newNode } from './flow-graph.js'
import FlowNode from './FlowNode.vue'
const props = defineProps({ draft: Object, sources: Array })
const emit = defineEmits(['saved', 'cancel'])
const can = inject('canPermission', () => false)
const graph = ref({ nodes: [], edges: [] })
const error = ref(''), busy = ref(false)
const drawerOpen = ref(false), editingKey = ref(null), configForm = ref({})
const testOpen = ref(false), testBusy = ref(false), testResult = ref(null), testTab = ref('logs'), previewTab = ref('')
const nodeTypes = { flowNode: FlowNode }
const vueNodes = computed(() => toVueNodes(graph.value))
const vueEdges = computed(() => toVueEdges(graph.value))
const { onConnect, onNodeClick, onNodesChange, onEdgesChange, project } = useVueFlow()
watch(() => props.draft, draft => {
  error.value = ''
  if (draft?.configuration) { try { graph.value = JSON.parse(draft.configuration) } catch { graph.value = { nodes: [], edges: [] }; error.value = '任务配置解析失败' } }
  else graph.value = { nodes: [], edges: [] }
}, { immediate: true })
onNodesChange(changes => {
  for (const c of changes) {
    if (c.type === 'position' && c.position) { const n = graph.value.nodes.find(n => n.key === c.id); if (n) n.position = { x: c.position.x, y: c.position.y } }
    if (c.type === 'remove') graph.value.nodes = graph.value.nodes.filter(n => n.key !== c.id)
  }
})
onEdgesChange(changes => {
  for (const c of changes) if (c.type === 'remove') graph.value.edges = graph.value.edges.filter(e => `${e.from}->${e.to}` !== c.id)
})
onConnect(params => {
  if (!params.source || !params.target || params.source === params.target) return
  if (graph.value.edges.some(e => e.from === params.source && e.to === params.target)) return
  const target = graph.value.nodes.find(n => n.key === params.target)
  const source = graph.value.nodes.find(n => n.key === params.source)
  if (target && ['SOURCE_TABLE', 'SOURCE_SQL'].includes(target.component)) { error.value = '来源节点不能有入边'; return }
  if (source && source.component === 'SINK_TABLE') { error.value = '目标节点不能有出边'; return }
  if (target && graph.value.edges.some(e => e.to === params.target)) { error.value = '该节点已有入边（预处理/目标仅允许单入边）'; return }
  if (source && graph.value.edges.some(e => e.from === params.source)) { error.value = '该节点已有出边（来源/预处理仅允许单出边）'; return }
  graph.value.edges = [...graph.value.edges, { from: params.source, to: params.target }]
  error.value = ''
})
onNodeClick(({ node }) => openConfig(node.id))
function addComponent(component) {
  const position = project ? project({ x: 120 + graph.value.nodes.length * 30, y: 80 + graph.value.nodes.length * 40 }) : { x: 120, y: 80 }
  graph.value.nodes = [...graph.value.nodes, newNode(component, graph.value.nodes, position)]
}
function openConfig(key) {
  const node = graph.value.nodes.find(n => n.key === key)
  if (!node) return
  editingKey.value = key
  configForm.value = JSON.parse(JSON.stringify(node.config || defaultConfig(node.component)))
  if (node.component === 'SINK_TABLE' && Array.isArray(configForm.value.keyColumns)) configForm.value.keyColumns = configForm.value.keyColumns.join(', ')
  drawerOpen.value = true
}
function saveConfig() {
  const node = graph.value.nodes.find(n => n.key === editingKey.value)
  if (!node) { drawerOpen.value = false; return }
  const config = { ...configForm.value }
  if (node.component === 'SINK_TABLE') config.keyColumns = String(config.keyColumns || '').split(',').map(s => s.trim()).filter(Boolean)
  if (node.component === 'FIELD_MAP') config.mappings = (config.mappings || []).filter(m => m.from && m.to)
  node.config = config
  drawerOpen.value = false
}
const editingDef = computed(() => { const n = graph.value.nodes.find(n => n.key === editingKey.value); return n ? componentDef(n.component) : null })
async function save() {
  if (busy.value) return
  const errors = validateFlow(graph.value)
  if (errors.length) { error.value = errors.join('；'); return }
  busy.value = true; error.value = ''
  try {
    const configuration = JSON.stringify(graph.value)
    const url = props.draft?.id ? `/api/tasks/${props.draft.id}/versions` : '/api/tasks'
    const body = props.draft?.id ? { configuration } : { name: props.draft.name, type: 'FLOW', directoryId: props.draft.directoryId ?? null, description: props.draft.description ?? null, configuration }
    const response = await authorizedRequest(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
    if (!response.ok) throw new Error(response.status === 403 ? '当前账号没有任务保存权限' : response.status === 409 ? '任务运行中，不能修改' : '流程保存失败，请检查配置')
    emit('saved')
  } catch (e) { error.value = e.message } finally { busy.value = false }
}
async function testRun() {
  const errors = validateFlow(graph.value)
  if (errors.length) { error.value = errors.join('；'); return }
  testBusy.value = true; error.value = ''; testResult.value = null
  try {
    const response = await authorizedRequest('/api/tasks/flow/test-run', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ configuration: graph.value }) })
    if (!response.ok) throw new Error(response.status === 408 ? '试运行超过 30 秒' : response.status === 403 ? '当前账号没有运行权限' : '试运行失败，请检查配置与数据源')
    testResult.value = await response.json()
    testTab.value = 'logs'
    previewTab.value = Object.keys(testResult.value.previews || {})[0] || ''
    testOpen.value = true
  } catch (e) { error.value = e.message } finally { testBusy.value = false }
}
async function leave() {
  try { await ElMessageBox.confirm('离开后未保存的编排将丢失。', '确认离开', { confirmButtonText: '离开', cancelButtonText: '继续编辑', type: 'warning' }) } catch { return }
  emit('cancel')
}
const previewKeys = computed(() => Object.keys(testResult.value?.previews || {}))
</script>
<template>
  <section class="flow-editor">
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/>
    <div class="toolbar">
      <div><el-button text @click="leave">← 返回任务列表</el-button><strong>{{ draft?.id ? '编辑流程' : '新建流程' }}：{{ draft?.name }}</strong></div>
      <div class="row">
        <el-button v-if="can('workflow.task.execute')" :loading="testBusy" @click="testRun">试运行</el-button>
        <el-button v-if="draft?.id ? can('workflow.task.update') : can('workflow.task.create')" type="primary" :loading="busy" @click="save">保存</el-button>
      </div>
    </div>
    <div class="editor-layout">
      <aside class="palette">
        <div v-for="g in GROUPS" :key="g.key" class="palette-group">
          <strong>{{ g.label }}</strong>
          <el-button v-for="c in COMPONENTS.filter(c => c.group === g.key)" :key="c.component" class="palette-item" @click="addComponent(c.component)">{{ c.label }}</el-button>
        </div>
        <p>点击组件加入画布；拖动节点调整位置；从节点右侧连接点拖至下一节点左侧建立连线；点击节点配置。</p>
      </aside>
      <div class="canvas">
        <VueFlow :nodes="vueNodes" :edges="vueEdges" :node-types="nodeTypes" :default-viewport="{ zoom: 0.9 }" :min-zoom="0.4" :max-zoom="1.5" fit-view-on-init :delete-key-code="['Backspace','Delete']">
          <Background :gap="20" :size="1" pattern-color="#d6e4e1" />
          <Controls position="bottom-right" :show-interactive="false" />
          <div v-if="!vueNodes.length" class="empty">从左侧点击组件开始编排。</div>
        </VueFlow>
      </div>
    </div>
    <el-drawer v-model="drawerOpen" :title="editingDef ? `组件配置：${editingDef.label}` : '组件配置'" size="30%" direction="rtl">
      <el-form v-if="editingDef" label-position="top">
        <el-form-item v-for="f in editingDef.fields" :key="f.key" :label="f.label">
          <el-select v-if="f.type === 'source'" v-model="configForm[f.key]" clearable placeholder="选择数据源"><el-option v-for="s in sources" :key="s.id" :label="s.name" :value="s.id"/></el-select>
          <el-select v-else-if="f.type === 'select'" v-model="configForm[f.key]"><el-option v-for="o in f.options" :key="o.value" :label="o.label" :value="o.value"/></el-select>
          <el-input-number v-else-if="f.type === 'number'" v-model="configForm[f.key]" :min="1" :max="10000"/>
          <el-input v-else-if="f.type === 'textarea' || f.type === 'expr'" v-model="configForm[f.key]" type="textarea" :rows="3"/>
          <el-input v-else-if="f.type === 'list'" v-model="configForm[f.key]" placeholder="逗号分隔"/>
          <div v-else-if="f.type === 'mappings'" class="mappings">
            <div v-for="(m, i) in configForm.mappings" :key="i" class="row">
              <el-input v-model="m.from" placeholder="上游字段"/>
              <span>→</span>
              <el-input v-model="m.to" placeholder="输出字段"/>
              <el-button text type="danger" @click="configForm.mappings.splice(i, 1)">删除</el-button>
            </div>
            <el-button text type="primary" @click="configForm.mappings.push({ from: '', to: '' })">添加映射</el-button>
          </div>
          <el-input v-else v-model="configForm[f.key]"/>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="drawerOpen = false">取消</el-button><el-button type="primary" @click="saveConfig">确定</el-button></template>
    </el-drawer>
    <el-dialog v-model="testOpen" title="试运行结果" width="min(900px, 95vw)">
      <el-tabs v-model="testTab">
        <el-tab-pane label="运行日志" name="logs">
          <div class="logs"><div v-for="line in testResult?.logs || []" :key="line.lineNo" :class="['log-line', line.level.toLowerCase()]">[{{ line.level }}] {{ line.message }}</div><div v-if="!(testResult?.logs || []).length" class="status-text">无日志</div></div>
        </el-tab-pane>
        <el-tab-pane label="数据预览" name="preview">
          <el-tabs v-model="previewTab" type="card">
            <el-tab-pane v-for="key in previewKeys" :key="key" :label="`目标 ${key}`" :name="key">
              <el-table :data="(testResult.previews[key].rows || []).map((r, i) => ({ __i: i, ...Object.fromEntries(testResult.previews[key].columns.map((c, j) => [c, r[j]])) }))" max-height="360">
                <el-table-column v-for="c in testResult.previews[key].columns" :key="c" :prop="c" :label="c" min-width="120"/>
              </el-table>
            </el-tab-pane>
          </el-tabs>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>
  </section>
</template>
<style scoped>
.toolbar{display:flex;justify-content:space-between;align-items:center;gap:16px;padding:12px 0}
.row{display:flex;gap:10px;align-items:center}
.editor-layout{display:flex;gap:14px;align-items:stretch}
.palette{flex:0 0 200px;background:#fff;border:1px solid var(--el-border-color-lighter);border-radius:4px;padding:12px;display:flex;flex-direction:column;gap:10px}
.palette-group{display:flex;flex-direction:column;gap:6px}
.palette-item{justify-content:flex-start;margin-left:0}
.palette p{color:#728582;font-size:12px}
.canvas{flex:1;min-width:0;height:560px;border:1px solid #cbdcda;border-radius:8px;background:#f8fbfa;position:relative}
.empty{position:absolute;top:45%;left:0;right:0;text-align:center;color:#718581;pointer-events:none}
.mappings .row{margin:6px 0}
.logs{max-height:360px;overflow:auto;font-family:monospace;font-size:12px}
.log-line.error{color:#c45656}
.log-line.warn{color:#b88230}
.status-text{color:#728582;font-size:12px}
@media(max-width:700px){.editor-layout{flex-direction:column}.palette{flex:none;width:100%}.canvas{height:420px}}
</style>
```
注意：`useVueFlow()` 需在 VueFlow 组件上下文内调用——本组件 template 含 VueFlow 且 setup 中调用 useVueFlow 会创建/绑定默认 store；若告警找不到 provider，把 `useVueFlow` 调用移入子组件或给 VueFlow 传 `:id` 并以 `useVueFlow(id)` 获取。实现时以 build+运行无告警为准，必要时调整（属机械修正，汇报说明）。

- [x] **步骤 3：构建验证**

运行：`cd frontend && npm run build && node --test "tests/*.test.js"`
预期：build 成功、既有测试不回归。

- [x] **步骤 4：Commit**

```bash
git add frontend/src/FlowNode.vue frontend/src/FlowEditor.vue
git commit -m "feat(processing): 流程画布编辑器与节点卡片

三栏布局：组件面板点击入画布、VueFlow 连线层级校验、配置抽屉、
试运行日志/预览双 Tab；保存走创建/新版本接口。"
```

---

### 任务 8：列表页 FLOW 集成与全量验证

**文件：**
- 修改：`frontend/src/ProcessingTasks.vue`、`frontend/src/TaskFormDialog.vue`

- [x] **步骤 1：TaskFormDialog 增加 FLOW 选项与 flow emit**

- emits 增加 `'flow'`：`const emit = defineEmits(['update:open', 'saved', 'flow'])`
- 类型 select 增加 `<el-option label="流程编排" value="FLOW"/>`
- SQL/SYNC 配置区、参数编辑器、源数据源下拉与 retry 区均包进 `<template v-if="form.type !== 'FLOW'"> … </template>`（FLOW 基础信息仅名称/类型/目录/描述；审查后修订）
- save() 开头（busy 守卫后）增加：
```js
    if (form.value.type === 'FLOW') { emit('flow', { name: form.value.name, directoryId: directoryId.value, description: description.value }); emit('update:open', false); return }
```
（FLOW 的配置由画布产生，表单不 POST。）

- [x] **步骤 2：ProcessingTasks 视图切换与 FLOW 入口**

- import FlowEditor；新增 `const view = ref('list'), flowDraft = ref(null)`
- openCreate 保持（表单选 FLOW 后 emit flow）；新增：
```js
function openFlow(metadata) { selected.value = []; flowDraft.value = { ...metadata, id: null }; view.value = 'editor' }
function openFlowEdit(row) { selected.value = []; flowDraft.value = { ...row }; view.value = 'editor' }
function flowSaved() { view.value = 'list'; flowDraft.value = null; message.value = '流程已保存'; loadAll() }
function flowCancel() { view.value = 'list'; flowDraft.value = null }
```
- openEdit 改为：`function openEdit(row) { if (row.type === 'FLOW') { openFlowEdit(row); return } formTask.value = row; formReadonly.value = row.runState === 'RUNNING'; formOpen.value = true }`
- openCopy 对 FLOW 走画布复制：`if (row.type === 'FLOW') { openFlow({ ...row, id: null, name: row.name + '-副本' }); return }`（审查后补充，保证复制对 FLOW 一致）
- 类型列模板：`{{ scope.row.type === 'SQL' ? 'SQL 加工' : scope.row.type === 'SYNC' ? '数据库同步' : '流程编排' }}`
- template 根 section 内：列表布局与对话框包 `<template v-if="view === 'list'"> … </template>`；新增：
```html
    <FlowEditor v-else :draft="flowDraft" :sources="sources" @saved="flowSaved" @cancel="flowCancel"/>
```
- TaskFormDialog 增加 `@flow="openFlow"`
- 轮询 watch 保持（editor 视图下轮询继续刷新 tasks 无害；如需暂停可在 watch 条件加 view.value==='list'，自行选择并说明）

- [x] **步骤 3：全量验证**

运行：
```bash
cd frontend && npm run build && node --test "tests/*.test.js"
cd ../backend && TMP=D:/tmp TEMP=D:/tmp JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot" mvn test
```
预期：build 成功、前端测试全过、后端全量 0 失败。

- [x] **步骤 4：用户验收清单（本环境无浏览器，转交控制者）**

1. 新增任务选“流程编排”→ 保存基础信息后进入画布；点击组件入画布、连线、点击节点配置抽屉
2. 非法连线即时提示（来源入边/目标出边/多入边）；删除节点/连线可用
3. 保存前客户端校验提示缺失配置；保存后回列表出现 FLOW 任务（类型列“流程编排”）
4. 试运行：日志 Tab 有节点开始/完成；预览 Tab 各目标数据正确且目标表无写入；超 30 秒提示
5. 编辑 FLOW 任务直接进画布带原配置；保存创建新版本
6. 启动 FLOW 任务 → 运行记录可见；运行中停止 → INTERRUPTED 手动停止；目标表部分批次回滚
7. 运行记录/重试/删除/复制对 FLOW 与 SQL/SYNC 一致
8. 窄屏画布面板置顶

- [x] **步骤 5：Commit**

```bash
git add frontend/src/ProcessingTasks.vue frontend/src/TaskFormDialog.vue
git commit -m "feat(processing): 列表页 FLOW 两段式创建与画布编辑入口

表单选流程编排进入画布；FLOW 行编辑直开画布；类型列与视图切换；
保存/取消回列表刷新。"
```

---

## 自检记录

- **规格覆盖度：** VueFlow 扩展（任务 7）、FLOW 类型与配置模型（任务 3/4）、7 组件（任务 3/6）、受限表达式（任务 2）、结构/字段校验（任务 3/4）、流式执行+统计+日志（任务 1/4）、试运行不写+限量+30s（任务 5）、三栏画布+两段式创建+试运行双 Tab（任务 7/8）、V34 存储与双 sink 接线（任务 1）、测试策略各条（各任务测试步骤）。node_key 列为规格必要细化已在头部声明。
- **占位符扫描：** 无 TODO/待定；所有代码步骤含完整代码；两处"实现时机械修正"声明（useVueFlow provider、workflow_runs 插入列）属环境适配非占位。
- **类型一致性：** FlowGraph.Node/Edge/Graph 与 FlowTaskExecutor/validator/前端 graph JSON 字段（key/component/config/position、from/to）一致；ExecutionSinks(log,stats) 四参 runner 签名在 service/worker/测试 mock 一致；DbRunLogSink 构造 (jdbc,ownerType,ownerId,nodeKey,attempt) 与 store 工厂一致；前端 configForm keyColumns 字符串↔数组转换与后端 keyColumns 数组一致；previews 结构 {columns,rows} 前后端一致。
