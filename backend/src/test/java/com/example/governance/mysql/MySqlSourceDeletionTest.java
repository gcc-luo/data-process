package com.example.governance.mysql;

import com.example.governance.assets.CredentialCipher;
import com.example.governance.assets.DataSourceDefinition;
import com.example.governance.assets.DataSourceStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Base64;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="governance.mysql.test", matches="true")
class MySqlSourceDeletionTest {
    @Test void deletionChecksCurrentReferencesEvenWithAnOlderRepeatableReadSnapshot() throws Exception {
        var db = new DriverManagerDataSource(System.getProperty("governance.mysql.url"),System.getProperty("governance.mysql.user"),System.getProperty("governance.mysql.password", ""));
        Flyway.configure().dataSource(db).locations("classpath:db/mysql").load().migrate();
        var jdbc = new JdbcTemplate(db); var manager = new DataSourceTransactionManager(db);
        var store = new DataSourceStore(jdbc,new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32])));
        long source = store.create(new DataSourceDefinition("snapshot-" + java.util.UUID.randomUUID(), "MYSQL:8.x","localhost",3306,"db","u"), "secret");
        var outer = new TransactionTemplate(manager); var inner = new TransactionTemplate(manager); inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThrows(IllegalStateException.class, () -> outer.executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT COUNT(*) FROM processing_task_versions", Integer.class); // establish REPEATABLE READ snapshot
            inner.executeWithoutResult(newTx -> {
                assertTrue(store.lockSources(List.of(source)));
                long task = new com.example.governance.workflow.TaskVersionStore(jdbc,manager).create("snapshot", "SQL", "{\"sourceId\":" + source + "}");
                assertTrue(task > 0);
            });
            store.deleteUnused(source);
        }));
        assertTrue(store.find(source).isPresent());
    }
}
