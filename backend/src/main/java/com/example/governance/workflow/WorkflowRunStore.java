package com.example.governance.workflow;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists run state and serializes claims with a database row lock. */
public final class WorkflowRunStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final WorkflowDefinitionStore definitions;
    public WorkflowRunStore(JdbcTemplate jdbc, PlatformTransactionManager manager, WorkflowDefinitionStore definitions) {
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(manager); this.definitions = definitions;
    }
    public long create(long workflowId, int version, LocalDate date) {
        if (date == null) throw new IllegalArgumentException("Business date is required");
        return transaction.execute(status -> {
            var definition = definitions.get(workflowId, version).orElseThrow(() -> new IllegalArgumentException("Unknown workflow version"));
            var key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("INSERT INTO workflow_runs(workflow_id,workflow_version,business_date,state,created_at) VALUES (?,?,?,'PENDING',CURRENT_TIMESTAMP)", new String[]{"id"});
                statement.setLong(1, workflowId); statement.setInt(2, version); statement.setDate(3, java.sql.Date.valueOf(date)); return statement;
            }, key);
            Number id = key.getKey();
            if (id == null) throw new IllegalStateException("Run ID was not generated");
            for (var node : definition.nodes()) jdbc.update("INSERT INTO workflow_run_nodes(run_id,node_key,state) VALUES (?,?,'PENDING')", id.longValue(), node.key());
            return id.longValue();
        });
    }
    public List<String> claimReady(long runId) {
        return transaction.execute(status -> {
            var run = lock(runId);
            if (Set.of("SUCCEEDED", "FAILED", "INTERRUPTED").contains(run.state())) return List.of();
            var nodes = jdbc.query("SELECT node_key,state FROM workflow_run_nodes WHERE run_id=?", (rs, row) -> new NodeState(rs.getString(1), rs.getString(2)), runId);
            if (nodes.stream().anyMatch(n -> n.state().equals("FAILED"))) return List.of();
            var definition = definitions.get(run.workflowId(), run.version()).orElseThrow();
            var graph = new WorkflowGraph(definition.nodes().stream().map(WorkflowDefinitionStore.Node::key).collect(java.util.stream.Collectors.toSet()), definition.edges());
            Set<String> succeeded = new HashSet<>(), started = new HashSet<>();
            for (var node : nodes) { if (node.state().equals("SUCCEEDED")) succeeded.add(node.key()); else if (!node.state().equals("PENDING")) started.add(node.key()); }
            var ready = graph.ready(succeeded, started).stream().sorted().toList();
            for (var node : ready) {
                jdbc.update("UPDATE workflow_run_nodes SET state='RUNNING',started_at=CURRENT_TIMESTAMP WHERE run_id=? AND node_key=? AND state='PENDING'", runId, node);
                Integer previous = jdbc.queryForObject("SELECT COALESCE(MAX(attempt_no),0) FROM workflow_node_attempts WHERE run_id=? AND node_key=?", Integer.class, runId, node);
                jdbc.update("INSERT INTO workflow_node_attempts(run_id,node_key,attempt_no,state,started_at) VALUES (?,?,?,'RUNNING',CURRENT_TIMESTAMP)", runId, node, previous + 1);
            }
            if (!ready.isEmpty()) jdbc.update("UPDATE workflow_runs SET state='RUNNING' WHERE id=?", runId);
            return ready;
        });
    }
    public record AttemptView(int attempt, String state, java.time.LocalDateTime startedAt, java.time.LocalDateTime finishedAt, String errorMessage) {}
    public record NodeView(String key, String state, List<AttemptView> attempts) {}
    public record RunView(long id, long workflowId, int workflowVersion, LocalDate businessDate, String state, List<NodeView> nodes) {}
    public java.util.Optional<RunView> view(long runId) {
        var rows = jdbc.query("SELECT workflow_id,workflow_version,business_date,state FROM workflow_runs WHERE id=?", (rs, row) -> new RunView(runId, rs.getLong(1), rs.getInt(2), rs.getDate(3).toLocalDate(), rs.getString(4), List.of()), runId);
        if (rows.isEmpty()) return java.util.Optional.empty();
        var run = rows.getFirst();
        var basic = jdbc.query("SELECT node_key,state FROM workflow_run_nodes WHERE run_id=? ORDER BY node_key", (rs, row) -> new NodeView(rs.getString(1), rs.getString(2), List.of()), runId);
        var attempts = jdbc.query("SELECT node_key,attempt_no,state,started_at,finished_at,error_message FROM workflow_node_attempts WHERE run_id=? ORDER BY node_key,attempt_no", (rs, row) -> new java.util.AbstractMap.SimpleImmutableEntry<>(rs.getString(1), new AttemptView(rs.getInt(2), rs.getString(3), rs.getTimestamp(4).toLocalDateTime(), rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toLocalDateTime(), rs.getString(6))), runId);
        var grouped = attempts.stream().collect(java.util.stream.Collectors.groupingBy(java.util.Map.Entry::getKey, java.util.stream.Collectors.mapping(java.util.Map.Entry::getValue, java.util.stream.Collectors.toList())));
        var nodes = basic.stream().map(n -> new NodeView(n.key(), n.state(), List.copyOf(grouped.getOrDefault(n.key(), List.of())))).toList();
        return java.util.Optional.of(new RunView(run.id(), run.workflowId(), run.workflowVersion(), run.businessDate(), run.state(), nodes));
    }
    public record RunSummary(long id, int workflowVersion, LocalDate businessDate, String state, java.time.LocalDateTime createdAt) {}
    public List<RunSummary> list(long workflowId, String state, LocalDate date, Long beforeId, int limit) {
        if (workflowId < 1 || (state != null && !Set.of("PENDING", "RUNNING", "WAITING", "FAILED", "SUCCEEDED", "INTERRUPTED").contains(state)) || (beforeId != null && beforeId < 1) || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid run history filter");
        StringBuilder sql = new StringBuilder("SELECT id,workflow_version,business_date,state,created_at FROM workflow_runs WHERE workflow_id=?");
        var args = new java.util.ArrayList<Object>(); args.add(workflowId);
        if (state != null) { sql.append(" AND state=?"); args.add(state); }
        if (date != null) { sql.append(" AND business_date=?"); args.add(java.sql.Date.valueOf(date)); }
        if (beforeId != null) { sql.append(" AND id<?"); args.add(beforeId); }
        sql.append(" ORDER BY id DESC LIMIT ").append(limit);
        return jdbc.query(sql.toString(), (rs, row) -> new RunSummary(rs.getLong(1), rs.getInt(2), rs.getDate(3).toLocalDate(), rs.getString(4), rs.getTimestamp(5).toLocalDateTime()), args.toArray());
    }
    public record Snapshot(long workflowId, int workflowVersion, LocalDate businessDate) {}
    public Snapshot snapshot(long runId) {
        return jdbc.query("SELECT workflow_id,workflow_version,business_date FROM workflow_runs WHERE id=?", (rs, row) -> new Snapshot(rs.getLong(1), rs.getInt(2), rs.getDate(3).toLocalDate()), runId).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown run"));
    }
    public void finish(long runId, String node, boolean succeeded) {
        transaction.executeWithoutResult(status -> {
            lock(runId);
            int updated = jdbc.update("UPDATE workflow_run_nodes SET state=?,finished_at=CURRENT_TIMESTAMP WHERE run_id=? AND node_key=? AND state='RUNNING'", succeeded ? "SUCCEEDED" : "FAILED", runId, node);
            if (updated != 1) throw new IllegalStateException("Node is not running");
            int attempts = jdbc.update("UPDATE workflow_node_attempts SET state=?,finished_at=CURRENT_TIMESTAMP,error_message=? WHERE run_id=? AND node_key=? AND state='RUNNING'", succeeded ? "SUCCEEDED" : "FAILED", succeeded ? null : "任务执行失败", runId, node);
            if (attempts != 1) throw new IllegalStateException("Node attempt is inconsistent");
            var states = jdbc.queryForList("SELECT state FROM workflow_run_nodes WHERE run_id=?", String.class, runId);
            String state = states.contains("RUNNING") ? "RUNNING" : states.contains("FAILED") ? "FAILED" : states.contains("RETRY_WAIT") ? "WAITING" : states.stream().allMatch("SUCCEEDED"::equals) ? "SUCCEEDED" : "RUNNING";
            jdbc.update("UPDATE workflow_runs SET state=? WHERE id=?", state, runId);
        });
    }
    public boolean scheduleRetry(long runId, String node, int maxRetries, int delaySeconds, java.time.Instant now) {
        if (maxRetries < 0 || maxRetries > 3 || delaySeconds < 1 || delaySeconds > 3600 || now == null) throw new IllegalArgumentException("Invalid retry policy");
        return transaction.execute(status -> {
            var run = lock(runId);
            if (!Set.of("RUNNING", "FAILED", "WAITING").contains(run.state())) return false;
            var states = jdbc.queryForList("SELECT state FROM workflow_run_nodes WHERE run_id=? AND node_key=?", String.class, runId, node);
            if (states.isEmpty() || !states.getFirst().equals("FAILED")) return false;
            Integer attempt = jdbc.queryForObject("SELECT COALESCE(MAX(attempt_no),0) FROM workflow_node_attempts WHERE run_id=? AND node_key=?", Integer.class, runId, node);
            if (attempt > maxRetries) return false;
            long retryAt = now.plusSeconds(delaySeconds).toEpochMilli();
            jdbc.update("UPDATE workflow_run_nodes SET state='RETRY_WAIT',next_retry_epoch=? WHERE run_id=? AND node_key=?", retryAt, runId, node);
            var all = jdbc.queryForList("SELECT state FROM workflow_run_nodes WHERE run_id=?", String.class, runId);
            String next = all.contains("FAILED") ? "FAILED" : all.contains("RUNNING") ? "RUNNING" : "WAITING";
            jdbc.update("UPDATE workflow_runs SET state=? WHERE id=?", next, runId);
            return true;
        });
    }
    public List<Long> dueRetries(java.time.Instant now, int limit) {
        if (now == null || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid retry query");
        return jdbc.query("SELECT DISTINCT n.run_id FROM workflow_run_nodes n JOIN workflow_runs r ON r.id=n.run_id WHERE n.state='RETRY_WAIT' AND n.next_retry_epoch<=? AND r.state IN ('WAITING','RUNNING') ORDER BY n.run_id LIMIT " + limit + "", (rs, row) -> rs.getLong(1), now.toEpochMilli());
    }
    public boolean activateDueRetries(long runId, java.time.Instant now) {
        if (now == null) throw new IllegalArgumentException("Time is required");
        return transaction.execute(status -> {
            var run = lock(runId);
            if (!Set.of("WAITING", "RUNNING").contains(run.state())) return false;
            int updated = jdbc.update("UPDATE workflow_run_nodes SET state='PENDING',next_retry_epoch=NULL WHERE run_id=? AND state='RETRY_WAIT' AND next_retry_epoch<=?", runId, now.toEpochMilli());
            if (updated > 0) jdbc.update("UPDATE workflow_runs SET state=CASE WHEN EXISTS (SELECT 1 FROM workflow_run_nodes WHERE run_id=? AND state='RUNNING') THEN 'RUNNING' ELSE 'PENDING' END WHERE id=?", runId, runId);
            return updated > 0;
        });
    }
    public void markInterrupted(long runId) {
        transaction.executeWithoutResult(status -> {
            var run = lock(runId);
            if (!Set.of("PENDING", "RUNNING", "WAITING").contains(run.state())) return;
            jdbc.update("UPDATE workflow_node_attempts SET state='INTERRUPTED',finished_at=CURRENT_TIMESTAMP WHERE run_id=? AND state='RUNNING'", runId);
            jdbc.update("UPDATE workflow_run_nodes SET state='INTERRUPTED',finished_at=CURRENT_TIMESTAMP WHERE run_id=? AND state IN ('RUNNING','RETRY_WAIT')", runId);
            jdbc.update("UPDATE workflow_runs SET state='INTERRUPTED' WHERE id=?", runId);
        });
    }
    public void retryFailed(long runId) {
        transaction.executeWithoutResult(status -> {
            var run = lock(runId);
            if (!Set.of("FAILED", "INTERRUPTED").contains(run.state())) throw new IllegalStateException("Run cannot be retried");
            jdbc.update("UPDATE workflow_run_nodes SET state='PENDING',started_at=NULL,finished_at=NULL,next_retry_epoch=NULL WHERE run_id=? AND state IN ('FAILED','INTERRUPTED','RETRY_WAIT')", runId);
            jdbc.update("UPDATE workflow_runs SET state='PENDING' WHERE id=?", runId);
        });
    }
    /** Call only during single-node startup before dispatching new work. */
    public int markInterrupted() {
        return transaction.execute(status -> {
            jdbc.update("UPDATE workflow_node_attempts SET state='INTERRUPTED',finished_at=CURRENT_TIMESTAMP WHERE state='RUNNING' AND run_id IN (SELECT id FROM workflow_runs WHERE state IN ('PENDING','RUNNING','WAITING'))");
            jdbc.update("UPDATE workflow_run_nodes SET state='INTERRUPTED',finished_at=CURRENT_TIMESTAMP WHERE state IN ('RUNNING','RETRY_WAIT') AND run_id IN (SELECT id FROM workflow_runs WHERE state IN ('PENDING','RUNNING','WAITING'))");
            return jdbc.update("UPDATE workflow_runs SET state='INTERRUPTED' WHERE state IN ('PENDING','RUNNING','WAITING')");
        });
    }
    public String status(long runId) { return jdbc.queryForObject("SELECT state FROM workflow_runs WHERE id=?", String.class, runId); }
    public int currentAttempt(long runId, String node) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(attempt_no),1) FROM workflow_node_attempts WHERE run_id=? AND node_key=?", Integer.class, runId, node);
    }
    public RunLogSink nodeLogSink(long runId, String node) { return new DbRunLogSink(jdbc, "WORKFLOW_NODE", runId, node, currentAttempt(runId, node)); }
    public java.util.function.Consumer<String> nodeStatsSink(long runId, String node) {
        return nodeStatsSink(runId, node, new DbRunLogSink(jdbc, "WORKFLOW_NODE", runId, node, currentAttempt(runId, node)));
    }
    /** 传入业务日志 sink：stats 写入失败的 WARN 与业务日志共用行号计数。 */
    public java.util.function.Consumer<String> nodeStatsSink(long runId, String node, RunLogSink log) {
        int attempt = currentAttempt(runId, node);
        return TaskRunService.resilientStats(json -> jdbc.update("UPDATE workflow_node_attempts SET stats=? WHERE run_id=? AND node_key=? AND attempt_no=?", json, runId, node, attempt), log);
    }
    private record Run(long workflowId, int version, String state) {}
    private record NodeState(String key, String state) {}
    private Run lock(long runId) {
        return jdbc.query("SELECT workflow_id,workflow_version,state FROM workflow_runs WHERE id=? FOR UPDATE", (rs, row) -> new Run(rs.getLong(1), rs.getInt(2), rs.getString(3)), runId).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown run"));
    }
}
