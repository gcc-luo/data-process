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
        var ex = assertThrows(IllegalArgumentException.class, () -> new FlowTaskExecutor().execute(graph(bad), id -> f.ds(), LocalDate.of(2026, 11, 20), new RunCancelContext(), ExecutionSinks.NOOP, 0));
        assertTrue(ex.getMessage().contains("节点 m"));
    }
}
