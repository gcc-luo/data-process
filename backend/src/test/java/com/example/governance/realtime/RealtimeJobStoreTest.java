package com.example.governance.realtime;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RealtimeJobStoreTest {
    @Test void cursorAdvancesOnlyAfterSuccessfulTargetCommitAndSurvivesRestart() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate(); var jdbc = new JdbcTemplate(db);
        jdbc.update("INSERT INTO data_sources(id,name,source_type,host,port,database_name,username,password_cipher) VALUES (1,'a','MYSQL','localhost',3306,'db','u','cipher'),(2,'b','MYSQL','localhost',3306,'db','u','cipher')");
        var calls = new AtomicInteger();
        RealtimeJobStore.Processor processor = (config, cursor) -> {
            if (calls.getAndIncrement() == 1) throw new IllegalStateException("sensitive");
            return new IncrementalSyncExecutor.Result(1, new IncrementalSyncExecutor.Cursor(cursor == null ? "10" : "11", cursor == null ? 1 : 2));
        };
        var store = new RealtimeJobStore(jdbc, new DataSourceTransactionManager(db), processor);
        var mapping = new IncrementalSyncExecutor.Config("src","dst",List.of("id"),List.of("id"),"seq","id",List.of("id"),"LONG",100);
        long id = store.create("job", new RealtimeJobStore.JobConfig(1, 2, mapping, 5));
        var now = Instant.parse("2026-11-16T00:00:00Z"); store.start(id, now);
        assertTrue(store.poll(id, now)); assertEquals("10", store.view(id).orElseThrow().watermark());
        assertFalse(store.poll(id, now.plusSeconds(5))); assertEquals("ERROR", store.view(id).orElseThrow().state());
        assertEquals("10", store.view(id).orElseThrow().watermark());
        assertFalse(store.view(id).orElseThrow().lastError().contains("sensitive"));
        store.start(id, now.plusSeconds(10)); assertTrue(store.poll(id, now.plusSeconds(10)));
        assertEquals("11", store.view(id).orElseThrow().watermark());
        assertEquals(2, store.view(id).orElseThrow().processed());
    }
    @Test void concurrentPollsClaimOneBatch() throws Exception {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate(); var jdbc = new JdbcTemplate(db);
        jdbc.update("INSERT INTO data_sources(id,name,source_type,host,port,database_name,username,password_cipher) VALUES (1,'a','MYSQL','localhost',3306,'db','u','cipher'),(2,'b','MYSQL','localhost',3306,'db','u','cipher')");
        var calls = new AtomicInteger();
        var store = new RealtimeJobStore(jdbc, new DataSourceTransactionManager(db), (config,cursor) -> { calls.incrementAndGet(); return new IncrementalSyncExecutor.Result(1, new IncrementalSyncExecutor.Cursor("10",1)); });
        var mapping = new IncrementalSyncExecutor.Config("src","dst",List.of("id"),List.of("id"),"seq","id",List.of("id"),"LONG",100);
        long id = store.create("job", new RealtimeJobStore.JobConfig(1,2,mapping,5)); var now = Instant.parse("2026-11-16T00:00:00Z"); store.start(id, now);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2); var gate = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Boolean> poll = () -> { gate.await(); return store.poll(id, now); };
            var first = pool.submit(poll); var second = pool.submit(poll); gate.countDown();
            assertNotEquals(first.get(), second.get()); assertEquals(1, calls.get());
        } finally { pool.shutdownNow(); }
    }
}
