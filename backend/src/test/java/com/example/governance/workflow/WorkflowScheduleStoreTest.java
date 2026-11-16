package com.example.governance.workflow;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowScheduleStoreTest {
    @Test void createsOneRunPerScheduledTimeWithLocalBusinessDate() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db);
        long task = new TaskVersionStore(jdbc, tx).create("task", "SQL", "{}");
        var definitions = new WorkflowDefinitionStore(jdbc, tx);
        long workflow = definitions.create("daily", List.of(new WorkflowDefinitionStore.Node("a", task, 1)), List.of());
        var runs = new WorkflowRunStore(jdbc, tx, definitions);
        var schedules = new WorkflowScheduleStore(jdbc, tx, definitions, runs);
        Instant created = Instant.parse("2026-11-15T15:59:00Z");
        long id = schedules.create(workflow, 1, "0 0 0 * * *", "Asia/Shanghai", created);
        assertTrue(schedules.fireDue(id, created).isEmpty());
        long run = schedules.fireDue(id, Instant.parse("2026-11-15T16:00:00Z")).orElseThrow();
        assertEquals(java.time.LocalDate.of(2026, 11, 16), runs.snapshot(run).businessDate());
        assertTrue(schedules.fireDue(id, Instant.parse("2026-11-15T16:00:00Z")).isEmpty());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM workflow_runs", Integer.class));
    }
    @Test void rejectsInvalidCronAndZone() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db);
        var definitions = new WorkflowDefinitionStore(jdbc, tx);
        var schedules = new WorkflowScheduleStore(jdbc, tx, definitions, new WorkflowRunStore(jdbc, tx, definitions));
        assertThrows(IllegalArgumentException.class, () -> schedules.create(1, 1, "invalid", "Asia/Shanghai", Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> schedules.create(1, 1, "0 0 0 * * *", "invalid", Instant.now()));
    }
    @Test void concurrentFireClaimsProduceOnlyOneRun() throws Exception {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db);
        long task = new TaskVersionStore(jdbc, tx).create("task", "SQL", "{}");
        var defs = new WorkflowDefinitionStore(jdbc, tx);
        long workflow = defs.create("daily", List.of(new WorkflowDefinitionStore.Node("a", task, 1)), List.of());
        var schedules = new WorkflowScheduleStore(jdbc, tx, defs, new WorkflowRunStore(jdbc, tx, defs));
        var now = Instant.parse("2026-11-15T16:00:00Z");
        long id = schedules.create(workflow, 1, "0 0 0 * * *", "Asia/Shanghai", now.minusSeconds(60));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2); var start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<java.util.Optional<Long>> fire = () -> { start.await(); return schedules.fireDue(id, now); };
            var a = pool.submit(fire); var b = pool.submit(fire); start.countDown();
            assertEquals(1, (a.get().isPresent() ? 1 : 0) + (b.get().isPresent() ? 1 : 0));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM workflow_runs", Integer.class));
        } finally { pool.shutdownNow(); }
    }
}
