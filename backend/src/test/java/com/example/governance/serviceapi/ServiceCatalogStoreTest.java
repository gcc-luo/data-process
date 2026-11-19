package com.example.governance.serviceapi;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ServiceCatalogStoreTest {
    @Test void publishesGrantedServiceAndEnforcesCallerRateLimit() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db);
        jdbc.update("INSERT INTO departments(id,name) VALUES (1,'dept')");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (1,'a','hash',1,TRUE,'v')");
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (1,'db','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        jdbc.update("INSERT INTO datasets(id,source_id,name,table_name) VALUES (1,1,'dataset','data')");
        jdbc.update("INSERT INTO dataset_fields(dataset_id,ordinal_no,field_name,data_type,nullable) VALUES (1,0,'ID','BIGINT',FALSE)");
        var store = new ServiceCatalogStore(jdbc, new DataSourceTransactionManager(db));
        long service = store.createService(1, 1, "customers", List.of("ID"), 100);
        var caller = store.createCaller("app", 1, 1);
        assertNotEquals(caller.token(), jdbc.queryForObject("SELECT token_hash FROM service_callers WHERE id=?", String.class, caller.id()));
        store.submit(service); store.publish(service); store.grant(service, caller.id());
        assertEquals(1, store.grants().size());
        store.revoke(service, caller.id());
        assertThrows(SecurityException.class, () -> store.begin(caller.token(), service, Instant.parse("2026-11-16T00:00:00Z")));
        store.grant(service, caller.id());
        var call = store.begin(caller.token(), service, Instant.parse("2026-11-16T00:00:01Z"));
        assertEquals(1, call.userId()); assertEquals(List.of("ID"), call.fields());
        store.complete(call.invocationId(), true, 5);
        assertThrows(ServiceCatalogStore.RateLimitException.class, () -> store.begin(caller.token(), service, Instant.parse("2026-11-16T00:00:02Z")));
        assertThrows(SecurityException.class, () -> store.begin("invalid", service, Instant.now()));
        jdbc.update("UPDATE users SET security_version='new-version' WHERE id=1");
        assertThrows(SecurityException.class, () -> store.begin(caller.token(), service, Instant.parse("2026-11-16T00:01:00Z")));
        store.disableCaller(caller.id());
        assertThrows(SecurityException.class, () -> store.begin(caller.token(), service, Instant.parse("2026-11-16T00:01:00Z")));
    }
    @Test void simultaneousRequestsCannotExceedCallerLimit() throws Exception {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate(); var jdbc = new JdbcTemplate(db);
        jdbc.update("INSERT INTO departments(id,name) VALUES (1,'dept')");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (1,'a','hash',1,TRUE,'v')");
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (1,'db','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        jdbc.update("INSERT INTO datasets(id,source_id,name,table_name) VALUES (1,1,'dataset','data')");
        jdbc.update("INSERT INTO dataset_fields(dataset_id,ordinal_no,field_name,data_type,nullable) VALUES (1,0,'ID','BIGINT',FALSE)");
        var store = new ServiceCatalogStore(jdbc, new DataSourceTransactionManager(db));
        long service = store.createService(1, 1, "customers", List.of("ID"), 100); var caller = store.createCaller("app", 1, 1);
        store.submit(service); store.publish(service); store.grant(service, caller.id());
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2); var gate = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Boolean> call = () -> { gate.await(); try { store.begin(caller.token(), service, Instant.parse("2026-11-16T00:00:01Z")); return true; } catch (ServiceCatalogStore.RateLimitException ex) { return false; } };
            var a = pool.submit(call); var b = pool.submit(call); gate.countDown();
            assertNotEquals(a.get(), b.get());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM service_invocations", Integer.class));
        } finally { pool.shutdownNow(); }
    }
}
