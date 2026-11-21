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
        assertTrue(statsJson.get(0).contains("\"nodeKey\":\"f\""));
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

    @Test void missingUpstreamFieldNamesNode() throws Exception {
        var f = fixture();
        String bad = CHAIN.replace("\"field\":\"name\"", "\"field\":\"nope\"");
        var ex = assertThrows(IllegalArgumentException.class, () -> new FlowTaskExecutor().execute(graph(bad), id -> f.ds(), LocalDate.of(2026, 11, 20), new RunCancelContext(), ExecutionSinks.NOOP, 0));
        assertTrue(ex.getMessage().contains("m"));
    }
}
