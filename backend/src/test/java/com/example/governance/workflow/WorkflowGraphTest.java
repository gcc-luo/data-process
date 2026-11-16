package com.example.governance.workflow;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowGraphTest {
    @Test void findsReadyTasksOnlyAfterAllDependenciesSucceed() {
        var graph = new WorkflowGraph(Set.of("customers", "orders", "summary"),
                List.of(new WorkflowGraph.Edge("customers", "summary"), new WorkflowGraph.Edge("orders", "summary")));
        assertEquals(Set.of("customers", "orders"), graph.ready(Set.of(), Set.of()));
        assertEquals(Set.of("orders"), graph.ready(Set.of("customers"), Set.of()));
        assertEquals(Set.of("summary"), graph.ready(Set.of("customers", "orders"), Set.of()));
    }

    @Test void rejectsCyclesAndUnknownTasks() {
        assertThrows(IllegalArgumentException.class, () -> new WorkflowGraph(Set.of("a", "b"),
                List.of(new WorkflowGraph.Edge("a", "b"), new WorkflowGraph.Edge("b", "a"))));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowGraph(Set.of("a"),
                List.of(new WorkflowGraph.Edge("a", "missing"))));
    }
}
