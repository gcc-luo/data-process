package com.example.governance.assets;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.Base64;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DataSourceStoreTest {
    @Test void savesCredentialsEncryptedAndReturnsOnlyConnectionMetadata() throws Exception {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db);
        var cipher = new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32]));
        var store = new DataSourceStore(jdbc, cipher);
        var definition = new DataSourceDefinition("业务库", "MYSQL:8.x", "db.example", 3306, "orders", "reader");
        long id = store.create(definition, "secret-password");
        assertEquals(definition, store.find(id).orElseThrow());
        String ciphertext = jdbc.queryForObject("SELECT password_cipher FROM data_sources WHERE id=?", String.class, id);
        assertNotEquals("secret-password", ciphertext);
        assertEquals("secret-password", store.passwordForExecution(id));
    }
    @Test void deletionRejectsAnyHistoricalTaskReferenceAndMalformedConfigurations() throws Exception {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate(); var jdbc = new JdbcTemplate(db);
        var tx = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(db));
        var store = new DataSourceStore(jdbc, new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32])));
        var definition = new DataSourceDefinition("db", "MYSQL:8.x", "localhost", 3306, "db", "reader");
        long source = store.create(definition, "secret");
        jdbc.update("INSERT INTO processing_tasks(id,name,task_type,current_version) VALUES (8,'job','SQL',2)");
        jdbc.update("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (8,1,?),(8,2,?)", "{\"sourceId\":" + source + ",\"sql\":\"select 1\",\"parameters\":[]}", "{\"sourceId\":999,\"sql\":\"select 1\",\"parameters\":[]}");
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(x -> store.deleteUnused(source)));
        jdbc.update("UPDATE processing_task_versions SET configuration='{}' WHERE task_id=8 AND version=1");
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(x -> store.deleteUnused(source)));
        jdbc.update("DELETE FROM processing_task_versions WHERE task_id=8");
        assertTrue(Boolean.TRUE.equals(tx.execute(x -> store.deleteUnused(source))));
        assertFalse(Boolean.TRUE.equals(tx.execute(x -> store.deleteUnused(source))));
    }
    @Test void deletionAndTaskCreationSerializeOnTheSourceRow() throws Exception {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        Flyway.configure().dataSource(db).load().migrate(); var jdbc = new JdbcTemplate(db);
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(db);
        var store = new DataSourceStore(jdbc, new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32])));
        long source = store.create(new DataSourceDefinition("db", "MYSQL:8.x", "localhost", 3306, "db", "reader"), "secret");
        jdbc.update("INSERT INTO processing_tasks(id,name,task_type,current_version) VALUES (8,'job','SQL',1)");
        var locked = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var creator = pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(x -> {
                assertTrue(store.lockSources(java.util.List.of(source))); locked.countDown();
                try { if (!release.await(3, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                jdbc.update("INSERT INTO processing_task_versions(task_id,version,configuration) VALUES (8,1,?)", "{\"sourceId\":" + source + "}");
            }));
            assertTrue(locked.await(3, java.util.concurrent.TimeUnit.SECONDS));
            var deletion = pool.submit(() -> new org.springframework.transaction.support.TransactionTemplate(manager).execute(x -> store.deleteUnused(source)));
            release.countDown(); creator.get();
            var error = assertThrows(java.util.concurrent.ExecutionException.class, deletion::get);
            assertInstanceOf(IllegalStateException.class, error.getCause());
            assertTrue(store.find(source).isPresent());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
