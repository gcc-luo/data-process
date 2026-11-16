package com.example.governance.query;

import com.example.governance.security.DatasetAccess;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/** Executes row-restricted previews. Dataset and policy must originate from trusted server-side metadata. */
public final class DatasetQueryEngine {
    private final QueryLimits limits;
    public DatasetQueryEngine() { this(new QueryLimits(8, 30, 1000)); }
    public DatasetQueryEngine(QueryLimits limits) { this.limits = java.util.Objects.requireNonNull(limits); }
    public record Dataset(String catalog, String schema, String table, String departmentField, Set<String> fields) {
        public Dataset { fields = Set.copyOf(fields); }
    }
    public record Policy(Set<Long> departments, Map<String, DatasetAccess.Field> fields, Map<String, Map<Long, DatasetAccess.Field>> grants) {
        public Policy {
            departments = Set.copyOf(departments); fields = Map.copyOf(fields);
            var copy = new java.util.HashMap<String, Map<Long, DatasetAccess.Field>>();
            grants.forEach((field, values) -> copy.put(field, Map.copyOf(values)));
            grants = Map.copyOf(copy);
        }
        public Policy(Set<Long> departments, Map<String, DatasetAccess.Field> fields) {
            this(departments, fields, uniform(departments, fields));
        }
        private static Map<String, Map<Long, DatasetAccess.Field>> uniform(Set<Long> departments, Map<String, DatasetAccess.Field> fields) {
            var result = new java.util.HashMap<String, Map<Long, DatasetAccess.Field>>();
            fields.forEach((field, mode) -> { var grants = new java.util.HashMap<Long, DatasetAccess.Field>(); departments.forEach(id -> grants.put(id, mode)); result.put(field, grants); });
            return result;
        }
    }
    public List<Map<String, Object>> query(DataSource source, Dataset dataset, Policy policy, List<String> selected, int limit) throws SQLException {
        if (source == null || dataset == null || policy == null || policy.departments().isEmpty() || policy.departments().size() > 1000) throw new SecurityException("Dataset access denied");
        if (selected == null || selected.isEmpty() || selected.size() > 100 || limit < 1 || limit > limits.maxRows() || new java.util.HashSet<>(selected).size() != selected.size()) throw new IllegalArgumentException("Invalid query");
        if (dataset.table() == null || dataset.table().isBlank() || dataset.departmentField() == null || !dataset.fields().contains(dataset.departmentField())) throw new SecurityException("Dataset policy unavailable");
        Set<Long> allowed = new java.util.HashSet<>(policy.departments());
        for (String name : selected) {
            if (name == null || !dataset.fields().contains(name) || !policy.fields().containsKey(name) || !policy.grants().containsKey(name)) throw new SecurityException("Field access denied");
            allowed.retainAll(policy.grants().get(name).keySet());
        }
        if (allowed.isEmpty()) return List.of();
        try (var permit = limits.acquire(); var connection = source.getConnection()) {
            connection.setReadOnly(true);
            var metadata = connection.getMetaData();
            String quote = metadata.getIdentifierQuoteString();
            if (quote == null || quote.isBlank()) throw new SQLException("Database does not support identifier quoting");
            String qualifier = "MySQL".equalsIgnoreCase(metadata.getDatabaseProductName()) ? dataset.catalog() : dataset.schema();
            if (qualifier == null || qualifier.isBlank()) qualifier = dataset.schema() == null || dataset.schema().isBlank() ? dataset.catalog() : dataset.schema();
            String table = qualifier == null || qualifier.isBlank() ? quote(dataset.table(), quote) : quote(qualifier, quote) + "." + quote(dataset.table(), quote);
            String fields = selected.stream().map(name -> quote(name, quote)).collect(java.util.stream.Collectors.joining(","));
            String placeholders = String.join(",", Collections.nCopies(allowed.size(), "?"));
            String sql = "SELECT " + fields + "," + quote(dataset.departmentField(), quote) + " FROM " + table + " WHERE " + quote(dataset.departmentField(), quote) + " IN (" + placeholders + ")";
            try (var statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(limits.timeoutSeconds()); statement.setMaxRows(limit);
                int index = 1;
                for (Long department : allowed.stream().sorted().toList()) statement.setLong(index++, department);
                try (var rows = statement.executeQuery()) {
                    List<Map<String, Object>> result = new ArrayList<>();
                    while (rows.next() && result.size() < limit) {
                        long department = rows.getLong(selected.size() + 1);
                        if (rows.wasNull() || !allowed.contains(department)) throw new SecurityException("Dataset policy mismatch");
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 0; i < selected.size(); i++) {
                            String field = selected.get(i);
                            Object value = rows.getObject(i + 1);
                            row.put(field, value == null ? null : policy.grants().get(field).get(department) == DatasetAccess.Field.FULL ? safeValue(value) : "****");
                        }
                        result.add(Collections.unmodifiableMap(row));
                    }
                    return List.copyOf(result);
                }
            }
        }
    }
    static Object safeValue(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Clob) return "[大文本]";
        if (value instanceof java.sql.Blob || value instanceof byte[]) return "[二进制数据]";
        if (value instanceof String text) return text.length() > 4096 ? text.substring(0, 4096) + "…" : text;
        if (value instanceof Number || value instanceof Boolean || value instanceof java.util.Date || value instanceof java.time.temporal.TemporalAccessor) return value;
        return "[不支持的数据类型]";
    }
    static String quote(String value, String quote) {
        if (value == null || value.isBlank() || value.length() > 255 || value.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid identifier");
        return quote + value.replace(quote, quote + quote) + quote;
    }
}
