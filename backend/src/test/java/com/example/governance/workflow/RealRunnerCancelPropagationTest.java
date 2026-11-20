package com.example.governance.workflow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RealRunnerCancelPropagationTest {
    @Test void fourArgExecutePropagatesCancelToStatement() throws Exception {
        var entered = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var statement = (PreparedStatement) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{PreparedStatement.class}, (p, m, a) -> {
            if (m.getName().equals("executeUpdate")) { entered.countDown(); assertTrue(cancelled.await(5, TimeUnit.SECONDS)); throw new SQLException("cancelled"); }
            if (m.getName().equals("cancel")) { cancelled.countDown(); return null; }
            return null;
        });
        var connection = (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class}, (p, m, a) -> m.getName().equals("prepareStatement") ? statement : null);
        var ds = (DataSource) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{DataSource.class}, (p, m, a) -> m.getName().equals("getConnection") ? connection : null);
        try (var runner = new CamelTaskRunner(id -> ds)) {
            var version = new TaskVersionStore.Version(1, 1, "SQL", "{\"sourceId\":1,\"sql\":\"SELECT 1\",\"parameters\":[]}");
            var cancel = new RunCancelContext();
            var thread = new Thread(() -> {
                try { runner.execute(version, LocalDate.of(2026, 11, 20), cancel, ExecutionSinks.NOOP); }
                catch (Exception expected) { /* 被取消的语句抛异常 */ }
            });
            thread.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            cancel.cancel();
            thread.join(5000);
            assertFalse(thread.isAlive());
            assertEquals(0, cancelled.getCount(), "Statement.cancel 必须被四参路径触发");
        }
    }
}
