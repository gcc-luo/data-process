package com.example.governance.mysql;
import com.example.governance.query.DatasetPolicyStore;
import com.example.governance.security.AccessPolicy;
import com.example.governance.security.DatasetAccess;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfSystemProperty(named="governance.mysql.test",matches="true")
class MySqlPolicySnapshotTest {
    @Test void resolvingPolicyUsesOneConsistentSnapshotDuringConcurrentChange() throws Exception {
        var db = new DriverManagerDataSource(System.getProperty("governance.mysql.url"), System.getProperty("governance.mysql.user"), System.getProperty("governance.mysql.password", ""));
        Flyway.configure().dataSource(db).locations("classpath:db/mysql").load().migrate(); var setup = new JdbcTemplate(db); var tx = new DataSourceTransactionManager(db);
        setup.update("INSERT INTO departments(id,name) VALUES (1,'dept')");
        setup.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (1,'u','h',1,TRUE,'v')");
        setup.update("INSERT INTO roles(id,name) VALUES (1,'role')"); setup.update("INSERT INTO user_roles(user_id,role_id) VALUES (1,1)"); setup.update("INSERT INTO role_permissions(role_id,permission) VALUES (1,'dataset.read')");
        setup.update("INSERT INTO data_sources(id,name,source_type,host,port,database_name,username,password_cipher) VALUES (1,'db','MYSQL','localhost',3306,'db','u','cipher')");
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
