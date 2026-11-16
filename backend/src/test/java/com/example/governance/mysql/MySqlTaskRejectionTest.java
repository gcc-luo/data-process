package com.example.governance.mysql;
import com.example.governance.security.AuditLog;
import com.example.governance.workflow.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@EnabledIfSystemProperty(named="governance.mysql.test",matches="true")
class MySqlTaskRejectionTest {
    @Test void rejectedDispatchPersistsRunAndAttemptInSameState() throws Exception {
        var db = new DriverManagerDataSource(System.getProperty("governance.mysql.url"),System.getProperty("governance.mysql.user"),System.getProperty("governance.mysql.password", ""));
        Flyway.configure().dataSource(db).locations("classpath:db/mysql").load().migrate(); var jdbc = new JdbcTemplate(db); var manager = new DataSourceTransactionManager(db);
        var tasks = new TaskVersionStore(jdbc,manager); long task=tasks.create("rejected", "SQL", "{}");
        var executor=mock(ThreadPoolTaskExecutor.class); doThrow(new java.util.concurrent.RejectedExecutionException()).when(executor).execute(any(Runnable.class));
        var service=new TaskRunService(jdbc,tasks,mock(CamelTaskRunner.class),executor,mock(AuditLog.class));
        long id=new TransactionTemplate(manager).execute(status -> service.start(task,1,LocalDate.now(),null));
        assertEquals("INTERRUPTED",jdbc.queryForObject("SELECT state FROM task_runs WHERE id=?",String.class,id));
        assertEquals("INTERRUPTED",jdbc.queryForObject("SELECT state FROM task_run_attempts WHERE run_id=? AND attempt_no=1",String.class,id));
    }
}
