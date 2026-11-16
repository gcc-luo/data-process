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
        long task = store.create("daily report", "SQL", "{\"sql\":\"select 1\"}");
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
        assertThrows(IllegalArgumentException.class, () -> store.create("job", "SQL", "[]"));
        assertThrows(IllegalArgumentException.class, () -> store.create("job", "SQL", "not json"));
    }
}
