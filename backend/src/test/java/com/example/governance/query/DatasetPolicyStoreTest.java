package com.example.governance.query;

import com.example.governance.security.AccessPolicy;
import com.example.governance.security.DatasetAccess;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DatasetPolicyStoreTest {
    @Test void resolvesDepartmentAndFieldGrantsFromTrustedDatabase() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db);
        jdbc.update("INSERT INTO departments(id,name,parent_id) VALUES (1,'parent',NULL),(2,'child',1),(3,'other',NULL)");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (1,'a','hash',1,TRUE,'v'),(2,'b','hash',3,TRUE,'v2')");
        jdbc.update("INSERT INTO roles(id,name) VALUES (1,'analyst')");
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (1,1)");
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (1,'datasource.query.read'),(1,'datasource.dataset.read')");
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (1,'db','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        jdbc.update("INSERT INTO datasets(id,source_id,name,schema_name,table_name) VALUES (1,1,'clients','PUBLIC','CLIENTS')");
        jdbc.update("INSERT INTO dataset_fields(dataset_id,ordinal_no,field_name,data_type,nullable) VALUES (1,0,'DEPARTMENT_ID','BIGINT',FALSE),(1,1,'ID','INTEGER',FALSE),(1,2,'PHONE','VARCHAR',TRUE)");
        var store = new DatasetPolicyStore(jdbc, tx);
        store.configure(1, "DEPARTMENT_ID", 1, AccessPolicy.Scope.OWN_AND_DESCENDANTS, Map.of("ID", DatasetAccess.Field.FULL, "PHONE", DatasetAccess.Field.MASKED));
        var resolved = store.resolve(1, 1);
        assertEquals(Set.of(1L, 2L), resolved.policy().departments());
        assertEquals(DatasetAccess.Field.MASKED, resolved.policy().fields().get("PHONE"));
        assertThrows(SecurityException.class, () -> store.resolve(2, 1));
        assertThrows(IllegalArgumentException.class, () -> store.configure(1, "PHONE", 1, AccessPolicy.Scope.OWN, Map.of("ID", DatasetAccess.Field.FULL)));
        jdbc.update("INSERT INTO roles(id,name) VALUES (2,'restricted')");
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (1,2)");
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (2,'datasource.query.read'),(2,'datasource.dataset.read')");
        store.configure(1, "DEPARTMENT_ID", 1, AccessPolicy.Scope.OWN, Map.of("ID", DatasetAccess.Field.FULL, "PHONE", DatasetAccess.Field.FULL));
        store.configure(1, "DEPARTMENT_ID", 2, AccessPolicy.Scope.OWN_AND_DESCENDANTS, Map.of("ID", DatasetAccess.Field.FULL, "PHONE", DatasetAccess.Field.MASKED));
        var combined = store.resolve(1, 1).policy();
        assertEquals(DatasetAccess.Field.FULL, combined.grants().get("PHONE").get(1L));
        assertEquals(DatasetAccess.Field.MASKED, combined.grants().get("PHONE").get(2L));
    }
    @Test void readsAndRevokesRolePoliciesWithoutChangingOtherRoles() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate(); var jdbc = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db);
        jdbc.update("INSERT INTO roles(id,name) VALUES (1,'a'),(2,'b')");
        jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (1,'db','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        jdbc.update("INSERT INTO datasets(id,source_id,name,table_name) VALUES (1,1,'data','t')");
        jdbc.update("INSERT INTO dataset_fields(dataset_id,ordinal_no,field_name,data_type,nullable) VALUES (1,0,'DEPT','BIGINT',FALSE),(1,1,'OTHER_DEPT','BIGINT',FALSE),(1,2,'ID','INTEGER',FALSE)");
        var store = new DatasetPolicyStore(jdbc,tx);
        store.configure(1,"DEPT",1,AccessPolicy.Scope.OWN,Map.of("ID",DatasetAccess.Field.FULL));
        store.configure(1,"DEPT",2,AccessPolicy.Scope.OWN_AND_DESCENDANTS,Map.of("ID",DatasetAccess.Field.MASKED));
        var policies = store.listPolicies(1);
        assertEquals("DEPT", policies.departmentField()); assertEquals(2, policies.rules().size());
        assertThrows(IllegalStateException.class, () -> store.configure(1,"OTHER_DEPT",1,AccessPolicy.Scope.OWN,Map.of("ID",DatasetAccess.Field.FULL)));
        assertTrue(store.revoke(1,1)); assertFalse(store.revoke(1,1));
        assertEquals(2, store.listPolicies(1).rules().get(0).roleId());
        assertEquals("DEPT", store.listPolicies(1).departmentField());
        assertTrue(store.revoke(1,2));
        store.configure(1,"OTHER_DEPT",1,AccessPolicy.Scope.OWN,Map.of("ID",DatasetAccess.Field.FULL));
        assertEquals("OTHER_DEPT",store.listPolicies(1).departmentField());
        jdbc.update("INSERT INTO departments(id,name) VALUES (1,'dept')");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (1,'u','h',1,TRUE,'v')");
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (1,1)");
        jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (1,'datasource.query.read'),(1,'datasource.dataset.read')");
        try (var pool = new com.zaxxer.hikari.HikariDataSource()) {
            pool.setJdbcUrl(db.getUrl()); pool.setUsername("sa"); pool.setPassword(""); pool.setMaximumPoolSize(1); pool.setConnectionTimeout(1000);
            var singleManager = new DataSourceTransactionManager(pool);
            var repeatable = new org.springframework.transaction.support.TransactionTemplate(singleManager);
            repeatable.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            var pooledStore = new DatasetPolicyStore(new JdbcTemplate(pool), singleManager);
            assertEquals("OTHER_DEPT", repeatable.execute(status -> pooledStore.resolve(1, 1)).dataset().departmentField());
        }
        var lower = new org.springframework.transaction.support.TransactionTemplate(tx);
        lower.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThrows(IllegalStateException.class, () -> lower.execute(status -> store.resolve(1, 1)));

    }
    @Test void resolvingPolicyUsesOneConsistentSnapshotDuringConcurrentChange() throws Exception {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate(); var setup = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db);
        setup.update("INSERT INTO departments(id,name) VALUES (1,'dept')");
        setup.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (1,'u','h',1,TRUE,'v')");
        setup.update("INSERT INTO roles(id,name) VALUES (1,'role')"); setup.update("INSERT INTO user_roles(user_id,role_id) VALUES (1,1)"); setup.update("INSERT INTO role_permissions(role_id,permission) VALUES (1,'datasource.query.read'),(1,'datasource.dataset.read')");
        setup.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,password_cipher) VALUES (1,'db','MYSQL','MYSQL:8.x','localhost',3306,'db','u','cipher')");
        setup.update("INSERT INTO datasets(id,source_id,name,table_name) VALUES (1,1,'data','t')");
        setup.update("INSERT INTO dataset_fields(dataset_id,ordinal_no,field_name,data_type,nullable) VALUES (1,0,'DEPT_A','BIGINT',FALSE),(1,1,'DEPT_B','BIGINT',FALSE),(1,2,'SECRET','VARCHAR',FALSE)");
        var writer = new DatasetPolicyStore(setup,tx); writer.configure(1,"DEPT_A",1,AccessPolicy.Scope.OWN,Map.of("SECRET",DatasetAccess.Field.MASKED));
        var read = new java.util.concurrent.CountDownLatch(1); var changed = new java.util.concurrent.CountDownLatch(1);
        var intercepted = new JdbcTemplate(db) {
            @Override public <T> java.util.List<T> query(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
                var rows = super.query(sql,mapper,args);
                if (sql.startsWith("SELECT source_id,catalog_name")) { read.countDown(); try { if (!changed.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } }
                return rows;
            }
        };
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var result = pool.submit(() -> new DatasetPolicyStore(intercepted,tx).resolve(1,1));
            assertTrue(read.await(5, java.util.concurrent.TimeUnit.SECONDS));
            writer.configure(1,"DEPT_B",1,AccessPolicy.Scope.OWN,Map.of("SECRET",DatasetAccess.Field.FULL)); changed.countDown();
            var resolved = result.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue((resolved.dataset().departmentField().equals("DEPT_A") && resolved.policy().fields().get("SECRET") == DatasetAccess.Field.MASKED) || (resolved.dataset().departmentField().equals("DEPT_B") && resolved.policy().fields().get("SECRET") == DatasetAccess.Field.FULL));
        } finally { changed.countDown(); pool.shutdownNow(); }
    }
}
