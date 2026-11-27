package com.example.governance.workflow;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import com.example.governance.security.AuditLog;
import static org.junit.jupiter.api.Assertions.*;

/** Task cron schedule: upsert validation, due firing advances atomically, unpublished tasks skip. */
class OrchScheduleTest {
    private TaskRunService runs(JdbcTemplate jdbc, TaskVersionStore tasks) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.setQueueCapacity(10); executor.initialize();
        return new TaskRunService(jdbc, tasks, Mockito.mock(CamelTaskRunner.class), executor, new AuditLog(jdbc), new TaskExecutionLimiter(4));
    }

    @Test void upsertValidatesCronAndFiresOncePerDueTime() {
        var db = OrchFixtures.h2(); var jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db);
        var tasks = new TaskVersionStore(jdbc, tx);
        long task = OrchFixtures.published(tasks, "scheduled", OrchFixtures.dml(1, "UPDATE t SET x=1"));
        var service = runs(jdbc, tasks);
        var schedules = new TaskScheduleStore(jdbc, tx, tasks, service);
        var now = Instant.parse("2026-11-21T00:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> schedules.upsert(task, "not-a-cron", "UTC", true, now));
        assertThrows(IllegalArgumentException.class, () -> schedules.upsert(task, "0 0 0 * * *", "Mars/Olympus", true, now));
        var view = schedules.upsert(task, "0 0 0 * * *", "UTC", true, now);
        assertTrue(view.enabled());
        assertNotNull(view.nextFireEpoch());
        // not due yet
        assertTrue(schedules.due(now, 10).isEmpty());
        // due at next midnight
        var due = Instant.parse("2026-11-22T00:00:01Z");
        var dueIds = schedules.due(due, 10);
        assertEquals(1, dueIds.size());
        var fired = schedules.fireDue(dueIds.get(0), due);
        assertTrue(fired.isPresent());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM task_runs WHERE task_id=?", Integer.class, task));
        // same instant no longer due (advanced)
        assertTrue(schedules.fireDue(dueIds.get(0), due).isEmpty());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM task_runs WHERE task_id=?", Integer.class, task));
    }

    @Test void unpublishedTaskIsSkipped() {
        var db = OrchFixtures.h2(); var jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db);
        var tasks = new TaskVersionStore(jdbc, tx);
        long task = tasks.create("draft-only", "ORCH", "{}");
        var schedules = new TaskScheduleStore(jdbc, tx, tasks, runs(jdbc, tasks));
        var now = Instant.parse("2026-11-21T00:00:00Z");
        schedules.upsert(task, "0 0 0 * * *", "UTC", true, now);
        var due = Instant.parse("2026-11-22T00:00:01Z");
        var ids = schedules.due(due, 10);
        assertEquals(1, ids.size());
        assertTrue(schedules.fireDue(ids.get(0), due).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM task_runs WHERE task_id=?", Integer.class, task));
    }

    @Test void deleteRemovesSchedule() {
        var db = OrchFixtures.h2(); var jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db);
        var tasks = new TaskVersionStore(jdbc, tx);
        long task = OrchFixtures.published(tasks, "s", OrchFixtures.dml(1, "UPDATE t SET x=1"));
        var schedules = new TaskScheduleStore(jdbc, tx, tasks, runs(jdbc, tasks));
        schedules.upsert(task, "0 0 0 * * *", "UTC", true, Instant.now());
        assertTrue(schedules.find(task).isPresent());
        schedules.delete(task);
        assertTrue(schedules.find(task).isEmpty());
    }
}