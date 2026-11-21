package com.example.governance.workflow;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.junit.jupiter.api.Assertions.*;

class ResilientStatsTest {
    @Test void statsWriteFailureDegradesToWarnWithoutThrowing() {
        var logs = new ArrayList<String>();
        RunLogSink log = (level, message) -> logs.add(level + ":" + message);
        var consumer = TaskRunService.resilientStats(json -> { throw new DataAccessResourceFailureException("db down"); }, log);
        assertDoesNotThrow(() -> consumer.accept("[]"));
        assertTrue(logs.stream().anyMatch(l -> l.startsWith("WARN:") && l.contains("统计写入失败") && l.contains("db down")), String.join("\n", logs));
    }

    @Test void statsPassThroughOnSuccess() {
        var received = new ArrayList<String>();
        var logs = new ArrayList<String>();
        var consumer = TaskRunService.resilientStats(received::add, (level, message) -> logs.add(level));
        consumer.accept("[1]");
        assertEquals(List.of("[1]"), received);
        assertTrue(logs.isEmpty());
    }

    @Test void nonDataAccessExceptionStillPropagates() {
        var consumer = TaskRunService.resilientStats(json -> { throw new IllegalStateException("bug"); }, (level, message) -> { });
        assertThrows(IllegalStateException.class, () -> consumer.accept("[]"));
    }
}
