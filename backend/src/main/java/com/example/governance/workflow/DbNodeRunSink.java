package com.example.governance.workflow;

import org.springframework.jdbc.core.JdbcTemplate;

/** Persists per-node execution state for a run attempt; best-effort like log sinks. */
public final class DbNodeRunSink implements OrchTaskExecutor.NodeSink {
    private final JdbcTemplate jdbc;
    private final long runId;
    private final int attempt;

    public DbNodeRunSink(JdbcTemplate jdbc, long runId, int attempt) { this.jdbc = jdbc; this.runId = runId; this.attempt = attempt; }

    @Override public void start(String nodeKey, int attemptNo) {
        try {
            jdbc.update("INSERT INTO task_node_runs(run_id,attempt_no,node_key,state,started_at) VALUES (?,?,?,'RUNNING',CURRENT_TIMESTAMP)", runId, attemptNo, nodeKey);
        } catch (org.springframework.dao.DataAccessException ex) { /* best-effort */ }
    }
    @Override public void success(String nodeKey, int attemptNo, long rowsIn, long rowsOut, long durationMs) {
        try {
            jdbc.update("UPDATE task_node_runs SET state='SUCCEEDED',rows_in=?,rows_out=?,duration_ms=?,finished_at=CURRENT_TIMESTAMP WHERE run_id=? AND attempt_no=? AND node_key=?",
                    rowsIn, rowsOut, durationMs, runId, attemptNo, nodeKey);
        } catch (org.springframework.dao.DataAccessException ex) { /* best-effort */ }
    }
    @Override public void failure(String nodeKey, int attemptNo, String error, long durationMs) {
        try {
            String message = error == null ? "" : error.length() > 500 ? error.substring(0, 500) : error;
            jdbc.update("UPDATE task_node_runs SET state='FAILED',error_message=?,duration_ms=?,finished_at=CURRENT_TIMESTAMP WHERE run_id=? AND attempt_no=? AND node_key=?",
                    message, durationMs, runId, attemptNo, nodeKey);
        } catch (org.springframework.dao.DataAccessException ex) { /* best-effort */ }
    }
    @Override public void skipped(String nodeKey) {
        try {
            jdbc.update("INSERT INTO task_node_runs(run_id,attempt_no,node_key,state,finished_at) VALUES (?,?,?,'SKIPPED',CURRENT_TIMESTAMP)", runId, attempt, nodeKey);
        } catch (org.springframework.dao.DataAccessException ex) { /* best-effort */ }
    }
}