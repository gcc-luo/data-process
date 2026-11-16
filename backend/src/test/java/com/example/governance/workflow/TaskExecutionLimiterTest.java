package com.example.governance.workflow;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class TaskExecutionLimiterTest {
    @Test void serializesExecutionsAtConfiguredLimitAndReleasesAfterFailure() throws Exception {
        var limiter = new TaskExecutionLimiter(1);
        var waiting = new CountDownLatch(1); var acquired = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try (var permit = limiter.acquire()) {
            var future = pool.submit(() -> { waiting.countDown(); try (var next = limiter.acquire()) { acquired.countDown(); } return null; });
            assertTrue(waiting.await(1, TimeUnit.SECONDS)); assertFalse(acquired.await(150, TimeUnit.MILLISECONDS));
            permit.close(); assertTrue(acquired.await(2, TimeUnit.SECONDS)); future.get();
        } finally { pool.shutdownNow(); }
        try (var ignored = limiter.acquire()) { assertNotNull(ignored); }
    }
    @Test void rejectsOutOfRangeLimits() {
        assertThrows(IllegalArgumentException.class, () -> new TaskExecutionLimiter(0));
        assertThrows(IllegalArgumentException.class, () -> new TaskExecutionLimiter(65));
    }
}
