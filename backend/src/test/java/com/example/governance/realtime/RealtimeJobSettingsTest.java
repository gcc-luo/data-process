package com.example.governance.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class RealtimeJobSettingsTest {
    @Test void updatesOnlyBatchAndIntervalWhileStoppedAndPreservesCursorAndMapping() throws Exception {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate(); var jdbc = new JdbcTemplate(db);
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (1,'a','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher'),(2,'b','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        var observed = new AtomicReference<RealtimeJobStore.JobConfig>();
        var store = new RealtimeJobStore(jdbc,new DataSourceTransactionManager(db),(config,cursor) -> { observed.set(config); return new IncrementalSyncExecutor.Result(1,new IncrementalSyncExecutor.Cursor(cursor == null ? "5" : "6",1)); });
        var mapping = new IncrementalSyncExecutor.Config("src","dst",List.of("id"),List.of("id"),"seq","id",List.of("id"),"LONG",100);
        long id = store.create("job",new RealtimeJobStore.JobConfig(1,2,mapping,5)); var now=Instant.parse("2026-11-16T00:00:00Z");
        store.start(id,now); assertTrue(store.poll(id,now)); assertThrows(IllegalStateException.class,() -> store.updateSettings(id,20,33));
        store.stop(id); store.updateSettings(id,20,33); var view=store.view(id).orElseThrow();
        assertEquals("5",view.watermark()); assertEquals(1,view.tie()); assertEquals(1,view.processed()); assertEquals(20,view.intervalSeconds()); assertEquals(33,view.batchSize());
        var stored=new ObjectMapper().readValue(jdbc.queryForObject("SELECT config FROM realtime_jobs WHERE id=?",String.class,id),RealtimeJobStore.JobConfig.class);
        assertEquals(mapping.sourceTable(),stored.mapping().sourceTable()); assertEquals(mapping.targetKeys(),stored.mapping().targetKeys()); assertEquals(1,stored.sourceId()); assertEquals(2,stored.targetId());
        store.start(id,now.plusSeconds(20)); assertTrue(store.poll(id,now.plusSeconds(20))); assertEquals(33,observed.get().mapping().batchSize());
        assertThrows(IllegalArgumentException.class,() -> store.updateSettings(id,4,33)); assertThrows(IllegalArgumentException.class,() -> store.updateSettings(id,20,1001));
    }
    @Test void settingsCanBeUpdatedAfterFailureWithoutLosingFailureOrCursor() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate(); var jdbc = new JdbcTemplate(db);
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (1,'a','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        var store=new RealtimeJobStore(jdbc,new DataSourceTransactionManager(db),(config,cursor) -> { throw new IllegalStateException(); });
        var mapping=new IncrementalSyncExecutor.Config("src","dst",List.of("id"),List.of("id"),"seq","id",List.of("id"),"LONG",100);
        long id=store.create("job",new RealtimeJobStore.JobConfig(1,1,mapping,5)); var now=Instant.now();store.start(id,now); assertFalse(store.poll(id,now));
        store.updateSettings(id,10,50); assertEquals("ERROR",store.view(id).orElseThrow().state()); assertNotNull(store.view(id).orElseThrow().lastError());
    }
    @Test void concurrentStartAndEditAreSerialized() throws Exception {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        Flyway.configure().dataSource(db).load().migrate(); var jdbc=new JdbcTemplate(db);
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (1,'a','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        var store=new RealtimeJobStore(jdbc,new DataSourceTransactionManager(db),(config,cursor) -> new IncrementalSyncExecutor.Result(0,cursor));
        var mapping=new IncrementalSyncExecutor.Config("src","dst",List.of("id"),List.of("id"),"seq","id",List.of("id"),"LONG",100);
        long id=store.create("job",new RealtimeJobStore.JobConfig(1,1,mapping,5)); var gate=new java.util.concurrent.CountDownLatch(1); var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start=pool.submit(() -> { gate.await(); store.start(id,Instant.now()); return null; });
            var edit=pool.submit(() -> { gate.await(); try { store.updateSettings(id,10,50); return true; } catch (IllegalStateException ex) { return false; } });
            gate.countDown(); start.get(); boolean applied=edit.get(); var view=store.view(id).orElseThrow();
            assertEquals("RUNNING",view.state()); assertEquals(applied ? 10 : 5,view.intervalSeconds()); assertEquals(applied ? 50 : 100,view.batchSize());
        } finally { pool.shutdownNow(); }
    }
}
