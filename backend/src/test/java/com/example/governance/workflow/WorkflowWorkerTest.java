package com.example.governance.workflow;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowWorkerTest {
    @Test void executesDependenciesUsingPinnedVersionsAndRecordsFailure() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db);
        var tasks = new TaskVersionStore(jdbc, tx);
        long first = tasks.create("first", "SQL", "{\"sql\":\"old\"}");
        long second = tasks.create("second", "SQL", "{\"sql\":\"fail\"}");
        var definitions = new WorkflowDefinitionStore(jdbc, tx);
        long workflow = definitions.create("daily", List.of(new WorkflowDefinitionStore.Node("a", first, 1), new WorkflowDefinitionStore.Node("b", second, 1)), List.of(new WorkflowGraph.Edge("a", "b")));
        var runs = new WorkflowRunStore(jdbc, tx, definitions);
        long run = runs.create(workflow, 1, LocalDate.of(2026, 11, 16));
        tasks.addVersion(first, "{\"sql\":\"new\"}");
        var seen = new ArrayList<String>();
        var worker = new WorkflowWorker(runs, definitions, tasks, (task, date) -> {
            seen.add(task.configuration()); assertEquals(LocalDate.of(2026, 11, 16), date);
            if (task.configuration().contains("fail")) throw new IllegalStateException("sensitive database detail");
        });
        worker.process(run);
        assertEquals(List.of("{\"sql\":\"old\"}", "{\"sql\":\"fail\"}"), seen);
        assertEquals("FAILED", runs.status(run));
        assertEquals("任务执行失败", jdbc.queryForObject("SELECT error_message FROM workflow_node_attempts WHERE run_id=? AND node_key='b'", String.class, run));
    }
    @Test void safeRetryIsPersistedAndCanResumeAfterDelay() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate(); var jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db);
        var tasks = new TaskVersionStore(jdbc, tx);
        long task = tasks.create("task", "SQL", "{\"retry\":{\"safe\":true,\"maxRetries\":1,\"delaySeconds\":30}}");
        var definitions = new WorkflowDefinitionStore(jdbc, tx);
        long workflow = definitions.create("daily", List.of(new WorkflowDefinitionStore.Node("a", task, 1)), List.of());
        var runs = new WorkflowRunStore(jdbc, tx, definitions); long run = runs.create(workflow, 1, LocalDate.of(2026, 11, 16));
        var now = java.time.Instant.parse("2026-11-16T00:00:00Z"); var count = new java.util.concurrent.atomic.AtomicInteger();
        var worker = new WorkflowWorker(runs, definitions, tasks, (version, date) -> { if (count.getAndIncrement() == 0) throw new IllegalStateException(); }, java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
        worker.process(run); assertEquals("WAITING", runs.status(run));
        assertTrue(runs.activateDueRetries(run, now.plusSeconds(30))); worker.process(run);
        assertEquals("SUCCEEDED", runs.status(run)); assertEquals(2, count.get());
    }
    @Test void independentBranchesRunConcurrentlyBeforeTheirJoin() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate(); var jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db);
        var tasks = new TaskVersionStore(jdbc, tx);
        long first = tasks.create("first", "SQL", "{}"); long second = tasks.create("second", "SQL", "{}"); long join = tasks.create("join", "SQL", "{}");
        var definitions = new WorkflowDefinitionStore(jdbc, tx);
        long workflow = definitions.create("parallel", List.of(new WorkflowDefinitionStore.Node("a", first, 1), new WorkflowDefinitionStore.Node("b", second, 1), new WorkflowDefinitionStore.Node("c", join, 1)), List.of(new WorkflowGraph.Edge("a", "c"), new WorkflowGraph.Edge("b", "c")));
        var runs = new WorkflowRunStore(jdbc, tx, definitions); long run = runs.create(workflow, 1, LocalDate.now());
        var started = new java.util.concurrent.CountDownLatch(2); var completed = new java.util.concurrent.atomic.AtomicInteger();
        var worker = new WorkflowWorker(runs, definitions, tasks, (task, date) -> {
            if (task.taskId() == join) { if (completed.get() != 2) throw new IllegalStateException("join before branches"); return; }
            started.countDown(); if (!started.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("branches did not run concurrently"); completed.incrementAndGet();
        });
        worker.process(run); assertEquals("SUCCEEDED", runs.status(run));
    }
    @Test void limitsConcurrentBranchesAcrossWorkersSharingALimiter() throws Exception {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate(); var jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db);
        var tasks = new TaskVersionStore(jdbc, tx); long task = tasks.create("task", "SQL", "{}");
        var definitions = new WorkflowDefinitionStore(jdbc, tx); long flow = definitions.create("flow", List.of(new WorkflowDefinitionStore.Node("a",task,1)), List.of());
        var runs = new WorkflowRunStore(jdbc, tx, definitions); long one = runs.create(flow,1,LocalDate.now()), two = runs.create(flow,1,LocalDate.now());
        var firstStarted = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1); var entered = new java.util.concurrent.atomic.AtomicInteger();
        WorkflowWorker.TaskRunner runner = (version, date) -> { entered.incrementAndGet(); firstStarted.countDown(); if (!release.await(3, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException(); };
        var limiter = new TaskExecutionLimiter(1);
        var first = new WorkflowWorker(runs, definitions, tasks, runner, limiter); var second = new WorkflowWorker(runs, definitions, tasks, runner, limiter);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var f1 = pool.submit(() -> first.process(one)); assertTrue(firstStarted.await(3, java.util.concurrent.TimeUnit.SECONDS));
            var f2 = pool.submit(() -> second.process(two));
            Thread.sleep(150); assertEquals(1, entered.get()); release.countDown(); f1.get(); f2.get();
            assertEquals(2, entered.get()); assertEquals("SUCCEEDED", runs.status(one)); assertEquals("SUCCEEDED", runs.status(two));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
