package com.example.governance.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import javax.sql.DataSource;

/** Streaming pull pipeline for FLOW tasks; preview mode caps sources and never writes targets. */
public final class FlowTaskExecutor {
    private static final java.util.regex.Pattern IDENT = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");
    /** durationMs 口径：非 sink 节点为迭代器构建期耗时（惰性排空计入下游 sink），sink 节点含上游排空与写入。 */
    public record NodeStat(String nodeKey, long rowsIn, long rowsOut, long durationMs) {}
    public record Preview(List<String> columns, List<List<Object>> rows) {}
    public record Outcome(List<NodeStat> stats, Map<String, Preview> previews) {}
    private final ObjectMapper mapper = new ObjectMapper();

    public Outcome execute(FlowGraph.Graph graph, CamelTaskRunner.DataSourceProvider provider, LocalDate businessDate, RunCancelContext cancel, ExecutionSinks sinks, int previewLimit) throws Exception {
        FlowGraph.validate(graph);
        // 防御上限：试运行/预览最多收集 1000 行，不信任调用方传入的限额
        int limit = previewLimit > 0 ? Math.min(previewLimit, 1000) : 0;
        var order = FlowGraph.topoOrder(graph);
        var upstream = FlowGraph.upstream(graph);
        var outputs = new LinkedHashMap<String, Iterator<Map<String, Object>>>();
        var columns = new LinkedHashMap<String, List<String>>();
        var stats = new ArrayList<NodeStat>();
        // 惰性迭代器的计数在下游排空后才定型：快照延迟到管道结束，避免创建期记 0
        var nodeCounts = new LinkedHashMap<String, long[]>();
        var durations = new LinkedHashMap<String, Long>();
        var previews = new LinkedHashMap<String, Preview>();
        var closeables = new ArrayList<AutoCloseable>();
        String currentKey = null;
        try {
            for (FlowGraph.Node node : order) {
                currentKey = node.key();
                long started = System.nanoTime();
                sinks.log().log("INFO", "节点 " + node.key() + " (" + node.component() + ") 开始");
                long[] counts = {0, 0};
                Iterator<Map<String, Object>> it;
                List<String> cols;
                switch (node.component()) {
                    case "SOURCE_TABLE", "SOURCE_SQL" -> {
                        DataSource ds = provider.get(node.config().path("sourceId").asLong());
                        Connection connection = ds.getConnection();
                        closeables.add(connection);
                        connection.setReadOnly(true);
                        String sql = node.component().equals("SOURCE_TABLE") ? "SELECT * FROM " + node.config().path("table").textValue() : node.config().path("sql").textValue();
                        PreparedStatement statement = connection.prepareStatement(sql);
                        closeables.add(statement);
                        cancel.register(statement);
                        statement.setFetchSize(500);
                        statement.setQueryTimeout(30);
                        if (node.component().equals("SOURCE_SQL")) bindParameters(statement, node.config().path("parameters"), businessDate);
                        ResultSet rows = statement.executeQuery();
                        closeables.add(rows);
                        cols = new ArrayList<>();
                        for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) cols.add(rows.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT));
                        it = sourceIterator(rows, cols, cancel, limit, counts);
                    }
                    case "FIELD_MAP", "FIELD_FILTER", "FIELD_MASK", "FIELD_CALC" -> {
                        String up = upstream.get(node.key());
                        Iterator<Map<String, Object>> source = outputs.get(up);
                        cols = transformColumns(node, columns.get(up));
                        it = transformIterator(node, source, cols, cancel, counts);
                    }
                    case "SINK_TABLE" -> {
                        String up = upstream.get(node.key());
                        cols = columns.get(up);
                        Iterator<Map<String, Object>> source = outputs.get(up);
                        if (limit > 0) {
                            var collected = new ArrayList<List<Object>>();
                            while (collected.size() < limit && source.hasNext()) {
                                var row = source.next();
                                counts[0]++;
                                collected.add(cols.stream().map(row::get).toList());
                            }
                            counts[1] = collected.size();
                            previews.put(node.key(), new Preview(List.copyOf(cols), collected));
                            it = java.util.Collections.emptyIterator();
                        } else {
                            counts[1] = writeSink(node, provider, cols, source, cancel, counts, sinks);
                            it = java.util.Collections.emptyIterator();
                        }
                    }
                    default -> throw new IllegalArgumentException("Unknown component: " + node.component());
                }
                outputs.put(node.key(), it);
                columns.put(node.key(), cols);
                durations.put(node.key(), (System.nanoTime() - started) / 1_000_000);
                nodeCounts.put(node.key(), counts);
            }
            for (var entry : nodeCounts.entrySet()) {
                long[] counts = entry.getValue();
                stats.add(new NodeStat(entry.getKey(), counts[0], counts[1], durations.get(entry.getKey())));
                sinks.log().log("INFO", "节点 " + entry.getKey() + " 完成: 入 " + counts[0] + " 出 " + counts[1]);
            }
            if (limit == 0) sinks.stats().accept(mapper.writeValueAsString(stats));
            return new Outcome(List.copyOf(stats), previews);
        } catch (Exception ex) {
            // 失败诊断：记当前处理节点后重抛；日志自身失败不得掩盖原因异常
            try { sinks.log().log("ERROR", "节点 " + (currentKey == null ? "流程" : currentKey) + " 处理失败: " + ex.getMessage()); } catch (Exception ignored) { }
            throw ex;
        } finally {
            for (int i = closeables.size() - 1; i >= 0; i--) try { closeables.get(i).close(); } catch (Exception ignored) { }
        }
    }

    private Iterator<Map<String, Object>> sourceIterator(ResultSet rows, List<String> cols, RunCancelContext cancel, int previewLimit, long[] counts) {
        return new Iterator<>() {
            private Map<String, Object> next;
            private boolean done;
            private void advance() {
                if (next != null || done) return;
                try {
                    if (cancel.isCancelled()) throw new InterruptedException("手动停止");
                    if ((previewLimit > 0 && counts[0] >= previewLimit) || !rows.next()) { done = true; return; }
                    var row = new LinkedHashMap<String, Object>();
                    for (int i = 0; i < cols.size(); i++) row.put(cols.get(i), convert(rows.getObject(i + 1)));
                    counts[0]++;
                    next = row;
                } catch (Exception ex) { throw wrap(ex); }
            }
            @Override public boolean hasNext() { advance(); return next != null; }
            @Override public Map<String, Object> next() { advance(); if (next == null) throw new NoSuchElementException(); var r = next; next = null; counts[1]++; return r; }
        };
    }

    private List<String> transformColumns(FlowGraph.Node node, List<String> input) {
        return switch (node.component()) {
            case "FIELD_MAP" -> {
                var cols = new ArrayList<String>();
                for (JsonNode m : node.config().path("mappings")) cols.add(m.path("to").textValue().toLowerCase(Locale.ROOT));
                yield cols;
            }
            case "FIELD_CALC" -> {
                var cols = new ArrayList<>(input);
                String field = node.config().path("field").textValue().toLowerCase(Locale.ROOT);
                if (cols.contains(field)) throw new IllegalArgumentException("节点 " + node.key() + ": 计算字段已存在 " + field);
                cols.add(field);
                yield cols;
            }
            default -> input;
        };
    }

    private Iterator<Map<String, Object>> transformIterator(FlowGraph.Node node, Iterator<Map<String, Object>> source, List<String> cols, RunCancelContext cancel, long[] counts) {
        FlowExpression.Expr filter = node.component().equals("FIELD_FILTER") ? FlowExpression.parse(node.config().path("expression").textValue()) : null;
        FlowExpression.Expr calc = node.component().equals("FIELD_CALC") ? FlowExpression.parse(node.config().path("expression").textValue()) : null;
        // 构建期 hoist：热路径不再遍历 JsonNode
        List<String[]> mappings = new ArrayList<>();
        if (node.component().equals("FIELD_MAP")) for (JsonNode m : node.config().path("mappings")) mappings.add(new String[]{m.path("from").textValue().toLowerCase(Locale.ROOT), m.path("to").textValue().toLowerCase(Locale.ROOT)});
        String field = node.component().equals("FIELD_MASK") || node.component().equals("FIELD_CALC") ? node.config().path("field").textValue().toLowerCase(Locale.ROOT) : null;
        return new Iterator<>() {
            private Map<String, Object> next;
            private void advance() {
                if (next != null) return;
                try {
                    while (source.hasNext()) {
                        if (cancel.isCancelled()) throw new InterruptedException("手动停止");
                        var row = source.next();
                        counts[0]++;
                        switch (node.component()) {
                            case "FIELD_MAP" -> {
                                var mapped = new LinkedHashMap<String, Object>();
                                for (String[] m : mappings) {
                                    if (!row.containsKey(m[0])) throw new IllegalArgumentException("节点 " + node.key() + ": 上游缺少字段 " + m[0]);
                                    mapped.put(m[1], row.get(m[0]));
                                }
                                next = mapped; counts[1]++; return;
                            }
                            case "FIELD_FILTER" -> {
                                if (Boolean.TRUE.equals(filter.eval(row))) { next = row; counts[1]++; return; }
                            }
                            case "FIELD_MASK" -> {
                                if (!row.containsKey(field)) throw new IllegalArgumentException("节点 " + node.key() + ": 上游缺少字段 " + field);
                                var masked = new LinkedHashMap<>(row);
                                masked.put(field, "****");
                                next = masked; counts[1]++; return;
                            }
                            case "FIELD_CALC" -> {
                                var extended = new LinkedHashMap<>(row);
                                extended.put(field, calc.eval(row));
                                next = extended; counts[1]++; return;
                            }
                            default -> throw new IllegalArgumentException("Unknown component");
                        }
                    }
                } catch (Exception ex) { throw wrap(ex); }
            }
            @Override public boolean hasNext() { advance(); return next != null; }
            @Override public Map<String, Object> next() { advance(); if (next == null) throw new NoSuchElementException(); var r = next; next = null; return r; }
        };
    }

    private long writeSink(FlowGraph.Node node, CamelTaskRunner.DataSourceProvider provider, List<String> cols, Iterator<Map<String, Object>> source, RunCancelContext cancel, long[] counts, ExecutionSinks sinks) throws Exception {
        DataSource ds = provider.get(node.config().path("sourceId").asLong());
        String table = node.config().path("table").textValue();
        String mode = node.config().path("writeMode").asText("APPEND");
        int batchSize = node.config().path("batchSize").asInt(500);
        List<String> keys = new ArrayList<>();
        for (JsonNode k : node.config().path("keyColumns")) keys.add(k.textValue().toLowerCase(Locale.ROOT));
        try (Connection target = ds.getConnection()) {
            target.setAutoCommit(false);
            try {
                var targetColumns = new java.util.HashSet<String>();
                var meta = target.getMetaData();
                String[] parts = table.split("\\.");
                // MySQL 限定名首段是 catalog 而非 schema；标识符大小写按库归一（同 verifyUniqueKey）
                boolean mysql = meta.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql");
                String catalog = mysql ? (parts.length == 2 ? parts[0] : target.getCatalog()) : null;
                String schema = mysql ? null : (parts.length == 2 ? parts[0] : target.getSchema());
                String name = parts[parts.length - 1];
                if (meta.storesUpperCaseIdentifiers()) { name = name.toUpperCase(Locale.ROOT); if (schema != null) schema = schema.toUpperCase(Locale.ROOT); }
                else if (meta.storesLowerCaseIdentifiers()) { name = name.toLowerCase(Locale.ROOT); if (schema != null) schema = schema.toLowerCase(Locale.ROOT); }
                try (ResultSet rc = meta.getColumns(catalog, schema, name, null)) {
                    while (rc.next()) targetColumns.add(rc.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
                if (targetColumns.isEmpty()) try (ResultSet rc = meta.getColumns(catalog, schema, name.toUpperCase(Locale.ROOT), null)) {
                    while (rc.next()) targetColumns.add(rc.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
                for (String col : cols) {
                    // 列名直接拼 SQL：白名单拦截非法上游列名，防注入
                    if (!IDENT.matcher(col).matches()) throw new IllegalArgumentException("节点 " + node.key() + ": 列名非法 " + col);
                    if (!targetColumns.contains(col)) throw new IllegalArgumentException("节点 " + node.key() + ": 目标表缺少字段 " + col);
                }
                if ("UPSERT".equals(mode)) DatabaseSyncExecutor.verifyUniqueKey(target, table, keys);
                String placeholders = String.join(",", java.util.Collections.nCopies(cols.size(), "?"));
                String assignments = null;
                String where = null;
                if ("UPSERT".equals(mode)) {
                    var nonKeys = cols.stream().filter(c -> !keys.contains(c)).toList();
                    assignments = nonKeys.isEmpty() ? keys.get(0) + "=" + keys.get(0) : nonKeys.stream().map(c -> c + "=?").collect(java.util.stream.Collectors.joining(","));
                    where = keys.stream().map(k -> k + "=?").collect(java.util.stream.Collectors.joining(" AND "));
                }
                // update 与 insert 同纳入 try-with-resources（非 UPSERT 为 null，自动跳过关闭）
                try (PreparedStatement insert = target.prepareStatement("INSERT INTO " + table + " (" + String.join(",", cols) + ") VALUES (" + placeholders + ")");
                     PreparedStatement update = "UPSERT".equals(mode) ? target.prepareStatement("UPDATE " + table + " SET " + assignments + " WHERE " + where) : null) {
                    cancel.register(insert);
                    insert.setQueryTimeout(30);
                    if (update != null) {
                        update.setQueryTimeout(30);
                        cancel.register(update);
                    }
                    int pending = 0;
                    int batchNo = 0;
                    long written = 0;
                    while (source.hasNext()) {
                        if (cancel.isCancelled()) throw new InterruptedException("手动停止");
                        var row = source.next();
                        counts[0]++;
                        if ("UPSERT".equals(mode)) {
                            int pos = 1;
                            for (String c : cols) if (!keys.contains(c)) update.setObject(pos++, row.get(c));
                            for (String k : keys) {
                                if (!row.containsKey(k)) throw new IllegalArgumentException("节点 " + node.key() + ": 上游缺少字段 " + k);
                                Object v = row.get(k);
                                if (v == null) throw new IllegalArgumentException("节点 " + node.key() + ": 主键值为空 " + k);
                                update.setObject(pos++, v);
                            }
                            int changed = update.executeUpdate();
                            if (changed > 1) throw new IllegalStateException("节点 " + node.key() + ": 主键匹配多行");
                            if (changed == 0) { bind(insert, cols, row); insert.executeUpdate(); }
                        } else {
                            bind(insert, cols, row);
                            insert.addBatch();
                            pending++;
                            if (pending == batchSize) { insert.executeBatch(); written += pending; pending = 0; if (++batchNo % 10 == 0) sinks.log().log("INFO", "节点 " + node.key() + " 已写入 " + written + " 行"); }
                        }
                        counts[1]++;
                    }
                    if (pending > 0) { insert.executeBatch(); written += pending; if (++batchNo % 10 == 0) sinks.log().log("INFO", "节点 " + node.key() + " 已写入 " + written + " 行"); }
                    if (cancel.isCancelled()) throw new InterruptedException("手动停止");
                    target.commit();
                }
            } catch (Exception failure) {
                try { target.rollback(); } catch (Exception rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        }
        return counts[1];
    }
    private static void bind(PreparedStatement statement, List<String> cols, Map<String, Object> row) throws Exception {
        for (int i = 0; i < cols.size(); i++) statement.setObject(i + 1, row.get(cols.get(i)));
    }
    private static void bindParameters(PreparedStatement statement, JsonNode parameters, LocalDate businessDate) throws Exception {
        if (!parameters.isArray()) return;
        int pos = 1;
        for (JsonNode p : parameters) {
            String kind = p.path("type").textValue();
            if ("BUSINESS_DATE".equals(kind)) statement.setObject(pos++, java.sql.Date.valueOf(businessDate));
            else {
                JsonNode v = p.get("value");
                if (v == null || v.isNull()) statement.setObject(pos++, null);
                else if (v.isTextual()) statement.setObject(pos++, v.textValue());
                else if (v.isBoolean()) statement.setObject(pos++, v.booleanValue());
                else statement.setObject(pos++, v.decimalValue());
            }
        }
    }
    static Object convert(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal || value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Number n) return new BigDecimal(n.toString());
        if (value instanceof java.sql.Date || value instanceof java.sql.Timestamp || value instanceof java.sql.Time) return value.toString();
        // MySQL JDBC 可能返回 java.time 类型；二进制透传（sink setObject 支持，预览 Jackson 默认 base64）
        if (value instanceof java.time.LocalDate || value instanceof java.time.LocalDateTime || value instanceof java.time.LocalTime
                || value instanceof java.time.OffsetDateTime || value instanceof java.time.OffsetTime || value instanceof java.time.Instant) return value.toString();
        if (value instanceof byte[]) return value;
        throw new IllegalArgumentException("不支持的字段类型: " + value.getClass().getSimpleName());
    }
    private static RuntimeException wrap(Exception ex) {
        if (ex instanceof RuntimeException r) return r;
        if (ex instanceof InterruptedException i) return new IllegalStateException(i.getMessage(), i);
        return new IllegalStateException(ex.getMessage(), ex);
    }
}
