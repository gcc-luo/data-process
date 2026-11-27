package com.example.governance.dataobject;

import com.example.governance.assets.ExecutionDataSourceProvider;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;

/** Direct table preview through data source connection. No dataset required. */
@Service
public class ObjectPreviewService {
    private final ObjectStore objects;
    private final ExecutionDataSourceProvider sources;
    
    public ObjectPreviewService(ObjectStore objects, ExecutionDataSourceProvider sources) {
        this.objects = objects;
        this.sources = sources;
    }
    
    public record PreviewPage(List<Map<String, Object>> rows, long total, int page, int size) {}
    
    public PreviewPage preview(long userId, long objectId, List<String> fields, int page, int size, List<Filter> filters) throws Exception {
        var object = objects.find(objectId);
        var identity = object.identity();
        
        // Get all fields if not specified
        var allFields = objects.fields(objectId);
        var selected = (fields == null || fields.isEmpty())
            ? allFields.stream().map(f -> f.name()).sorted().limit(100).toList()
            : fields;
        
        if (selected.isEmpty()) {
            throw new SecurityException("对象没有可访问字段");
        }
        
        // Query directly through data source
        DataSource source = sources.get(identity.sourceId());
        return queryTable(source, identity, selected, page, size, filters);
    }
    
    public record Filter(String field, String operator, String value) {}
    
    private PreviewPage queryTable(DataSource source, ObjectStore.Identity identity, List<String> fields, int page, int size, List<Filter> filters) throws SQLException {
        try (Connection connection = source.getConnection()) {
            connection.setReadOnly(true);
            var metadata = connection.getMetaData();
            String quote = metadata.getIdentifierQuoteString();
            if (quote == null || quote.isBlank()) {
                throw new SQLException("Database does not support identifier quoting");
            }
            
            // Build table qualifier
            String qualifier = "MySQL".equalsIgnoreCase(metadata.getDatabaseProductName()) 
                ? identity.catalog() 
                : identity.schema();
            if (qualifier == null || qualifier.isBlank()) {
                qualifier = identity.schema() == null || identity.schema().isBlank() 
                    ? identity.catalog() 
                    : identity.schema();
            }
            
            String table = qualifier == null || qualifier.isBlank()
                ? quote(identity.table(), quote)
                : quote(qualifier, quote) + "." + quote(identity.table(), quote);
            
            String fieldList = fields.stream().map(name -> quote(name, quote)).collect(java.util.stream.Collectors.joining(","));
            
            // Build WHERE clause from filters
            StringBuilder whereClause = new StringBuilder();
            List<Object> whereParams = new ArrayList<>();
            if (filters != null && !filters.isEmpty()) {
                whereClause.append(" WHERE ");
                List<String> conditions = new ArrayList<>();
                for (Filter filter : filters) {
                    if (filter.field() == null || filter.field().isBlank()) continue;
                    String quotedField = quote(filter.field(), quote);
                    switch (filter.operator()) {
                        case "CONTAINS":
                            conditions.add(quotedField + " LIKE ?");
                            whereParams.add("%" + filter.value() + "%");
                            break;
                        case "STARTS_WITH":
                            conditions.add(quotedField + " LIKE ?");
                            whereParams.add(filter.value() + "%");
                            break;
                        case "EQ":
                            conditions.add(quotedField + " = ?");
                            whereParams.add(filter.value());
                            break;
                        case "NE":
                            conditions.add(quotedField + " <> ?");
                            whereParams.add(filter.value());
                            break;
                        case "GT":
                            conditions.add(quotedField + " > ?");
                            whereParams.add(filter.value());
                            break;
                        case "GTE":
                            conditions.add(quotedField + " >= ?");
                            whereParams.add(filter.value());
                            break;
                        case "LT":
                            conditions.add(quotedField + " < ?");
                            whereParams.add(filter.value());
                            break;
                        case "LTE":
                            conditions.add(quotedField + " <= ?");
                            whereParams.add(filter.value());
                            break;
                        case "IS_NULL":
                            conditions.add(quotedField + " IS NULL");
                            break;
                        case "NOT_NULL":
                            conditions.add(quotedField + " IS NOT NULL");
                            break;
                    }
                }
                if (conditions.isEmpty()) {
                    whereClause.setLength(0);
                } else {
                    whereClause.append(String.join(" AND ", conditions));
                }
            }
            
            // Count total
            String countSql = "SELECT COUNT(*) FROM " + table + whereClause;
            long total;
            try (var countStmt = connection.prepareStatement(countSql)) {
                for (int i = 0; i < whereParams.size(); i++) {
                    countStmt.setObject(i + 1, whereParams.get(i));
                }
                try (var countRs = countStmt.executeQuery()) {
                    countRs.next();
                    total = countRs.getLong(1);
                }
            }
            
            // Query with pagination
            String sql = "SELECT " + fieldList + " FROM " + table + whereClause + " LIMIT ? OFFSET ?";
            try (var statement = connection.prepareStatement(sql)) {
                int paramIndex = 1;
                for (Object param : whereParams) {
                    statement.setObject(paramIndex++, param);
                }
                statement.setInt(paramIndex++, size);
                statement.setInt(paramIndex, (page - 1) * size);
                statement.setQueryTimeout(30);
                
                try (ResultSet rows = statement.executeQuery()) {
                    List<Map<String, Object>> result = new ArrayList<>();
                    while (rows.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 0; i < fields.size(); i++) {
                            row.put(fields.get(i), safeValue(rows.getObject(i + 1)));
                        }
                        result.add(Collections.unmodifiableMap(row));
                    }
                    return new PreviewPage(List.copyOf(result), total, page, size);
                }
            }
        }
    }
    
    private static String quote(String value, String quoteChar) {
        return quoteChar + value.replace(quoteChar, quoteChar + quoteChar) + quoteChar;
    }
    
    private static Object safeValue(Object value) {
        if (value == null) return null;
        if (value instanceof byte[]) return "[binary]";
        return value;
    }
}
