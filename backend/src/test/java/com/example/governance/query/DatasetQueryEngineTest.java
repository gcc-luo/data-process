package com.example.governance.query;

import com.example.governance.security.DatasetAccess;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DatasetQueryEngineTest {
    @Test void filtersRowsByTrustedDepartmentsAndMasksRestrictedFields() throws Exception {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (var c = db.getConnection(); var s = c.createStatement()) {
            s.execute("CREATE TABLE CLIENTS (ID INT, DEPARTMENT_ID BIGINT, PHONE VARCHAR(20))");
            s.execute("INSERT INTO CLIENTS VALUES (1, 10, '13800000000'), (2, 20, '13900000000')");
        }
        var dataset = new DatasetQueryEngine.Dataset(null, "PUBLIC", "CLIENTS", "DEPARTMENT_ID", Set.of("ID", "DEPARTMENT_ID", "PHONE"));
        var policy = new DatasetQueryEngine.Policy(Set.of(10L), Map.of("ID", DatasetAccess.Field.FULL, "PHONE", DatasetAccess.Field.MASKED));
        var rows = new DatasetQueryEngine().query(db, dataset, policy, List.of("ID", "PHONE"), 100);
        assertEquals(1, rows.size()); assertEquals(1, rows.get(0).get("ID")); assertEquals("****", rows.get(0).get("PHONE"));
        assertThrows(SecurityException.class, () -> new DatasetQueryEngine().query(db, dataset, policy, List.of("DEPARTMENT_ID"), 10));
    }
    @Test void rejectsUntrustedIdentifiersAndEmptyScopes() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:x", "sa", "");
        var dataset = new DatasetQueryEngine.Dataset(null, "PUBLIC", "CLIENTS", "DEPARTMENT_ID", Set.of("ID", "DEPARTMENT_ID"));
        var empty = new DatasetQueryEngine.Policy(Set.of(), Map.of("ID", DatasetAccess.Field.FULL));
        assertThrows(SecurityException.class, () -> new DatasetQueryEngine().query(db, dataset, empty, List.of("ID"), 10));
        var policy = new DatasetQueryEngine.Policy(Set.of(1L), Map.of("ID", DatasetAccess.Field.FULL));
        assertThrows(SecurityException.class, () -> new DatasetQueryEngine().query(db, dataset, policy, List.of("ID; DROP TABLE CLIENTS"), 10));
    }
    @Test void combinesFieldAndDepartmentGrantsWithoutExpandingFullAccess() throws Exception {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (var c = db.getConnection(); var st = c.createStatement()) {
            st.execute("CREATE TABLE CLIENTS (ID INT, DEPARTMENT_ID BIGINT, PHONE VARCHAR(20))");
            st.execute("INSERT INTO CLIENTS VALUES (1,10,'one'),(2,20,'two')");
        }
        var dataset = new DatasetQueryEngine.Dataset(null, "PUBLIC", "CLIENTS", "DEPARTMENT_ID", Set.of("ID", "DEPARTMENT_ID", "PHONE"));
        var grants = Map.of("ID", Map.of(10L, DatasetAccess.Field.FULL, 20L, DatasetAccess.Field.FULL),
                            "PHONE", Map.of(10L, DatasetAccess.Field.FULL, 20L, DatasetAccess.Field.MASKED));
        var policy = new DatasetQueryEngine.Policy(Set.of(10L,20L), Map.of("ID", DatasetAccess.Field.FULL, "PHONE", DatasetAccess.Field.FULL), grants);
        var rows = new DatasetQueryEngine().query(db, dataset, policy, List.of("ID", "PHONE"), 100);
        assertEquals("one", rows.get(0).get("PHONE")); assertEquals("****", rows.get(1).get("PHONE"));
    }
    @Test void previewDoesNotMaterializeLargeObjects() throws Exception {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (var c = db.getConnection(); var st = c.createStatement()) {
            st.execute("CREATE TABLE DOCUMENTS (DEPARTMENT_ID BIGINT, CONTENT CLOB)");
            st.execute("INSERT INTO DOCUMENTS VALUES (1, 'private document')");
        }
        var dataset = new DatasetQueryEngine.Dataset(null, "PUBLIC", "DOCUMENTS", "DEPARTMENT_ID", Set.of("DEPARTMENT_ID", "CONTENT"));
        var policy = new DatasetQueryEngine.Policy(Set.of(1L), Map.of("CONTENT", DatasetAccess.Field.FULL));
        assertEquals("[大文本]", new DatasetQueryEngine().query(db, dataset, policy, List.of("CONTENT"), 1).get(0).get("CONTENT"));
    }
}
