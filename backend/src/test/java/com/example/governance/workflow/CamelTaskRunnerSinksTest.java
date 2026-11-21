package com.example.governance.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** SQL/SYNC 任务经真实 runner 执行时，日志与 stats 必须经 ExecutionSinks 落库（I2）。 */
class CamelTaskRunnerSinksTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static DriverManagerDataSource db() { return new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""); }

    @Test void sqlTaskLogsStartFinishAndPersistsStats() throws Exception {
        var db = db();
        try (var c = db.getConnection(); var s = c.createStatement()) { s.execute("CREATE TABLE report(business_day DATE)"); }
        var logs = new ArrayList<String>();
        var stats = new ArrayList<String>();
        try (var runner = new CamelTaskRunner(id -> db)) {
            runner.execute(new TaskVersionStore.Version(1, 1, "SQL", "{\"sourceId\":7,\"sql\":\"INSERT INTO report(business_day) VALUES (?)\",\"parameters\":[{\"type\":\"BUSINESS_DATE\"}]}"),
                    LocalDate.of(2026, 11, 16), new RunCancelContext(), new ExecutionSinks((l, m) -> logs.add(l + ":" + m), stats::add));
        }
        assertTrue(logs.stream().anyMatch(l -> l.startsWith("INFO:") && l.contains("SQL 任务开始")), String.join("\n", logs));
        assertTrue(logs.stream().anyMatch(l -> l.startsWith("INFO:") && l.contains("SQL 任务完成: 写入 1 行")), String.join("\n", logs));
        assertEquals(1, stats.size());
        var node = MAPPER.readTree(stats.get(0)).get(0);
        assertEquals("sql", node.path("nodeKey").asText());
        assertEquals(0, node.path("rowsIn").asLong());
        assertEquals(1, node.path("rowsOut").asLong());
        assertTrue(node.path("durationMs").isNumber());
    }

    @Test void syncTaskLogsStartFinishAndPersistsStats() throws Exception {
        var source = db();
        var target = db();
        try (var c = source.getConnection(); var s = c.createStatement()) { s.execute("CREATE TABLE src(id INT)"); s.execute("INSERT INTO src VALUES (1),(2)"); }
        try (var c = target.getConnection(); var s = c.createStatement()) { s.execute("CREATE TABLE dst(id INT)"); }
        var logs = new ArrayList<String>();
        var stats = new ArrayList<String>();
        try (var runner = new CamelTaskRunner(id -> id == 1 ? source : target)) {
            runner.execute(new TaskVersionStore.Version(1, 1, "SYNC", "{\"sourceId\":1,\"targetId\":2,\"sourceQuery\":\"SELECT id FROM src\",\"targetTable\":\"dst\",\"targetColumns\":[\"id\"]}"),
                    LocalDate.of(2026, 11, 16), new RunCancelContext(), new ExecutionSinks((l, m) -> logs.add(l + ":" + m), stats::add));
        }
        assertTrue(logs.stream().anyMatch(l -> l.startsWith("INFO:") && l.contains("同步任务开始")), String.join("\n", logs));
        assertTrue(logs.stream().anyMatch(l -> l.startsWith("INFO:") && l.contains("同步任务完成: 写入 2 行")), String.join("\n", logs));
        assertEquals(1, stats.size());
        var node = MAPPER.readTree(stats.get(0)).get(0);
        assertEquals("sync", node.path("nodeKey").asText());
        assertEquals(2, node.path("rowsIn").asLong());
        assertEquals(2, node.path("rowsOut").asLong());
        assertTrue(node.path("durationMs").isNumber());
    }
}
