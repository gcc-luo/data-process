package com.example.governance.workflow;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Shared H2 + ORCH definition fixtures for workflow tests. */
public final class OrchFixtures {
    private OrchFixtures() {}

    public static DriverManagerDataSource h2() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        return db;
    }

    /** Single DML SQL-script node (terminal step), named :business_date placeholder. */
    public static String dml(long sourceId, String sql) {
        return "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[{\"id\":\"n_sql\",\"component\":\"transform.sql\",\"name\":\"SQL 脚本\",\"enabled\":true," +
                "\"position\":{\"x\":100,\"y\":100},\"timeoutSeconds\":300,\"config\":{\"sourceId\":" + sourceId + ",\"sqlMode\":\"DML\",\"sql\":\"" + escape(sql) + "\",\"parameters\":[]}}],\"edges\":[]}";
    }

    public static String dmlBusinessDate(long sourceId, String sql) { return dml(sourceId, sql); }

    /** source.jdbc (SQL) -> sink.jdbc chain. */
    public static String sync(long sourceId, long targetId, String sourceSql, String targetTable, List<String> columns, String writeMode, List<String> keys) {
        String keyJson = keys == null ? "[]" : "[" + String.join(",", keys.stream().map(k -> "\"" + k + "\"").toList()) + "]";
        return "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" +
                "{\"id\":\"n_src\",\"component\":\"source.mysql\",\"name\":\"来源\",\"enabled\":true,\"position\":{\"x\":100,\"y\":100},\"timeoutSeconds\":300," +
                "\"config\":{\"sourceId\":" + sourceId + ",\"readMode\":\"SQL\",\"sql\":\"" + escape(sourceSql) + "\",\"parameters\":[],\"fetchSize\":500}}," +
                "{\"id\":\"n_sink\",\"component\":\"sink.mysql\",\"name\":\"写入\",\"enabled\":true,\"position\":{\"x\":400,\"y\":100},\"timeoutSeconds\":300," +
                "\"config\":{\"sourceId\":" + targetId + ",\"table\":\"" + targetTable + "\",\"writeMode\":\"" + writeMode + "\",\"keyColumns\":" + keyJson + ",\"batchSize\":500}}]," +
                "\"edges\":[{\"id\":\"e1\",\"from\":\"n_src\",\"fromPort\":\"out\",\"to\":\"n_sink\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
    }

    public static String escape(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }

    /** Create an ORCH task and publish version 1 directly (bypasses API validation for unit fixtures). */
    public static long published(TaskVersionStore tasks, String name, String definition) {
        long id = tasks.create(name, "ORCH", definition);
        tasks.addVersion(id, definition, "fixture", "tester");
        return id;
    }

    public static TaskVersionStore store(JdbcTemplate jdbc) { return new TaskVersionStore(jdbc, new DataSourceTransactionManager(jdbc.getDataSource())); }
}