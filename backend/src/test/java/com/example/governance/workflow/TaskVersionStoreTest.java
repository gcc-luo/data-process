package com.example.governance.workflow;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TaskVersionStoreTest {
    @Test void savesImmutableVersionsAndRetrievesEarlierConfiguration() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var store = new TaskVersionStore(new JdbcTemplate(db), new DataSourceTransactionManager(db));
        long task = store.create("daily report", "ORCH", "{\"sql\":\"select 1\"}");
        // create 只建任务行（current_version=0），首次发布产生版本 1
        assertEquals(0, store.publishedVersion(task));
        int first = store.addVersion(task, "{\"sql\":\"select 1\"}");
        assertEquals(1, first);
        int second = store.addVersion(task, "{\"sql\":\"select 2\"}");
        assertEquals(2, second);
        assertEquals("{\"sql\":\"select 1\"}", store.findVersion(task, 1).orElseThrow().configuration());
        assertEquals("{\"sql\":\"select 2\"}", store.findVersion(task, 2).orElseThrow().configuration());
        assertTrue(store.findVersion(task, 3).isEmpty());
    }
    @Test void rejectsInvalidTypesAndNonObjectConfiguration() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var store = new TaskVersionStore(new JdbcTemplate(db), new DataSourceTransactionManager(db));
        assertThrows(IllegalArgumentException.class, () -> store.create("job", null, "{}"));
        // 旧任务类型已删除，仅接受 ORCH
        assertThrows(IllegalArgumentException.class, () -> store.create("job", "SQL", "{}"));
        assertThrows(IllegalArgumentException.class, () -> store.create("job", "SYNC", "{}"));
        assertThrows(IllegalArgumentException.class, () -> store.create("job", "FLOW", "{}"));
        assertThrows(IllegalArgumentException.class, () -> store.create("job", "ORCH", "[]"));
        assertThrows(IllegalArgumentException.class, () -> store.create("job", "ORCH", "not json"));
    }
}
