package com.example.governance.workflow;

import com.example.governance.security.AuditLog;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TaskRunService {
    public record Attempt(int attempt, String state, LocalDateTime startedAt, LocalDateTime finishedAt, String errorMessage) {}
    public record View(long id, long taskId, int taskVersion, LocalDate businessDate, String state, LocalDateTime createdAt, LocalDateTime startedAt, LocalDateTime finishedAt, String errorMessage, List<Attempt> attempts) {}
    private record Run(long taskId, int version, LocalDate date, String state, int attempt) {}
    private final JdbcTemplate jdbc;
    private final TaskVersionStore tasks;
    private final CamelTaskRunner runner;
    private final ThreadPoolTaskExecutor executor;
    private final AuditLog audit;
    private final TransactionTemplate tx;
    private final TaskExecutionLimiter limiter;
    private final java.util.concurrent.ConcurrentHashMap<Long, RunCancelContext> active = new java.util.concurrent.ConcurrentHashMap<>();
    private record ActiveRun(long id, String state, int attempt) {}
    public TaskRunService(JdbcTemplate jdbc, TaskVersionStore tasks, CamelTaskRunner runner, ThreadPoolTaskExecutor executor, AuditLog audit) {
        this(jdbc, tasks, runner, executor, audit, new TaskExecutionLimiter(8));
    }
    @org.springframework.beans.factory.annotation.Autowired
    public TaskRunService(JdbcTemplate jdbc, TaskVersionStore tasks, CamelTaskRunner runner, ThreadPoolTaskExecutor executor, AuditLog audit, TaskExecutionLimiter limiter) {
        this.jdbc = jdbc; this.tasks = tasks; this.runner = runner; this.executor = executor; this.audit = audit; this.limiter = java.util.Objects.requireNonNull(limiter);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));
    }
    @Transactional
    public long start(long taskId, int version, LocalDate date, Authentication auth) { return start(taskId, version, date, null, auth); }
    @Transactional
    public long start(long taskId, int version, LocalDate date, com.fasterxml.jackson.databind.JsonNode variables, Authentication auth) {
        if (date == null || tasks.findVersion(taskId, version).isEmpty()) throw new IllegalArgumentException("Invalid task version or business date");
        String parameters = null;
        if (variables != null && !variables.isNull()) {
            if (!variables.isObject()) throw new IllegalArgumentException("运行变量必须是对象");
            try { parameters = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(variables); }
            catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalArgumentException("运行变量序列化失败"); }
            if (parameters.length() > 20000) throw new IllegalArgumentException("运行变量过大");
        }
        // 锁任务行后再查活跃 run，与并发 start/stop 串行化（stop 只锁 runs 行、不取任务锁，无锁环）
        jdbc.queryForList("SELECT id FROM processing_tasks WHERE id=? FOR UPDATE", Long.class, taskId);
        if (hasActiveRun(taskId)) throw new IllegalStateException("任务已在运行");
        var key = new GeneratedKeyHolder();
        String params = parameters;
        jdbc.update(c -> { var st = c.prepareStatement("INSERT INTO task_runs(task_id,task_version,business_date,state,created_at,parameters) VALUES (?,?,?,'PENDING',CURRENT_TIMESTAMP,?)", new String[]{"id"}); st.setLong(1, taskId); st.setInt(2, version); st.setDate(3, java.sql.Date.valueOf(date)); st.setString(4, params); return st; }, key);
        if (key.getKey() == null) throw new IllegalStateException("Task run ID unavailable");
        long id = key.getKey().longValue();
        jdbc.update("INSERT INTO task_run_attempts(run_id,attempt_no,state) VALUES (?,1,'PENDING')", id);
        if (auth != null) audit.success(auth, "task.run", "task_run", id);
        else jdbc.update("INSERT INTO audit_events(actor_id,occurred_at,action,object_type,object_id,result) VALUES (NULL,CURRENT_TIMESTAMP,'task.schedule.fire','task_run',?,'SUCCESS')", Long.toString(id));
        dispatchAfterCommit(id, taskId, version, date, 1);
        return id;
    }
    @Transactional
    public void retry(long taskId, long runId, Authentication auth) {
        // 先锁任务行再复查活跃 run，与 start/delete 锁序一致（任务行→runs 行）无锁环；防止并发 RUNNING 时重试旧实例产生双活跃
        jdbc.queryForList("SELECT id FROM processing_tasks WHERE id=? FOR UPDATE", Long.class, taskId);
        if (hasActiveRun(taskId)) throw new IllegalStateException("任务已在运行，不能重试");
        var rows = jdbc.query("SELECT task_id,task_version,business_date,state,attempt_no FROM task_runs WHERE id=? FOR UPDATE", (rs, row) -> new Run(rs.getLong(1),rs.getInt(2),rs.getDate(3).toLocalDate(),rs.getString(4),rs.getInt(5)),runId);
        if (rows.isEmpty() || rows.getFirst().taskId() != taskId) throw new IllegalArgumentException("Unknown task run");
        var run = rows.getFirst();
        if (!java.util.Set.of("FAILED", "INTERRUPTED").contains(run.state())) throw new IllegalStateException("Task run cannot be retried");
        int attempt = Math.addExact(run.attempt(), 1);
        jdbc.update("UPDATE task_runs SET state='PENDING',attempt_no=?,started_at=NULL,finished_at=NULL,error_message=NULL WHERE id=?",attempt,runId);
        jdbc.update("INSERT INTO task_run_attempts(run_id,attempt_no,state) VALUES (?,?,'PENDING')",runId,attempt);
        audit.success(auth, "task.retry", "task_run", runId);
        dispatchAfterCommit(runId,taskId,run.version(),run.date(),attempt);
    }
    private void dispatchAfterCommit(long id, long taskId, int version, LocalDate date, int attempt) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { try { executor.execute(() -> execute(id, taskId, version, date, attempt)); } catch (RuntimeException ex) { finish(id,attempt,"INTERRUPTED",null); } }
        });
    }
    private void execute(long id, long taskId, int versionNo, LocalDate date, int attempt) {
        // 上下文先于 claim 注册，闭合 claim→put 窗口；claim 失败（如 PENDING 已被 stop 落终态）则摘除后返回
        var cancel = new RunCancelContext();
        cancel.worker(Thread.currentThread());
        active.put(id, cancel);
        boolean claimed;
        try {
            claimed = Boolean.TRUE.equals(tx.execute(status -> {
                if (jdbc.update("UPDATE task_runs SET state='RUNNING',started_at=CURRENT_TIMESTAMP WHERE id=? AND state='PENDING' AND attempt_no=?",id,attempt) != 1) return false;
                if (jdbc.update("UPDATE task_run_attempts SET state='RUNNING',started_at=CURRENT_TIMESTAMP WHERE run_id=? AND attempt_no=? AND state='PENDING'",id,attempt) != 1) throw new IllegalStateException("Attempt state mismatch");
                return true;
            }));
        } catch (Exception ex) { cancel.detach(); active.remove(id); finish(id,attempt,"INTERRUPTED",null); return; }
        if (!claimed) { cancel.detach(); active.remove(id); return; }
        try (var permit = limiter.acquire()) {
            var logSink = new DbRunLogSink(jdbc, "TASK_RUN", id, null, attempt);
            var sinks = new ExecutionSinks(logSink,
                    resilientStats(json -> jdbc.update("UPDATE task_run_attempts SET stats=? WHERE run_id=? AND attempt_no=?", json, id, attempt), logSink),
                    nodeKey -> new DbRunLogSink(jdbc, "TASK_RUN", id, nodeKey, attempt));
            var version = tasks.findVersion(taskId, versionNo).orElseThrow();
            var parameters = jdbc.query("SELECT parameters FROM task_runs WHERE id=?", (rs, row) -> rs.getString(1), id);
            String params = parameters.isEmpty() ? null : parameters.getFirst();
            var ctx = new OrchTaskExecutor.Context(taskId, id, date, CamelTaskRunner.parseVariables(params), false);
            var outcome = runner.execute(version, ctx, cancel, sinks, new DbNodeRunSink(jdbc, id, attempt), 100, 0);
            persistSamples(id, outcome);
            // 成功路径在摘除上下文前落终态，闭合 remove→finish 窗口；stop 与业务完成竞态判 INTERRUPTED
            if (cancel.isCancelled()) Thread.interrupted(); // 清除 cancel 留下的中断标志，避免带标志取连接失败
            // Failure recording a successful business write is ambiguous and must not be classified as a task failure.
            try { finish(id, attempt, cancel.isCancelled() ? "INTERRUPTED" : "SUCCEEDED", cancel.isCancelled() ? "手动停止" : null); }
            catch (RuntimeException ex) { finish(id,attempt,"INTERRUPTED",null); }
        }
        catch (InterruptedException ex) { finish(id, attempt, "INTERRUPTED", cancel.isCancelled() ? "手动停止" : null); Thread.currentThread().interrupt(); }
        catch (Exception ex) {
            if (cancel.isCancelled()) Thread.interrupted(); // 同上：先落库再保留中断语义
            finish(id, attempt, cancel.isCancelled() ? "INTERRUPTED" : "FAILED", cancel.isCancelled() ? "手动停止" : "任务执行失败");
        }
        finally { cancel.detach(); active.remove(id); }
    }
    /** 统计属观测数据：写入失败降级 WARN 日志，不得击穿业务执行结果。 */
    static java.util.function.Consumer<String> resilientStats(java.util.function.Consumer<String> delegate, RunLogSink log) {
        return json -> {
            try { delegate.accept(json); }
            catch (org.springframework.dao.DataAccessException ex) { log.log("WARN", "统计写入失败: " + ex.getMessage()); }
        };
    }
    private void finish(long id, int attempt, String state, String error) {
        tx.executeWithoutResult(status -> {
            int updated = jdbc.update("UPDATE task_runs SET state=?,finished_at=CURRENT_TIMESTAMP,error_message=? WHERE id=? AND attempt_no=? AND state IN ('PENDING','RUNNING')",state,error,id,attempt);
            if (updated == 0) return;
            if (jdbc.update("UPDATE task_run_attempts SET state=?,finished_at=CURRENT_TIMESTAMP,error_message=? WHERE run_id=? AND attempt_no=? AND state IN ('PENDING','RUNNING')",state,error,id,attempt) != 1) throw new IllegalStateException("Attempt state mismatch");
        });
    }
    @Transactional
    public int stop(long taskId, Authentication auth) {
        var rows = jdbc.query("SELECT id,state,attempt_no FROM task_runs WHERE task_id=? AND state IN ('PENDING','RUNNING') FOR UPDATE",
                (rs, row) -> new ActiveRun(rs.getLong(1), rs.getString(2), rs.getInt(3)), taskId);
        if (rows.isEmpty()) throw new IllegalStateException("任务未在运行");
        for (ActiveRun run : rows) {
            RunCancelContext cancel = active.get(run.id());
            if ("PENDING".equals(run.state()) || cancel == null) finish(run.id(), run.attempt(), "INTERRUPTED", "手动停止");
            else cancel.cancel();
        }
        audit.success(auth, "task.stop", "task", taskId);
        return rows.size();
    }
    public int recover() {
        return tx.execute(status -> {
            jdbc.update("UPDATE task_node_runs SET state='INTERRUPTED',finished_at=CURRENT_TIMESTAMP WHERE state IN ('PENDING','RUNNING') AND run_id IN (SELECT id FROM task_runs WHERE state IN ('PENDING','RUNNING'))");
            jdbc.update("UPDATE task_run_attempts SET state='INTERRUPTED',finished_at=CURRENT_TIMESTAMP WHERE state IN ('PENDING','RUNNING') AND run_id IN (SELECT id FROM task_runs WHERE state IN ('PENDING','RUNNING'))");
            return jdbc.update("UPDATE task_runs SET state='INTERRUPTED',finished_at=CURRENT_TIMESTAMP WHERE state IN ('PENDING','RUNNING')");
        });
    }
    public record LastRun(String state, LocalDateTime startedAt, LocalDateTime finishedAt) {}
    public record TaskRunSummary(boolean running, LastRun lastRun) {}
    /** 活跃 run 判定：start 在任务行锁内调用，与并发 start/stop 原子串行化；DELETE 的权威守卫在 store.delete 锁内锁定读复查，控制器前置检查仅快速失败；基本信息 PUT 守卫为非原子 best-effort 调用，基本信息不影响执行语义。 */
    public boolean hasActiveRun(long taskId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM task_runs WHERE task_id=? AND state IN ('PENDING','RUNNING')", Integer.class, taskId) > 0;
    }
    private record RunRow(long taskId, String state, LocalDateTime startedAt, LocalDateTime finishedAt) {}
    /** Latest run plus active flag per task; aggregated in SQL, no full-table materialization. */
    public java.util.Map<Long, TaskRunSummary> summary() {
        var latest = jdbc.query("SELECT r.task_id,r.state,r.started_at,r.finished_at FROM task_runs r JOIN (SELECT task_id,MAX(id) AS max_id FROM task_runs GROUP BY task_id) m ON m.max_id=r.id",
                (rs, row) -> { var s = rs.getTimestamp(3); var f = rs.getTimestamp(4); return new RunRow(rs.getLong(1), rs.getString(2), s == null ? null : s.toLocalDateTime(), f == null ? null : f.toLocalDateTime()); });
        var active = new java.util.HashSet<>(jdbc.queryForList("SELECT DISTINCT task_id FROM task_runs WHERE state IN ('PENDING','RUNNING')", Long.class));
        var result = new java.util.HashMap<Long, TaskRunSummary>();
        for (RunRow row : latest) result.put(row.taskId(), new TaskRunSummary(active.contains(row.taskId()), new LastRun(row.state(), row.startedAt(), row.finishedAt())));
        return result;
    }
    public List<View> list(long taskId) {
        var runs = jdbc.query("SELECT id,task_id,task_version,business_date,state,created_at,started_at,finished_at,error_message FROM task_runs WHERE task_id=? ORDER BY id DESC LIMIT 100", (rs, row) -> new View(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getDate(4).toLocalDate(), rs.getString(5), rs.getTimestamp(6).toLocalDateTime(), rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toLocalDateTime(), rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toLocalDateTime(), rs.getString(9), List.of()), taskId);
        if (runs.isEmpty()) return List.of();
        String marks = String.join(",", java.util.Collections.nCopies(runs.size(), "?"));
        var attempts = jdbc.query("SELECT run_id,attempt_no,state,started_at,finished_at,error_message FROM task_run_attempts WHERE run_id IN (" + marks + ") ORDER BY run_id,attempt_no", (rs, row) -> new java.util.AbstractMap.SimpleImmutableEntry<>(rs.getLong(1),new Attempt(rs.getInt(2),rs.getString(3),rs.getTimestamp(4)==null?null:rs.getTimestamp(4).toLocalDateTime(),rs.getTimestamp(5)==null?null:rs.getTimestamp(5).toLocalDateTime(),rs.getString(6))), runs.stream().map(View::id).toArray());
        var grouped = attempts.stream().collect(java.util.stream.Collectors.groupingBy(java.util.Map.Entry::getKey,java.util.stream.Collectors.mapping(java.util.Map.Entry::getValue,java.util.stream.Collectors.toList())));
        return runs.stream().map(r -> new View(r.id(),r.taskId(),r.taskVersion(),r.businessDate(),r.state(),r.createdAt(),r.startedAt(),r.finishedAt(),r.errorMessage(),List.copyOf(grouped.getOrDefault(r.id(),List.of())))).toList();
    }

    // ---- run detail / node state / logs (orchestration console) ----
    public record NodeRunView(String nodeKey, String state, int attempt, Long rowsIn, Long rowsOut, Long durationMs, String errorMessage, LocalDateTime startedAt, LocalDateTime finishedAt, boolean hasSample, String nodeName) {}
    public record RunDetail(long id, long taskId, int taskVersion, LocalDate businessDate, String state, LocalDateTime createdAt, LocalDateTime startedAt, LocalDateTime finishedAt, String errorMessage, String parameters, List<NodeRunView> nodes) {}
    public record LogLine(long id, String nodeKey, int attempt, int lineNo, String level, String message, LocalDateTime createdAt) {}

    public RunDetail detail(long runId) {
        var rows = jdbc.query("SELECT id,task_id,task_version,business_date,state,created_at,started_at,finished_at,error_message,parameters FROM task_runs WHERE id=?",
                (rs, row) -> new RunDetail(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getDate(4).toLocalDate(), rs.getString(5),
                        rs.getTimestamp(6).toLocalDateTime(), rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toLocalDateTime(),
                        rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toLocalDateTime(), rs.getString(9), rs.getString(10), List.of()), runId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Unknown run");
        var base = rows.getFirst();
        return new RunDetail(base.id(), base.taskId(), base.taskVersion(), base.businessDate(), base.state(), base.createdAt(), base.startedAt(), base.finishedAt(), base.errorMessage(), base.parameters(), nodeRuns(runId, nodeNames(base.taskId(), base.taskVersion())));
    }

    /** Resolve business-readable node names from the pinned version definition (id → name). */
    private java.util.Map<String, String> nodeNames(long taskId, int version) {
        var map = new java.util.HashMap<String, String>();
        try {
            var config = tasks.findVersion(taskId, version).map(TaskVersionStore.Version::configuration).orElse(null);
            if (config == null) return map;
            for (var n : new com.fasterxml.jackson.databind.ObjectMapper().readTree(config).path("nodes")) {
                String id = n.path("id").asText("");
                String name = n.path("name").asText("");
                if (!id.isBlank() && !name.isBlank()) map.put(id, name);
            }
        } catch (Exception ex) { /* fall back to node keys */ }
        return map;
    }

    public List<NodeRunView> nodeRuns(long runId, java.util.Map<String, String> nameMap) {
        return jdbc.query("SELECT n.node_key,n.state,n.attempt_no,n.rows_in,n.rows_out,n.duration_ms,n.error_message,n.started_at,n.finished_at,n.sample_json IS NOT NULL " +
                        "FROM task_node_runs n JOIN (SELECT node_key,MAX(attempt_no) AS max_attempt FROM task_node_runs WHERE run_id=? GROUP BY node_key) m " +
                        "ON m.node_key=n.node_key AND m.max_attempt=n.attempt_no WHERE n.run_id=? ORDER BY n.id",
                (rs, row) -> new NodeRunView(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getObject(4, Long.class), rs.getObject(5, Long.class),
                        rs.getObject(6, Long.class), rs.getString(7), rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toLocalDateTime(),
                        rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toLocalDateTime(), rs.getBoolean(10),
                        nameMap.getOrDefault(rs.getString(1), rs.getString(1))), runId, runId);
    }

    /** Bounded data-preview sample for a node of a run (latest attempt that has a sample). */
    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> preview(long runId, String nodeKey) {
        var rows = jdbc.query("SELECT sample_json FROM task_node_runs WHERE run_id=? AND node_key=? AND sample_json IS NOT NULL ORDER BY attempt_no DESC LIMIT 1",
                (rs, row) -> rs.getString(1), runId, nodeKey);
        if (rows.isEmpty()) return java.util.Map.of("columns", java.util.List.of(), "rows", java.util.List.of());
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(rows.getFirst(), java.util.Map.class); }
        catch (Exception ex) { return java.util.Map.of("columns", java.util.List.of(), "rows", java.util.List.of()); }
    }

    /** Persist <=100-row samples per node for real runs (best-effort, never breaks the run). */
    private void persistSamples(long runId, OrchTaskExecutor.Outcome outcome) {
        if (outcome == null || outcome.previews().isEmpty()) return;
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var attempts = new java.util.HashMap<String, Integer>();
        for (var s : outcome.states()) attempts.put(s.nodeKey(), s.attempt());
        for (var e : outcome.previews().entrySet()) {
            try {
                String json = mapper.writeValueAsString(e.getValue());
                Integer att = attempts.get(e.getKey());
                jdbc.update("UPDATE task_node_runs SET sample_json=? WHERE run_id=? AND node_key=? AND attempt_no=?", json, runId, e.getKey(), att == null ? 1 : att);
            } catch (Exception ex) { /* best-effort */ }
        }
    }

    public List<LogLine> logs(long runId, String nodeKey, String level, long afterId, int limit) {
        int capped = Math.min(Math.max(limit, 1), 1000);
        var sql = new StringBuilder("SELECT id,node_key,attempt_no,line_no,level,message,created_at FROM execution_logs WHERE owner_type='TASK_RUN' AND owner_id=? AND id>?");
        var args = new java.util.ArrayList<Object>();
        args.add(runId); args.add(afterId);
        if (nodeKey != null && !nodeKey.isBlank()) { sql.append(" AND node_key=?"); args.add(nodeKey); }
        if (level != null && !level.isBlank()) { sql.append(" AND level=?"); args.add(level.toUpperCase(java.util.Locale.ROOT)); }
        sql.append(" ORDER BY id LIMIT ").append(capped);
        return jdbc.query(sql.toString(), (rs, row) -> new LogLine(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getString(5), rs.getString(6), rs.getTimestamp(7).toLocalDateTime()), args.toArray());
    }
}
