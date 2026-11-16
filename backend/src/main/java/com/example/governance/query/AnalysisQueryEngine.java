package com.example.governance.query;

import com.example.governance.security.DatasetAccess;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/** Parameterized, bounded aggregation over authorized departments and full-access fields. */
public final class AnalysisQueryEngine {
    private final QueryLimits limits;
    public AnalysisQueryEngine() { this(new QueryLimits(8, 30, 1000)); }
    public AnalysisQueryEngine(QueryLimits limits) { this.limits = java.util.Objects.requireNonNull(limits); }
    public record Metric(String operation, String field) {}
    public record Filter(String field, String operation, Object value) {}
    public record Sort(String field, String direction) {}
    public record Query(List<String> groups, List<Metric> metrics, List<Filter> filters, int limit, List<Sort> sort) {
        public Query(List<String> groups, List<Metric> metrics, List<Filter> filters, int limit) { this(groups, metrics, filters, limit, List.of()); }
        public Query { if (sort == null) sort = List.of(); }
    }
    public List<Map<String, Object>> query(DataSource source, DatasetQueryEngine.Dataset dataset, DatasetQueryEngine.Policy policy, Query query) throws SQLException {
        if (source == null || dataset == null || policy == null || policy.departments().isEmpty() || policy.departments().size() > 1000) throw new SecurityException("Dataset access denied");
        if (query == null || query.groups() == null || query.metrics() == null || query.filters() == null || query.groups().size() > 20 || query.metrics().isEmpty() || query.metrics().size() > 20 || query.filters().size() > 20 || query.limit() < 1 || query.limit() > limits.maxRows() || new HashSet<>(query.groups()).size() != query.groups().size()) throw new IllegalArgumentException("Invalid analysis query");
        if (dataset.departmentField() == null || !dataset.fields().contains(dataset.departmentField())) throw new SecurityException("Dataset policy unavailable");
        Set<String> used = new HashSet<>(query.groups());
        for (var metric : query.metrics()) {
            if (metric == null || metric.operation() == null || !Set.of("COUNT", "SUM", "AVG", "MIN", "MAX").contains(metric.operation())) throw new IllegalArgumentException("Invalid metric");
            if (metric.field() == null || metric.field().isBlank()) { if (!"COUNT".equals(metric.operation())) throw new IllegalArgumentException("Metric field required"); }
            else used.add(metric.field());
        }
        for (var filter : query.filters()) {
            if (filter == null || filter.field() == null || filter.operation() == null || !Set.of("EQ", "NE", "GT", "GTE", "LT", "LTE", "IS_NULL", "IS_NOT_NULL").contains(filter.operation())) throw new IllegalArgumentException("Invalid filter");
            if (!Set.of("IS_NULL", "IS_NOT_NULL").contains(filter.operation()) && !(filter.value() instanceof String || filter.value() instanceof Number || filter.value() instanceof Boolean)) throw new IllegalArgumentException("Invalid filter value");
            if (filter.value() instanceof String text && text.length() > 4096) throw new IllegalArgumentException("Filter value too long");
            used.add(filter.field());
        }
        for (int i = 0; i < query.metrics().size(); i++) if (query.groups().contains("metric_" + i)) throw new IllegalArgumentException("Conflicting output name");
        if (query.sort().size() > 20) throw new IllegalArgumentException("Too many sort columns");
        var outputs = new HashSet<>(query.groups());
        for (int i = 0; i < query.metrics().size(); i++) outputs.add("metric_" + i);
        var sorted = new HashSet<String>();
        for (var sort : query.sort()) if (sort == null || sort.field() == null || sort.direction() == null || !outputs.contains(sort.field()) || !Set.of("ASC", "DESC").contains(sort.direction()) || !sorted.add(sort.field())) throw new IllegalArgumentException("Invalid sort");
        Set<Long> allowed = new HashSet<>(policy.departments());
        for (String field : used) {
            if (field == null || !dataset.fields().contains(field) || !policy.grants().containsKey(field)) throw new SecurityException("Field access denied");
            Set<Long> full = new HashSet<>();
            policy.grants().get(field).forEach((department, mode) -> { if (mode == DatasetAccess.Field.FULL) full.add(department); });
            allowed.retainAll(full);
        }
        if (allowed.isEmpty()) throw new SecurityException("Full field access required for analysis");
        try (var permit = limits.acquire(); var connection = source.getConnection()) {
            connection.setReadOnly(true);
            var metadata = connection.getMetaData(); String quote = metadata.getIdentifierQuoteString();
            if (quote == null || quote.isBlank()) throw new SQLException("Database does not support identifier quoting");
            String qualifier = "MySQL".equalsIgnoreCase(metadata.getDatabaseProductName()) ? dataset.catalog() : dataset.schema();
            if (qualifier == null || qualifier.isBlank()) qualifier = dataset.schema() == null || dataset.schema().isBlank() ? dataset.catalog() : dataset.schema();
            String table = qualifier == null || qualifier.isBlank() ? DatasetQueryEngine.quote(dataset.table(), quote) : DatasetQueryEngine.quote(qualifier, quote) + "." + DatasetQueryEngine.quote(dataset.table(), quote);
            var selections = new ArrayList<String>();
            for (String group : query.groups()) selections.add(DatasetQueryEngine.quote(group, quote));
            for (int i = 0; i < query.metrics().size(); i++) {
                var metric = query.metrics().get(i); String argument = metric.field() == null || metric.field().isBlank() ? "*" : DatasetQueryEngine.quote(metric.field(), quote);
                selections.add(metric.operation() + "(" + argument + ") AS " + DatasetQueryEngine.quote("metric_" + i, quote));
            }
            StringBuilder sql = new StringBuilder("SELECT ").append(String.join(",", selections)).append(" FROM ").append(table)
                    .append(" WHERE ").append(DatasetQueryEngine.quote(dataset.departmentField(), quote)).append(" IN (").append(String.join(",", Collections.nCopies(allowed.size(), "?"))).append(")");
            for (var filter : query.filters()) {
                String operator = switch (filter.operation()) { case "EQ" -> " = ?"; case "NE" -> " <> ?"; case "GT" -> " > ?"; case "GTE" -> " >= ?"; case "LT" -> " < ?"; case "LTE" -> " <= ?"; case "IS_NULL" -> " IS NULL"; default -> " IS NOT NULL"; };
                sql.append(" AND ").append(DatasetQueryEngine.quote(filter.field(), quote)).append(operator);
            }
            if (!query.groups().isEmpty()) sql.append(" GROUP BY ").append(query.groups().stream().map(g -> DatasetQueryEngine.quote(g, quote)).collect(java.util.stream.Collectors.joining(",")));
            if (!query.sort().isEmpty()) sql.append(" ORDER BY ").append(query.sort().stream().map(order -> DatasetQueryEngine.quote(order.field(), quote) + " " + order.direction()).collect(java.util.stream.Collectors.joining(",")));
            try (var statement = connection.prepareStatement(sql.toString())) {
                statement.setQueryTimeout(limits.timeoutSeconds()); statement.setMaxRows(query.limit()); int index = 1;
                for (Long department : allowed.stream().sorted().toList()) statement.setLong(index++, department);
                for (var filter : query.filters()) if (!Set.of("IS_NULL", "IS_NOT_NULL").contains(filter.operation())) statement.setObject(index++, filter.value());
                try (var rows = statement.executeQuery()) {
                    var result = new ArrayList<Map<String, Object>>();
                    while (rows.next() && result.size() < query.limit()) {
                        Map<String, Object> row = new LinkedHashMap<>(); int col = 1;
                        for (String group : query.groups()) row.put(group, DatasetQueryEngine.safeValue(rows.getObject(col++)));
                        for (int i = 0; i < query.metrics().size(); i++) row.put("metric_" + i, DatasetQueryEngine.safeValue(rows.getObject(col++)));
                        result.add(Collections.unmodifiableMap(row));
                    }
                    return List.copyOf(result);
                }
            }
        }
    }
}
