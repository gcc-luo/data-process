package com.example.governance.workflow;

import com.example.governance.security.AuditLog;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StandaloneTaskRetryTest {
    @Test void failedRunCanRetryWithPinnedVersionAndAttemptHistory() throws Exception {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate(); var jdbc = new JdbcTemplate(db); var manager = new DataSourceTransactionManager(db);
        var tasks = new TaskVersionStore(jdbc, manager); long task = tasks.create("task", "SQL", "{}");
        var jobs = new CopyOnWriteArrayList<Runnable>(); var executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(call -> { jobs.add(call.getArgument(0)); return null; }).when(executor).execute(any(Runnable.class));
        var runner = mock(CamelTaskRunner.class); doThrow(new IllegalStateException("sensitive")).doNothing().when(runner).execute(any(), any());
        var audit = mock(AuditLog.class); var service = new TaskRunService(jdbc, tasks, runner, executor, audit);
        var date = LocalDate.of(2026, 11, 16); var tx = new TransactionTemplate(manager);
        long run = tx.execute(status -> service.start(task, 1, date, null)); jobs.getFirst().run();
        assertEquals("FAILED", service.list(task).getFirst().state());
        tasks.addVersion(task, "{\"updated\":true}");
        tx.executeWithoutResult(status -> service.retry(task, run, null)); assertEquals(2, jobs.size());
        jobs.get(1).run(); var view = service.list(task).getFirst();
        assertEquals("SUCCEEDED", view.state()); assertEquals(1, view.taskVersion());
        assertEquals(java.util.List.of("FAILED", "SUCCEEDED"), view.attempts().stream().map(TaskRunService.Attempt::state).toList());
        assertEquals("任务执行失败", view.attempts().getFirst().errorMessage());
        verify(runner, times(2)).execute(argThat(version -> version.version() == 1), eq(date));
        verify(audit).success(null, "task.retry", "task_run", run);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> service.retry(task, run, null)));
        assertThrows(IllegalArgumentException.class, () -> tx.executeWithoutResult(status -> service.retry(task + 1, run, null)));
    }
    @Test void recoveryAndConcurrentRetryCannotRunAnOldAttemptTwice() throws Exception {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate(); var jdbc = new JdbcTemplate(db); var manager = new DataSourceTransactionManager(db);
        var tasks = new TaskVersionStore(jdbc, manager); long task = tasks.create("task", "SQL", "{}");
        var jobs = new CopyOnWriteArrayList<Runnable>(); var executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(call -> { jobs.add(call.getArgument(0)); return null; }).when(executor).execute(any(Runnable.class));
        var runner = mock(CamelTaskRunner.class); var service = new TaskRunService(jdbc, tasks, runner, executor, mock(AuditLog.class));
        long run = new TransactionTemplate(manager).execute(status -> service.start(task,1,LocalDate.now(),null));
        assertEquals(1, service.recover()); assertEquals("INTERRUPTED", service.list(task).getFirst().attempts().getFirst().state());
        var gate = new java.util.concurrent.CountDownLatch(1); var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> retry = () -> { gate.await(); try { new TransactionTemplate(manager).executeWithoutResult(status -> service.retry(task,run,null)); return true; } catch (IllegalStateException ex) { return false; } };
            var a = pool.submit(retry); var b = pool.submit(retry); gate.countDown();
            assertEquals(1, (a.get() ? 1 : 0) + (b.get() ? 1 : 0));
            assertEquals(2,jobs.size()); jobs.getFirst().run();
            assertEquals("PENDING",service.list(task).getFirst().state()); verifyNoInteractions(runner);
            jobs.get(1).run(); var view = service.list(task).getFirst();
            assertEquals("SUCCEEDED",view.state()); assertEquals(java.util.List.of("INTERRUPTED","SUCCEEDED"),view.attempts().stream().map(TaskRunService.Attempt::state).toList());
            verify(runner,times(1)).execute(any(),any());
        } finally { pool.shutdownNow(); }
    }
}
