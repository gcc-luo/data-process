package com.example.governance.workflow;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Task-level cron schedule (P1). Row lock atomically advances the schedule and creates the run; missed fires are skipped, not backfilled. */
public final class TaskScheduleStore {
    public record View(long id, long taskId, String cron, String zone, boolean enabled, Long nextFireEpoch) {}
    private record Row(long taskId, String cron, String zone, long next, boolean enabled) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TaskVersionStore tasks;
    private final TaskRunService runs;

    public TaskScheduleStore(JdbcTemplate jdbc, PlatformTransactionManager manager, TaskVersionStore tasks, TaskRunService runs) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager); this.tasks = tasks; this.runs = runs;
    }

    public Optional<View> find(long taskId) {
        return jdbc.query("SELECT id,task_id,cron_expression,zone,enabled,next_fire_epoch FROM task_schedules WHERE task_id=?",
                (rs, row) -> new View(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getObject(6, Long.class)), taskId)
                .stream().findFirst();
    }

    public View upsert(long taskId, String expression, String zoneName, boolean enabled, Instant now) {
        CronExpression cron;
        ZoneId zone;
        try { cron = CronExpression.parse(expression); zone = ZoneId.of(zoneName); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("Cron 表达式或时区非法"); }
        if (expression.length() > 120 || zoneName.length() > 64) throw new IllegalArgumentException("Cron 表达式或时区过长");
        var next = cron.next(now.atZone(zone));
        if (next == null) throw new IllegalArgumentException("Cron 没有未来执行时间");
        long nextEpoch = next.toInstant().toEpochMilli();
        tx.executeWithoutResult(status -> {
            if (jdbc.queryForList("SELECT id FROM processing_tasks WHERE id=? FOR UPDATE", Long.class, taskId).isEmpty())
                throw new IllegalArgumentException("任务不存在");
            int updated = jdbc.update("UPDATE task_schedules SET cron_expression=?,zone=?,enabled=?,next_fire_epoch=? WHERE task_id=?",
                    expression, zoneName, enabled, nextEpoch, taskId);
            if (updated == 0) jdbc.update("INSERT INTO task_schedules(task_id,cron_expression,zone,enabled,next_fire_epoch) VALUES (?,?,?,?,?)",
                    taskId, expression, zoneName, enabled, nextEpoch);
        });
        return find(taskId).orElseThrow();
    }

    public void delete(long taskId) { jdbc.update("DELETE FROM task_schedules WHERE task_id=?", taskId); }

    public List<Long> due(Instant now, int limit) {
        return jdbc.query("SELECT id FROM task_schedules WHERE enabled=TRUE AND next_fire_epoch<=? ORDER BY next_fire_epoch,id LIMIT " + Math.min(Math.max(limit, 1), 100),
                (rs, row) -> rs.getLong(1), now.toEpochMilli());
    }

    /** Advance and fire one due schedule; returns the created run id, or empty when not due/disabled/unpublished. */
    public Optional<Long> fireDue(long scheduleId, Instant now) {
        return tx.execute(status -> {
            var rows = jdbc.query("SELECT task_id,cron_expression,zone,next_fire_epoch,enabled FROM task_schedules WHERE id=? FOR UPDATE",
                    (rs, row) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getBoolean(5)), scheduleId);
            if (rows.isEmpty()) throw new IllegalArgumentException("Unknown schedule");
            var schedule = rows.get(0);
            if (!schedule.enabled() || schedule.next() > now.toEpochMilli()) return Optional.empty();
            int version = tasks.publishedVersion(schedule.taskId());
            if (version < 1) return Optional.empty();
            ZoneId zone = ZoneId.of(schedule.zone());
            var fire = Instant.ofEpochMilli(schedule.next()).atZone(zone);
            var next = CronExpression.parse(schedule.cron()).next(fire);
            if (next == null) throw new IllegalStateException("Schedule has no future execution");
            long runId = runs.start(schedule.taskId(), version, fire.toLocalDate(), null, null);
            jdbc.update("UPDATE task_schedules SET next_fire_epoch=? WHERE id=?", next.toInstant().toEpochMilli(), scheduleId);
            return Optional.of(runId);
        });
    }
}