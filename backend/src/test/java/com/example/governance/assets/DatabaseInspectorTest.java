package com.example.governance.assets;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.Connection;
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
    @Test void mysqlStyleNullSchemaFallsBackToCatalog() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:inspect_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (var c = ds.getConnection(); var st = c.createStatement()) { st.execute("CREATE TABLE BOOK (ID INT)"); }
        var inspector = new DatabaseInspector((d, p) -> mysqlLike(ds.getConnection()));
        var definition = new DataSourceDefinition("test", "MYSQL:8.x", "localhost", 3306, "sparta1", "sa");
        var tables = inspector.tables(definition, "p", null, null);
        var book = tables.stream().filter(t -> t.name().equals("BOOK")).findFirst().orElseThrow();
        assertNotNull(book.schema());
        assertEquals(book.catalog(), book.schema());
        var columns = inspector.columns(definition, "p", book.catalog(), book.schema(), "BOOK");
        assertEquals("ID", columns.getFirst().name());
    }
    /** Models MySQL drivers: database lives in TABLE_CAT, TABLE_SCHEM is null and schema patterns are ignored. */
    private static Connection mysqlLike(Connection real) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            if (method.getName().equals("getMetaData")) return mysqlLikeMetaData(real.getMetaData());
            return method.invoke(real, args);
        });
    }
    private static java.sql.DatabaseMetaData mysqlLikeMetaData(java.sql.DatabaseMetaData real) {
        return (java.sql.DatabaseMetaData) java.lang.reflect.Proxy.newProxyInstance(java.sql.DatabaseMetaData.class.getClassLoader(), new Class<?>[]{java.sql.DatabaseMetaData.class}, (proxy, method, args) -> {
            if (method.getName().equals("getTables") || method.getName().equals("getColumns")) {
                args[1] = null;
                return nullSchema((java.sql.ResultSet) method.invoke(real, args));
            }
            return method.invoke(real, args);
        });
    }
    private static java.sql.ResultSet nullSchema(java.sql.ResultSet real) {
        return (java.sql.ResultSet) java.lang.reflect.Proxy.newProxyInstance(java.sql.ResultSet.class.getClassLoader(), new Class<?>[]{java.sql.ResultSet.class}, (proxy, method, args) -> {
            if (method.getName().equals("getString") && args != null && args.length == 1 && "TABLE_SCHEM".equals(args[0])) return null;
            return method.invoke(real, args);
        });
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
