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

    public boolean test(DataSourceDefinition definition, String password) {
        try (Connection connection = connector.connect(definition, password)) { return connection.isValid(5); }
        catch (SQLException ex) { return false; }
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
    public List<Table> tables(DataSourceDefinition definition, String password, String catalog, String schema) throws InspectionException {
        try (var connection = connector.connect(definition, password)) {
            var metadata = connection.getMetaData();
            try (var rows = metadata.getTables(catalog, literalPattern(schema, metadata.getSearchStringEscape()), "%", new String[]{"TABLE", "VIEW"})) {
                List<Table> result = new ArrayList<>();
                while (rows.next() && result.size() < MAX_RESULTS) {
                    var actualCatalog = rows.getString("TABLE_CAT"); var actualSchema = rows.getString("TABLE_SCHEM");
                    if (!matchesRequested(catalog, actualCatalog) || !matchesRequested(schema, actualSchema)) throw new SQLException("Table identity mismatch");
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
            String escape = metadata.getSearchStringEscape();
            String pattern = literalPattern(table, escape);
            try (var rows = metadata.getColumns(catalog, literalPattern(schema, escape), pattern, "%")) {
                List<Column> result = new ArrayList<>();
                String[] identity = null;
                while (rows.next()) {
                    if (result.size() >= MAX_RESULTS) throw new SQLException("Too many columns");
                    String actualTable = rows.getString("TABLE_NAME");
                    String[] current = { rows.getString("TABLE_CAT"), rows.getString("TABLE_SCHEM"), actualTable };
                    if (!matchesRequested(catalog, current[0]) || !matchesRequested(schema, current[1]) || actualTable == null || !actualTable.equals(table)) throw new SQLException("Table identity mismatch");
                    if (identity == null) identity = current;
                    else if (!java.util.Arrays.equals(identity, current)) throw new SQLException("Ambiguous table identity");
                    result.add(new Column(rows.getString("COLUMN_NAME"), rows.getString("TYPE_NAME"), rows.getInt("COLUMN_SIZE"), rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls));
                }
                return List.copyOf(result);
            }
        } catch (SQLException ex) { throw new InspectionException(); }
    }
}
