package com.example.governance.workflow;

import java.util.*;

/** Immutable dependency graph for a single workflow definition. */
public final class WorkflowGraph {
    public record Edge(String from, String to) {}
    private final Set<String> tasks;
    private final Map<String, Set<String>> predecessors;

    public WorkflowGraph(Set<String> tasks, List<Edge> edges) {
        if (tasks == null || tasks.isEmpty()) throw new IllegalArgumentException("Workflow must have tasks");
        this.tasks = Set.copyOf(tasks);
        this.predecessors = new HashMap<>();
        for (String task : this.tasks) predecessors.put(task, new HashSet<>());
        for (Edge edge : edges) {
            if (!this.tasks.contains(edge.from()) || !this.tasks.contains(edge.to()))
                throw new IllegalArgumentException("Edge references an unknown task");
            predecessors.get(edge.to()).add(edge.from());
        }
        Set<String> visited = new HashSet<>();
        while (true) {
            Set<String> next = ready(visited, Set.of());
            if (next.isEmpty()) break;
            visited.addAll(next);
        }
        if (visited.size() != this.tasks.size()) throw new IllegalArgumentException("Workflow contains a cycle");
    }

    public Set<String> tasks() { return tasks; }

    public Set<String> ready(Set<String> succeeded, Set<String> started) {
        Set<String> result = new HashSet<>();
        for (String task : tasks)
            if (!succeeded.contains(task) && !started.contains(task) && succeeded.containsAll(predecessors.get(task))) result.add(task);
        return Set.copyOf(result);
    }
}
