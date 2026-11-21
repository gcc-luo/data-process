package com.example.governance.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class FlowTaskExecutorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private record Fixture(JdbcTemplate jdbc, DataSource ds) {}
    private Fixture fixture() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:flow_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).load().migrate();
        var jdbc = new JdbcTemplate(ds);
        jdbc.update("CREATE TABLE orders(id INT PRIMARY KEY, name VARCHAR(50), amount DECIMAL(10,2))");
        jdbc.update("INSERT INTO orders VALUES (1,'a',10),(2,'b',-5),(3,'c',20)");
        jdbc.update("CREATE TABLE orders_copy(id INT PRIMARY KEY, name VARCHAR(50), amount DECIMAL(10,2), tag VARCHAR(10))");
        return new Fixture(jdbc, ds);
    }
    private FlowGraph.Graph graph(String json) throws Exception { return FlowGraph.parse(MAPPER.readTree(json)); }
    private static final String CHAIN = "{\"nodes\":["
            + "{\"key\":\"s\",\"component\":\"SOURCE_TABLE\",\"config\":{\"sourceId\":1,\"table\":\"orders\"}},"
            + "{\"key\":\"f\",\"component\":\"FIELD_FILTER\",\"config\":{\"expression\":\"amount > 0\"}},"
            + "{\"key\":\"m\",\"component\":\"FIELD_MASK\",\"config\":{\"field\":\"name\"}},"
            + "{\"key\":\"c\",\"component\":\"FIELD_CALC\",\"config\":{\"field\":\"tag\",\"expression\":\"CONCAT('t', id)\"}},"
            + "{\"key\":\"t\",\"component\":\"SINK_TABLE\",\"config\":{\"sourceId\":1,\"table\":\"orders_copy\",\"writeMode\":\"APPEND\"}}],"
            + "\"edges\":[{\"from\":\"s\",\"to\":\"f\"},{\"from\":\"f\",\"to\":\"m\"},{\"from\":\"m\",\"to\":\"c\"},{\"from\":\"c\",\"to\":\"t\"}]}";

    @Test void appendPipelineFiltersMasksCalculatesAndCounts() throws Exception {
        var f = fixture();
        var logs = new ArrayList<String>();
        var statsJson = new ArrayList<String>();
        var outcome = new FlowTaskExecutor().execute(graph(CHAIN), id -> f.ds(), LocalDate.of(2026, 11, 20), new RunCancelContext(),
                new ExecutionSinks((l, m) -> logs.add(l + ":" + m), statsJson::add), 0);
        assertEquals(2, f.jdbc().queryForObject("SELECT COUNT(*) FROM orders_copy", Integer.class));
        assertEquals("****", f.jdbc().queryForObject("SELECT name FROM orders_copy WHERE id=1", String.class));
        assertEquals("t1", f.jdbc().queryForObject("SELECT tag FROM orders_copy WHERE id=1", String.class));
        assertTrue(logs.stream().anyMatch(l -> l.startsWith("INFO:")));
        assertEquals(1, statsJson.size());
        var stats = MAPPER.readTree(statsJson.get(0));
        java.util.function.Function<String, com.fasterxml.jackson.databind.JsonNode> stat = key -> {
            for (var n : stats) if (n.path("nodeKey").asText().equals(key)) return n;
            throw new AssertionError("缺少节点统计 " + key);
        };
        assertEquals(3, stat.apply("s").path("rowsIn").asLong());
        assertEquals(3, stat.apply("s").path("rowsOut").asLong());
        assertEquals(3, stat.apply("f").path("rowsIn").asLong());
        assertEquals(2, stat.apply("f").path("rowsOut").asLong());
        assertEquals(2, stat.apply("t").path("rowsIn").asLong());
        assertEquals(2, stat.apply("t").path("rowsOut").asLong());
        assertTrue(outcome.previews().isEmpty());
    }

    @Test void upsertUpdatesExistingKeys() throws Exception {
        var f = fixture();
        f.jdbc().update("INSERT INTO orders_copy VALUES (1,'old',1,'x')");
        String upsert = CHAIN.replace("\"writeMode\":\"APPEND\"", "\"writeMode\":\"UPSERT\",\"keyColumns\":[\"id\"]");
        new FlowTaskExecutor().execute(graph(upsert), id -> f.ds(), LocalDate.of(2026, 11, 20), new RunCancelContext(), ExecutionSinks.NOOP, 0);
        assertEquals(2, f.jdbc().queryForObject("SELECT COUNT(*) FROM orders_copy", Integer.class));
        assertEquals("****", f.jdbc().queryForObject("SELECT name FROM orders_copy WHERE id=1", String.class));
    }

    @Test void previewDoesNotWriteAndCapsRows() throws Exception {
        var f = fixture();
        var outcome = new FlowTaskExecutor().execute(graph(CHAIN), id -> f.ds(), LocalDate.of(2026, 11, 20), new RunCancelContext(), ExecutionSinks.NOOP, 1);
        assertEquals(0, f.jdbc().queryForObject("SELECT COUNT(*) FROM orders_copy", Integer.class));
        var preview = outcome.previews().get("t");
        assertNotNull(preview);
        assertEquals(1, preview.rows().size());
        assertEquals(List.of("id", "name", "amount", "tag"), preview.columns());
        // 预览容量满后不得多拉一行：末级拦截器与 sink 的统计口径对账
        var byKey = outcome.stats().stream().collect(java.util.stream.Collectors.toMap(FlowTaskExecutor.NodeStat::nodeKey, s -> s));
        assertEquals(1, byKey.get("c").rowsOut());
        assertEquals(1, byKey.get("t").rowsIn());
    }

    @Test void cancelMidPipelineRollsBackTarget() throws Exception {
        var f = fixture();
        var cancel = new RunCancelContext();
        var executor = new FlowTaskExecutor();
        // 包装 DataSource 代理：getConnection 时置取消标志，来源迭代器逐行检查即中断
        var proxy = (DataSource) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{DataSource.class}, (p, m, a) -> {
            if (m.getName().equals("getConnection")) { cancel.cancel(); }
            return m.invoke(f.ds(), a);
        });
        assertThrows(Exception.class, () -> executor.execute(graph(CHAIN), id -> proxy, LocalDate.of(2026, 11, 20), cancel, ExecutionSinks.NOOP, 0));
        assertEquals(0, f.jdbc().queryForObject("SELECT COUNT(*) FROM orders_copy", Integer.class));
    }

    @Test void cancelAfterPartialWriteRollsBack() throws Exception {
        var f = fixture();
        var cancel = new RunCancelContext();
        var batches = new java.util.concurrent.atomic.AtomicInteger();
        var rolledBack = new java.util.concurrent.atomic.AtomicBoolean();
        var committed = new java.util.concurrent.atomic.AtomicBoolean();
        // sink 端 DataSource 代理：首批 executeBatch 真实写入后置取消（batchSize=1 逐批），
        // 下一轮逐行检查中断 → writeSink 必须 rollback 已写批次
        java.util.function.Function<java.sql.PreparedStatement, java.sql.PreparedStatement> wrapStatement = real ->
            (java.sql.PreparedStatement) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{java.sql.PreparedStatement.class}, (p, m, a) -> {
                try {
                    if (m.getName().equals("executeBatch")) {
                        if (cancel.isCancelled()) throw new InterruptedException("手动停止");
                        var result = m.invoke(real, a);
                        if (batches.incrementAndGet() == 1) cancel.cancel();
                        return result;
                    }
                    return m.invoke(real, a);
                } catch (java.lang.reflect.InvocationTargetException e) { throw e.getTargetException(); }
            });
        var proxyDs = (DataSource) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{DataSource.class}, (p, m, a) -> {
            try {
                if (!m.getName().equals("getConnection")) return m.invoke(f.ds(), a);
                var real = f.ds().getConnection();
                return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{java.sql.Connection.class}, (p2, m2, a2) -> {
                    try {
                        if (m2.getName().equals("prepareStatement")) return wrapStatement.apply((java.sql.PreparedStatement) m2.invoke(real, a2));
                        if (m2.getName().equals("rollback")) rolledBack.set(true);
                        if (m2.getName().equals("commit")) committed.set(true);
                        return m2.invoke(real, a2);
                    } catch (java.lang.reflect.InvocationTargetException e) { throw e.getTargetException(); }
                });
            } catch (java.lang.reflect.InvocationTargetException e) { throw e.getTargetException(); }
        });
        String config = CHAIN.replace("\"sourceId\":1,\"table\":\"orders_copy\"", "\"sourceId\":2,\"table\":\"orders_copy\"")
                .replace("\"writeMode\":\"APPEND\"", "\"writeMode\":\"APPEND\",\"batchSize\":1");
        assertThrows(Exception.class, () -> new FlowTaskExecutor().execute(graph(config), id -> id == 2 ? proxyDs : f.ds(), LocalDate.of(2026, 11, 20), cancel, ExecutionSinks.NOOP, 0));
        assertEquals(1, batches.get(), "首批应已真实写入");
        assertTrue(rolledBack.get(), "必须显式 rollback");
        assertFalse(committed.get(), "不得 commit");
        assertEquals(0, f.jdbc().queryForObject("SELECT COUNT(*) FROM orders_copy", Integer.class));
    }

    @Test void missingUpstreamFieldNamesNode() throws Exception {
        var f = fixture();
        String bad = CHAIN.replace("\"field\":\"name\"", "\"field\":\"nope\"");
        var logs = new ArrayList<String>();
        var ex = assertThrows(IllegalArgumentException.class, () -> new FlowTaskExecutor().execute(graph(bad), id -> f.ds(), LocalDate.of(2026, 11, 20), new RunCancelContext(),
                new ExecutionSinks((l, m) -> logs.add(l + ":" + m), s -> { }), 0));
        assertTrue(ex.getMessage().contains("节点 m"));
        // 失败诊断日志：ERROR 记当前处理节点与原因后重抛
        assertTrue(logs.stream().anyMatch(l -> l.startsWith("ERROR:") && l.contains("处理失败") && l.contains("上游缺少字段")), String.join("\n", logs));
    }

    @Test void upsertCancelCancelsBothInsertAndUpdate() throws Exception {
        var f = fixture();
        f.jdbc().update("INSERT INTO orders_copy VALUES (1,'old',1,'x')");
        var cancel = new RunCancelContext();
        var fired = new java.util.concurrent.atomic.AtomicBoolean();
        var cancelledSql = new java.util.concurrent.ConcurrentHashMap<String, Boolean>();
        // 代理记录每条语句的 cancel 调用：首次 executeUpdate 真实执行后置取消，
        // 多语句注册下 insert 与 update 均应被 cancel
        java.util.function.BiFunction<String, java.sql.PreparedStatement, java.sql.PreparedStatement> wrap = (sql, real) ->
            (java.sql.PreparedStatement) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{java.sql.PreparedStatement.class}, (p, m, a) -> {
                try {
                    if (m.getName().equals("cancel")) { cancelledSql.put(sql, true); return null; }
                    var result = m.invoke(real, a);
                    if (m.getName().equals("executeUpdate") && fired.compareAndSet(false, true)) cancel.cancel();
                    return result;
                } catch (java.lang.reflect.InvocationTargetException e) { throw e.getTargetException(); }
            });
        var proxyDs = (DataSource) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{DataSource.class}, (p, m, a) -> {
            try {
                if (!m.getName().equals("getConnection")) return m.invoke(f.ds(), a);
                var real = f.ds().getConnection();
                return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{java.sql.Connection.class}, (p2, m2, a2) -> {
                    try {
                        if (m2.getName().equals("prepareStatement")) return wrap.apply((String) a2[0], (java.sql.PreparedStatement) m2.invoke(real, a2));
                        return m2.invoke(real, a2);
                    } catch (java.lang.reflect.InvocationTargetException e) { throw e.getTargetException(); }
                });
            } catch (java.lang.reflect.InvocationTargetException e) { throw e.getTargetException(); }
        });
        String config = CHAIN.replace("\"sourceId\":1,\"table\":\"orders_copy\"", "\"sourceId\":2,\"table\":\"orders_copy\"")
                .replace("\"writeMode\":\"APPEND\"", "\"writeMode\":\"UPSERT\",\"keyColumns\":[\"id\"]");
        assertThrows(Exception.class, () -> new FlowTaskExecutor().execute(graph(config), id -> id == 2 ? proxyDs : f.ds(), LocalDate.of(2026, 11, 20), cancel, ExecutionSinks.NOOP, 0));
        assertTrue(cancelledSql.keySet().stream().anyMatch(s -> s.startsWith("INSERT")), "insert 应被 cancel: " + cancelledSql.keySet());
        assertTrue(cancelledSql.keySet().stream().anyMatch(s -> s.startsWith("UPDATE")), "update 应被 cancel: " + cancelledSql.keySet());
        assertEquals(1, f.jdbc().queryForObject("SELECT COUNT(*) FROM orders_copy", Integer.class), "回滚后仅剩种子行");
    }

    @Test void upsertMissingKeyFieldNamesUpstream() throws Exception {
        var f = fixture();
        String bad = "{\"nodes\":["
                + "{\"key\":\"s\",\"component\":\"SOURCE_TABLE\",\"config\":{\"sourceId\":1,\"table\":\"orders\"}},"
                + "{\"key\":\"m\",\"component\":\"FIELD_MAP\",\"config\":{\"mappings\":[{\"from\":\"name\",\"to\":\"name\"}]}},"
                + "{\"key\":\"t\",\"component\":\"SINK_TABLE\",\"config\":{\"sourceId\":1,\"table\":\"orders_copy\",\"writeMode\":\"UPSERT\",\"keyColumns\":[\"id\"]}}],"
                + "\"edges\":[{\"from\":\"s\",\"to\":\"m\"},{\"from\":\"m\",\"to\":\"t\"}]}";
        var ex = assertThrows(IllegalArgumentException.class, () -> new FlowTaskExecutor().execute(graph(bad), id -> f.ds(), LocalDate.of(2026, 11, 20), new RunCancelContext(), ExecutionSinks.NOOP, 0));
        assertTrue(ex.getMessage().contains("上游缺少字段 id"), ex.getMessage());
    }

    @Test void fieldMapRenamesColumns() throws Exception {
        var f = fixture();
        f.jdbc().update("CREATE TABLE orders_map(id INT PRIMARY KEY, label VARCHAR(50))");
        String mapGraph = "{\"nodes\":["
                + "{\"key\":\"s\",\"component\":\"SOURCE_TABLE\",\"config\":{\"sourceId\":1,\"table\":\"orders\"}},"
                + "{\"key\":\"m\",\"component\":\"FIELD_MAP\",\"config\":{\"mappings\":[{\"from\":\"id\",\"to\":\"id\"},{\"from\":\"name\",\"to\":\"label\"}]}},"
                + "{\"key\":\"t\",\"component\":\"SINK_TABLE\",\"config\":{\"sourceId\":1,\"table\":\"orders_map\",\"writeMode\":\"APPEND\"}}],"
                + "\"edges\":[{\"from\":\"s\",\"to\":\"m\"},{\"from\":\"m\",\"to\":\"t\"}]}";
        new FlowTaskExecutor().execute(graph(mapGraph), id -> f.ds(), LocalDate.of(2026, 11, 20), new RunCancelContext(), ExecutionSinks.NOOP, 0);
        assertEquals(3, f.jdbc().queryForObject("SELECT COUNT(*) FROM orders_map", Integer.class));
        assertEquals("a", f.jdbc().queryForObject("SELECT label FROM orders_map WHERE id=1", String.class));
        assertEquals("b", f.jdbc().queryForObject("SELECT label FROM orders_map WHERE id=2", String.class));
    }

    @Test void convertHandlesJavaTimeAndBinary() {
        assertEquals("2026-11-20", FlowTaskExecutor.convert(LocalDate.of(2026, 11, 20)));
        assertEquals("2026-11-20T09:30:15", FlowTaskExecutor.convert(java.time.LocalDateTime.of(2026, 11, 20, 9, 30, 15)));
        assertEquals("09:30:15", FlowTaskExecutor.convert(java.time.LocalTime.of(9, 30, 15)));
        assertEquals("2026-11-20T09:30:15+08:00", FlowTaskExecutor.convert(java.time.OffsetDateTime.of(2026, 11, 20, 9, 30, 15, 0, java.time.ZoneOffset.ofHours(8))));
        assertEquals("09:30:15+08:00", FlowTaskExecutor.convert(java.time.OffsetTime.of(9, 30, 15, 0, java.time.ZoneOffset.ofHours(8))));
        assertEquals("2026-11-20T01:30:00Z", FlowTaskExecutor.convert(java.time.Instant.parse("2026-11-20T01:30:00Z")));
        byte[] bytes = {1, 2, 3};
        assertSame(bytes, FlowTaskExecutor.convert(bytes));
        assertThrows(IllegalArgumentException.class, () -> FlowTaskExecutor.convert(new Object()));
    }

    @Test void qualifiedSinkTableResolvesSchemaOnH2() throws Exception {
        var f = fixture();
        f.jdbc().update("CREATE SCHEMA flow_target");
        f.jdbc().update("CREATE TABLE flow_target.orders_copy(id INT PRIMARY KEY, name VARCHAR(50), amount DECIMAL(10,2), tag VARCHAR(10))");
        // 小写限定名：H2 非 MySQL 走 schema 位，storesUpperCase 归一后必须命中列
        String qualified = CHAIN.replace("\"table\":\"orders_copy\"", "\"table\":\"flow_target.orders_copy\"");
        new FlowTaskExecutor().execute(graph(qualified), id -> f.ds(), LocalDate.of(2026, 11, 20), new RunCancelContext(), ExecutionSinks.NOOP, 0);
        assertEquals(2, f.jdbc().queryForObject("SELECT COUNT(*) FROM flow_target.orders_copy", Integer.class));
    }

    @Test void sinkRejectsIllegalUpstreamColumnNames() throws Exception {
        var f = fixture();
        f.jdbc().update("CREATE TABLE weird(\"bad name\" VARCHAR(10))");
        f.jdbc().update("INSERT INTO weird VALUES ('x')");
        f.jdbc().update("CREATE TABLE weird_copy(id INT)");
        String g = "{\"nodes\":["
                + "{\"key\":\"s\",\"component\":\"SOURCE_TABLE\",\"config\":{\"sourceId\":1,\"table\":\"weird\"}},"
                + "{\"key\":\"t\",\"component\":\"SINK_TABLE\",\"config\":{\"sourceId\":1,\"table\":\"weird_copy\",\"writeMode\":\"APPEND\"}}],"
                + "\"edges\":[{\"from\":\"s\",\"to\":\"t\"}]}";
        var ex = assertThrows(IllegalArgumentException.class, () -> new FlowTaskExecutor().execute(graph(g), id -> f.ds(), LocalDate.of(2026, 11, 20), new RunCancelContext(), ExecutionSinks.NOOP, 0));
        assertTrue(ex.getMessage().contains("列名非法 bad name"), ex.getMessage());
    }

    @Test void sinkLogsProgressEveryTenBatches() throws Exception {
        var f = fixture();
        f.jdbc().update("CREATE TABLE bulk(id INT)");
        var values = new StringBuilder();
        for (int i = 1; i <= 25; i++) values.append(i == 1 ? "" : ",").append("(").append(i).append(")");
        f.jdbc().update("INSERT INTO bulk VALUES " + values);
        f.jdbc().update("CREATE TABLE bulk_copy(id INT)");
        String g = "{\"nodes\":["
                + "{\"key\":\"s\",\"component\":\"SOURCE_TABLE\",\"config\":{\"sourceId\":1,\"table\":\"bulk\"}},"
                + "{\"key\":\"t\",\"component\":\"SINK_TABLE\",\"config\":{\"sourceId\":1,\"table\":\"bulk_copy\",\"writeMode\":\"APPEND\",\"batchSize\":1}}],"
                + "\"edges\":[{\"from\":\"s\",\"to\":\"t\"}]}";
        var logs = new ArrayList<String>();
        new FlowTaskExecutor().execute(graph(g), id -> f.ds(), LocalDate.of(2026, 11, 20), new RunCancelContext(), new ExecutionSinks((l, m) -> logs.add(m), s -> {}), 0);
        assertTrue(logs.stream().anyMatch(m -> m.equals("节点 t 已写入 10 行")), String.join("\n", logs));
        assertTrue(logs.stream().anyMatch(m -> m.equals("节点 t 已写入 20 行")), String.join("\n", logs));
        assertEquals(25, f.jdbc().queryForObject("SELECT COUNT(*) FROM bulk_copy", Integer.class));
    }

    @Test void sourceSqlBindsBusinessDate() throws Exception {
        var f = fixture();
        f.jdbc().update("CREATE TABLE orders_d(d DATE, id INT)");
        f.jdbc().update("INSERT INTO orders_d VALUES (DATE '2026-11-20',1),(DATE '2026-11-19',2)");
        f.jdbc().update("CREATE TABLE orders_d_copy(d DATE, id INT)");
        String sqlGraph = "{\"nodes\":["
                + "{\"key\":\"s\",\"component\":\"SOURCE_SQL\",\"config\":{\"sourceId\":1,\"sql\":\"SELECT d, id FROM orders_d WHERE d = ?\",\"parameters\":[{\"type\":\"BUSINESS_DATE\"}]}},"
                + "{\"key\":\"t\",\"component\":\"SINK_TABLE\",\"config\":{\"sourceId\":1,\"table\":\"orders_d_copy\",\"writeMode\":\"APPEND\"}}],"
                + "\"edges\":[{\"from\":\"s\",\"to\":\"t\"}]}";
        new FlowTaskExecutor().execute(graph(sqlGraph), id -> f.ds(), LocalDate.of(2026, 11, 20), new RunCancelContext(), ExecutionSinks.NOOP, 0);
        assertEquals(1, f.jdbc().queryForObject("SELECT COUNT(*) FROM orders_d_copy", Integer.class));
        assertEquals(1, f.jdbc().queryForObject("SELECT id FROM orders_d_copy", Integer.class));
    }
}
