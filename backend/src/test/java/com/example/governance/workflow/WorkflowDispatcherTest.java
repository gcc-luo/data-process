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

class WorkflowDispatcherTest {
    @Test void dispatchesPinnedSqlToDatabase() throws Exception {
        var metadata = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var business = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(metadata).load().migrate();
        new JdbcTemplate(business).execute("CREATE TABLE output_data (business_day DATE)");
        var jdbc = new JdbcTemplate(metadata); var tx = new DataSourceTransactionManager(metadata);
        var tasks = new TaskVersionStore(jdbc, tx);
        long task = OrchFixtures.published(tasks, "daily", OrchFixtures.dml(1, "INSERT INTO output_data(business_day) VALUES (:business_date)"));
        var definitions = new WorkflowDefinitionStore(jdbc, tx);
        long workflow = definitions.create("daily", List.of(new WorkflowDefinitionStore.Node("a", task, 1)), List.of());
        var runs = new WorkflowRunStore(jdbc, tx, definitions);
        long run = runs.create(workflow, 1, LocalDate.of(2026, 11, 16));
        try (var runner = new CamelTaskRunner(id -> business)) {
            var worker = new WorkflowWorker(runs, definitions, tasks, runner);
            new WorkflowDispatcher(runs, worker, Runnable::run).dispatch(run);
        }
        assertEquals("SUCCEEDED", runs.status(run));
        assertEquals(LocalDate.of(2026, 11, 16), new JdbcTemplate(business).queryForObject("SELECT business_day FROM output_data", LocalDate.class));
    }
}
