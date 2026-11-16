package com.example.governance.workflow;
import com.example.governance.security.AuditLog;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class TaskConcurrencyIntegrationTest {
    @Test void independentAndWorkflowTasksShareConfiguredExecutionCapacity() throws Exception {
        var db = new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate(); var jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db);
        var tasks = new TaskVersionStore(jdbc,tx); long task=tasks.create("task","SQL","{}");
        var defs=new WorkflowDefinitionStore(jdbc,tx); long flow=defs.create("flow",List.of(new WorkflowDefinitionStore.Node("a",task,1)),List.of());
        var runs=new WorkflowRunStore(jdbc,tx,defs); long run=runs.create(flow,1,LocalDate.now());
        var started=new CountDownLatch(1); var release=new CountDownLatch(1); var active=new AtomicInteger();
        var runner=mock(CamelTaskRunner.class); doAnswer(inv -> { active.incrementAndGet(); started.countDown(); if(!release.await(3,TimeUnit.SECONDS)) throw new IllegalStateException(); return null; }).when(runner).execute(any(),any());
        var limiter=new TaskExecutionLimiter(1); var executor=new ThreadPoolTaskExecutor(); executor.setCorePoolSize(1); executor.initialize();
        var tasksService=new TaskRunService(jdbc,tasks,runner,executor,mock(AuditLog.class),limiter);
        var workflow=new WorkflowWorker(runs,defs,tasks,runner,limiter); var worker=Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(tx).executeWithoutResult(status -> tasksService.start(task,1,LocalDate.now(),null));
            assertTrue(started.await(3,TimeUnit.SECONDS)); var pending=worker.submit(() -> workflow.process(run));
            Thread.sleep(150); assertEquals(1,active.get()); release.countDown(); pending.get(3,TimeUnit.SECONDS);
            assertEquals(2,active.get()); assertEquals("SUCCEEDED",runs.status(run));
        } finally { release.countDown(); executor.shutdown(); worker.shutdownNow(); }
    }
}
