package com.example.governance.workflow;

import com.example.governance.assets.ExecutionDataSourceProvider;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class OrchQueryServiceTest {
    private DriverManagerDataSource db() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new JdbcTemplate(db).execute("CREATE TABLE src(id INT, name VARCHAR(40))");
        new JdbcTemplate(db).update("INSERT INTO src VALUES (1,'a'),(2,'b'),(3,'c'),(4,'d'),(5,'e')");
        return db;
    }
    private OrchQueryService service(DriverManagerDataSource db) throws Exception {
        var provider = Mockito.mock(ExecutionDataSourceProvider.class);
        Mockito.when(provider.get(1L)).thenReturn(db);
        return new OrchQueryService(provider);
    }

    @Test void previewsSelectWithBoundParamsAndTruncates() throws Exception {
        var svc = service(db());
        var res = svc.preview(1, "SELECT id, name FROM src WHERE id > :min ORDER BY id", Map.of("min", 1), Map.of("min", "NUMBER"), 3);
        assertEquals(3, res.rowCount());
        assertTrue(res.truncated());
        assertTrue(res.columns().get(0).equalsIgnoreCase("id"));
        assertEquals(2, ((Number) res.rows().get(0).get(0)).intValue());
    }

    @Test void rejectsWriteAndMultiStatementSql() throws Exception {
        var svc = service(db());
        assertThrows(IllegalArgumentException.class, () -> svc.preview(1, "DELETE FROM src", Map.of(), Map.of(), 100));
        assertThrows(IllegalArgumentException.class, () -> svc.preview(1, "DROP TABLE src", Map.of(), Map.of(), 100));
        assertThrows(IllegalArgumentException.class, () -> svc.preview(1, "SELECT id FROM src; DELETE FROM src", Map.of(), Map.of(), 100));
    }

    @Test void missingParameterValueFails() throws Exception {
        var svc = service(db());
        assertThrows(IllegalArgumentException.class, () -> svc.preview(1, "SELECT id FROM src WHERE id > :min", Map.of(), Map.of("min", "NUMBER"), 100));
    }
}