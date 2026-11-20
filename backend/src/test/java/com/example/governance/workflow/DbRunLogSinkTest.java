package com.example.governance.workflow;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DbRunLogSinkTest {
    private JdbcTemplate fixture() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        return new JdbcTemplate(db);
    }

    @Test void linesAreNumberedAndTruncatedAt2000() {
        var jdbc = fixture();
        var sink = new DbRunLogSink(jdbc, "TASK_RUN", 7, null, 1);
        for (int i = 1; i <= 2005; i++) sink.log("INFO", "line " + i);
        assertEquals(2001, jdbc.queryForObject("SELECT COUNT(*) FROM execution_logs WHERE owner_type='TASK_RUN' AND owner_id=7", Integer.class));
        assertEquals("line 2000", jdbc.queryForObject("SELECT message FROM execution_logs WHERE line_no=2000", String.class));
        assertEquals("WARN", jdbc.queryForObject("SELECT level FROM execution_logs WHERE line_no=2001", String.class));
        assertEquals("日志超过 2000 行，已截断", jdbc.queryForObject("SELECT message FROM execution_logs WHERE line_no=2001", String.class));
        assertNull(jdbc.queryForObject("SELECT node_key FROM execution_logs WHERE line_no=1", String.class));
    }

    @Test void longMessagesAreClippedAndNodeKeyStored() {
        var jdbc = fixture();
        var sink = new DbRunLogSink(jdbc, "WORKFLOW_NODE", 9, "n1", 2);
        sink.log("ERROR", "x".repeat(3000));
        assertEquals(2000, jdbc.queryForObject("SELECT LENGTH(message) FROM execution_logs WHERE owner_id=9", Integer.class));
        assertEquals("n1", jdbc.queryForObject("SELECT node_key FROM execution_logs WHERE owner_id=9", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT attempt_no FROM execution_logs WHERE owner_id=9", Integer.class));
    }
}
