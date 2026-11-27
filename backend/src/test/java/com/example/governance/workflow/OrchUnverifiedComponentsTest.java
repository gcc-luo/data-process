package com.example.governance.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end execution tests for components that were implemented but lacked
 * direct execution verification: transform.sql, transform.filter, transform.mask,
 * transform.calc, source.postgresql, sink.postgresql, source.oracle, sink.oracle.
 *
 * PostgreSQL/Oracle components share the same JDBC code path as MySQL; these tests
 * confirm the executor correctly dispatches to the right switch branch and the
 * component registry allows them through validation.
 */
class OrchUnverifiedComponentsTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static final LocalDate DATE = LocalDate.of(2026, 11, 21);

    private static DriverManagerDataSource db() {
        return new DriverManagerDataSource("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    private static void exec(DataSource s, String sql) {
        try (var c = s.getConnection(); var st = c.createStatement()) { st.execute(sql); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private OrchTaskExecutor.Outcome run(String def, CamelTaskRunner.DataSourceProvider provider,
                                         OrchTaskExecutor.Context ctx, int maxRows) throws Exception {
        try (var sql = new SqlTaskExecutor()) {
            var executor = new OrchTaskExecutor(sql, new HttpTargetGuard(""));
            return executor.execute(OrchDefinition.parse(M.readTree(def)), provider, ctx,
                    new RunCancelContext(), ExecutionSinks.NOOP, null, 0, maxRows, null);
        }
    }

    // ---- transform.sql (SELECT mode) ----

    @Test void transformSqlSelectProducesDownstreamRows() throws Exception {
        var source = db(); var target = db();
        exec(source, "CREATE TABLE src(id INT, name VARCHAR(40), amount DECIMAL(10,2))");
        exec(source, "INSERT INTO src VALUES (1,'a',10.0),(2,'b',20.0),(3,'c',30.0)");
        exec(target, "CREATE TABLE dst(id INT, name VARCHAR(40))");
        // transform.sql in SELECT mode: reads from source, produces rows for downstream
        String def = "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" +
                "{\"id\":\"s\",\"component\":\"source.mysql\",\"name\":\"源\",\"enabled\":true,\"config\":{\"sourceId\":1,\"readMode\":\"SQL\",\"sql\":\"SELECT id,name,amount FROM src\"}}," +
                "{\"id\":\"q\",\"component\":\"transform.sql\",\"name\":\"SQL查询\",\"enabled\":true,\"config\":{\"sourceId\":1,\"sqlMode\":\"SELECT\",\"sql\":\"SELECT id,name FROM src WHERE amount > 15\"}}," +
                "{\"id\":\"k\",\"component\":\"sink.mysql\",\"name\":\"写\",\"enabled\":true,\"config\":{\"sourceId\":2,\"table\":\"dst\",\"writeMode\":\"APPEND\"}}]," +
                "\"edges\":[" +
                "{\"id\":\"e1\",\"from\":\"s\",\"fromPort\":\"out\",\"to\":\"q\",\"toPort\":\"dep\",\"kind\":\"CONTROL\"}," +
                "{\"id\":\"e2\",\"from\":\"q\",\"fromPort\":\"out\",\"to\":\"k\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
        var outcome = run(def, id -> id == 1 ? source : target,
                new OrchTaskExecutor.Context(1, 1L, DATE, Map.of(), false), 1000);
        assertEquals(2, new JdbcTemplate(target).queryForObject("SELECT COUNT(*) FROM dst", Integer.class));
        assertTrue(outcome.states().stream().allMatch(s -> "SUCCEEDED".equals(s.state())));
    }

    @Test void transformSqlDmlExecutesAndTerminates() throws Exception {
        var db = db();
        exec(db, "CREATE TABLE orders(id INT, status VARCHAR(20))");
        exec(db, "INSERT INTO orders VALUES (1,'PENDING'),(2,'PENDING'),(3,'DONE')");
        // transform.sql in DML mode: executes UPDATE, no downstream data output
        String def = "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" +
                "{\"id\":\"q\",\"component\":\"transform.sql\",\"name\":\"DML\",\"enabled\":true,\"config\":{\"sourceId\":1,\"sqlMode\":\"DML\",\"sql\":\"UPDATE orders SET status='PROCESSED' WHERE status='PENDING'\"}}],\"edges\":[]}";
        var outcome = run(def, id -> db,
                new OrchTaskExecutor.Context(1, 1L, DATE, Map.of(), false), 1000);
        assertEquals("SUCCEEDED", outcome.states().get(0).state());
        assertEquals(2, new JdbcTemplate(db).queryForObject("SELECT COUNT(*) FROM orders WHERE status='PROCESSED'", Integer.class));
        assertEquals(1, new JdbcTemplate(db).queryForObject("SELECT COUNT(*) FROM orders WHERE status='DONE'", Integer.class));
    }

    @Test void transformSqlDmlDryRunSkipsWrite() throws Exception {
        var db = db();
        exec(db, "CREATE TABLE t(id INT, val VARCHAR(20))");
        exec(db, "INSERT INTO t VALUES (1,'old')");
        String def = "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" +
                "{\"id\":\"q\",\"component\":\"transform.sql\",\"name\":\"DML\",\"enabled\":true,\"config\":{\"sourceId\":1,\"sqlMode\":\"DML\",\"sql\":\"UPDATE t SET val='new' WHERE id=1\"}}],\"edges\":[]}";
        try (var sql = new SqlTaskExecutor()) {
            var executor = new OrchTaskExecutor(sql, new HttpTargetGuard(""));
            executor.execute(OrchDefinition.parse(M.readTree(def)), id -> db,
                    new OrchTaskExecutor.Context(1, null, DATE, Map.of(), true),
                    new RunCancelContext(), ExecutionSinks.NOOP, null, 0, 1000, null);
        }
        // dry-run must not modify data
        assertEquals("old", new JdbcTemplate(db).queryForObject("SELECT val FROM t WHERE id=1", String.class));
    }

    @Test void transformSqlWithNamedParameterBinding() throws Exception {
        var db = db();
        exec(db, "CREATE TABLE items(id INT, label VARCHAR(40))");
        exec(db, "INSERT INTO items VALUES (1,'alpha'),(2,'beta')");
        exec(db, "CREATE TABLE result(id INT, label VARCHAR(40))");
        String def = "{\"schemaVersion\":1,\"variables\":[{\"name\":\"min_id\",\"type\":\"NUMBER\",\"valueSource\":\"MANUAL\",\"required\":true}],\"nodes\":[" +
                "{\"id\":\"q\",\"component\":\"transform.sql\",\"name\":\"查询\",\"enabled\":true,\"config\":{\"sourceId\":1,\"sqlMode\":\"SELECT\",\"sql\":\"SELECT id,label FROM items WHERE id >= :min_id\"}}," +
                "{\"id\":\"k\",\"component\":\"sink.mysql\",\"name\":\"写\",\"enabled\":true,\"config\":{\"sourceId\":1,\"table\":\"result\",\"writeMode\":\"APPEND\"}}]," +
                "\"edges\":[{\"id\":\"e1\",\"from\":\"q\",\"fromPort\":\"out\",\"to\":\"k\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
        run(def, id -> db,
                new OrchTaskExecutor.Context(1, 1L, DATE, Map.of("min_id", 2), false), 1000);
        assertEquals(1, new JdbcTemplate(db).queryForObject("SELECT COUNT(*) FROM result", Integer.class));
        assertEquals("beta", new JdbcTemplate(db).queryForObject("SELECT label FROM result WHERE id=2", String.class));
    }

    // ---- transform.filter ----

    @Test void filterRetainsMatchingRows() throws Exception {
        var source = db(); var target = db();
        exec(source, "CREATE TABLE src(id INT, amount DECIMAL(10,2), status VARCHAR(20))");
        exec(source, "INSERT INTO src VALUES (1,500.0,'PAID'),(2,1500.0,'PAID'),(3,200.0,'PENDING'),(4,3000.0,'PAID')");
        exec(target, "CREATE TABLE dst(id INT, amount DECIMAL(10,2), status VARCHAR(20))");
        String def = "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" +
                "{\"id\":\"s\",\"component\":\"source.mysql\",\"name\":\"源\",\"enabled\":true,\"config\":{\"sourceId\":1,\"readMode\":\"SQL\",\"sql\":\"SELECT id,amount,status FROM src\"}}," +
                "{\"id\":\"f\",\"component\":\"transform.filter\",\"name\":\"过滤\",\"enabled\":true,\"config\":{\"expression\":\"amount >= 1000 and status = 'PAID'\"}}," +
                "{\"id\":\"k\",\"component\":\"sink.mysql\",\"name\":\"写\",\"enabled\":true,\"config\":{\"sourceId\":2,\"table\":\"dst\",\"writeMode\":\"APPEND\"}}]," +
                "\"edges\":[" +
                "{\"id\":\"e1\",\"from\":\"s\",\"fromPort\":\"out\",\"to\":\"f\",\"toPort\":\"in\",\"kind\":\"DATA\"}," +
                "{\"id\":\"e2\",\"from\":\"f\",\"fromPort\":\"out\",\"to\":\"k\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
        var outcome = run(def, id -> id == 1 ? source : target,
                new OrchTaskExecutor.Context(1, 1L, DATE, Map.of(), false), 1000);
        assertEquals(2, new JdbcTemplate(target).queryForObject("SELECT COUNT(*) FROM dst", Integer.class));
        assertTrue(outcome.states().stream().allMatch(s -> "SUCCEEDED".equals(s.state())));
    }

    @Test void filterWithOrCondition() throws Exception {
        var source = db(); var target = db();
        exec(source, "CREATE TABLE src(id INT, category VARCHAR(20))");
        exec(source, "INSERT INTO src VALUES (1,'A'),(2,'B'),(3,'C'),(4,'D')");
        exec(target, "CREATE TABLE dst(id INT, category VARCHAR(20))");
        String def = "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" +
                "{\"id\":\"s\",\"component\":\"source.mysql\",\"name\":\"源\",\"enabled\":true,\"config\":{\"sourceId\":1,\"readMode\":\"SQL\",\"sql\":\"SELECT id,category FROM src\"}}," +
                "{\"id\":\"f\",\"component\":\"transform.filter\",\"name\":\"过滤\",\"enabled\":true,\"config\":{\"expression\":\"category = 'A' or category = 'C'\"}}," +
                "{\"id\":\"k\",\"component\":\"sink.mysql\",\"name\":\"写\",\"enabled\":true,\"config\":{\"sourceId\":2,\"table\":\"dst\",\"writeMode\":\"APPEND\"}}]," +
                "\"edges\":[" +
                "{\"id\":\"e1\",\"from\":\"s\",\"fromPort\":\"out\",\"to\":\"f\",\"toPort\":\"in\",\"kind\":\"DATA\"}," +
                "{\"id\":\"e2\",\"from\":\"f\",\"fromPort\":\"out\",\"to\":\"k\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
        run(def, id -> id == 1 ? source : target,
                new OrchTaskExecutor.Context(1, 1L, DATE, Map.of(), false), 1000);
        assertEquals(2, new JdbcTemplate(target).queryForObject("SELECT COUNT(*) FROM dst", Integer.class));
    }

    @Test void filterDropsAllRowsWhenNoneMatch() throws Exception {
        var source = db(); var target = db();
        exec(source, "CREATE TABLE src(id INT, val INT)");
        exec(source, "INSERT INTO src VALUES (1,5),(2,8),(3,3)");
        exec(target, "CREATE TABLE dst(id INT, val INT)");
        String def = "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" +
                "{\"id\":\"s\",\"component\":\"source.mysql\",\"name\":\"源\",\"enabled\":true,\"config\":{\"sourceId\":1,\"readMode\":\"SQL\",\"sql\":\"SELECT id,val FROM src\"}}," +
                "{\"id\":\"f\",\"component\":\"transform.filter\",\"name\":\"过滤\",\"enabled\":true,\"config\":{\"expression\":\"val > 100\"}}," +
                "{\"id\":\"k\",\"component\":\"sink.mysql\",\"name\":\"写\",\"enabled\":true,\"config\":{\"sourceId\":2,\"table\":\"dst\",\"writeMode\":\"APPEND\"}}]," +
                "\"edges\":[" +
                "{\"id\":\"e1\",\"from\":\"s\",\"fromPort\":\"out\",\"to\":\"f\",\"toPort\":\"in\",\"kind\":\"DATA\"}," +
                "{\"id\":\"e2\",\"from\":\"f\",\"fromPort\":\"out\",\"to\":\"k\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
        run(def, id -> id == 1 ? source : target,
                new OrchTaskExecutor.Context(1, 1L, DATE, Map.of(), false), 1000);
        assertEquals(0, new JdbcTemplate(target).queryForObject("SELECT COUNT(*) FROM dst", Integer.class));
    }

    // ---- transform.mask ----

    @Test void maskReplacesFieldWithStars() throws Exception {
        var source = db(); var target = db();
        exec(source, "CREATE TABLE src(id INT, phone VARCHAR(20), name VARCHAR(40))");
        exec(source, "INSERT INTO src VALUES (1,'13800138000','Alice'),(2,'13900139000','Bob')");
        exec(target, "CREATE TABLE dst(id INT, phone VARCHAR(20), name VARCHAR(40))");
        String def = "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" +
                "{\"id\":\"s\",\"component\":\"source.mysql\",\"name\":\"源\",\"enabled\":true,\"config\":{\"sourceId\":1,\"readMode\":\"SQL\",\"sql\":\"SELECT id,phone,name FROM src\"}}," +
                "{\"id\":\"m\",\"component\":\"transform.mask\",\"name\":\"脱敏\",\"enabled\":true,\"config\":{\"field\":\"phone\"}}," +
                "{\"id\":\"k\",\"component\":\"sink.mysql\",\"name\":\"写\",\"enabled\":true,\"config\":{\"sourceId\":2,\"table\":\"dst\",\"writeMode\":\"APPEND\"}}]," +
                "\"edges\":[" +
                "{\"id\":\"e1\",\"from\":\"s\",\"fromPort\":\"out\",\"to\":\"m\",\"toPort\":\"in\",\"kind\":\"DATA\"}," +
                "{\"id\":\"e2\",\"from\":\"m\",\"fromPort\":\"out\",\"to\":\"k\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
        run(def, id -> id == 1 ? source : target,
                new OrchTaskExecutor.Context(1, 1L, DATE, Map.of(), false), 1000);
        assertEquals(2, new JdbcTemplate(target).queryForObject("SELECT COUNT(*) FROM dst", Integer.class));
        assertEquals("****", new JdbcTemplate(target).queryForObject("SELECT phone FROM dst WHERE id=1", String.class));
        assertEquals("****", new JdbcTemplate(target).queryForObject("SELECT phone FROM dst WHERE id=2", String.class));
        // name should remain unchanged
        assertEquals("Alice", new JdbcTemplate(target).queryForObject("SELECT name FROM dst WHERE id=1", String.class));
    }

    @Test void maskFailsWhenFieldMissing() throws Exception {
        var source = db(); var target = db();
        exec(source, "CREATE TABLE src(id INT, name VARCHAR(40))");
        exec(source, "INSERT INTO src VALUES (1,'Alice')");
        exec(target, "CREATE TABLE dst(id INT, name VARCHAR(40))");
        String def = "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" +
                "{\"id\":\"s\",\"component\":\"source.mysql\",\"name\":\"源\",\"enabled\":true,\"config\":{\"sourceId\":1,\"readMode\":\"SQL\",\"sql\":\"SELECT id,name FROM src\"}}," +
                "{\"id\":\"m\",\"component\":\"transform.mask\",\"name\":\"脱敏\",\"enabled\":true,\"config\":{\"field\":\"nonexistent\"}}," +
                "{\"id\":\"k\",\"component\":\"sink.mysql\",\"name\":\"写\",\"enabled\":true,\"config\":{\"sourceId\":2,\"table\":\"dst\",\"writeMode\":\"APPEND\"}}]," +
                "\"edges\":[" +
                "{\"id\":\"e1\",\"from\":\"s\",\"fromPort\":\"out\",\"to\":\"m\",\"toPort\":\"in\",\"kind\":\"DATA\"}," +
                "{\"id\":\"e2\",\"from\":\"m\",\"fromPort\":\"out\",\"to\":\"k\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
        var ex = assertThrows(IllegalArgumentException.class, () ->
                run(def, id -> id == 1 ? source : target,
                        new OrchTaskExecutor.Context(1, 1L, DATE, Map.of(), false), 1000));
        assertTrue(ex.getMessage().contains("nonexistent"));
    }

    // ---- transform.calc ----

    @Test void calcAddsComputedField() throws Exception {
        var source = db(); var target = db();
        exec(source, "CREATE TABLE src(id INT, price DECIMAL(10,2), qty INT)");
        exec(source, "INSERT INTO src VALUES (1,10.5,3),(2,20.0,5)");
        exec(target, "CREATE TABLE dst(id INT, price DECIMAL(10,2), qty INT, total DECIMAL(10,2))");
        String def = "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" +
                "{\"id\":\"s\",\"component\":\"source.mysql\",\"name\":\"源\",\"enabled\":true,\"config\":{\"sourceId\":1,\"readMode\":\"SQL\",\"sql\":\"SELECT id,price,qty FROM src\"}}," +
                "{\"id\":\"c\",\"component\":\"transform.calc\",\"name\":\"计算\",\"enabled\":true,\"config\":{\"field\":\"total\",\"expression\":\"price * qty\"}}," +
                "{\"id\":\"k\",\"component\":\"sink.mysql\",\"name\":\"写\",\"enabled\":true,\"config\":{\"sourceId\":2,\"table\":\"dst\",\"writeMode\":\"APPEND\"}}]," +
                "\"edges\":[" +
                "{\"id\":\"e1\",\"from\":\"s\",\"fromPort\":\"out\",\"to\":\"c\",\"toPort\":\"in\",\"kind\":\"DATA\"}," +
                "{\"id\":\"e2\",\"from\":\"c\",\"fromPort\":\"out\",\"to\":\"k\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
        run(def, id -> id == 1 ? source : target,
                new OrchTaskExecutor.Context(1, 1L, DATE, Map.of(), false), 1000);
        assertEquals(2, new JdbcTemplate(target).queryForObject("SELECT COUNT(*) FROM dst", Integer.class));
        var total1 = new JdbcTemplate(target).queryForObject("SELECT total FROM dst WHERE id=1", java.math.BigDecimal.class);
        assertEquals(0, new java.math.BigDecimal("31.5").compareTo(total1));
        var total2 = new JdbcTemplate(target).queryForObject("SELECT total FROM dst WHERE id=2", java.math.BigDecimal.class);
        assertEquals(0, new java.math.BigDecimal("100.0").compareTo(total2));
    }

    @Test void calcRejectsDuplicateFieldName() throws Exception {
        var source = db(); var target = db();
        exec(source, "CREATE TABLE src(id INT, name VARCHAR(40))");
        exec(source, "INSERT INTO src VALUES (1,'Alice')");
        exec(target, "CREATE TABLE dst(id INT, name VARCHAR(40))");
        String def = "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" +
                "{\"id\":\"s\",\"component\":\"source.mysql\",\"name\":\"源\",\"enabled\":true,\"config\":{\"sourceId\":1,\"readMode\":\"SQL\",\"sql\":\"SELECT id,name FROM src\"}}," +
                "{\"id\":\"c\",\"component\":\"transform.calc\",\"name\":\"计算\",\"enabled\":true,\"config\":{\"field\":\"name\",\"expression\":\"id + 1\"}}," +
                "{\"id\":\"k\",\"component\":\"sink.mysql\",\"name\":\"写\",\"enabled\":true,\"config\":{\"sourceId\":2,\"table\":\"dst\",\"writeMode\":\"APPEND\"}}]," +
                "\"edges\":[" +
                "{\"id\":\"e1\",\"from\":\"s\",\"fromPort\":\"out\",\"to\":\"c\",\"toPort\":\"in\",\"kind\":\"DATA\"}," +
                "{\"id\":\"e2\",\"from\":\"c\",\"fromPort\":\"out\",\"to\":\"k\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
        var ex = assertThrows(IllegalArgumentException.class, () ->
                run(def, id -> id == 1 ? source : target,
                        new OrchTaskExecutor.Context(1, 1L, DATE, Map.of(), false), 1000));
        assertTrue(ex.getMessage().contains("已存在"));
    }

    @Test void calcWithRoundFunction() throws Exception {
        var source = db(); var target = db();
        exec(source, "CREATE TABLE src(id INT, raw_val DECIMAL(10,4))");
        exec(source, "INSERT INTO src VALUES (1,3.14159),(2,2.71828)");
        exec(target, "CREATE TABLE dst(id INT, raw_val DECIMAL(10,4), rounded DECIMAL(10,2))");
        String def = "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" +
                "{\"id\":\"s\",\"component\":\"source.mysql\",\"name\":\"源\",\"enabled\":true,\"config\":{\"sourceId\":1,\"readMode\":\"SQL\",\"sql\":\"SELECT id,raw_val FROM src\"}}," +
                "{\"id\":\"c\",\"component\":\"transform.calc\",\"name\":\"计算\",\"enabled\":true,\"config\":{\"field\":\"rounded\",\"expression\":\"ROUND(raw_val, 2)\"}}," +
                "{\"id\":\"k\",\"component\":\"sink.mysql\",\"name\":\"写\",\"enabled\":true,\"config\":{\"sourceId\":2,\"table\":\"dst\",\"writeMode\":\"APPEND\"}}]," +
                "\"edges\":[" +
                "{\"id\":\"e1\",\"from\":\"s\",\"fromPort\":\"out\",\"to\":\"c\",\"toPort\":\"in\",\"kind\":\"DATA\"}," +
                "{\"id\":\"e2\",\"from\":\"c\",\"fromPort\":\"out\",\"to\":\"k\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
        run(def, id -> id == 1 ? source : target,
                new OrchTaskExecutor.Context(1, 1L, DATE, Map.of(), false), 1000);
        var r1 = new JdbcTemplate(target).queryForObject("SELECT rounded FROM dst WHERE id=1", java.math.BigDecimal.class);
        assertEquals(0, new java.math.BigDecimal("3.14").compareTo(r1));
        var r2 = new JdbcTemplate(target).queryForObject("SELECT rounded FROM dst WHERE id=2", java.math.BigDecimal.class);
        assertEquals(0, new java.math.BigDecimal("2.72").compareTo(r2));
    }

    // ---- source.postgresql / sink.postgresql (same JDBC path, verify dispatch) ----

    @Test void postgresqlSourceAndSinkExecuteEndToEnd() throws Exception {
        var source = db(); var target = db();
        exec(source, "CREATE TABLE pg_src(id INT, label VARCHAR(40))");
        exec(source, "INSERT INTO pg_src VALUES (1,'pg-a'),(2,'pg-b')");
        exec(target, "CREATE TABLE pg_dst(id INT, label VARCHAR(40))");
        String def = "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" +
                "{\"id\":\"s\",\"component\":\"source.postgresql\",\"name\":\"PG源\",\"enabled\":true,\"config\":{\"sourceId\":1,\"readMode\":\"SQL\",\"sql\":\"SELECT id,label FROM pg_src\"}}," +
                "{\"id\":\"k\",\"component\":\"sink.postgresql\",\"name\":\"PG写\",\"enabled\":true,\"config\":{\"sourceId\":2,\"table\":\"pg_dst\",\"writeMode\":\"APPEND\"}}]," +
                "\"edges\":[{\"id\":\"e1\",\"from\":\"s\",\"fromPort\":\"out\",\"to\":\"k\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
        var outcome = run(def, id -> id == 1 ? source : target,
                new OrchTaskExecutor.Context(1, 1L, DATE, Map.of(), false), 1000);
        assertEquals(2, new JdbcTemplate(target).queryForObject("SELECT COUNT(*) FROM pg_dst", Integer.class));
        assertEquals("pg-a", new JdbcTemplate(target).queryForObject("SELECT label FROM pg_dst WHERE id=1", String.class));
        assertTrue(outcome.states().stream().allMatch(s -> "SUCCEEDED".equals(s.state())));
    }

    @Test void postgresqlSinkUpsertIsIdempotent() throws Exception {
        var source = db(); var target = db();
        exec(source, "CREATE TABLE pg_src(id INT, val VARCHAR(40))");
        exec(source, "INSERT INTO pg_src VALUES (1,'first'),(2,'second')");
        exec(target, "CREATE TABLE pg_dst(id INT PRIMARY KEY, val VARCHAR(40))");
        String def = "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" +
                "{\"id\":\"s\",\"component\":\"source.postgresql\",\"name\":\"PG源\",\"enabled\":true,\"config\":{\"sourceId\":1,\"readMode\":\"SQL\",\"sql\":\"SELECT id,val FROM pg_src\"}}," +
                "{\"id\":\"k\",\"component\":\"sink.postgresql\",\"name\":\"PG写\",\"enabled\":true,\"config\":{\"sourceId\":2,\"table\":\"pg_dst\",\"writeMode\":\"UPSERT\",\"keyColumns\":[\"id\"]}}]," +
                "\"edges\":[{\"id\":\"e1\",\"from\":\"s\",\"fromPort\":\"out\",\"to\":\"k\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
        CamelTaskRunner.DataSourceProvider provider = id -> id == 1 ? source : target;
        // first run
        run(def, provider, new OrchTaskExecutor.Context(1, 1L, DATE, Map.of(), false), 1000);
        assertEquals(2, new JdbcTemplate(target).queryForObject("SELECT COUNT(*) FROM pg_dst", Integer.class));
        // second run — must not duplicate
        run(def, provider, new OrchTaskExecutor.Context(1, 2L, DATE, Map.of(), false), 1000);
        assertEquals(2, new JdbcTemplate(target).queryForObject("SELECT COUNT(*) FROM pg_dst", Integer.class));
    }

    // ---- source.oracle / sink.oracle (same JDBC path, verify dispatch) ----

    @Test void oracleSourceAndSinkExecuteEndToEnd() throws Exception {
        var source = db(); var target = db();
        exec(source, "CREATE TABLE ora_src(id INT, label VARCHAR(40))");
        exec(source, "INSERT INTO ora_src VALUES (1,'ora-a'),(2,'ora-b'),(3,'ora-c')");
        exec(target, "CREATE TABLE ora_dst(id INT, label VARCHAR(40))");
        String def = "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" +
                "{\"id\":\"s\",\"component\":\"source.oracle\",\"name\":\"ORA源\",\"enabled\":true,\"config\":{\"sourceId\":1,\"readMode\":\"SQL\",\"sql\":\"SELECT id,label FROM ora_src\"}}," +
                "{\"id\":\"k\",\"component\":\"sink.oracle\",\"name\":\"ORA写\",\"enabled\":true,\"config\":{\"sourceId\":2,\"table\":\"ora_dst\",\"writeMode\":\"APPEND\"}}]," +
                "\"edges\":[{\"id\":\"e1\",\"from\":\"s\",\"fromPort\":\"out\",\"to\":\"k\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
        var outcome = run(def, id -> id == 1 ? source : target,
                new OrchTaskExecutor.Context(1, 1L, DATE, Map.of(), false), 1000);
        assertEquals(3, new JdbcTemplate(target).queryForObject("SELECT COUNT(*) FROM ora_dst", Integer.class));
        assertEquals("ora-b", new JdbcTemplate(target).queryForObject("SELECT label FROM ora_dst WHERE id=2", String.class));
        assertTrue(outcome.states().stream().allMatch(s -> "SUCCEEDED".equals(s.state())));
    }

    @Test void oracleSinkDryRunDoesNotWrite() throws Exception {
        var source = db(); var target = db();
        exec(source, "CREATE TABLE ora_src(id INT)");
        exec(source, "INSERT INTO ora_src VALUES (1),(2)");
        exec(target, "CREATE TABLE ora_dst(id INT)");
        String def = "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" +
                "{\"id\":\"s\",\"component\":\"source.oracle\",\"name\":\"ORA源\",\"enabled\":true,\"config\":{\"sourceId\":1,\"readMode\":\"SQL\",\"sql\":\"SELECT id FROM ora_src\"}}," +
                "{\"id\":\"k\",\"component\":\"sink.oracle\",\"name\":\"ORA写\",\"enabled\":true,\"config\":{\"sourceId\":2,\"table\":\"ora_dst\",\"writeMode\":\"APPEND\"}}]," +
                "\"edges\":[{\"id\":\"e1\",\"from\":\"s\",\"fromPort\":\"out\",\"to\":\"k\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
        try (var sql = new SqlTaskExecutor()) {
            var executor = new OrchTaskExecutor(sql, new HttpTargetGuard(""));
            var outcome = executor.execute(OrchDefinition.parse(M.readTree(def)),
                    id -> id == 1 ? source : target,
                    new OrchTaskExecutor.Context(1, null, DATE, Map.of(), true),
                    new RunCancelContext(), ExecutionSinks.NOOP, null, 100, 1000, null);
            assertEquals(0, new JdbcTemplate(target).queryForObject("SELECT COUNT(*) FROM ora_dst", Integer.class));
            assertTrue(outcome.previews().containsKey("k"));
            assertEquals(2, outcome.previews().get("k").rows().size());
        }
    }

    // ---- Component registry validation ----

    @Test void allImplementedComponentsAreEnabledInRegistry() {
        var enabledTypes = OrchComponents.all().stream()
                .filter(OrchComponents.Component::enabled)
                .map(OrchComponents.Component::type)
                .toList();
        // Verify all 16 implemented components are enabled
        assertTrue(enabledTypes.contains("source.mysql"));
        assertTrue(enabledTypes.contains("source.postgresql"));
        assertTrue(enabledTypes.contains("source.oracle"));
        assertTrue(enabledTypes.contains("source.http"));
        assertTrue(enabledTypes.contains("transform.sql"));
        assertTrue(enabledTypes.contains("transform.field-map"));
        assertTrue(enabledTypes.contains("transform.filter"));
        assertTrue(enabledTypes.contains("transform.clean"));
        assertTrue(enabledTypes.contains("transform.mask"));
        assertTrue(enabledTypes.contains("transform.calc"));
        assertTrue(enabledTypes.contains("control.branch"));
        assertTrue(enabledTypes.contains("control.merge"));
        assertTrue(enabledTypes.contains("sink.mysql"));
        assertTrue(enabledTypes.contains("sink.postgresql"));
        assertTrue(enabledTypes.contains("sink.oracle"));
        assertTrue(enabledTypes.contains("sink.http"));
        assertEquals(16, enabledTypes.size());
    }

    @Test void disabledComponentsCannotBeExecuted() {
        var disabledTypes = OrchComponents.all().stream()
                .filter(c -> !c.enabled())
                .map(OrchComponents.Component::type)
                .toList();
        assertTrue(disabledTypes.contains("source.sqlserver"));
        assertTrue(disabledTypes.contains("source.kafka"));
        assertTrue(disabledTypes.contains("source.ftp"));
        assertTrue(disabledTypes.contains("source.minio"));
        assertTrue(disabledTypes.contains("transform.python"));
        assertTrue(disabledTypes.contains("transform.procedure"));
        assertTrue(disabledTypes.contains("transform.error-handler"));
        assertTrue(disabledTypes.contains("sink.kafka"));
        assertTrue(disabledTypes.contains("sink.file"));
        assertTrue(disabledTypes.contains("sink.minio"));
        assertTrue(disabledTypes.contains("job.flink"));
        assertEquals(11, disabledTypes.size());
    }

    // ---- Chained pipeline: filter → calc → mask → sink ----

    @Test void chainedTransformPipelineFilterCalcMaskSink() throws Exception {
        var source = db(); var target = db();
        exec(source, "CREATE TABLE src(id INT, name VARCHAR(40), amount DECIMAL(10,2), phone VARCHAR(20))");
        exec(source, "INSERT INTO src VALUES (1,'Alice',500.0,'13800001111'),(2,'Bob',1500.0,'13900002222'),(3,'Carol',2000.0,'13700003333'),(4,'Dave',300.0,'13600004444')");
        exec(target, "CREATE TABLE dst(id INT, name VARCHAR(40), amount DECIMAL(10,2), phone VARCHAR(20), tax DECIMAL(10,2))");
        // Pipeline: source → filter(amount>=1000) → calc(tax=amount*0.1) → mask(phone) → sink
        String def = "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" +
                "{\"id\":\"s\",\"component\":\"source.mysql\",\"name\":\"源\",\"enabled\":true,\"config\":{\"sourceId\":1,\"readMode\":\"SQL\",\"sql\":\"SELECT id,name,amount,phone FROM src\"}}," +
                "{\"id\":\"f\",\"component\":\"transform.filter\",\"name\":\"过滤\",\"enabled\":true,\"config\":{\"expression\":\"amount >= 1000\"}}," +
                "{\"id\":\"c\",\"component\":\"transform.calc\",\"name\":\"计算税\",\"enabled\":true,\"config\":{\"field\":\"tax\",\"expression\":\"amount * 0.1\"}}," +
                "{\"id\":\"m\",\"component\":\"transform.mask\",\"name\":\"脱敏\",\"enabled\":true,\"config\":{\"field\":\"phone\"}}," +
                "{\"id\":\"k\",\"component\":\"sink.mysql\",\"name\":\"写\",\"enabled\":true,\"config\":{\"sourceId\":2,\"table\":\"dst\",\"writeMode\":\"APPEND\"}}]," +
                "\"edges\":[" +
                "{\"id\":\"e1\",\"from\":\"s\",\"fromPort\":\"out\",\"to\":\"f\",\"toPort\":\"in\",\"kind\":\"DATA\"}," +
                "{\"id\":\"e2\",\"from\":\"f\",\"fromPort\":\"out\",\"to\":\"c\",\"toPort\":\"in\",\"kind\":\"DATA\"}," +
                "{\"id\":\"e3\",\"from\":\"c\",\"fromPort\":\"out\",\"to\":\"m\",\"toPort\":\"in\",\"kind\":\"DATA\"}," +
                "{\"id\":\"e4\",\"from\":\"m\",\"fromPort\":\"out\",\"to\":\"k\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
        var outcome = run(def, id -> id == 1 ? source : target,
                new OrchTaskExecutor.Context(1, 1L, DATE, Map.of(), false), 1000);
        assertEquals(2, new JdbcTemplate(target).queryForObject("SELECT COUNT(*) FROM dst", Integer.class));
        // phone masked
        assertEquals("****", new JdbcTemplate(target).queryForObject("SELECT phone FROM dst WHERE id=2", String.class));
        // tax computed
        var tax = new JdbcTemplate(target).queryForObject("SELECT tax FROM dst WHERE id=2", java.math.BigDecimal.class);
        assertEquals(0, new java.math.BigDecimal("150.0").compareTo(tax));
        // all states succeeded
        assertTrue(outcome.states().stream().allMatch(s -> "SUCCEEDED".equals(s.state())));
    }
}
