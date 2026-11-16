package com.example.governance.workflow;

import com.example.governance.GovernanceApplication;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class StartupRecoveryOrderingTest {
    static volatile CountDownLatch reached, release;
    @TestConfiguration(proxyBeanMethods = false)
    static class GateConfig {
        @Bean static BeanPostProcessor gateRecovery() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) throws BeansException {
                    if (!name.equals("recoverInterruptedRuns")) return bean;
                    if (bean instanceof SmartInitializingSingleton recovery) return (SmartInitializingSingleton) () -> { waitForRelease(); recovery.afterSingletonsInstantiated(); };
                    if (bean instanceof ApplicationRunner recovery) return (ApplicationRunner) args -> { waitForRelease(); recovery.run(args); };
                    return bean;
                }
            };
        }
    }
    private static void waitForRelease() {
        reached.countDown();
        try { if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Recovery gate timed out"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
    }
    private static boolean acceptsConnections(int port) {
        try (var socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), 400); return true; }
        catch (java.io.IOException ex) { return false; }
    }
    @Test void recoveryFinishesBeforeWebServerAcceptsRequests() throws Exception {
        var url = "jdbc:h2:mem:recovery_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        var db = new DriverManagerDataSource(url, "sa", ""); Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db);
        long task = new TaskVersionStore(jdbc, tx).create("pending", "SQL", "{}");
        var defs = new WorkflowDefinitionStore(jdbc, tx);
        long workflow = defs.create("pending", List.of(new WorkflowDefinitionStore.Node("n", task, 1)), List.of());
        long run = new WorkflowRunStore(jdbc, tx, defs).create(workflow, 1, LocalDate.of(2026, 11, 16));
        jdbc.update("INSERT INTO task_runs(id,task_id,task_version,business_date,state,created_at,attempt_no) VALUES (101,?,1,?,'PENDING',CURRENT_TIMESTAMP,1)", task, java.sql.Date.valueOf(LocalDate.of(2026, 11, 16)));
        jdbc.update("INSERT INTO task_run_attempts(run_id,attempt_no,state) VALUES (101,1,'PENDING')");
        int port; try (var reserved = new ServerSocket(0)) { port = reserved.getLocalPort(); }
        reached = new CountDownLatch(1); release = new CountDownLatch(1); var pool = Executors.newSingleThreadExecutor();
        ConfigurableApplicationContext context = null;
        var future = pool.submit(() -> new SpringApplicationBuilder(GovernanceApplication.class, GateConfig.class).run(
            "--server.port=" + port, "--server.address=127.0.0.1", "--spring.datasource.url=" + url, "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.flyway.locations=classpath:db/migration", "--governance.credential-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
        try {
            assertTrue(reached.await(20, TimeUnit.SECONDS), "recovery was not invoked");
            assertFalse(acceptsConnections(port), "web server must not accept requests before recovery finishes");
            release.countDown(); context = future.get(20, TimeUnit.SECONDS);
            assertTrue(acceptsConnections(port));
            assertEquals("INTERRUPTED", jdbc.queryForObject("SELECT state FROM task_runs WHERE id=101", String.class));
            assertEquals("INTERRUPTED", jdbc.queryForObject("SELECT state FROM task_run_attempts WHERE run_id=101", String.class));
            assertEquals("INTERRUPTED", jdbc.queryForObject("SELECT state FROM workflow_runs WHERE id=?", String.class, run));
        } finally {
            release.countDown();
            if (context == null && future.isDone() && !future.isCancelled()) { try { context = future.get(); } catch (Exception ignored) {} }
            if (context != null) context.close();
            pool.shutdownNow();
        }
    }
}
