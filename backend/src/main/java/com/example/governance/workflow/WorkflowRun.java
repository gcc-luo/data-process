package com.example.governance.workflow;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** In-memory state transitions; persistence and execution are separate responsibilities. */
public final class WorkflowRun {
    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED }
    private final WorkflowGraph graph;
    private final Map<String, Status> tasks = new HashMap<>();

    public WorkflowRun(WorkflowGraph graph, Set<String> taskIds) {
        if (graph == null || taskIds == null || !graph.tasks().equals(taskIds))
            throw new IllegalArgumentException("Task IDs must match the workflow graph");
        this.graph = graph;
        taskIds.forEach(id -> tasks.put(id, Status.PENDING));
    }

    public synchronized void start(String id) {
        require(id, Status.PENDING);
        Set<String> succeeded = ids(Status.SUCCEEDED);
        Set<String> started = tasks.entrySet().stream().filter(e -> e.getValue() != Status.PENDING)
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        if (!graph.ready(succeeded, started).contains(id)) throw new IllegalStateException("Dependencies not satisfied");
        tasks.put(id, Status.RUNNING);
    }

    public synchronized void succeed(String id) { require(id, Status.RUNNING); tasks.put(id, Status.SUCCEEDED); }
    public synchronized void fail(String id) { require(id, Status.RUNNING); tasks.put(id, Status.FAILED); }
    public synchronized void retry(String id) { require(id, Status.FAILED); tasks.put(id, Status.PENDING); }
    public synchronized Status status() {
        if (tasks.containsValue(Status.RUNNING)) return Status.RUNNING;
        if (tasks.containsValue(Status.FAILED)) return Status.FAILED;
        if (tasks.values().stream().allMatch(s -> s == Status.SUCCEEDED)) return Status.SUCCEEDED;
        return Status.PENDING;
    }
    private void require(String id, Status expected) {
        if (tasks.get(id) != expected) throw new IllegalStateException("Invalid task state");
    }
    private Set<String> ids(Status status) {
        return tasks.entrySet().stream().filter(e -> e.getValue() == status).map(Map.Entry::getKey).collect(Collectors.toSet());
    }
}
