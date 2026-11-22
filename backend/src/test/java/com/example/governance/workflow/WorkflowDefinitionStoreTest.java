package com.example.governance.workflow;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowDefinitionStoreTest {
    @Test void savesDependenciesAndPinsTaskVersions() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db);
        var transaction = new DataSourceTransactionManager(db);
        var tasks = new TaskVersionStore(jdbc, transaction);
        long first = OrchFixtures.published(tasks, "first", "{\"sql\":\"one\"}");
        long second = OrchFixtures.published(tasks, "second", "{\"sql\":\"two\"}");
        var store = new WorkflowDefinitionStore(jdbc, transaction);
        var nodes = List.of(new WorkflowDefinitionStore.Node("a", first, 1, 120.5, 42.0), new WorkflowDefinitionStore.Node("b", second, 1, 350.0, 200.0));
        long workflow = store.create("daily", nodes, List.of(new WorkflowGraph.Edge("a", "b")));
        tasks.addVersion(first, "{\"sql\":\"changed\"}");
        var saved = store.get(workflow, 1).orElseThrow();
        assertEquals(nodes, saved.nodes());
        assertEquals(List.of(new WorkflowGraph.Edge("a", "b")), saved.edges());
        assertEquals(1, saved.nodes().getFirst().taskVersion());
        var revised = List.of(new WorkflowDefinitionStore.Node("a", first, 2));
        assertEquals(2, store.addVersion(workflow, revised, List.of()));
        assertEquals(nodes, store.get(workflow, 1).orElseThrow().nodes());
        assertEquals(revised, store.get(workflow, 2).orElseThrow().nodes());
    }
    @Test void rejectsCyclesWithoutSavingPartialWorkflow() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db);
        var tx = new DataSourceTransactionManager(db);
        long task = new TaskVersionStore(jdbc, tx).create("task", "ORCH", "{}");
        var store = new WorkflowDefinitionStore(jdbc, tx);
        var nodes = List.of(new WorkflowDefinitionStore.Node("a", task, 1), new WorkflowDefinitionStore.Node("b", task, 1));
        assertThrows(IllegalArgumentException.class, () -> store.create("bad", nodes, List.of(new WorkflowGraph.Edge("a", "b"), new WorkflowGraph.Edge("b", "a"))));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM workflow_definitions", Integer.class));
    }
}
