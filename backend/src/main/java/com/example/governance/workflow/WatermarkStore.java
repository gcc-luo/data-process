package com.example.governance.workflow;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** Incremental watermark checkpoints; advanced only after a run fully succeeds. */
public final class WatermarkStore {
    public record Watermark(String value, String tieBreaker, Long committedRunId) {}
    private final JdbcTemplate jdbc;
    public WatermarkStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Watermark> find(long taskId, String nodeKey) {
        return jdbc.query("SELECT watermark_value,tie_breaker_value,committed_run_id FROM task_watermarks WHERE task_id=? AND node_key=?",
                (rs, row) -> new Watermark(rs.getString(1), rs.getString(2), rs.getObject(3, Long.class)), taskId, nodeKey)
                .stream().findFirst();
    }

    public void commit(long taskId, String nodeKey, String value, String tieBreaker, long runId) {
        int updated = jdbc.update("UPDATE task_watermarks SET watermark_value=?,tie_breaker_value=?,committed_run_id=?,updated_at=CURRENT_TIMESTAMP WHERE task_id=? AND node_key=?",
                value, tieBreaker, runId, taskId, nodeKey);
        if (updated == 0) jdbc.update("INSERT INTO task_watermarks(task_id,node_key,watermark_value,tie_breaker_value,committed_run_id,updated_at) VALUES (?,?,?,?,?,CURRENT_TIMESTAMP)",
                taskId, nodeKey, value, tieBreaker, runId);
    }

    /** Comparable watermark ordering: numeric when both parse as numbers, otherwise lexical (ISO timestamps). */
    public static boolean greater(String candidate, String candidateTie, String current, String currentTie) {
        if (current == null) return candidate != null;
        if (candidate == null) return false;
        int cmp = compare(candidate, current);
        if (cmp != 0) return cmp > 0;
        if (candidateTie == null) return false;
        if (currentTie == null) return true;
        return compare(candidateTie, currentTie) > 0;
    }
    private static int compare(String a, String b) {
        try { return new java.math.BigDecimal(a).compareTo(new java.math.BigDecimal(b)); }
        catch (NumberFormatException ex) { return a.compareTo(b); }
    }
}