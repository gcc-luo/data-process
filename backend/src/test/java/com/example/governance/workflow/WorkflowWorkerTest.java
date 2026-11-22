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
    /** 带标记的最小 ORCH JSON：lambda runner 只读 configuration 字符串，顶层额外字段被 OrchDefinition.parse 忽略。 */
    private static String orch(String marker) { return "{\"schemaVersion\":1,\"nodes\":[],\"edges\":[],\"marker\":\"" + marker + "\"}"; }

    @Test void executesDependenciesUsingPinnedVersionsAndRecordsFailure() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db);
        var tasks = new TaskVersionStore(jdbc, tx);
        long first = OrchFixtures.published(tasks, "first", orch("old"));
        long second = OrchFixtures.published(tasks, "second", orch("fail"));
        var definitions = new WorkflowDefinitionStore(jdbc, tx);
        long workflow = definitions.create("daily", List.of(new WorkflowDefinitionStore.Node("a", first, 1), new WorkflowDefinitionStore.Node("b", second, 1)), List.of(new WorkflowGraph.Edge("a", "b")));
        var runs = new WorkflowRunStore(jdbc, tx, definitions);
        long run = runs.create(workflow, 1, LocalDate.of(2026, 11, 16));
        tasks.addVersion(first, orch("new"));
        var seen = new ArrayList<String>();
        var worker = new WorkflowWorker(runs, definitions, tasks, (task, date) -> {
            seen.add(task.configuration()); assertEquals(LocalDate.of(2026, 11, 16), date);
            if (task.configuration().contains("fail")) throw new IllegalStateException("sensitive database detail");
        });
        worker.process(run);
        assertEquals(List.of(orch("old"), orch("fail")), seen);
        assertEquals("FAILED", runs.status(run));
        assertEquals("任务执行失败", jdbc.queryForObject("SELECT error_message FROM workflow_node_attempts WHERE run_id=? AND node_key='b'", String.class, run));
    }
    @Test void safeRetryIsPersistedAndCanResumeAfterDelay() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate(); var jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db);
        var tasks = new TaskVersionStore(jdbc, tx);
        // WorkflowWorker 直接读原始 JSON 的顶层 retry；OrchDefinition.parse 忽略该字段
        long task = OrchFixtures.published(tasks, "task", "{\"schemaVersion\":1,\"nodes\":[],\"edges\":[],\"retry\":{\"safe\":true,\"maxRetries\":1,\"delaySeconds\":30}}");
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
        long first = OrchFixtures.published(tasks, "first", "{}"); long second = OrchFixtures.published(tasks, "second", "{}"); long join = OrchFixtures.published(tasks, "join", "{}");
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
        var tasks = new TaskVersionStore(jdbc, tx); long task = OrchFixtures.published(tasks, "task", "{}");
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
