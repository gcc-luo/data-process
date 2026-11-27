package com.example.governance.workflow;

import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;

/** Explicitly invoked worker; scheduler and external execution adapters are configured separately. */
public final class WorkflowWorker {
    @FunctionalInterface public interface TaskRunner {
        void execute(TaskVersionStore.Version task, LocalDate businessDate) throws Exception;
        /** 支持取消/日志的实现必须 override 四参；default 委托两参会丢弃 cancel 与 sinks（见 RealRunnerCancelPropagationTest）。 */
        default void execute(TaskVersionStore.Version task, LocalDate businessDate, RunCancelContext cancel, ExecutionSinks sinks) throws Exception { execute(task, businessDate); }
    }
    private final TaskExecutionLimiter limiter;
    private final WorkflowRunStore runs;
    private final WorkflowDefinitionStore definitions;
    private final TaskVersionStore tasks;
    private final TaskRunner runner;
    private final java.time.Clock clock;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    public WorkflowWorker(WorkflowRunStore runs, WorkflowDefinitionStore definitions, TaskVersionStore tasks, TaskRunner runner) {
        this(runs, definitions, tasks, runner, java.time.Clock.systemUTC(), new TaskExecutionLimiter(8));
    }
    public WorkflowWorker(WorkflowRunStore runs, WorkflowDefinitionStore definitions, TaskVersionStore tasks, TaskRunner runner, TaskExecutionLimiter limiter) {
        this(runs, definitions, tasks, runner, java.time.Clock.systemUTC(), limiter);
    }
    WorkflowWorker(WorkflowRunStore runs, WorkflowDefinitionStore definitions, TaskVersionStore tasks, TaskRunner runner, java.time.Clock clock) {
        this(runs, definitions, tasks, runner, clock, new TaskExecutionLimiter(8));
    }
    private WorkflowWorker(WorkflowRunStore runs, WorkflowDefinitionStore definitions, TaskVersionStore tasks, TaskRunner runner, java.time.Clock clock, TaskExecutionLimiter limiter) {
        this.runs = runs; this.definitions = definitions; this.tasks = tasks; this.runner = runner; this.clock = clock; this.limiter = java.util.Objects.requireNonNull(limiter);
    }
    public void process(long runId) {
        var snapshot = runs.snapshot(runId);
        var definition = definitions.get(snapshot.workflowId(), snapshot.workflowVersion()).orElseThrow();
        Map<String, WorkflowDefinitionStore.Node> nodes = definition.nodes().stream().collect(Collectors.toMap(WorkflowDefinitionStore.Node::key, n -> n));
        while (true) {
            var ready = runs.claimReady(runId);
            if (ready.isEmpty()) return;
            var branches = java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, ready.size()));
            try {
                var futures = ready.stream().map(key -> branches.submit(() -> {
                    try (var permit = limiter.acquire()) { executeNode(runId, snapshot.businessDate(), key, nodes.get(key)); }
                    return null;
                })).toList();
                for (var future : futures) future.get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Worker interrupted", ex);
            } catch (java.util.concurrent.ExecutionException ex) {
                throw new IllegalStateException("Branch execution failed", ex.getCause());
            } finally {
                branches.shutdown();
            }

        }
    }
    private void executeNode(long runId, LocalDate businessDate, String key, WorkflowDefinitionStore.Node node) throws InterruptedException {
        boolean success; TaskVersionStore.Version task = null;
        try {
            task = tasks.findVersion(node.taskId(), node.taskVersion()).orElseThrow();
            var logSink = runs.nodeLogSink(runId, key);
            runner.execute(task, businessDate, new RunCancelContext(), new ExecutionSinks(logSink, runs.nodeStatsSink(runId, key, logSink)));
            success = true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ex;
        } catch (Exception ex) {
            success = false;
        }
        runs.finish(runId, key, success);
        if (!success && task != null) scheduleRetry(runId, key, task);
    }
    private void scheduleRetry(long runId, String key, TaskVersionStore.Version task) {
        try {
            var retry = mapper.readTree(task.configuration()).path("retry");
            if (!retry.isObject() || !retry.path("safe").isBoolean() || !retry.path("safe").booleanValue()) return;
            var max = retry.path("maxRetries"); var delay = retry.path("delaySeconds");
            if (!max.isInt() || !delay.isInt() || max.intValue() < 1 || max.intValue() > 3 || delay.intValue() < 1 || delay.intValue() > 3600) return;
            runs.scheduleRetry(runId, key, max.intValue(), delay.intValue(), clock.instant());
        } catch (java.io.IOException | NullPointerException ignored) { /* Invalid saved policy never enables retries. */ }
    }
}
