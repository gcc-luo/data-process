package com.example.governance.mysql;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfSystemProperty(named="governance.mysql.test", matches="true")
class MySqlMigrationTest {
    @Test void migratesAndEnforcesReferentialIntegrity() throws Exception {
        String url = System.getProperty("governance.mysql.url"), user = System.getProperty("governance.mysql.user"), password = System.getProperty("governance.mysql.password", "");
        var flyway = Flyway.configure().dataSource(url,user,password).locations("classpath:db/mysql").load();
        flyway.migrate();
        try (var c = DriverManager.getConnection(url,user,password); var st = c.createStatement()) {
            try (var rs = st.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE")) { assertTrue(rs.next()); assertEquals(23,rs.getInt(1)); }
            assertThrows(java.sql.SQLException.class, () -> st.execute("INSERT INTO workflow_versions(workflow_id,version) VALUES (999999,1)"));
            var db = new org.springframework.jdbc.datasource.DriverManagerDataSource(url, user, password);
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(db);
            var tx = new org.springframework.jdbc.datasource.DataSourceTransactionManager(db);
            var tasks = new com.example.governance.workflow.TaskVersionStore(jdbc, tx);
            long task = tasks.create("mysql task", "SQL", "{}");
            var definitions = new com.example.governance.workflow.WorkflowDefinitionStore(jdbc, tx);
            long workflow = definitions.create("mysql flow", java.util.List.of(new com.example.governance.workflow.WorkflowDefinitionStore.Node("a", task, 1)), java.util.List.of());
            var runs = new com.example.governance.workflow.WorkflowRunStore(jdbc, tx, definitions);
            long run = runs.create(workflow, 1, java.time.LocalDate.of(2026, 11, 16));
            assertEquals(java.util.List.of("a"), runs.claimReady(run)); runs.finish(run, "a", true); assertEquals("SUCCEEDED", runs.status(run));
            assertTrue(runs.dueRetries(java.time.Instant.now(), 5).isEmpty());
            assertEquals(run, runs.list(workflow, "SUCCEEDED", java.time.LocalDate.of(2026, 11, 16), null, 10).get(0).id());
            jdbc.update("INSERT INTO audit_events(actor_id,occurred_at,action,object_type,object_id,result) VALUES (NULL,CURRENT_TIMESTAMP,'test.event','test','1','SUCCESS')");
            assertEquals("test.event", new com.example.governance.security.AuditLog(jdbc).list("test.event", null, 10).get(0).action());

        }
    }
}
