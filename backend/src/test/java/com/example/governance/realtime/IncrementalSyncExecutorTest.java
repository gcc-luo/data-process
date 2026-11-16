package com.example.governance.realtime;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class IncrementalSyncExecutorTest {
    @Test void advancesCompositeCursorAfterCommittedUpsertAndSupportsReplay() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var target = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (var c = source.getConnection(); var st = c.createStatement()) { st.execute("CREATE TABLE src(id BIGINT PRIMARY KEY, seq BIGINT NOT NULL, name VARCHAR(40))"); st.execute("INSERT INTO src VALUES (1,10,'a'),(2,10,'b'),(3,11,'c')"); }
        try (var c = target.getConnection(); var st = c.createStatement()) { st.execute("CREATE TABLE dst(id BIGINT PRIMARY KEY, name VARCHAR(40))"); st.execute("INSERT INTO dst VALUES (1,'old')"); }
        var config = new IncrementalSyncExecutor.Config("src", "dst", List.of("id","name"), List.of("id","name"), "seq", "id", List.of("id"), "LONG", 2);
        try (var executor = new IncrementalSyncExecutor()) {
            var first = executor.poll(source, target, config, null);
            assertEquals(2, first.count()); assertEquals(new IncrementalSyncExecutor.Cursor("10",2), first.cursor());
            var replay = executor.poll(source, target, config, null); assertEquals(first.cursor(), replay.cursor());
            var second = executor.poll(source, target, config, first.cursor());
            assertEquals(1, second.count()); assertEquals(new IncrementalSyncExecutor.Cursor("11",3), second.cursor());
        }
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(target);
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM dst", Integer.class));
        assertEquals("a", jdbc.queryForObject("SELECT name FROM dst WHERE id=1", String.class));
    }
    @Test void failedLaterWriteRollsBackEntireTargetBatch() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var target = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (var c = source.getConnection(); var st = c.createStatement()) { st.execute("CREATE TABLE src(id BIGINT PRIMARY KEY, seq BIGINT, name VARCHAR(40))"); st.execute("INSERT INTO src VALUES (1,10,'a'),(2,11,'b')"); }
        try (var c = target.getConnection(); var st = c.createStatement()) { st.execute("CREATE TABLE dst(id BIGINT PRIMARY KEY, name VARCHAR(40) UNIQUE)"); st.execute("INSERT INTO dst VALUES (99,'b')"); }
        var config = new IncrementalSyncExecutor.Config("src", "dst", List.of("id","name"), List.of("id","name"), "seq", "id", List.of("id"), "LONG", 10);
        try (var executor = new IncrementalSyncExecutor()) { assertThrows(Exception.class, () -> executor.poll(source, target, config, null)); }
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(target);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dst", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dst WHERE id=1", Integer.class));
    }
    @Test void timestampWatermarkUsesTieKeyForIdenticalTimes() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var target = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (var c = source.getConnection(); var st = c.createStatement()) { st.execute("CREATE TABLE src(id BIGINT PRIMARY KEY, changed TIMESTAMP, name VARCHAR(40))"); st.execute("INSERT INTO src VALUES (1,TIMESTAMP '2026-11-16 10:00:00','a'),(2,TIMESTAMP '2026-11-16 10:00:00','b')"); }
        try (var c = target.getConnection(); var st = c.createStatement()) { st.execute("CREATE TABLE dst(id BIGINT PRIMARY KEY, name VARCHAR(40))"); }
        var config = new IncrementalSyncExecutor.Config("src", "dst", List.of("id","name"), List.of("id","name"), "changed", "id", List.of("id"), "TIMESTAMP", 1);
        try (var executor = new IncrementalSyncExecutor()) {
            var first = executor.poll(source, target, config, null); var second = executor.poll(source, target, config, first.cursor());
            assertEquals(1, first.count()); assertEquals(1, second.count()); assertEquals(2, second.cursor().tie());
        }
    }
}
