package com.example.governance.assets;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.DriverManager;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseInspectorTest {
    @Test void discoversTablesAndColumnsUsingJdbcMetadata() throws Exception {
        var datasource = new DriverManagerDataSource("jdbc:h2:mem:inspect;DB_CLOSE_DELAY=-1", "sa", "");
        try (var connection = datasource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE CUSTOMERS (ID BIGINT PRIMARY KEY, NAME VARCHAR(30))");
        }
        var inspector = new DatabaseInspector((definition, password) -> datasource.getConnection());
        var definition = new DataSourceDefinition("test", "POSTGRESQL:general", "localhost", 5432, "test", "sa");
        assertTrue(inspector.test(definition, "password"));
        var tables = inspector.tables(definition, "password", null, "PUBLIC");
        assertTrue(tables.stream().anyMatch(t -> t.name().equals("CUSTOMERS")));
        var columns = inspector.columns(definition, "password", null, "PUBLIC", "CUSTOMERS");
        assertTrue(columns.stream().anyMatch(c -> c.name().equals("ID") && !c.nullable()));
    }
    @Test void failedConnectionsDoNotExposeUnderlyingError() throws Exception {
        var inspector = new DatabaseInspector((definition, password) -> { throw new SQLException("sensitive information"); });
        var definition = new DataSourceDefinition("test", "MYSQL:8.x", "localhost", 3306, "test", "sa");
        assertFalse(inspector.test(definition, "secret"));
        var error = assertThrows(DatabaseInspector.InspectionException.class, () -> inspector.tables(definition, "secret", null, null));
        assertFalse(error.getMessage().contains("sensitive"));
    }
    @Test void probeReturnsDriverErrorButMasksPassword() throws Exception {
        var inspector = new DatabaseInspector((definition, password) -> { throw new SQLException("Access denied with secret123 in message"); });
        var definition = new DataSourceDefinition("test", "MYSQL:8.x", "localhost", 3306, "test", "sa");
        var result = inspector.probe(definition, "secret123");
        assertFalse(result.connected());
        assertTrue(result.error().contains("Access denied"));
        assertFalse(result.error().contains("secret123"));
        var ok = new DatabaseInspector((d, p) -> new DriverManagerDataSource("jdbc:h2:mem:probe_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "").getConnection());
        var success = ok.probe(definition, "password");
        assertTrue(success.connected());
        assertNull(success.error());
    }
    @Test void rejectsAmbiguousTableNamesAcrossSchemas() throws Exception {
        var datasource = new DriverManagerDataSource("jdbc:h2:mem:inspect_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (var c = datasource.getConnection(); var st = c.createStatement()) { st.execute("CREATE SCHEMA OTHER"); st.execute("CREATE TABLE PUBLIC.ITEMS (ID INT)"); st.execute("CREATE TABLE OTHER.ITEMS (SECRET INT)"); }
        var inspector = new DatabaseInspector((definition, password) -> datasource.getConnection());
        var definition = new DataSourceDefinition("test", "POSTGRESQL:general", "localhost", 5432, "test", "sa");
        assertThrows(DatabaseInspector.InspectionException.class, () -> inspector.columns(definition, "password", null, null, "ITEMS"));
    }
    @Test void schemaNamesAreLiteralsRatherThanWildcardPatterns() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:inspect_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (var c = ds.getConnection(); var st = c.createStatement()) { st.execute("CREATE SCHEMA APPXA"); st.execute("CREATE TABLE APPXA.ITEMS (SECRET INT)"); }
        var inspector = new DatabaseInspector((definition, password) -> ds.getConnection());
        var definition = new DataSourceDefinition("test", "POSTGRESQL:general", "localhost", 5432, "test", "sa");
        assertTrue(inspector.columns(definition, "p", null, "APP_A", "ITEMS").isEmpty());
        assertTrue(inspector.tables(definition, "p", null, "APP_A").isEmpty());
        assertEquals("SECRET", inspector.columns(definition, "p", null, "APPXA", "ITEMS").getFirst().name());
    }
}
