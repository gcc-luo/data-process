package com.example.governance.masterdata;

import com.example.governance.assets.ExecutionDataSourceProvider;
import com.example.governance.dataobject.ObjectStore;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Executes bounded queries and primary-key-scoped record changes against registered physical tables. */
@Service
public class MasterDataRecordService {
    public record Filter(String field, String operator, Object value) {}
    public record QueryRequest(int page, int size, List<Filter> filters) {}
    public record Page(List<Map<String, Object>> rows, long total, int page, int size) {}
    public record RecordInput(Map<String, Object> key, Map<String, Object> values) {}
    public record Mutation(long affected, Map<String, Object> key, Map<String, Object> before, Map<String, Object> after) {}
    private record Predicate(String sql, List<Object> values) {}

    private static final int MAX_PAGE = 10000;
    private static final int MAX_SIZE = 100;
    private static final int MAX_FILTERS = 30;
    private static final int MAX_TEXT = 4096;
    private final MasterDataService catalog;
    private final ExecutionDataSourceProvider sources;

    public MasterDataRecordService(MasterDataService catalog, ExecutionDataSourceProvider sources) {
        this.catalog = catalog;
        this.sources = sources;
    }

    public Page query(long objectId, QueryRequest request) throws GeneralSecurityException {
        if (request == null || request.page() < 1 || request.page() > MAX_PAGE || request.size() < 1 || request.size() > MAX_SIZE)
            throw new IllegalArgumentException("分页参数无效");
        long offset = (long) (request.page() - 1) * request.size();
        if (offset > 100000) throw new IllegalArgumentException("查询页数过大，请缩小查询条件");
        try {
            var detail = catalog.detail(objectId);
            var identity = detail.object().identity();
            try (Connection connection = sources.get(identity.sourceId()).getConnection()) {
                connection.setReadOnly(true);
                String quote = quoteString(connection);
                String table = qualified(identity, connection, quote);
                Predicate where = predicate(detail.fields(), request.filters(), quote);
                long total = count(connection, table, where);
                String order = detail.fields().stream().filter(field -> "YES".equals(field.primaryKey()))
                        .map(field -> quote(field.name(), quote)).collect(Collectors.joining(","));
                String orderSql = order.isEmpty() ? "" : " ORDER BY " + order;
                String sql = "SELECT " + detail.fields().stream().map(field -> quote(field.name(), quote)).collect(Collectors.joining(","))
                        + " FROM " + table + where.sql() + orderSql;
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setQueryTimeout(30);
                    statement.setFetchSize(Math.min(500, request.size()));
                    statement.setMaxRows(Math.toIntExact(offset + request.size()));
                    bind(statement, where.values());
                    try (ResultSet result = statement.executeQuery()) {
                        long skipped = 0;
                        List<Map<String, Object>> rows = new ArrayList<>(request.size());
                        while (result.next()) {
                            if (skipped++ < offset) continue;
                            Map<String, Object> row = new LinkedHashMap<>();
                            for (var field : detail.fields()) row.put(field.name(), safe(result.getObject(field.name())));
                            rows.add(Collections.unmodifiableMap(row));
                        }
                        return new Page(List.copyOf(rows), total, request.page(), request.size());
                    }
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("读取主数据失败，请检查数据源连接或筛选条件", ex);
        }
    }

    public Mutation create(long objectId, RecordInput input) throws GeneralSecurityException {
        if (input == null || input.values() == null || input.values().isEmpty()) throw new IllegalArgumentException("请至少填写一个字段");
        var detail = writableDetail(objectId, false);
        Map<String, MasterDataService.FieldView> fields = fields(detail.fields());
        rejectUnknown(input.values(), fields.keySet(), "字段");
        List<String> insertFields = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        Map<String, Object> after = new LinkedHashMap<>();
        for (var field : detail.fields()) {
            boolean generated = "YES".equals(field.autoIncrement()) || "YES".equals(field.generated());
            boolean present = input.values().containsKey(field.name());
            Object raw = input.values().get(field.name());
            if (generated) {
                if (present && raw != null) throw new IllegalArgumentException("字段“" + field.name() + "”由数据库生成，不能手动填写");
                continue;
            }
            if (!present) {
                if (!"YES".equals(field.nullable()) && field.defaultValue() == null)
                    throw new IllegalArgumentException("请填写必填字段“" + field.name() + "”");
                continue;
            }
            Object value = convert(field, raw);
            if (value == null && !"YES".equals(field.nullable())) throw new IllegalArgumentException("字段“" + field.name() + "”不能为空");
            insertFields.add(field.name());
            values.add(value);
            after.put(field.name(), safe(value));
        }
        if (insertFields.isEmpty()) throw new IllegalArgumentException("没有可写入的字段，请检查数据库默认值和生成列");
        var identity = detail.object().identity();
        try (Connection connection = sources.get(identity.sourceId()).getConnection()) {
            connection.setAutoCommit(false);
            try {
                String quote = quoteString(connection);
                String table = qualified(identity, connection, quote);
                String columns = insertFields.stream().map(field -> quote(field, quote)).collect(Collectors.joining(","));
                String placeholders = String.join(",", Collections.nCopies(insertFields.size(), "?"));
                String sql = "INSERT INTO " + table + " (" + columns + ") VALUES (" + placeholders + ")";
                Map<String, Object> key = new LinkedHashMap<>();
                try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    bind(statement, values);
                    int changed = statement.executeUpdate();
                    if (changed != 1) throw new IllegalStateException("新增记录失败，请检查字段约束");
                    for (var field : detail.fields()) if ("YES".equals(field.primaryKey()) && after.containsKey(field.name())) key.put(field.name(), after.get(field.name()));
                    try (ResultSet generated = statement.getGeneratedKeys()) {
                        List<MasterDataService.FieldView> missingKeys = detail.fields().stream()
                                .filter(field -> "YES".equals(field.primaryKey()) && !key.containsKey(field.name())).toList();
                        if (generated.next() && missingKeys.size() == 1
                                && ("YES".equals(missingKeys.getFirst().autoIncrement()) || detail.fields().stream().filter(field -> "YES".equals(field.primaryKey())).count() == 1)) {
                            var keyField = missingKeys.getFirst();
                            Object value = safe(generated.getObject(1));
                            if (value != null) {
                                key.put(keyField.name(), value);
                                after.put(keyField.name(), value);
                            }
                        }
                    } catch (SQLException ignored) {
                        // Some supported JDBC drivers do not expose generated keys; the row is still committed safely.
                    }
                }
                List<MasterDataService.FieldView> primaryKeys = detail.fields().stream().filter(field -> "YES".equals(field.primaryKey())).toList();
                if (!primaryKeys.isEmpty() && primaryKeys.stream().allMatch(field -> key.containsKey(field.name()))) {
                    Map<String, Object> inserted = selectRow(connection, table, detail.fields(), primaryKeys, key, quote, false);
                    if (inserted != null) after.putAll(inserted);
                }
                connection.commit();
                return new Mutation(1, immutable(key), null, immutable(after));
            } catch (SQLException | RuntimeException ex) {
                rollback(connection);
                throw ex;
            }
        } catch (SQLException ex) {
            throw writeFailure(ex);
        }
    }

    public Mutation update(long objectId, RecordInput input) throws GeneralSecurityException {
        if (input == null || input.key() == null || input.values() == null || input.values().isEmpty()) throw new IllegalArgumentException("请选择记录并填写要修改的字段");
        var detail = writableDetail(objectId, true);
        List<MasterDataService.FieldView> keys = primaryKey(detail.fields());
        validateKey(input.key(), keys);
        Map<String, MasterDataService.FieldView> fields = fields(detail.fields());
        rejectUnknown(input.values(), fields.keySet(), "字段");
        Map<String, Object> changes = new LinkedHashMap<>();
        for (var entry : input.values().entrySet()) {
            var field = fields.get(entry.getKey());
            if ("YES".equals(field.primaryKey())) throw new IllegalArgumentException("主键字段不能修改");
            if ("YES".equals(field.autoIncrement()) || "YES".equals(field.generated())) throw new IllegalArgumentException("字段“" + field.name() + "”由数据库生成，不能修改");
            Object value = convert(field, entry.getValue());
            if (value == null && !"YES".equals(field.nullable())) throw new IllegalArgumentException("字段“" + field.name() + "”不能为空");
            changes.put(field.name(), value);
        }
        var identity = detail.object().identity();
        try (Connection connection = sources.get(identity.sourceId()).getConnection()) {
            connection.setAutoCommit(false);
            try {
                String quote = quoteString(connection);
                String table = qualified(identity, connection, quote);
                Map<String, Object> before = selectRow(connection, table, detail.fields(), keys, input.key(), quote, true);
                if (before == null) throw new IllegalArgumentException("记录不存在或已被删除");
                String set = changes.keySet().stream().map(name -> quote(name, quote) + "=?").collect(Collectors.joining(","));
                String where = keys.stream().map(field -> quote(field.name(), quote) + "=?").collect(Collectors.joining(" AND "));
                try (PreparedStatement statement = connection.prepareStatement("UPDATE " + table + " SET " + set + " WHERE " + where)) {
                    List<Object> params = new ArrayList<>(changes.values());
                    keys.forEach(field -> params.add(convert(field, input.key().get(field.name()))));
                    bind(statement, params);
                    int changed = statement.executeUpdate();
                    if (changed != 1) throw new IllegalStateException("记录不存在或已变化，请刷新后重试");
                }
                Map<String, Object> after = selectRow(connection, table, detail.fields(), keys, input.key(), quote, false);
                connection.commit();
                return new Mutation(1, immutable(input.key()), immutable(before), after == null ? immutable(changes) : immutable(after));
            } catch (SQLException | RuntimeException ex) {
                rollback(connection);
                throw ex;
            }
        } catch (SQLException ex) {
            throw writeFailure(ex);
        }
    }

    public Mutation delete(long objectId, Map<String, Object> key) throws GeneralSecurityException {
        var detail = writableDetail(objectId, true);
        List<MasterDataService.FieldView> keys = primaryKey(detail.fields());
        validateKey(key, keys);
        var identity = detail.object().identity();
        try (Connection connection = sources.get(identity.sourceId()).getConnection()) {
            connection.setAutoCommit(false);
            try {
                String quote = quoteString(connection);
                String table = qualified(identity, connection, quote);
                Map<String, Object> before = selectRow(connection, table, detail.fields(), keys, key, quote, true);
                if (before == null) throw new IllegalArgumentException("记录不存在或已被删除");
                String where = keys.stream().map(field -> quote(field.name(), quote) + "=?").collect(Collectors.joining(" AND "));
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE " + where)) {
                    List<Object> params = new ArrayList<>();
                    keys.forEach(field -> params.add(convert(field, key.get(field.name()))));
                    bind(statement, params);
                    int changed = statement.executeUpdate();
                    if (changed != 1) throw new IllegalStateException("记录不存在或已变化，请刷新后重试");
                }
                connection.commit();
                return new Mutation(1, immutable(key), immutable(before), null);
            } catch (SQLException | RuntimeException ex) {
                rollback(connection);
                throw ex;
            }
        } catch (SQLException ex) {
            throw writeFailure(ex);
        }
    }

    private MasterDataService.ObjectDetail writableDetail(long objectId, boolean requireKey) throws GeneralSecurityException {
        try {
            var detail = catalog.detail(objectId);
            if (!detail.canInsert()) throw new IllegalStateException(detail.writeDisabledReason());
            if (requireKey && !detail.canEditDelete()) throw new IllegalStateException(detail.writeDisabledReason());
            return detail;
        } catch (SQLException ex) {
            throw new IllegalStateException("无法读取数据对象字段，请检查数据源连接", ex);
        }
    }

    private static Map<String, MasterDataService.FieldView> fields(List<MasterDataService.FieldView> values) {
        return values.stream().collect(Collectors.toMap(MasterDataService.FieldView::name, value -> value));
    }

    private static void rejectUnknown(Map<String, Object> values, Set<String> allowed, String description) {
        if (values.size() > allowed.size() || values.keySet().stream().anyMatch(name -> name == null || !allowed.contains(name)))
            throw new IllegalArgumentException(description + "已变化，请刷新后重试");
    }

    private static List<MasterDataService.FieldView> primaryKey(List<MasterDataService.FieldView> fields) {
        if (fields.stream().anyMatch(field -> "UNKNOWN".equals(field.primaryKey()))) throw new IllegalStateException("数据库未提供可靠的主键元数据，不能修改或删除");
        List<MasterDataService.FieldView> keys = fields.stream().filter(field -> "YES".equals(field.primaryKey())).toList();
        if (keys.isEmpty()) throw new IllegalStateException("未找到主键，不能修改或删除记录");
        return keys;
    }

    private static void validateKey(Map<String, Object> key, List<MasterDataService.FieldView> fields) {
        Set<String> names = fields.stream().map(MasterDataService.FieldView::name).collect(Collectors.toSet());
        if (key.size() != names.size() || !key.keySet().equals(names) || key.values().stream().anyMatch(value -> value == null))
            throw new IllegalArgumentException("记录主键不完整，请刷新后重试");
    }

    private static Map<String, Object> selectRow(Connection connection, String table, List<MasterDataService.FieldView> columns,
                                                  List<MasterDataService.FieldView> keys, Map<String, Object> key,
                                                  String quote, boolean lock) throws SQLException {
        String selected = columns.stream().map(field -> quote(field.name(), quote)).collect(Collectors.joining(","));
        String where = keys.stream().map(field -> quote(field.name(), quote) + "=?").collect(Collectors.joining(" AND "));
        String sql = "SELECT " + selected + " FROM " + table + " WHERE " + where + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(30);
            List<Object> values = new ArrayList<>();
            keys.forEach(field -> values.add(convert(field, key.get(field.name()))));
            bind(statement, values);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                Map<String, Object> result = new LinkedHashMap<>();
                for (var field : columns) result.put(field.name(), safe(rows.getObject(field.name())));
                if (rows.next()) throw new IllegalStateException("主键没有唯一定位记录，已取消操作");
                return Collections.unmodifiableMap(result);
            }
        }
    }

    private static Predicate predicate(List<MasterDataService.FieldView> fields, List<Filter> filters, String quote) {
        if (filters == null || filters.isEmpty()) return new Predicate("", List.of());
        if (filters.size() > MAX_FILTERS) throw new IllegalArgumentException("筛选条件不能超过 " + MAX_FILTERS + " 项");
        Map<String, MasterDataService.FieldView> allowed = fields(fields);
        List<String> clauses = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (Filter filter : filters) {
            if (filter == null || filter.field() == null || filter.field().length() > 255 || !allowed.containsKey(filter.field())) throw new IllegalArgumentException("筛选字段无效，请重新选择");
            String column = quote(filter.field(), quote);
            String rawOperator = filter.operator() == null ? "" : filter.operator();
            if (rawOperator.length() > 20) throw new IllegalArgumentException("筛选操作无效");
            String operator = rawOperator.toUpperCase(java.util.Locale.ROOT);
            switch (operator) {
                case "IS_NULL" -> clauses.add(column + " IS NULL");
                case "NOT_NULL" -> clauses.add(column + " IS NOT NULL");
                case "EQ" -> {
                    if (filter.value() == null) clauses.add(column + " IS NULL");
                    else { clauses.add(column + " = ?"); values.add(convert(allowed.get(filter.field()), filter.value())); }
                }
                case "NE" -> {
                    if (filter.value() == null) clauses.add(column + " IS NOT NULL");
                    else { clauses.add(column + " <> ?"); values.add(convert(allowed.get(filter.field()), filter.value())); }
                }
                case "GT", "GTE", "LT", "LTE" -> {
                    if (filter.value() == null) throw new IllegalArgumentException("筛选内容不能为空");
                    String sqlOperator = switch (operator) { case "GT" -> ">"; case "GTE" -> ">="; case "LT" -> "<"; default -> "<="; };
                    clauses.add(column + " " + sqlOperator + " ?"); values.add(convert(allowed.get(filter.field()), filter.value()));
                }
                case "CONTAINS", "STARTS_WITH" -> {
                    int type = allowed.get(filter.field()).jdbcType();
                    if (!Set.of(Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB).contains(type))
                        throw new IllegalArgumentException("模糊筛选仅支持文本字段");
                    if (filter.value() == null) throw new IllegalArgumentException("筛选内容不能为空");
                    if (filter.value() instanceof Map<?, ?> || filter.value() instanceof List<?>) throw new IllegalArgumentException("筛选内容格式无效");
                    String text = filter.value().toString();
                    if (text.length() > MAX_TEXT) throw new IllegalArgumentException("筛选内容过长");
                    String pattern = text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
                    clauses.add(column + " LIKE ? ESCAPE '!'");
                    values.add(operator.equals("CONTAINS") ? "%" + pattern + "%" : pattern + "%");
                }
                default -> throw new IllegalArgumentException("筛选操作无效");
            }
        }
        return new Predicate(" WHERE " + String.join(" AND ", clauses), List.copyOf(values));
    }

    private static long count(Connection connection, String table, Predicate where) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table + where.sql())) {
            statement.setQueryTimeout(30);
            bind(statement, where.values());
            try (ResultSet result = statement.executeQuery()) { result.next(); return result.getLong(1); }
        }
    }

    private static String qualified(ObjectStore.Identity identity, Connection connection, String quote) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName();
        String namespace = "MySQL".equalsIgnoreCase(product) ? identity.catalog() : identity.schema();
        if (namespace == null || namespace.isBlank()) namespace = identity.schema() == null || identity.schema().isBlank() ? identity.catalog() : identity.schema();
        return (namespace == null || namespace.isBlank() ? "" : quote(namespace, quote) + ".") + quote(identity.table(), quote);
    }

    private static String quoteString(Connection connection) throws SQLException {
        String quote = connection.getMetaData().getIdentifierQuoteString();
        if (quote == null || quote.isBlank()) throw new SQLException("数据库不支持安全标识符引用");
        return quote.trim();
    }

    private static String quote(String identifier, String quote) {
        if (!MasterDataService.trustedIdentifier(identifier)) throw new IllegalArgumentException("数据库字段名无效");
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    private static void bind(PreparedStatement statement, List<Object> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (value == null) statement.setNull(i + 1, Types.NULL);
            else statement.setObject(i + 1, value);
        }
    }

    private static Object convert(MasterDataService.FieldView field, Object value) {
        if (value == null) return null;
        if (value instanceof Map<?, ?> || value instanceof List<?>) throw new IllegalArgumentException("字段“" + field.name() + "”的值格式无效");
        String raw = value.toString();
        if (raw.length() > MAX_TEXT) throw new IllegalArgumentException("字段“" + field.name() + "”内容过长");
        try {
            return switch (field.jdbcType()) {
                case Types.TINYINT -> integer(field, raw, 8);
                case Types.SMALLINT -> integer(field, raw, 16);
                case Types.INTEGER -> integer(field, raw, 32);
                case Types.BIGINT -> integer(field, raw, 64);
                case Types.NUMERIC, Types.DECIMAL -> decimal(field, raw);
                case Types.FLOAT, Types.REAL, Types.DOUBLE -> finiteDouble(raw);
                case Types.BOOLEAN, Types.BIT -> parseBoolean(raw);
                case Types.DATE -> java.sql.Date.valueOf(LocalDate.parse(raw));
                case Types.TIME -> java.sql.Time.valueOf(LocalTime.parse(raw));
                case Types.TIME_WITH_TIMEZONE -> OffsetTime.parse(raw);
                case Types.TIMESTAMP -> Timestamp.valueOf(java.time.LocalDateTime.parse(raw.replace(' ', 'T')));
                case Types.TIMESTAMP_WITH_TIMEZONE -> OffsetDateTime.parse(raw.replace(' ', 'T'));
                case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> throw new IllegalArgumentException("暂不支持直接编辑二进制字段“" + field.name() + "”");
                default -> {
                    if (field.length() != null && raw.length() > field.length()) throw new IllegalArgumentException("字段“" + field.name() + "”超过最大长度 " + field.length());
                    if (raw.length() > MAX_TEXT) throw new IllegalArgumentException("字段“" + field.name() + "”内容过长");
                    yield raw;
                }
            };
        } catch (NumberFormatException | ArithmeticException | java.time.DateTimeException ex) {
            throw new IllegalArgumentException("字段“" + field.name() + "”的值格式无效", ex);
        }
    }

    private static Boolean parseBoolean(String value) {
        if (Set.of("true", "1", "yes", "y").contains(value.toLowerCase(java.util.Locale.ROOT))) return true;
        if (Set.of("false", "0", "no", "n").contains(value.toLowerCase(java.util.Locale.ROOT))) return false;
        throw new IllegalArgumentException("布尔字段值只能为 true/false 或 1/0");
    }

    private static BigDecimal decimal(MasterDataService.FieldView field, String raw) {
        BigDecimal value = new BigDecimal(raw);
        if (field.scale() != null && value.stripTrailingZeros().scale() > field.scale())
            throw new IllegalArgumentException("字段“" + field.name() + "”的小数位数超过 " + field.scale());
        if (field.length() != null && field.scale() != null && value.precision() - value.scale() > field.length() - field.scale())
            throw new IllegalArgumentException("字段“" + field.name() + "”的整数位数超出范围");
        return value;
    }

    private static Object integer(MasterDataService.FieldView field, String raw, int bits) {
        BigInteger value = new BigDecimal(raw).toBigIntegerExact();
        boolean unsigned = field.type() != null && field.type().toUpperCase(java.util.Locale.ROOT).contains("UNSIGNED");
        BigInteger min = unsigned ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(bits - 1).negate();
        BigInteger max = BigInteger.ONE.shiftLeft(unsigned ? bits : bits - 1).subtract(BigInteger.ONE);
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) throw new IllegalArgumentException("字段“" + field.name() + "”超出整数范围");
        return bits <= 32 ? value.intValueExact() : value.longValueExact();
    }

    private static Double finiteDouble(String value) {
        double parsed = Double.parseDouble(value);
        if (!Double.isFinite(parsed)) throw new IllegalArgumentException("数字字段值必须是有限数值");
        return parsed;
    }

    private static Object safe(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Clob) return "[大文本]";
        if (value instanceof java.sql.Blob || value instanceof byte[]) return "[二进制数据]";
        if (value instanceof String text && text.length() > MAX_TEXT) return text.substring(0, MAX_TEXT) + "…";
        if (value instanceof Number number) return number.toString();
        if (value instanceof java.util.Date || value instanceof java.time.temporal.TemporalAccessor) return value.toString();
        if (value instanceof Boolean || value instanceof String) return value;
        return "[不支持的数据类型]";
    }

    private static Map<String, Object> immutable(Map<String, Object> value) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(value == null ? Map.of() : value));
    }

    private static IllegalStateException writeFailure(SQLException cause) {
        return new IllegalStateException("目标数据库拒绝了记录操作，请检查数据源写入权限、字段约束和主键值", cause);
    }

    private static void rollback(Connection connection) {
        try { connection.rollback(); } catch (SQLException ignored) { }
    }
}
