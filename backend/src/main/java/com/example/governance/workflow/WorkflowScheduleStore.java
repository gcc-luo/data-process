package com.example.governance.workflow;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses a row lock to atomically advance each schedule and create its corresponding run. */
public final class WorkflowScheduleStore {
    private record Schedule(long workflowId, int version, String cron, String zone, long next, boolean enabled) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WorkflowDefinitionStore definitions;
    private final WorkflowRunStore runs;
    public WorkflowScheduleStore(JdbcTemplate jdbc, PlatformTransactionManager manager, WorkflowDefinitionStore definitions, WorkflowRunStore runs) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager); this.definitions = definitions; this.runs = runs;
    }
    public long create(long workflowId, int version, String expression, String zoneName, Instant now) {
        CronExpression cron;
        ZoneId zone;
        try { cron = CronExpression.parse(expression); zone = ZoneId.of(zoneName); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("Invalid cron or time zone"); }
        if (now == null || expression.length() > 128 || zoneName.length() > 128 || definitions.get(workflowId, version).isEmpty()) throw new IllegalArgumentException("Invalid schedule");
        ZonedDateTime next = cron.next(now.atZone(zone));
        if (next == null) throw new IllegalArgumentException("Schedule has no future execution");
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO workflow_schedules(workflow_id,workflow_version,cron_expression,zone_id,next_fire_epoch,enabled) VALUES (?,?,?,?,?,TRUE)", new String[]{"id"});
            statement.setLong(1, workflowId); statement.setInt(2, version); statement.setString(3, expression); statement.setString(4, zoneName); statement.setLong(5, next.toInstant().toEpochMilli()); return statement;
        }, key);
        Number id = key.getKey();
        if (id == null) throw new IllegalStateException("Schedule ID was not generated");
        return id.longValue();
    }
    public record View(long id, long workflowId, int version, String cron, String zone, long nextFireEpoch, boolean enabled) {}
    public List<View> list() {
        return jdbc.query("SELECT id,workflow_id,workflow_version,cron_expression,zone_id,next_fire_epoch,enabled FROM workflow_schedules ORDER BY id", (rs, row) -> new View(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getLong(6), rs.getBoolean(7)));
    }
    public void setEnabled(long id, boolean enabled, Instant now) {
        if (now == null) throw new IllegalArgumentException("Time is required");
        tx.executeWithoutResult(status -> {
            var schedules = jdbc.query("SELECT workflow_id,workflow_version,cron_expression,zone_id,next_fire_epoch,enabled FROM workflow_schedules WHERE id=? FOR UPDATE", (rs, row) -> new Schedule(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getBoolean(6)), id);
            if (schedules.isEmpty()) throw new IllegalArgumentException("Unknown schedule");
            var schedule = schedules.get(0);
            if (schedule.enabled() == enabled) return;
            long next = schedule.next();
            if (enabled) {
                var nextTime = CronExpression.parse(schedule.cron()).next(now.atZone(ZoneId.of(schedule.zone())));
                if (nextTime == null) throw new IllegalStateException("Schedule has no future execution");
                next = nextTime.toInstant().toEpochMilli();
            }
            jdbc.update("UPDATE workflow_schedules SET enabled=?,next_fire_epoch=? WHERE id=?", enabled, next, id);
        });
    }
    public List<Long> due(Instant now, int limit) {
        if (now == null || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid due query");
        return jdbc.query("SELECT id FROM workflow_schedules WHERE enabled=TRUE AND next_fire_epoch<=? ORDER BY next_fire_epoch,id LIMIT " + limit + "", (rs, row) -> rs.getLong(1), now.toEpochMilli());
    }
    public Optional<Long> fireDue(long scheduleId, Instant now) {
        if (now == null) throw new IllegalArgumentException("Time is required");
        return tx.execute(status -> {
            var schedules = jdbc.query("SELECT workflow_id,workflow_version,cron_expression,zone_id,next_fire_epoch,enabled FROM workflow_schedules WHERE id=? FOR UPDATE", (rs, row) -> new Schedule(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getBoolean(6)), scheduleId);
            if (schedules.isEmpty()) throw new IllegalArgumentException("Unknown schedule");
            var schedule = schedules.get(0);
            if (!schedule.enabled() || schedule.next() > now.toEpochMilli()) return Optional.empty();
            ZoneId zone = ZoneId.of(schedule.zone());
            var fire = Instant.ofEpochMilli(schedule.next()).atZone(zone);
            var next = CronExpression.parse(schedule.cron()).next(fire);
            if (next == null) throw new IllegalStateException("Schedule has no future execution");
            long runId = runs.create(schedule.workflowId(), schedule.version(), fire.toLocalDate());
            jdbc.update("UPDATE workflow_schedules SET next_fire_epoch=? WHERE id=?", next.toInstant().toEpochMilli(), scheduleId);
            jdbc.update("INSERT INTO audit_events(actor_id,occurred_at,action,object_type,object_id,result) VALUES (NULL,CURRENT_TIMESTAMP,'workflow.schedule.fire','run',?,'SUCCESS')", Long.toString(runId));
            return Optional.of(runId);
        });
    }
}
