package com.example.governance.assets;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Read-only JDBC discovery. The caller enforces authorization and handles secret access. */
public final class DatabaseInspector {
    private static final int MAX_RESULTS = 1000;
    @FunctionalInterface interface Connector { Connection connect(DataSourceDefinition definition, String password) throws SQLException; }
    public record Table(String catalog, String schema, String name, String type) {}
    public record Column(String name, String type, int size, boolean nullable) {}
    public static final class InspectionException extends Exception {
        InspectionException() { super("Database inspection failed"); }
    }
    private final Connector connector;
    DatabaseInspector(Connector connector) { this.connector = connector; }
    public DatabaseInspector(com.example.governance.assets.driver.ConnectionFactory factory) { this(factory::open); }

    public record TestResult(boolean connected, String error) {}

    /** Connection probe for the test button: returns the driver error so the UI can show it, with the password masked. */
    public TestResult probe(DataSourceDefinition definition, String password) {
        try (Connection connection = connector.connect(definition, password)) {
            return connection.isValid(5) ? new TestResult(true, null) : new TestResult(false, "连接校验超时，驱动未确认连接有效");
        } catch (SQLException ex) {
            return new TestResult(false, sanitize(ex.getMessage(), password));
        } catch (IllegalArgumentException | NullPointerException ex) {
            return new TestResult(false, sanitize("连接配置无效：" + ex.getMessage(), password));
        }
    }

    private static String sanitize(String message, String password) {
        if (message == null || message.isBlank()) return "连接失败，请检查配置";
        if (password != null && !password.isEmpty()) message = message.replace(password, "******");
        return message;
    }

    public boolean test(DataSourceDefinition definition, String password) {
        return probe(definition, password).connected();
    }

    private static String literalPattern(String value, String escape) throws SQLException {
        if (value == null) return null;
        if (escape == null || escape.isEmpty()) {
            if (value.contains("_") || value.contains("%")) throw new SQLException("Metadata wildcard escaping unavailable");
            return value;
        }
        return value.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }
    private static boolean matchesRequested(String requested, String actual) { return requested == null || java.util.Objects.equals(requested, actual); }
    private record MetadataScope(String catalog, String schema) {}
    private static String nonBlank(String value) { return value == null || value.isBlank() ? null : value; }
    /** MySQL uses catalogs for databases; keep metadata inspection within the database selected by the connection URL. */
    private static MetadataScope metadataScope(Connection connection, DataSourceDefinition definition, String catalog, String schema) throws SQLException {
        if (!"MYSQL".equalsIgnoreCase(definition.type())) return new MetadataScope(catalog, schema);
        String currentCatalog = nonBlank(connection.getCatalog());
        if (currentCatalog == null) currentCatalog = definition.database();
        String requestedCatalog = nonBlank(catalog);
        String requestedSchema = nonBlank(schema);
        if (!matchesRequested(requestedCatalog, currentCatalog) || !matchesRequested(requestedSchema, currentCatalog))
            throw new SQLException("Requested database is outside the configured data source");
        return new MetadataScope(currentCatalog, null);
    }
    /** MySQL-style drivers report the database as catalog and leave schema null; present catalog as schema for a consistent identity. */
    private static String schemaOrCatalog(String schema, String catalog) { return schema == null || schema.isBlank() ? catalog : schema; }
    public List<Table> tables(DataSourceDefinition definition, String password, String catalog, String schema) throws InspectionException {
        try (var connection = connector.connect(definition, password)) {
            var metadata = connection.getMetaData();
            var scope = metadataScope(connection, definition, catalog, schema);
            try (var rows = metadata.getTables(scope.catalog(), literalPattern(scope.schema(), metadata.getSearchStringEscape()), "%", new String[]{"TABLE", "VIEW"})) {
                List<Table> result = new ArrayList<>();
                while (rows.next() && result.size() < MAX_RESULTS) {
                    var actualCatalog = rows.getString("TABLE_CAT");
                    var rawSchema = rows.getString("TABLE_SCHEM");
                    var actualSchema = "MYSQL".equalsIgnoreCase(definition.type()) ? schemaOrCatalog(rawSchema, actualCatalog) : rawSchema;
                    if (!matchesRequested(scope.catalog(), actualCatalog) || !matchesRequested(scope.schema(), actualSchema)) throw new SQLException("Table identity mismatch");
                    result.add(new Table(actualCatalog, actualSchema, rows.getString("TABLE_NAME"), rows.getString("TABLE_TYPE")));
                }
                return List.copyOf(result);
            }
        } catch (SQLException ex) { throw new InspectionException(); }
    }

    public List<Column> columns(DataSourceDefinition definition, String password, String catalog, String schema, String table) throws InspectionException {
        if (table == null || table.isBlank()) throw new IllegalArgumentException("Table is required");
        try (var connection = connector.connect(definition, password)) {
            DatabaseMetaData metadata = connection.getMetaData();
            var scope = metadataScope(connection, definition, catalog, schema);
            String escape = metadata.getSearchStringEscape();
            String pattern = literalPattern(table, escape);
            try (var rows = metadata.getColumns(scope.catalog(), literalPattern(scope.schema(), escape), pattern, "%")) {
                List<Column> result = new ArrayList<>();
                String[] identity = null;
                while (rows.next()) {
                    if (result.size() >= MAX_RESULTS) throw new SQLException("Too many columns");
                    String actualTable = rows.getString("TABLE_NAME");
                    String actualCatalog = rows.getString("TABLE_CAT");
                    String rawSchema = rows.getString("TABLE_SCHEM");
                    String actualSchema = "MYSQL".equalsIgnoreCase(definition.type()) ? schemaOrCatalog(rawSchema, actualCatalog) : rawSchema;
                    String[] current = { actualCatalog, actualSchema, actualTable };
                    if (!matchesRequested(scope.catalog(), current[0]) || !matchesRequested(scope.schema(), current[1]) || actualTable == null || !actualTable.equals(table)) throw new SQLException("Table identity mismatch");
                    if (identity == null) identity = current;
                    else if (!java.util.Arrays.equals(identity, current)) throw new SQLException("Ambiguous table identity");
                    result.add(new Column(rows.getString("COLUMN_NAME"), rows.getString("TYPE_NAME"), rows.getInt("COLUMN_SIZE"), rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls));
                }
                return List.copyOf(result);
            }
        } catch (SQLException ex) { throw new InspectionException(); }
    }
}
