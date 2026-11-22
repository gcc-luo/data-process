package com.example.governance.workflow;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** End-to-end engine tests on H2: data landing, branch/merge/clean, retry, watermark, caps, cancel. */
class OrchTaskExecutorTest {
    private static DriverManagerDataSource db() {
        return new DriverManagerDataSource("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    }
    private static void exec(DataSource s, String sql) {
        try (var c = s.getConnection(); var st = c.createStatement()) { st.execute(sql); } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static final LocalDate DATE = LocalDate.of(2026, 11, 21);

    private OrchTaskExecutor.Outcome run(String def, CamelTaskRunner.DataSourceProvider provider, OrchTaskExecutor.Context ctx,
                                         RunCancelContext cancel, WatermarkStore wm, int maxRows) throws Exception {
        try (var sql = new SqlTaskExecutor()) {
            var executor = new OrchTaskExecutor(sql, new HttpTargetGuard(""));
            var logs = new ArrayList<String>();
            var states = new ArrayList<OrchTaskExecutor.NodeState>();
            var sink = new OrchTaskExecutor.NodeSink() {
                public void start(String k, int a) {}
                public void success(String k, int a, long in, long out, long ms) {}
                public void failure(String k, int a, String e, long ms) {}
                public void skipped(String k) {}
            };
            return executor.execute(OrchDefinition.parse(new com.fasterxml.jackson.databind.ObjectMapper().readTree(def)),
                    provider, ctx, cancel, new ExecutionSinks((l, m) -> logs.add(m), j -> {}), sink, 0, maxRows, wm);
        }
    }

    @Test void sourceMapSinkUpsertIsIdempotentOnRerun() throws Exception {
        var source = db(); var target = db();
        exec(source, "CREATE TABLE src(id INT, order_no VARCHAR(40), amount DECIMAL(10,2))");
        exec(source, "INSERT INTO src VALUES (1,'A',10.5),(2,'B',20.0)");
        exec(target, "CREATE TABLE dst(order_id INT PRIMARY KEY, order_no VARCHAR(40), order_amount DECIMAL(10,2))");
        String def = "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" +
                "{\"id\":\"s\",\"component\":\"source.mysql\",\"name\":\"源\",\"enabled\":true,\"config\":{\"sourceId\":1,\"readMode\":\"SQL\",\"sql\":\"SELECT id,order_no,amount FROM src\"}}," +
                "{\"id\":\"m\",\"component\":\"transform.field-map\",\"name\":\"映射\",\"enabled\":true,\"config\":{\"mappings\":[{\"from\":\"id\",\"to\":\"order_id\",\"targetType\":\"INTEGER\"},{\"from\":\"order_no\",\"to\":\"order_no\",\"targetType\":\"STRING\"},{\"from\":\"amount\",\"to\":\"order_amount\",\"targetType\":\"DECIMAL\"}]}}," +
                "{\"id\":\"k\",\"component\":\"sink.mysql\",\"name\":\"写\",\"enabled\":true,\"config\":{\"sourceId\":2,\"table\":\"dst\",\"writeMode\":\"UPSERT\",\"keyColumns\":[\"order_id\"],\"batchSize\":500}}]," +
                "\"edges\":[{\"id\":\"e1\",\"from\":\"s\",\"fromPort\":\"out\",\"to\":\"m\",\"toPort\":\"in\",\"kind\":\"DATA\"},{\"id\":\"e2\",\"from\":\"m\",\"fromPort\":\"out\",\"to\":\"k\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
        CamelTaskRunner.DataSourceProvider provider = id -> id == 1 ? source : target;
        var ctx = new OrchTaskExecutor.Context(1, 100L, DATE, Map.of(), false);
        run(def, provider, ctx, new RunCancelContext(), null, 1000);
        var jdbc = new JdbcTemplate(target);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM dst", Integer.class));
        assertEquals("A", jdbc.queryForObject("SELECT order_no FROM dst WHERE order_id=1", String.class));
        // rerun must not duplicate
        run(def, provider, new OrchTaskExecutor.Context(1, 101L, DATE, Map.of(), false), new RunCancelContext(), null, 1000);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM dst", Integer.class));
    }

    @Test void branchRoutesRowsAndDefaultCatchesRest() throws Exception {
        var source = db(); var vip = db(); var normal = db();
        exec(source, "CREATE TABLE src(id INT, amount DECIMAL(10,2))");
        exec(source, "INSERT INTO src VALUES (1,1500),(2,500),(3,2000)");
        exec(vip, "CREATE TABLE vip(id INT, amount DECIMAL(10,2))"); exec(normal, "CREATE TABLE normal(id INT, amount DECIMAL(10,2))");
        String def = "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" +
                "{\"id\":\"s\",\"component\":\"source.mysql\",\"name\":\"源\",\"enabled\":true,\"config\":{\"sourceId\":1,\"readMode\":\"SQL\",\"sql\":\"SELECT id,amount FROM src\"}}," +
                "{\"id\":\"b\",\"component\":\"control.branch\",\"name\":\"分支\",\"enabled\":true,\"config\":{\"rules\":[{\"key\":\"high\",\"label\":\"大额\",\"expression\":\"amount >= 1000\"}],\"defaultKey\":\"low\"}}," +
                "{\"id\":\"kv\",\"component\":\"sink.mysql\",\"name\":\"VIP\",\"enabled\":true,\"config\":{\"sourceId\":2,\"table\":\"vip\",\"writeMode\":\"APPEND\"}}," +
                "{\"id\":\"kn\",\"component\":\"sink.mysql\",\"name\":\"普通\",\"enabled\":true,\"config\":{\"sourceId\":3,\"table\":\"normal\",\"writeMode\":\"APPEND\"}}]," +
                "\"edges\":[{\"id\":\"e1\",\"from\":\"s\",\"fromPort\":\"out\",\"to\":\"b\",\"toPort\":\"in\",\"kind\":\"DATA\"}," +
                "{\"id\":\"e2\",\"from\":\"b\",\"fromPort\":\"high\",\"to\":\"kv\",\"toPort\":\"in\",\"kind\":\"DATA\"}," +
                "{\"id\":\"e3\",\"from\":\"b\",\"fromPort\":\"low\",\"to\":\"kn\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
        var outcome = run(def, id -> id == 1 ? source : id == 2 ? vip : normal, new OrchTaskExecutor.Context(1, 1L, DATE, Map.of(), false), new RunCancelContext(), null, 1000);
        assertEquals(2, new JdbcTemplate(vip).queryForObject("SELECT COUNT(*) FROM vip", Integer.class));
        assertEquals(1, new JdbcTemplate(normal).queryForObject("SELECT COUNT(*) FROM normal", Integer.class));
        assertTrue(outcome.states().stream().allMatch(s -> "SUCCEEDED".equals(s.state())));
    }

    @Test void mergeUnionCombinesBranches() throws Exception {
        var source = db(); var target = db();
        exec(source, "CREATE TABLE a(id INT)"); exec(source, "INSERT INTO a VALUES (1),(2)");
        exec(source, "CREATE TABLE b(id INT)"); exec(source, "INSERT INTO b VALUES (3)");
        exec(target, "CREATE TABLE dst(id INT)");
        String def = "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" +
                "{\"id\":\"sa\",\"component\":\"source.mysql\",\"name\":\"A\",\"enabled\":true,\"config\":{\"sourceId\":1,\"readMode\":\"SQL\",\"sql\":\"SELECT id FROM a\"}}," +
                "{\"id\":\"sb\",\"component\":\"source.mysql\",\"name\":\"B\",\"enabled\":true,\"config\":{\"sourceId\":1,\"readMode\":\"SQL\",\"sql\":\"SELECT id FROM b\"}}," +
                "{\"id\":\"mg\",\"component\":\"control.merge\",\"name\":\"汇聚\",\"enabled\":true,\"config\":{\"strategy\":\"UNION\"}}," +
                "{\"id\":\"k\",\"component\":\"sink.mysql\",\"name\":\"写\",\"enabled\":true,\"config\":{\"sourceId\":2,\"table\":\"dst\",\"writeMode\":\"APPEND\"}}]," +
                "\"edges\":[{\"id\":\"e1\",\"from\":\"sa\",\"fromPort\":\"out\",\"to\":\"mg\",\"toPort\":\"in\",\"kind\":\"DATA\"}," +
                "{\"id\":\"e2\",\"from\":\"sb\",\"fromPort\":\"out\",\"to\":\"mg\",\"toPort\":\"in\",\"kind\":\"DATA\"}," +
                "{\"id\":\"e3\",\"from\":\"mg\",\"fromPort\":\"out\",\"to\":\"k\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
        run(def, id -> id == 1 ? source : target, new OrchTaskExecutor.Context(1, 1L, DATE, Map.of(), false), new RunCancelContext(), null, 1000);
        assertEquals(3, new JdbcTemplate(target).queryForObject("SELECT COUNT(*) FROM dst", Integer.class));
    }

    @Test void cleanTrimsDedupsAndDropsNulls() throws Exception {
        var source = db(); var target = db();
        exec(source, "CREATE TABLE src(name VARCHAR(40))");
        exec(source, "INSERT INTO src VALUES ('  a '),('a'),(NULL),('b')");
        exec(target, "CREATE TABLE dst(name VARCHAR(40))");
        String def = "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" +
                "{\"id\":\"s\",\"component\":\"source.mysql\",\"name\":\"源\",\"enabled\":true,\"config\":{\"sourceId\":1,\"readMode\":\"SQL\",\"sql\":\"SELECT name FROM src\"}}," +
                "{\"id\":\"c\",\"component\":\"transform.clean\",\"name\":\"清洗\",\"enabled\":true,\"config\":{\"operations\":[{\"op\":\"TRIM\",\"fields\":[\"name\"]},{\"op\":\"DROP_NULL\",\"fields\":[\"name\"]},{\"op\":\"DEDUP\",\"fields\":[\"name\"]}]}}," +
                "{\"id\":\"k\",\"component\":\"sink.mysql\",\"name\":\"写\",\"enabled\":true,\"config\":{\"sourceId\":2,\"table\":\"dst\",\"writeMode\":\"APPEND\"}}]," +
                "\"edges\":[{\"id\":\"e1\",\"from\":\"s\",\"fromPort\":\"out\",\"to\":\"c\",\"toPort\":\"in\",\"kind\":\"DATA\"},{\"id\":\"e2\",\"from\":\"c\",\"fromPort\":\"out\",\"to\":\"k\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
        run(def, id -> id == 1 ? source : target, new OrchTaskExecutor.Context(1, 1L, DATE, Map.of(), false), new RunCancelContext(), null, 1000);
        assertEquals(2, new JdbcTemplate(target).queryForObject("SELECT COUNT(*) FROM dst", Integer.class));
    }

    @Test void nodeRetrySucceedsAfterTransientFailure() throws Exception {
        var source = db(); var target = db();
        exec(source, "CREATE TABLE src(id INT)"); exec(source, "INSERT INTO src VALUES (1)");
        exec(target, "CREATE TABLE dst(id INT)");
        String def = "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" +
                "{\"id\":\"s\",\"component\":\"source.mysql\",\"name\":\"源\",\"enabled\":true,\"config\":{\"sourceId\":1,\"readMode\":\"SQL\",\"sql\":\"SELECT id FROM src\"}}," +
                "{\"id\":\"k\",\"component\":\"sink.mysql\",\"name\":\"写\",\"enabled\":true,\"retry\":{\"enabled\":true,\"maxAttempts\":3,\"intervalSeconds\":1,\"backoff\":\"FIXED\",\"maxIntervalSeconds\":1,\"acknowledgeDuplicateRisk\":true},\"config\":{\"sourceId\":2,\"table\":\"dst\",\"writeMode\":\"APPEND\"}}]," +
                "\"edges\":[{\"id\":\"e1\",\"from\":\"s\",\"fromPort\":\"out\",\"to\":\"k\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
        var calls = new AtomicInteger();
        CamelTaskRunner.DataSourceProvider provider = id -> {
            if (id == 2 && calls.getAndIncrement() == 0) throw new IllegalStateException("transient connection failure");
            return id == 1 ? source : target;
        };
        var outcome = run(def, provider, new OrchTaskExecutor.Context(1, 1L, DATE, Map.of(), false), new RunCancelContext(), null, 1000);
        var sinkState = outcome.states().stream().filter(s -> s.nodeKey().equals("k")).findFirst().orElseThrow();
        assertEquals("SUCCEEDED", sinkState.state());
        assertEquals(2, sinkState.attempt());
        assertEquals(1, new JdbcTemplate(target).queryForObject("SELECT COUNT(*) FROM dst", Integer.class));
    }

    @Test void artifactCapFailsFastWithoutPartialWrite() throws Exception {
        var source = db(); var target = db();
        exec(source, "CREATE TABLE src(id INT)");
        exec(source, "INSERT INTO src VALUES (1),(2),(3),(4),(5)");
        exec(target, "CREATE TABLE dst(id INT)");
        String def = OrchFixtures.sync(1, 2, "SELECT id FROM src", "dst", List.of("id"), "APPEND", List.of());
        var ex = assertThrows(IllegalArgumentException.class, () -> run(def, id -> id == 1 ? source : target,
                new OrchTaskExecutor.Context(1, 1L, DATE, Map.of(), false), new RunCancelContext(), null, 3));
        assertTrue(ex.getMessage().contains("内存交接上限"));
        assertEquals(0, new JdbcTemplate(target).queryForObject("SELECT COUNT(*) FROM dst", Integer.class));
    }

    @Test void watermarkAdvancesOnlyOnSuccessAndReadsIncrementally() throws Exception {
        var meta = OrchFixtures.h2(); var wm = new WatermarkStore(new JdbcTemplate(meta));
        new JdbcTemplate(meta).update("INSERT INTO processing_tasks(id,name,task_type,current_version) VALUES (7,'wm','ORCH',0)");
        var source = db(); var target = db();
        exec(source, "CREATE TABLE src(id INT, updated_at TIMESTAMP)");
        exec(source, "INSERT INTO src VALUES (1, TIMESTAMP '2026-11-01 00:00:00'),(2, TIMESTAMP '2026-11-02 00:00:00')");
        exec(target, "CREATE TABLE dst(id INT PRIMARY KEY, updated_at TIMESTAMP)");
        String def = "{\"schemaVersion\":1,\"variables\":[{\"name\":\"last_update_time\",\"type\":\"DATETIME\",\"valueSource\":\"WATERMARK\",\"defaultValue\":\"2026-01-01T00:00:00\",\"required\":false}],\"nodes\":[" +
                "{\"id\":\"s\",\"component\":\"source.mysql\",\"name\":\"源\",\"enabled\":true,\"config\":{\"sourceId\":1,\"readMode\":\"SQL\",\"sql\":\"SELECT id,updated_at FROM src WHERE updated_at > :last_update_time OR (updated_at = :last_update_time AND id > :last_tie_breaker) ORDER BY updated_at,id\",\"incremental\":true,\"watermarkColumn\":\"updated_at\",\"tieBreakerColumn\":\"id\",\"watermarkVariable\":\"last_update_time\"}}," +
                "{\"id\":\"k\",\"component\":\"sink.mysql\",\"name\":\"写\",\"enabled\":true,\"config\":{\"sourceId\":2,\"table\":\"dst\",\"writeMode\":\"UPSERT\",\"keyColumns\":[\"id\"]}}]," +
                "\"edges\":[{\"id\":\"e1\",\"from\":\"s\",\"fromPort\":\"out\",\"to\":\"k\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
        CamelTaskRunner.DataSourceProvider provider = id -> id == 1 ? source : target;
        run(def, provider, new OrchTaskExecutor.Context(7, 1L, DATE, Map.of(), false), new RunCancelContext(), wm, 1000);
        assertEquals(2, new JdbcTemplate(target).queryForObject("SELECT COUNT(*) FROM dst", Integer.class));
        assertTrue(wm.find(7, "s").isPresent());
        // new later row → second run reads only it
        exec(source, "INSERT INTO src VALUES (3, TIMESTAMP '2026-11-03 00:00:00')");
        var outcome = run(def, provider, new OrchTaskExecutor.Context(7, 2L, DATE, Map.of(), false), new RunCancelContext(), wm, 1000);
        var srcState = outcome.states().stream().filter(s -> s.nodeKey().equals("s")).findFirst().orElseThrow();
        assertEquals(1, srcState.rowsOut());
        assertEquals(3, new JdbcTemplate(target).queryForObject("SELECT COUNT(*) FROM dst", Integer.class));
    }

    @Test void watermarkNotAdvancedWhenRunFails() throws Exception {
        var meta = OrchFixtures.h2(); var wm = new WatermarkStore(new JdbcTemplate(meta));
        new JdbcTemplate(meta).update("INSERT INTO processing_tasks(id,name,task_type,current_version) VALUES (7,'wm','ORCH',0)");
        var source = db(); var target = db();
        exec(source, "CREATE TABLE src(id INT, updated_at TIMESTAMP)");
        exec(source, "INSERT INTO src VALUES (1, TIMESTAMP '2026-11-01 00:00:00')");
        // target table missing → sink fails
        String def = "{\"schemaVersion\":1,\"variables\":[{\"name\":\"last_update_time\",\"type\":\"DATETIME\",\"valueSource\":\"WATERMARK\",\"defaultValue\":\"2026-01-01T00:00:00\"}],\"nodes\":[" +
                "{\"id\":\"s\",\"component\":\"source.mysql\",\"name\":\"源\",\"enabled\":true,\"config\":{\"sourceId\":1,\"readMode\":\"SQL\",\"sql\":\"SELECT id,updated_at FROM src WHERE updated_at >= :last_update_time\",\"incremental\":true,\"watermarkColumn\":\"updated_at\",\"watermarkVariable\":\"last_update_time\"}}," +
                "{\"id\":\"k\",\"component\":\"sink.mysql\",\"name\":\"写\",\"enabled\":true,\"config\":{\"sourceId\":2,\"table\":\"missing\",\"writeMode\":\"APPEND\"}}]," +
                "\"edges\":[{\"id\":\"e1\",\"from\":\"s\",\"fromPort\":\"out\",\"to\":\"k\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
        assertThrows(Exception.class, () -> run(def, id -> id == 1 ? source : target, new OrchTaskExecutor.Context(7, 1L, DATE, Map.of(), false), new RunCancelContext(), wm, 1000));
        assertTrue(wm.find(7, "s").isEmpty());
    }

    @Test void dryRunDoesNotWriteTarget() throws Exception {
        var source = db(); var target = db();
        exec(source, "CREATE TABLE src(id INT)"); exec(source, "INSERT INTO src VALUES (1),(2)");
        exec(target, "CREATE TABLE dst(id INT)");
        String def = OrchFixtures.sync(1, 2, "SELECT id FROM src", "dst", List.of("id"), "APPEND", List.of());
        try (var sql = new SqlTaskExecutor()) {
            var executor = new OrchTaskExecutor(sql, new HttpTargetGuard(""));
            var outcome = executor.execute(OrchDefinition.parse(new com.fasterxml.jackson.databind.ObjectMapper().readTree(def)),
                    id -> id == 1 ? source : target, new OrchTaskExecutor.Context(1, null, DATE, Map.of(), true),
                    new RunCancelContext(), ExecutionSinks.NOOP, null, 100, 1000, null);
            assertEquals(0, new JdbcTemplate(target).queryForObject("SELECT COUNT(*) FROM dst", Integer.class));
            assertTrue(outcome.previews().containsKey("n_sink"));
            assertEquals(2, outcome.previews().get("n_sink").rows().size());
        }
    }

    @Test void realRunCapturesPreviewWhileWritingData() throws Exception {
        var source = db(); var target = db();
        exec(source, "CREATE TABLE src(id INT, name VARCHAR(40))");
        exec(source, "INSERT INTO src VALUES (1,'a'),(2,'b')");
        exec(target, "CREATE TABLE dst(id INT, name VARCHAR(40))");
        String def = OrchFixtures.sync(1, 2, "SELECT id,name FROM src", "dst", List.of("id", "name"), "APPEND", List.of());
        var stats = new ArrayList<String>();
        try (var sql = new SqlTaskExecutor()) {
            var executor = new OrchTaskExecutor(sql, new HttpTargetGuard(""));
            var outcome = executor.execute(OrchDefinition.parse(new com.fasterxml.jackson.databind.ObjectMapper().readTree(def)),
                    id -> id == 1 ? source : target, new OrchTaskExecutor.Context(1, 1L, DATE, Map.of(), false),
                    new RunCancelContext(), new ExecutionSinks((l, m) -> {}, stats::add), null, 100, 1000, null);
            // 正式运行（dryRun=false）既写入了数据，也采样了预览，且写了统计
            assertEquals(2, new JdbcTemplate(target).queryForObject("SELECT COUNT(*) FROM dst", Integer.class));
            assertTrue(outcome.previews().containsKey("n_sink"));
            assertEquals(2, outcome.previews().get("n_sink").rows().size());
            assertEquals(1, stats.size());
        }
    }

    @Test void sqlInjectionViaNamedParameterCannotChangeStructure() throws Exception {
        var source = db();
        exec(source, "CREATE TABLE src(id INT, label VARCHAR(40))");
        exec(source, "INSERT INTO src VALUES (1,'safe')");
        exec(source, "CREATE TABLE dst(id INT, label VARCHAR(40))");
        String def = "{\"schemaVersion\":1,\"variables\":[{\"name\":\"p\",\"type\":\"STRING\",\"valueSource\":\"MANUAL\"}],\"nodes\":[" +
                "{\"id\":\"s\",\"component\":\"source.mysql\",\"name\":\"源\",\"enabled\":true,\"config\":{\"sourceId\":1,\"readMode\":\"SQL\",\"sql\":\"SELECT id,label FROM src WHERE label = :p\"}}," +
                "{\"id\":\"k\",\"component\":\"sink.mysql\",\"name\":\"写\",\"enabled\":true,\"config\":{\"sourceId\":1,\"table\":\"dst\",\"writeMode\":\"APPEND\"}}]," +
                "\"edges\":[{\"id\":\"e1\",\"from\":\"s\",\"fromPort\":\"out\",\"to\":\"k\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
        // malicious value is bound as a literal, matches nothing, no structural change
        run(def, id -> source, new OrchTaskExecutor.Context(1, 1L, DATE, Map.of("p", "x' OR '1'='1"), false), new RunCancelContext(), null, 1000);
        assertEquals(0, new JdbcTemplate(source).queryForObject("SELECT COUNT(*) FROM dst", Integer.class));
        assertEquals(1, new JdbcTemplate(source).queryForObject("SELECT COUNT(*) FROM src", Integer.class));
    }
}