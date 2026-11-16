package com.example.governance.workflow;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowRunStoreTest {
    @Test void claimsOnlyReadyNodesAndRecordsFailure() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db);
        long task = new TaskVersionStore(jdbc, tx).create("task", "SQL", "{}");
        var definitions = new WorkflowDefinitionStore(jdbc, tx);
        long workflow = definitions.create("daily", List.of(new WorkflowDefinitionStore.Node("a", task, 1), new WorkflowDefinitionStore.Node("b", task, 1)), List.of(new WorkflowGraph.Edge("a", "b")));
        var runs = new WorkflowRunStore(jdbc, tx, definitions);
        long run = runs.create(workflow, 1, LocalDate.of(2026, 11, 16));
        assertEquals(List.of("a"), runs.claimReady(run));
        assertTrue(runs.claimReady(run).isEmpty());
        runs.finish(run, "a", true);
        assertEquals(List.of("b"), runs.claimReady(run));
        runs.finish(run, "b", false);
        assertEquals("FAILED", runs.status(run));
    }
    @Test void concurrentClaimsDoNotStartTheSameNodeTwice() throws Exception {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db);
        long task = new TaskVersionStore(jdbc, tx).create("task", "SQL", "{}");
        var definitions = new WorkflowDefinitionStore(jdbc, tx);
        long workflow = definitions.create("daily", List.of(new WorkflowDefinitionStore.Node("a", task, 1)), List.of());
        var runs = new WorkflowRunStore(jdbc, tx, definitions);
        long run = runs.create(workflow, 1, LocalDate.of(2026, 11, 16));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<List<String>> claim = () -> { start.await(); return runs.claimReady(run); };
            var first = pool.submit(claim); var second = pool.submit(claim); start.countDown();
            assertEquals(1, first.get().size() + second.get().size());
        } finally { pool.shutdownNow(); }
    }
    @Test void runningInstancesAreMarkedInterruptedOnRecovery() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db);
        long task = new TaskVersionStore(jdbc, tx).create("task", "SQL", "{}");
        var definitions = new WorkflowDefinitionStore(jdbc, tx);
        long workflow = definitions.create("daily", List.of(new WorkflowDefinitionStore.Node("a", task, 1)), List.of());
        var runs = new WorkflowRunStore(jdbc, tx, definitions);
        long run = runs.create(workflow, 1, LocalDate.of(2026, 11, 16));
        runs.claimReady(run);
        assertEquals(1, runs.markInterrupted());
        assertEquals("INTERRUPTED", runs.status(run));
        assertTrue(runs.claimReady(run).isEmpty());
    }
    @Test void retriesFailedNodeWithoutRepeatingSuccessfulPredecessor() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db);
        long task = new TaskVersionStore(jdbc, tx).create("task", "SQL", "{}");
        var definitions = new WorkflowDefinitionStore(jdbc, tx);
        long workflow = definitions.create("daily", List.of(new WorkflowDefinitionStore.Node("a", task, 1), new WorkflowDefinitionStore.Node("b", task, 1)), List.of(new WorkflowGraph.Edge("a", "b")));
        var runs = new WorkflowRunStore(jdbc, tx, definitions);
        long run = runs.create(workflow, 1, LocalDate.of(2026, 11, 16));
        runs.claimReady(run); runs.finish(run, "a", true);
        runs.claimReady(run); runs.finish(run, "b", false);
        runs.retryFailed(run);
        assertEquals(List.of("b"), runs.claimReady(run));
        runs.finish(run, "b", true);
        assertEquals("SUCCEEDED", runs.status(run));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM workflow_node_attempts WHERE run_id=? AND node_key='b'", Integer.class, run));
    }
    @Test void automaticRetryWaitsUntilDueAndPreservesAttemptHistory() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db);
        long task = new TaskVersionStore(jdbc, tx).create("task", "SQL", "{}");
        var definitions = new WorkflowDefinitionStore(jdbc, tx);
        long workflow = definitions.create("daily", List.of(new WorkflowDefinitionStore.Node("a", task, 1)), List.of());
        var runs = new WorkflowRunStore(jdbc, tx, definitions);
        long run = runs.create(workflow, 1, LocalDate.of(2026, 11, 16));
        runs.claimReady(run); runs.finish(run, "a", false);
        var now = java.time.Instant.parse("2026-11-16T00:00:00Z");
        assertTrue(runs.scheduleRetry(run, "a", 1, 30, now));
        assertEquals("WAITING", runs.status(run));
        assertTrue(runs.dueRetries(now.plusSeconds(29), 10).isEmpty());
        assertEquals(List.of(run), runs.dueRetries(now.plusSeconds(30), 10));
        assertTrue(runs.activateDueRetries(run, now.plusSeconds(30)));
        assertEquals(List.of("a"), runs.claimReady(run)); runs.finish(run, "a", false);
        assertFalse(runs.scheduleRetry(run, "a", 1, 30, now.plusSeconds(30)));
        assertEquals("FAILED", runs.status(run));
    }
    @Test void listsRunsByWorkflowStateAndDateWithBoundedPagination() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db);
        long task = new TaskVersionStore(jdbc, tx).create("task", "SQL", "{}");
        var definitions = new WorkflowDefinitionStore(jdbc, tx);
        long a = definitions.create("a", List.of(new WorkflowDefinitionStore.Node("n",task,1)), List.of());
        long b = definitions.create("b", List.of(new WorkflowDefinitionStore.Node("n",task,1)), List.of());
        var runs = new WorkflowRunStore(jdbc, tx, definitions);
        long first = runs.create(a,1,LocalDate.of(2026,11,15));
        long second = runs.create(a,1,LocalDate.of(2026,11,16));
        runs.create(b,1,LocalDate.of(2026,11,16));
        runs.claimReady(second); runs.finish(second,"n",false);
        assertEquals(List.of(second), runs.list(a,"FAILED",LocalDate.of(2026,11,16),null,10).stream().map(WorkflowRunStore.RunSummary::id).toList());
        assertEquals(List.of(first), runs.list(a,null,null,second,10).stream().map(WorkflowRunStore.RunSummary::id).toList());
        assertThrows(IllegalArgumentException.class, () -> runs.list(a,"INVALID",null,null,10));
        assertThrows(IllegalArgumentException.class, () -> runs.list(a,null,null,null,101));
    }
}
