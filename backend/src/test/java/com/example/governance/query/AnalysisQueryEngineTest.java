package com.example.governance.query;

import com.example.governance.security.DatasetAccess;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisQueryEngineTest {
    @Test void groupsAndAggregatesOnlyAuthorizedDepartmentRows() throws Exception {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (var c = db.getConnection(); var s = c.createStatement()) {
            s.execute("CREATE TABLE SALES (DEPARTMENT_ID BIGINT, CATEGORY VARCHAR(30), AMOUNT DECIMAL(10,2))");
            s.execute("INSERT INTO SALES VALUES (10,'A',5),(10,'A',7),(20,'A',100)");
        }
        var dataset = new DatasetQueryEngine.Dataset(null, "PUBLIC", "SALES", "DEPARTMENT_ID", Set.of("DEPARTMENT_ID", "CATEGORY", "AMOUNT"));
        var policy = new DatasetQueryEngine.Policy(Set.of(10L), Map.of("CATEGORY", DatasetAccess.Field.FULL, "AMOUNT", DatasetAccess.Field.FULL));
        var query = new AnalysisQueryEngine.Query(List.of("CATEGORY"), List.of(new AnalysisQueryEngine.Metric("SUM", "AMOUNT")), List.of(new AnalysisQueryEngine.Filter("CATEGORY", "EQ", "A")), 100);
        var rows = new AnalysisQueryEngine().query(db, dataset, policy, query);
        assertEquals(1, rows.size()); assertEquals("A", rows.getFirst().get("CATEGORY"));
        assertEquals(0, new java.math.BigDecimal("12").compareTo((java.math.BigDecimal) rows.getFirst().get("metric_0")));
    }
    @Test void refusesMaskedFieldsAndUnregisteredNames() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:x", "sa", "");
        var dataset = new DatasetQueryEngine.Dataset(null, "PUBLIC", "SALES", "DEPARTMENT_ID", Set.of("DEPARTMENT_ID", "AMOUNT"));
        var policy = new DatasetQueryEngine.Policy(Set.of(10L), Map.of("AMOUNT", DatasetAccess.Field.MASKED));
        var query = new AnalysisQueryEngine.Query(List.of(), List.of(new AnalysisQueryEngine.Metric("SUM", "AMOUNT")), List.of(), 10);
        assertThrows(SecurityException.class, () -> new AnalysisQueryEngine().query(db, dataset, policy, query));
    }
    @Test void emptyAggregationPreservesNullAndRejectsMissingOperation() throws Exception {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (var c = db.getConnection(); var st = c.createStatement()) { st.execute("CREATE TABLE SALES (DEPARTMENT_ID BIGINT, AMOUNT INT)"); }
        var dataset = new DatasetQueryEngine.Dataset(null, "PUBLIC", "SALES", "DEPARTMENT_ID", Set.of("DEPARTMENT_ID", "AMOUNT"));
        var policy = new DatasetQueryEngine.Policy(Set.of(1L), Map.of("AMOUNT", DatasetAccess.Field.FULL));
        var result = new AnalysisQueryEngine().query(db, dataset, policy, new AnalysisQueryEngine.Query(List.of(), List.of(new AnalysisQueryEngine.Metric("SUM", "AMOUNT")), List.of(), 10));
        assertNull(result.getFirst().get("metric_0"));
        assertThrows(IllegalArgumentException.class, () -> new AnalysisQueryEngine().query(db, dataset, policy, new AnalysisQueryEngine.Query(List.of(), List.of(new AnalysisQueryEngine.Metric(null, "AMOUNT")), List.of(), 10)));
    }
    @Test void sortsBySelectedAggregateAndRejectsOtherColumns() throws Exception {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (var c = db.getConnection(); var st = c.createStatement()) { st.execute("CREATE TABLE SALES (DEPARTMENT_ID BIGINT, CATEGORY VARCHAR(30), AMOUNT INT)"); st.execute("INSERT INTO SALES VALUES (1,'A',3),(1,'B',10)"); }
        var dataset = new DatasetQueryEngine.Dataset(null, "PUBLIC", "SALES", "DEPARTMENT_ID", Set.of("DEPARTMENT_ID", "CATEGORY", "AMOUNT"));
        var policy = new DatasetQueryEngine.Policy(Set.of(1L), Map.of("CATEGORY", DatasetAccess.Field.FULL, "AMOUNT", DatasetAccess.Field.FULL));
        var query = new AnalysisQueryEngine.Query(List.of("CATEGORY"), List.of(new AnalysisQueryEngine.Metric("SUM", "AMOUNT")), List.of(), 10, List.of(new AnalysisQueryEngine.Sort("metric_0", "DESC")));
        assertEquals("B", new AnalysisQueryEngine().query(db, dataset, policy, query).getFirst().get("CATEGORY"));
        var invalid = new AnalysisQueryEngine.Query(List.of("CATEGORY"), query.metrics(), List.of(), 10, List.of(new AnalysisQueryEngine.Sort("AMOUNT", "DESC")));
        assertThrows(IllegalArgumentException.class, () -> new AnalysisQueryEngine().query(db, dataset, policy, invalid));
    }
}
