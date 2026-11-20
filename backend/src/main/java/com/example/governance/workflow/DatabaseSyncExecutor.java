package com.example.governance.workflow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;

/** Application-level batches; all target writes use one local transaction. Driver buffering varies. */
public final class DatabaseSyncExecutor implements AutoCloseable {
    private record Request(DataSource source, DataSource target, String query, List<?> parameters, String table, List<String> columns, List<String> keys, int batchSize, RunCancelContext cancel) {}
    private final DefaultCamelContext context = new DefaultCamelContext();
    private final ProducerTemplate producer;
    public DatabaseSyncExecutor() throws Exception {
        context.addRoutes(new RouteBuilder() {
            @Override public void configure() {
                errorHandler(noErrorHandler());
                from("direct:sync-data").process(exchange -> {
                    var request = exchange.getMessage().getBody(Request.class);
                    String placeholders = String.join(",", Collections.nCopies(request.columns().size(), "?"));
                    String insert = "INSERT INTO " + request.table() + " (" + String.join(",", request.columns()) + ") VALUES (" + placeholders + ")";
                    try (var source = request.source().getConnection(); var target = request.target().getConnection()) {
                        source.setReadOnly(true); target.setAutoCommit(false);
                        try {
                            if (!request.keys().isEmpty()) verifyUniqueKey(target, request.table(), request.keys());
                            try (var read = source.prepareStatement(request.query()); var write = target.prepareStatement(insert)) {
                                read.setFetchSize(request.batchSize()); read.setQueryTimeout(30); write.setQueryTimeout(30);
                                for (int i = 0; i < request.parameters().size(); i++) read.setObject(i + 1, request.parameters().get(i));
                                int count;
                                try (var rows = read.executeQuery()) {
                                    if (rows.getMetaData().getColumnCount() != request.columns().size()) throw new IllegalArgumentException("Column count mismatch");
                                    count = request.keys().isEmpty() ? appendRows(rows, write, request.columns().size(), request.batchSize(), request.cancel()) : upsertRows(rows, write, target, request);
                                }
                                target.commit(); exchange.getMessage().setBody(count);
                            }
                        } catch (Exception failure) {
                            try { target.rollback(); } catch (Exception rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                            throw failure;
                        }
                    }
                });
            }
        });
        context.start(); producer = context.createProducerTemplate();
    }
    private static int appendRows(ResultSet rows, PreparedStatement write, int width, int batchSize, RunCancelContext cancel) throws Exception {
        int count = 0, pending = 0;
        while (rows.next()) {
            if (cancel.isCancelled()) throw new InterruptedException("手动停止");
            for (int i = 0; i < width; i++) write.setObject(i + 1, rows.getObject(i + 1));
            write.addBatch(); count++; pending++;
            if (pending == batchSize) { checkBatch(write.executeBatch()); pending = 0; }
        }
        if (pending > 0) checkBatch(write.executeBatch());
        return count;
    }
    private static void checkBatch(int[] results) throws java.sql.SQLException {
        for (int result : results) if (result == java.sql.Statement.EXECUTE_FAILED) throw new java.sql.SQLException("Batch write failed");
    }
    private static int upsertRows(ResultSet rows, PreparedStatement insert, Connection target, Request request) throws Exception {
        List<String> nonKeys = request.columns().stream().filter(column -> request.keys().stream().noneMatch(key -> key.equalsIgnoreCase(column))).toList();
        String assignments = nonKeys.isEmpty() ? request.keys().getFirst() + "=" + request.keys().getFirst() : nonKeys.stream().map(column -> column + "=?").collect(java.util.stream.Collectors.joining(","));
        String where = request.keys().stream().map(key -> key + "=?").collect(java.util.stream.Collectors.joining(" AND "));
        try (var update = target.prepareStatement("UPDATE " + request.table() + " SET " + assignments + " WHERE " + where)) {
            update.setQueryTimeout(30); int count = 0;
            while (rows.next()) {
                if (request.cancel().isCancelled()) throw new InterruptedException("手动停止");
                Object[] values = new Object[request.columns().size()];
                for (int i = 0; i < values.length; i++) values[i] = rows.getObject(i + 1);
                int position = 1;
                for (String column : nonKeys) update.setObject(position++, values[indexOf(request.columns(), column)]);
                for (String key : request.keys()) {
                    Object value = values[indexOf(request.columns(), key)];
                    if (value == null) throw new IllegalArgumentException("Primary key value is null");
                    update.setObject(position++, value);
                }
                int changed = update.executeUpdate();
                if (changed > 1) throw new IllegalStateException("Primary key matched multiple rows");
                if (changed == 0) { for (int i = 0; i < values.length; i++) insert.setObject(i + 1, values[i]); insert.executeUpdate(); }
                count++;
            }
            return count;
        }
    }
    public static int indexOf(List<String> columns, String value) {
        for (int i = 0; i < columns.size(); i++) if (columns.get(i).equalsIgnoreCase(value)) return i;
        throw new IllegalArgumentException("Key column missing");
    }
    public static void verifyUniqueKey(Connection target, String table, List<String> keys) throws Exception {
        try { verifyPrimaryKey(target, table, keys); return; } catch (IllegalArgumentException notPrimary) { /* Check a declared unique index below. */ }
        var metadata = target.getMetaData(); String[] parts = table.split("\\.");
        boolean mysql = metadata.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql");
        String schema = mysql ? null : parts.length == 2 ? parts[0] : target.getSchema();
        String name = parts[parts.length - 1];
        if (metadata.storesUpperCaseIdentifiers()) { name = name.toUpperCase(Locale.ROOT); if (schema != null) schema = schema.toUpperCase(Locale.ROOT); }
        else if (metadata.storesLowerCaseIdentifiers()) { name = name.toLowerCase(Locale.ROOT); if (schema != null) schema = schema.toLowerCase(Locale.ROOT); }
        String catalog = mysql && parts.length == 2 ? parts[0] : target.getCatalog();
        var indexes = new java.util.HashMap<String, Set<String>>(); var invalid = new HashSet<String>();
        try (var rows = metadata.getIndexInfo(catalog, schema, name, true, false)) {
            while (rows.next()) {
                String index = rows.getString("INDEX_NAME"), column = rows.getString("COLUMN_NAME"), filter = rows.getString("FILTER_CONDITION");
                if (index == null) continue;
                if (rows.getBoolean("NON_UNIQUE") || column == null || (filter != null && !filter.isBlank())) { invalid.add(index); continue; }
                indexes.computeIfAbsent(index, ignored -> new HashSet<>()).add(column.toLowerCase(Locale.ROOT));
            }
        }
        var configured = keys.stream().map(key -> key.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        boolean found = false;
        for (var entry : indexes.entrySet()) {
            if (invalid.contains(entry.getKey()) || !entry.getValue().equals(configured)) continue;
            if (metadata.getDatabaseProductName().equalsIgnoreCase("PostgreSQL")) {
                // PostgreSQL JDBC may not expose a partial-index predicate via FILTER_CONDITION.
                try (var st = target.prepareStatement("SELECT 1 FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class t ON t.oid=i.indrelid JOIN pg_catalog.pg_class x ON x.oid=i.indexrelid JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname=? AND t.relname=? AND x.relname=? AND i.indpred IS NULL AND i.indexprs IS NULL AND i.indisvalid AND i.indisready")) {
                    st.setString(1, schema); st.setString(2, name); st.setString(3, entry.getKey());
                    try (var rows = st.executeQuery()) { if (!rows.next()) continue; }
                }
            }
            found = true; break;
        }
        if (!found) throw new IllegalArgumentException("Configured keys must match an unfiltered unique index");
    }
    public static void verifyPrimaryKey(Connection target, String table, List<String> keys) throws Exception {
        var metadata = target.getMetaData(); String[] parts = table.split("\\.");
        boolean mysql = metadata.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql");
        String schema = mysql ? null : parts.length == 2 ? parts[0] : target.getSchema();
        String tableName = parts[parts.length - 1];
        if (metadata.storesUpperCaseIdentifiers()) { tableName = tableName.toUpperCase(Locale.ROOT); if (schema != null) schema = schema.toUpperCase(Locale.ROOT); }
        else if (metadata.storesLowerCaseIdentifiers()) { tableName = tableName.toLowerCase(Locale.ROOT); if (schema != null) schema = schema.toLowerCase(Locale.ROOT); }
        String catalog = target.getCatalog();
        if (mysql) { if (parts.length == 2) catalog = parts[0]; schema = null; }
        Set<String> primary = new HashSet<>();
        try (var rows = metadata.getPrimaryKeys(catalog, schema, tableName)) { while (rows.next()) primary.add(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT)); }
        var configured = keys.stream().map(key -> key.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        if (primary.isEmpty() || !primary.equals(configured)) throw new IllegalArgumentException("Configured keys must match target primary key");
    }
    public int append(DataSource source, DataSource target, String query, String table, List<String> columns, int batchSize) { return append(source, target, query, List.of(), table, columns, batchSize); }
    public int append(DataSource source, DataSource target, String query, List<?> parameters, String table, List<String> columns, int batchSize) { return append(source, target, query, parameters, table, columns, batchSize, new RunCancelContext()); }
    public int append(DataSource source, DataSource target, String query, List<?> parameters, String table, List<String> columns, int batchSize, RunCancelContext cancel) { return execute(source, target, query, parameters, table, columns, List.of(), batchSize, cancel); }
    public int upsert(DataSource source, DataSource target, String query, List<?> parameters, String table, List<String> columns, List<String> keys, int batchSize) { return upsert(source, target, query, parameters, table, columns, keys, batchSize, new RunCancelContext()); }
    public int upsert(DataSource source, DataSource target, String query, List<?> parameters, String table, List<String> columns, List<String> keys, int batchSize, RunCancelContext cancel) {
        if (keys == null || keys.isEmpty() || keys.stream().anyMatch(key -> !validIdentifier(key)) || keys.stream().map(key -> key.toLowerCase(Locale.ROOT)).distinct().count() != keys.size() || columns == null || keys.stream().anyMatch(key -> columns.stream().noneMatch(column -> column != null && column.equalsIgnoreCase(key)))) throw new IllegalArgumentException("Invalid primary keys");
        return execute(source, target, query, parameters, table, columns, List.copyOf(keys), batchSize, cancel);
    }
    private int execute(DataSource source, DataSource target, String query, List<?> parameters, String table, List<String> columns, List<String> keys, int batchSize, RunCancelContext cancel) {
        if (source == null || target == null || query == null || query.isBlank() || parameters == null || parameters.size() > 1000 || !validQualifiedIdentifier(table) || columns == null || columns.isEmpty() || columns.size() > 1000 || columns.stream().anyMatch(c -> !validIdentifier(c)) || columns.stream().map(c -> c.toLowerCase(Locale.ROOT)).distinct().count() != columns.size() || batchSize < 1 || batchSize > 10000) throw new IllegalArgumentException("Invalid synchronization configuration");
        return producer.requestBody("direct:sync-data", new Request(source, target, query, Collections.unmodifiableList(new ArrayList<>(parameters)), table, List.copyOf(columns), keys, batchSize, cancel), Integer.class);
    }
    public static boolean validIdentifier(String value) { return value != null && value.matches("[A-Za-z_][A-Za-z0-9_$]*"); }
    public static boolean validQualifiedIdentifier(String value) { if (value == null) return false; String[] parts = value.split("\\.", -1); return parts.length <= 2 && java.util.Arrays.stream(parts).allMatch(DatabaseSyncExecutor::validIdentifier); }
    @Override public void close() { producer.stop(); context.stop(); }
}
