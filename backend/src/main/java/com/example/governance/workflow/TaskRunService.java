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
    public TaskRunService(JdbcTemplate jdbc, TaskVersionStore tasks, CamelTaskRunner runner, ThreadPoolTaskExecutor executor, AuditLog audit) {
        this(jdbc, tasks, runner, executor, audit, new TaskExecutionLimiter(8));
    }
    @org.springframework.beans.factory.annotation.Autowired
    public TaskRunService(JdbcTemplate jdbc, TaskVersionStore tasks, CamelTaskRunner runner, ThreadPoolTaskExecutor executor, AuditLog audit, TaskExecutionLimiter limiter) {
        this.jdbc = jdbc; this.tasks = tasks; this.runner = runner; this.executor = executor; this.audit = audit; this.limiter = java.util.Objects.requireNonNull(limiter);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));
    }
    @Transactional
    public long start(long taskId, int version, LocalDate date, Authentication auth) {
        if (date == null || tasks.findVersion(taskId, version).isEmpty()) throw new IllegalArgumentException("Invalid task version or business date");
        var key = new GeneratedKeyHolder();
        jdbc.update(c -> { var st = c.prepareStatement("INSERT INTO task_runs(task_id,task_version,business_date,state,created_at) VALUES (?,?,?,'PENDING',CURRENT_TIMESTAMP)", new String[]{"id"}); st.setLong(1, taskId); st.setInt(2, version); st.setDate(3, java.sql.Date.valueOf(date)); return st; }, key);
        if (key.getKey() == null) throw new IllegalStateException("Task run ID unavailable");
        long id = key.getKey().longValue();
        jdbc.update("INSERT INTO task_run_attempts(run_id,attempt_no,state) VALUES (?,1,'PENDING')", id);
        audit.success(auth, "task.run", "task_run", id);
        dispatchAfterCommit(id, taskId, version, date, 1);
        return id;
    }
    @Transactional
    public void retry(long taskId, long runId, Authentication auth) {
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
    private void execute(long id, long taskId, int version, LocalDate date, int attempt) {
        try {
            boolean claimed = Boolean.TRUE.equals(tx.execute(status -> {
                if (jdbc.update("UPDATE task_runs SET state='RUNNING',started_at=CURRENT_TIMESTAMP WHERE id=? AND state='PENDING' AND attempt_no=?",id,attempt) != 1) return false;
                if (jdbc.update("UPDATE task_run_attempts SET state='RUNNING',started_at=CURRENT_TIMESTAMP WHERE run_id=? AND attempt_no=? AND state='PENDING'",id,attempt) != 1) throw new IllegalStateException("Attempt state mismatch");
                return true;
            }));
            if (!claimed) return;
        } catch (Exception ex) { finish(id,attempt,"INTERRUPTED",null); return; }
        try (var permit = limiter.acquire()) { runner.execute(tasks.findVersion(taskId, version).orElseThrow(), date); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); finish(id,attempt,"INTERRUPTED",null); return; }
        catch (Exception ex) { finish(id,attempt,"FAILED","任务执行失败"); return; }
        // Failure recording a successful business write is ambiguous and must not be classified as a task failure.
        try { finish(id,attempt,"SUCCEEDED",null); }
        catch (RuntimeException ex) { finish(id,attempt,"INTERRUPTED",null); }
    }
    private void finish(long id, int attempt, String state, String error) {
        tx.executeWithoutResult(status -> {
            int updated = jdbc.update("UPDATE task_runs SET state=?,finished_at=CURRENT_TIMESTAMP,error_message=? WHERE id=? AND attempt_no=? AND state IN ('PENDING','RUNNING')",state,error,id,attempt);
            if (updated == 0) return;
            if (jdbc.update("UPDATE task_run_attempts SET state=?,finished_at=CURRENT_TIMESTAMP,error_message=? WHERE run_id=? AND attempt_no=? AND state IN ('PENDING','RUNNING')",state,error,id,attempt) != 1) throw new IllegalStateException("Attempt state mismatch");
        });
    }
    public int recover() {
        return tx.execute(status -> {
            jdbc.update("UPDATE task_run_attempts SET state='INTERRUPTED',finished_at=CURRENT_TIMESTAMP WHERE state IN ('PENDING','RUNNING') AND run_id IN (SELECT id FROM task_runs WHERE state IN ('PENDING','RUNNING'))");
            return jdbc.update("UPDATE task_runs SET state='INTERRUPTED',finished_at=CURRENT_TIMESTAMP WHERE state IN ('PENDING','RUNNING')");
        });
    }
    public record LastRun(String state, LocalDateTime startedAt, LocalDateTime finishedAt) {}
    public record TaskRunSummary(boolean running, LastRun lastRun) {}
    /** 非原子 best-effort 守卫：检查与后续写入不在同一事务，基本信息不影响执行语义。 */
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
}
