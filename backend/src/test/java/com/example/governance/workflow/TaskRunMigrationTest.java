package com.example.governance.workflow;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class TaskRunMigrationTest {
    @Test void existingTaskRunsGainFirstAttemptWithoutLosingStatusOrError() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).target(MigrationVersion.fromVersion("21")).load().migrate(); var jdbc = new JdbcTemplate(db);
        jdbc.update("INSERT INTO processing_tasks(id,name,task_type,current_version) VALUES (1,'job','SQL',1)");
        jdbc.update("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (1,1,'{}')");
        jdbc.update("INSERT INTO task_runs(id,task_id,task_version,business_date,state,created_at,started_at,finished_at,error_message) VALUES (1,1,1,DATE '2026-11-16','FAILED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'历史错误')");
        Flyway.configure().dataSource(db).load().migrate();
        assertEquals(1,jdbc.queryForObject("SELECT attempt_no FROM task_runs WHERE id=1",Integer.class));
        assertEquals("FAILED",jdbc.queryForObject("SELECT state FROM task_run_attempts WHERE run_id=1 AND attempt_no=1",String.class));
        assertEquals("历史错误",jdbc.queryForObject("SELECT error_message FROM task_run_attempts WHERE run_id=1 AND attempt_no=1",String.class));
    }
}
