package com.example.governance.workflow;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowRunTest {
    @Test void dependentTaskStartsOnlyAfterEveryPredecessorSucceeds() {
        var graph = new WorkflowGraph(Set.of("a", "b", "c"), List.of(
                new WorkflowGraph.Edge("a", "c"), new WorkflowGraph.Edge("b", "c")));
        var run = new WorkflowRun(graph, Set.of("a", "b", "c"));
        run.start("a"); run.start("b"); run.succeed("a");
        assertThrows(IllegalStateException.class, () -> run.start("c"));
        run.succeed("b"); run.start("c"); run.succeed("c");
        assertEquals(WorkflowRun.Status.SUCCEEDED, run.status());
    }

    @Test void failedTaskBlocksDependentsUntilExplicitRetry() {
        var graph = new WorkflowGraph(Set.of("a", "b"), List.of(new WorkflowGraph.Edge("a", "b")));
        var run = new WorkflowRun(graph, Set.of("a", "b"));
        run.start("a"); run.fail("a");
        assertEquals(WorkflowRun.Status.FAILED, run.status());
        assertThrows(IllegalStateException.class, () -> run.start("b"));
        run.retry("a"); run.start("a"); run.succeed("a"); run.start("b"); run.succeed("b");
        assertEquals(WorkflowRun.Status.SUCCEEDED, run.status());
    }
    @Test void rejectsTaskSetsThatDoNotMatchTheGraph() {
        var graph = new WorkflowGraph(Set.of("a", "b"), List.of());
        assertThrows(IllegalArgumentException.class, () -> new WorkflowRun(graph, Set.of("a")));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowRun(graph, Set.of("a", "b", "c")));
    }

    @Test void rejectsAnEmptyWorkflow() {
        assertThrows(IllegalArgumentException.class, () -> new WorkflowGraph(Set.of(), List.of()));
    }
}
