package com.example.governance.workflow;

import com.example.governance.assets.ExecutionDataSourceProvider;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;

/** Ad-hoc read-only SQL preview against a registered datasource: SELECT only, bounded rows, bound parameters. */
@Service
public class OrchQueryService {
    public record Result(List<String> columns, List<List<Object>> rows, int rowCount, boolean truncated) {}
    private final ExecutionDataSourceProvider provider;

    public OrchQueryService(ExecutionDataSourceProvider provider) { this.provider = provider; }

    public record ColumnInfo(String name, String type) {}

    /** Column metadata (name + SQL type) for a table or SELECT, read from ResultSetMetaData without fetching rows. */
    public List<ColumnInfo> schema(long sourceId, String sql, Map<String, Object> values, Map<String, String> types) throws Exception {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("SQL 不能为空");
        if (SqlParameters.hasMultipleStatements(sql)) throw new IllegalArgumentException("不允许多语句 SQL");
        var parsed = SqlParameters.parse(sql);
        DataSource ds = provider.get(sourceId);
        try (Connection connection = ds.getConnection()) {
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(parsed.jdbcSql())) {
                statement.setQueryTimeout(15);
                statement.setMaxRows(1);
                statement.setFetchSize(1);
                SqlParameters.bind(statement, parsed, values == null ? Map.of() : values, types == null ? Map.of() : types);
                try (ResultSet rows = statement.executeQuery()) {
                    var meta = rows.getMetaData();
                    var columns = new ArrayList<ColumnInfo>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) columns.add(new ColumnInfo(meta.getColumnLabel(i), meta.getColumnTypeName(i)));
                    return columns;
                }
            }
        }
    }

    public Result preview(long sourceId, String sql, Map<String, Object> values, Map<String, String> types, int limit) throws Exception {
        if (sql == null || sql.isBlank()) throw new IllegalArgumentException("SQL 不能为空");
        if (SqlParameters.hasMultipleStatements(sql)) throw new IllegalArgumentException("不允许多语句 SQL");
        String keyword = SqlParameters.firstKeyword(sql);
        if (!Set.of("SELECT", "WITH").contains(keyword)) throw new IllegalArgumentException("仅支持 SELECT 查询，禁止写操作");
        int capped = Math.min(Math.max(limit, 1), 200);
        var parsed = SqlParameters.parse(sql);
        DataSource ds = provider.get(sourceId);
        try (Connection connection = ds.getConnection()) {
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(parsed.jdbcSql())) {
                statement.setQueryTimeout(30);
                statement.setFetchSize(Math.min(capped + 1, 500));
                statement.setMaxRows(capped + 1);
                SqlParameters.bind(statement, parsed, values == null ? Map.of() : values, types == null ? Map.of() : types);
                try (ResultSet rows = statement.executeQuery()) {
                    int columnCount = rows.getMetaData().getColumnCount();
                    var columns = new ArrayList<String>();
                    for (int i = 1; i <= columnCount; i++) columns.add(rows.getMetaData().getColumnLabel(i));
                    var result = new ArrayList<List<Object>>();
                    boolean truncated = false;
                    while (rows.next()) {
                        if (result.size() >= capped) { truncated = true; break; }
                        var row = new ArrayList<Object>();
                        for (int i = 1; i <= columnCount; i++) row.add(OrchTaskExecutor.convert(rows.getObject(i)));
                        result.add(row);
                    }
                    return new Result(columns, result, result.size(), truncated);
                }
            }
        }
    }
}