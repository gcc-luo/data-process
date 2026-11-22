package com.example.governance.workflow;

import com.example.governance.security.AuditLog;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DispatchRejectionTest {
    @Test void taskQueueRejectionPersistsInterruptedAfterOuterCommit() throws Exception {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""); Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db); var tasks = new TaskVersionStore(jdbc, tx);
        long task = OrchFixtures.published(tasks, "task", "{}");
        var executor = mock(ThreadPoolTaskExecutor.class); doThrow(new java.util.concurrent.RejectedExecutionException()).when(executor).execute(any(Runnable.class));
        var service = new TaskRunService(jdbc, tasks, mock(CamelTaskRunner.class), executor, mock(AuditLog.class));
        long id = new TransactionTemplate(tx).execute(status -> service.start(task, 1, LocalDate.now(), null));
        assertEquals("INTERRUPTED", jdbc.queryForObject("SELECT state FROM task_runs WHERE id=?", String.class, id));
    }
    @Test void workflowQueueRejectionPersistsInterruptedAfterOuterCommit() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""); Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db); var tasks = new TaskVersionStore(jdbc, tx);
        long task = OrchFixtures.published(tasks, "task", "{}"); var defs = new WorkflowDefinitionStore(jdbc, tx);
        long workflow = defs.create("flow", List.of(new WorkflowDefinitionStore.Node("n", task, 1)), List.of());
        var runs = new WorkflowRunStore(jdbc, tx, defs);
        var dispatcher = new WorkflowDispatcher(runs, mock(WorkflowWorker.class), runnable -> { throw new java.util.concurrent.RejectedExecutionException(); });
        long id = new TransactionTemplate(tx).execute(status -> {
            long run = runs.create(workflow, 1, LocalDate.now());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { try { dispatcher.dispatch(run); } catch (RuntimeException ignored) {} } });
            return run;
        });
        assertEquals("INTERRUPTED", runs.status(id));
    }
    @Test void taskClaimFailureMarksInstanceInterrupted() throws Exception {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""); Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db); var tasks = new TaskVersionStore(jdbc, tx);
        long task = OrchFixtures.published(tasks, "task", "{}");
        var executor = mock(ThreadPoolTaskExecutor.class); var submitted = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        doAnswer(invocation -> { submitted.set(invocation.getArgument(0)); return null; }).when(executor).execute(any(Runnable.class));
        var failingJdbc = spy(jdbc);
        doThrow(new org.springframework.dao.TransientDataAccessResourceException("temporary failure")).when(failingJdbc).update(eq("UPDATE task_runs SET state='RUNNING',started_at=CURRENT_TIMESTAMP WHERE id=? AND state='PENDING' AND attempt_no=?"), anyLong(), anyInt());
        var service = new TaskRunService(failingJdbc, tasks, mock(CamelTaskRunner.class), executor, mock(AuditLog.class));
        long id = new TransactionTemplate(tx).execute(status -> service.start(task, 1, LocalDate.now(), null));
        assertDoesNotThrow(() -> submitted.get().run());
        assertEquals("INTERRUPTED", jdbc.queryForObject("SELECT state FROM task_runs WHERE id=?", String.class, id));
    }
}
