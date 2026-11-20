package com.example.governance.workflow;

import org.springframework.context.annotation.Bean;
import com.example.governance.assets.DataSourceStore;
import com.example.governance.assets.ExecutionDataSourceProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@EnableScheduling
public class WorkflowConfiguration {
    @Bean TaskVersionStore taskVersionStore(JdbcTemplate jdbc, PlatformTransactionManager tx) { return new TaskVersionStore(jdbc, tx); }
    @Bean TaskDirectoryStore taskDirectoryStore(JdbcTemplate jdbc, PlatformTransactionManager tx) { return new TaskDirectoryStore(jdbc, tx); }
    @Bean WorkflowDefinitionStore workflowDefinitionStore(JdbcTemplate jdbc, PlatformTransactionManager tx) { return new WorkflowDefinitionStore(jdbc, tx); }
    @Bean WorkflowRunStore workflowRunStore(JdbcTemplate jdbc, PlatformTransactionManager tx, WorkflowDefinitionStore definitions) { return new WorkflowRunStore(jdbc, tx, definitions); }
    @Bean ExecutionDataSourceProvider executionDataSourceProvider(DataSourceStore sources, com.example.governance.assets.driver.ConnectionFactory factory) { return new ExecutionDataSourceProvider(sources, factory); }
    @Bean(destroyMethod = "close") CamelTaskRunner camelTaskRunner(ExecutionDataSourceProvider provider) throws Exception { return new CamelTaskRunner(provider); }
    @Bean ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(3); scheduler.setThreadNamePrefix("scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true); scheduler.setAwaitTerminationSeconds(30); return scheduler;
    }
    @Bean ThreadPoolTaskExecutor workflowExecutor() {
        var executor = new ThreadPoolTaskExecutor(); executor.setCorePoolSize(2); executor.setMaxPoolSize(2); executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("workflow-"); executor.setWaitForTasksToCompleteOnShutdown(true); executor.setAwaitTerminationSeconds(30);
        return executor;
    }
    @Bean TaskExecutionLimiter taskExecutionLimiter(@org.springframework.beans.factory.annotation.Value("${governance.execution.max-concurrent:8}") int maxConcurrent) { return new TaskExecutionLimiter(maxConcurrent); }
    @Bean WorkflowWorker workflowWorker(WorkflowRunStore runs, WorkflowDefinitionStore definitions, TaskVersionStore tasks, CamelTaskRunner runner, TaskExecutionLimiter limiter) { return new WorkflowWorker(runs, definitions, tasks, runner, limiter); }
    @Bean WorkflowDispatcher workflowDispatcher(WorkflowRunStore runs, WorkflowWorker worker, ThreadPoolTaskExecutor workflowExecutor) { return new WorkflowDispatcher(runs, worker, workflowExecutor); }
    @Bean SmartInitializingSingleton recoverInterruptedRuns(WorkflowRunStore runs, TaskRunService taskRuns) { return () -> { runs.markInterrupted(); taskRuns.recover(); }; }
    @Bean WorkflowScheduleStore workflowScheduleStore(JdbcTemplate jdbc, PlatformTransactionManager tx, WorkflowDefinitionStore definitions, WorkflowRunStore runs) { return new WorkflowScheduleStore(jdbc, tx, definitions, runs); }
    @Bean WorkflowSchedulePoller workflowSchedulePoller(WorkflowScheduleStore schedules, WorkflowDispatcher dispatcher) { return new WorkflowSchedulePoller(schedules, dispatcher); }
    @Bean WorkflowRetryPoller workflowRetryPoller(WorkflowRunStore runs, WorkflowDispatcher dispatcher) { return new WorkflowRetryPoller(runs, dispatcher); }
    @Bean TaskDefinitionValidator taskDefinitionValidator() { return new TaskDefinitionValidator(); }
}
