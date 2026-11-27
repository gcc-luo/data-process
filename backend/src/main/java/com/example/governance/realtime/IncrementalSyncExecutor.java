package com.example.governance.realtime;

import com.example.governance.workflow.DatabaseSyncExecutor;
import java.sql.Connection;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;

/** One bounded poll; target upsert commits before its returned cursor may be persisted. */
public final class IncrementalSyncExecutor implements AutoCloseable {
    public record Config(String sourceTable, String targetTable, List<String> sourceColumns, List<String> targetColumns, String watermarkColumn, String tieColumn, List<String> targetKeys, String watermarkType, int batchSize) {}
    public record Cursor(String watermark, long tie) {}
    public record Result(int count, Cursor cursor) {}
    private record Request(DataSource source, DataSource target, Config config, Cursor cursor) {}
    private final DefaultCamelContext context = new DefaultCamelContext();
    private final ProducerTemplate producer;
    public IncrementalSyncExecutor() throws Exception {
        context.addRoutes(new RouteBuilder() {
            @Override public void configure() {
                errorHandler(noErrorHandler());
                from("direct:incremental-sync").process(exchange -> {
                    var request = exchange.getMessage().getBody(Request.class);
                    exchange.getMessage().setBody(execute(request));
                });
            }
        });
        context.start(); producer = context.createProducerTemplate();
    }
    public Result poll(DataSource source, DataSource target, Config config, Cursor cursor) {
        validate(source, target, config, cursor);
        return producer.requestBody("direct:incremental-sync", new Request(source, target, config, cursor), Result.class);
    }
    private static Result execute(Request request) throws Exception {
        var c = request.config();
        String where = request.cursor() == null ? "" : " WHERE (" + c.watermarkColumn() + " > ? OR (" + c.watermarkColumn() + " = ? AND " + c.tieColumn() + " > ?))";
        String query = "SELECT " + String.join(",", c.sourceColumns()) + "," + c.watermarkColumn() + "," + c.tieColumn() + " FROM " + c.sourceTable() + where + " ORDER BY " + c.watermarkColumn() + "," + c.tieColumn();
        String insertSql = "INSERT INTO " + c.targetTable() + " (" + String.join(",", c.targetColumns()) + ") VALUES (" + String.join(",", java.util.Collections.nCopies(c.targetColumns().size(), "?")) + ")";
        List<String> values = c.targetColumns().stream().filter(column -> c.targetKeys().stream().noneMatch(key -> key.equalsIgnoreCase(column))).toList();
        String assignments = values.isEmpty() ? c.targetKeys().get(0) + "=" + c.targetKeys().get(0) : values.stream().map(column -> column + "=?").collect(java.util.stream.Collectors.joining(","));
        String predicate = c.targetKeys().stream().map(key -> key + "=?").collect(java.util.stream.Collectors.joining(" AND "));
        try (var source = request.source().getConnection(); var target = request.target().getConnection()) {
            source.setReadOnly(true); target.setAutoCommit(false);
            try {
                DatabaseSyncExecutor.verifyPrimaryKey(source, c.sourceTable(), List.of(c.tieColumn()));
                DatabaseSyncExecutor.verifyPrimaryKey(target, c.targetTable(), c.targetKeys());
                try (var read = source.prepareStatement(query); var insert = target.prepareStatement(insertSql); var update = target.prepareStatement("UPDATE " + c.targetTable() + " SET " + assignments + " WHERE " + predicate)) {
                    read.setMaxRows(c.batchSize()); read.setFetchSize(c.batchSize()); read.setQueryTimeout(30); insert.setQueryTimeout(30); update.setQueryTimeout(30);
                    if (request.cursor() != null) {
                        Object value = "LONG".equals(c.watermarkType()) ? Long.valueOf(request.cursor().watermark()) : Timestamp.valueOf(LocalDateTime.parse(request.cursor().watermark()));
                        read.setObject(1, value); read.setObject(2, value); read.setLong(3, request.cursor().tie());
                    }
                    int count = 0; Cursor last = request.cursor();
                    try (var rows = read.executeQuery()) {
                        int width = c.sourceColumns().size(), type = rows.getMetaData().getColumnType(width + 1);
                        if ("LONG".equals(c.watermarkType()) && !List.of(Types.BIGINT, Types.INTEGER, Types.SMALLINT, Types.TINYINT, Types.NUMERIC, Types.DECIMAL).contains(type)) throw new IllegalArgumentException("Watermark must be an integer column");
                        if ("TIMESTAMP".equals(c.watermarkType()) && type != Types.TIMESTAMP) throw new IllegalArgumentException("Watermark must be a timestamp without time zone");
                        while (rows.next()) {
                            Object[] row = new Object[width]; for (int i = 0; i < width; i++) row[i] = rows.getObject(i + 1);
                            String watermark;
                            if ("LONG".equals(c.watermarkType())) { var number = rows.getBigDecimal(width + 1); if (number == null) throw new IllegalArgumentException("Null watermark"); watermark = Long.toString(number.longValueExact()); }
                            else { var timestamp = rows.getTimestamp(width + 1); if (timestamp == null) throw new IllegalArgumentException("Null watermark"); watermark = timestamp.toLocalDateTime().toString(); }
                            var tieNumber = rows.getBigDecimal(width + 2); if (tieNumber == null) throw new IllegalArgumentException("Null tie key"); long tie = tieNumber.longValueExact();
                            int index = 1;
                            for (String column : values) update.setObject(index++, row[DatabaseSyncExecutor.indexOf(c.targetColumns(), column)]);
                            for (String key : c.targetKeys()) { Object value = row[DatabaseSyncExecutor.indexOf(c.targetColumns(), key)]; if (value == null) throw new IllegalArgumentException("Null target key"); update.setObject(index++, value); }
                            int changed = update.executeUpdate();
                            if (changed > 1) throw new IllegalStateException("Target key matched multiple rows");
                            if (changed == 0) { for (int i = 0; i < width; i++) insert.setObject(i + 1, row[i]); insert.executeUpdate(); }
                            last = new Cursor(watermark, tie); count++;
                        }
                    }
                    target.commit(); return new Result(count, last);
                }
            } catch (Exception failure) {
                try { target.rollback(); } catch (Exception rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        }
    }
    public static void validate(DataSource source, DataSource target, Config c, Cursor cursor) {
        if (source == null || target == null) throw new IllegalArgumentException("Invalid data source");
        validateConfig(c, cursor);
    }
    public static void validateConfig(Config c, Cursor cursor) {
        if (c == null || !DatabaseSyncExecutor.validQualifiedIdentifier(c.sourceTable()) || !DatabaseSyncExecutor.validQualifiedIdentifier(c.targetTable()) || !DatabaseSyncExecutor.validIdentifier(c.watermarkColumn()) || !DatabaseSyncExecutor.validIdentifier(c.tieColumn()) || c.watermarkType() == null || !List.of("LONG", "TIMESTAMP").contains(c.watermarkType()) || c.batchSize() < 1 || c.batchSize() > 1000 || c.sourceColumns() == null || c.targetColumns() == null || c.targetKeys() == null || c.sourceColumns().isEmpty() || c.sourceColumns().size() != c.targetColumns().size() || c.sourceColumns().size() > 200 || c.targetKeys().isEmpty() || c.sourceColumns().stream().anyMatch(x -> !DatabaseSyncExecutor.validIdentifier(x)) || c.targetColumns().stream().anyMatch(x -> !DatabaseSyncExecutor.validIdentifier(x)) || c.targetKeys().stream().anyMatch(x -> !DatabaseSyncExecutor.validIdentifier(x)) || c.sourceColumns().stream().map(x -> x.toLowerCase(Locale.ROOT)).distinct().count() != c.sourceColumns().size() || c.targetColumns().stream().map(x -> x.toLowerCase(Locale.ROOT)).distinct().count() != c.targetColumns().size() || c.targetKeys().stream().map(x -> x.toLowerCase(Locale.ROOT)).distinct().count() != c.targetKeys().size() || c.targetKeys().stream().anyMatch(k -> c.targetColumns().stream().noneMatch(x -> x.equalsIgnoreCase(k)))) throw new IllegalArgumentException("Invalid incremental configuration");
        if (cursor != null) {
            try { if ("LONG".equals(c.watermarkType())) Long.parseLong(cursor.watermark()); else LocalDateTime.parse(cursor.watermark()); }
            catch (RuntimeException ex) { throw new IllegalArgumentException("Invalid incremental cursor"); }
        }
    }
    @Override public void close() { producer.stop(); context.stop(); }
}
