package com.example.governance.query;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class QueryLimitsTest {
    @Test void rejectsConcurrentQueriesAndReleasesCapacityAfterCompletion() throws Exception {
        var limits = new QueryLimits(1, 7, 25);
        try (var permit = limits.acquire()) {
            assertThrows(java.sql.SQLTransientException.class, limits::acquire);
            assertEquals(7, limits.timeoutSeconds()); assertEquals(25, limits.maxRows());
        }
        try (var permit = limits.acquire()) { assertNotNull(permit); }
    }
    @Test void rejectsUnsafeLimits() {
        assertThrows(IllegalArgumentException.class, () -> new QueryLimits(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new QueryLimits(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new QueryLimits(1, 1, 1001));
    }
    @Test void previewAndAnalysisShareTheSameCapacity() throws Exception {
        var limits = new QueryLimits(1, 5, 20);
        var db = new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:query_limits", "sa", "");
        var dataset = new DatasetQueryEngine.Dataset(null, "PUBLIC", "ITEMS", "DEPT", java.util.Set.of("DEPT", "VALUE"));
        var policy = new DatasetQueryEngine.Policy(java.util.Set.of(1L), java.util.Map.of("VALUE", com.example.governance.security.DatasetAccess.Field.FULL));
        var analysis = new AnalysisQueryEngine.Query(java.util.List.of(), java.util.List.of(new AnalysisQueryEngine.Metric("COUNT", "VALUE")), java.util.List.of(), 10);
        try (var permit = limits.acquire()) {
            assertThrows(java.sql.SQLTransientException.class, () -> new DatasetQueryEngine(limits).query(db, dataset, policy, java.util.List.of("VALUE"), 10));
            assertThrows(java.sql.SQLTransientException.class, () -> new AnalysisQueryEngine(limits).query(db, dataset, policy, analysis));
        }
        assertThrows(IllegalArgumentException.class, () -> new DatasetQueryEngine(limits).query(db, dataset, policy, java.util.List.of("VALUE"), 21));
    }
}
