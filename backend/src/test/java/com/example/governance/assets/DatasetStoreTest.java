package com.example.governance.assets;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DatasetStoreTest {
    @Test void savesDatasetAndFieldsAtomicallyAndRejectsDuplicateFields() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db);
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (1,'db','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        var store = new DatasetStore(jdbc, new DataSourceTransactionManager(db));
        var fields = List.of(new DatasetStore.Field("id", "BIGINT", false), new DatasetStore.Field("name", "VARCHAR", true));
        long id = store.create(1, "customers", null, "sales", "customers", fields);
        assertEquals(fields, store.fields(id));
        assertThrows(IllegalArgumentException.class, () -> store.create(1, "bad", null, "sales", "customers", List.of(fields.getFirst(), fields.getFirst())));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM datasets", Integer.class));
    }
    @Test void listingDoesNotAcquireASecondConnectionWhileStreamingResults() {
        var config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        config.setUsername("sa"); config.setMaximumPoolSize(2); config.setMinimumIdle(0); config.setConnectionTimeout(300);
        try (var db = new com.zaxxer.hikari.HikariDataSource(config)) {
            Flyway.configure().dataSource(db).load().migrate();
            db.getHikariConfigMXBean().setMaximumPoolSize(1);
            var jdbc = new JdbcTemplate(db);
            jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (1,'db','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
            var store = new DatasetStore(jdbc, new DataSourceTransactionManager(db));
            store.create(1, "orders", null, null, "orders", List.of(new DatasetStore.Field("id", "BIGINT", false)));
            assertEquals("id", store.list().getFirst().fields().getFirst().name());
        }
    }
    @Test void refreshOnlyAddsNewFieldsAndRejectsBreakingChangesAtomically() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate(); var jdbc = new JdbcTemplate(db);
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (1,'db','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        var store = new DatasetStore(jdbc, new DataSourceTransactionManager(db));
        var original = List.of(new DatasetStore.Field("id","BIGINT",false), new DatasetStore.Field("name","VARCHAR",true));
        long id = store.create(1,"data",null,null,"t",original);
        var added = new DatasetStore.Field("email","VARCHAR",true);
        assertEquals(1,store.refreshFields(id,List.of(original.get(1),original.get(0),added)));
        assertEquals(List.of(original.get(0),original.get(1),added),store.fields(id));
        assertThrows(IllegalStateException.class, () -> store.refreshFields(id,List.of(original.get(0),added)));
        assertThrows(IllegalStateException.class, () -> store.refreshFields(id,List.of(new DatasetStore.Field("id","VARCHAR",false),original.get(1),added)));
        assertThrows(IllegalStateException.class, () -> store.refreshFields(id,List.of(original.get(0),new DatasetStore.Field("name","VARCHAR",false),added)));
        assertThrows(IllegalArgumentException.class, () -> store.refreshFields(id,List.of(original.get(0),original.get(1),added,new DatasetStore.Field("EMAIL","INT",true))));
        assertEquals(List.of(original.get(0),original.get(1),added),store.fields(id));
    }
    @Test void refreshSerializesWithPolicyUpdatesAndNewFieldsRemainDenied() throws Exception {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        Flyway.configure().dataSource(db).load().migrate(); var jdbc = new JdbcTemplate(db); var manager = new DataSourceTransactionManager(db);
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (1,'db','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        jdbc.update("INSERT INTO roles(id,name) VALUES (1,'role')");
        var store = new DatasetStore(jdbc,manager); long id=store.create(1,"data",null,null,"t",List.of(new DatasetStore.Field("dept","BIGINT",false),new DatasetStore.Field("id","BIGINT",false)));
        var tx = new org.springframework.transaction.support.TransactionTemplate(manager);
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor(); var entered = new java.util.concurrent.CountDownLatch(1); final java.util.concurrent.Future<?>[] future = new java.util.concurrent.Future<?>[1];
        try {
            tx.executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM datasets WHERE id=? FOR UPDATE",Long.class,id);
                future[0] = pool.submit(() -> { entered.countDown(); return store.refreshFields(id,List.of(new DatasetStore.Field("dept","BIGINT",false),new DatasetStore.Field("id","BIGINT",false),new DatasetStore.Field("secret","VARCHAR",true))); });
                try { assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS)); Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                assertFalse(future[0].isDone());
                jdbc.update("INSERT INTO dataset_role_rules(dataset_id,role_id,scope) VALUES (?,1,'OWN')",id);
                jdbc.update("INSERT INTO dataset_role_fields(dataset_id,role_id,field_name,access_mode) VALUES (?,1,'id','FULL')",id);
            });
            assertEquals(1,future[0].get(3,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM dataset_role_fields WHERE dataset_id=? AND field_name='secret'",Integer.class,id));
        } finally { pool.shutdownNow(); }
    }
}
