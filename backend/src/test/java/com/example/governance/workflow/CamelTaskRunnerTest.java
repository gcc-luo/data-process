package com.example.governance.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.LocalDate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CamelTaskRunnerTest {
    @Test void executesPinnedSqlWithBusinessDate() throws Exception {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (var c = db.getConnection(); var s = c.createStatement()) { s.execute("CREATE TABLE report(business_day DATE)"); }
        try (var runner = new CamelTaskRunner(id -> { assertEquals(7L, id); return db; })) {
            runner.execute(new TaskVersionStore.Version(1, 1, "SQL", "{\"sourceId\":7,\"sql\":\"INSERT INTO report(business_day) VALUES (?)\",\"parameters\":[{\"type\":\"BUSINESS_DATE\"}]}"), LocalDate.of(2026, 11, 16));
        }
        try (var c = db.getConnection(); var s = c.createStatement(); var rows = s.executeQuery("SELECT business_day FROM report")) {
            assertTrue(rows.next()); assertEquals(LocalDate.of(2026, 11, 16), rows.getDate(1).toLocalDate());
        }
    }
    @Test void synchronizationFiltersByBusinessDate() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var target = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (var c = source.getConnection(); var s = c.createStatement()) { s.execute("CREATE TABLE src(id INT, business_day DATE)"); s.execute("INSERT INTO src VALUES (1, DATE '2026-11-16'), (2, DATE '2026-11-15')"); }
        try (var c = target.getConnection(); var s = c.createStatement()) { s.execute("CREATE TABLE dst(id INT)"); }
        try (var runner = new CamelTaskRunner(id -> id == 1 ? source : target)) {
            runner.execute(new TaskVersionStore.Version(1, 1, "SYNC", "{\"sourceId\":1,\"targetId\":2,\"sourceQuery\":\"SELECT id FROM src WHERE business_day=?\",\"sourceParameters\":[{\"type\":\"BUSINESS_DATE\"}],\"targetTable\":\"dst\",\"targetColumns\":[\"id\"]}"), LocalDate.of(2026, 11, 16));
        }
        try (var c = target.getConnection(); var s = c.createStatement(); var rows = s.executeQuery("SELECT id FROM dst")) {
            assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); assertFalse(rows.next());
        }
    }
    @Test void synchronizationCanUpsertUsingTargetPrimaryKey() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var target = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (var c = source.getConnection(); var st = c.createStatement()) { st.execute("CREATE TABLE src(id INT, name VARCHAR(40))"); st.execute("INSERT INTO src VALUES (1,'new')"); }
        try (var c = target.getConnection(); var st = c.createStatement()) { st.execute("CREATE TABLE dst(id INT PRIMARY KEY, name VARCHAR(40))"); st.execute("INSERT INTO dst VALUES (1,'old')"); }
        try (var runner = new CamelTaskRunner(id -> id == 1 ? source : target)) {
            runner.execute(new TaskVersionStore.Version(1, 1, "SYNC", "{\"sourceId\":1,\"targetId\":2,\"sourceQuery\":\"SELECT id,name FROM src\",\"targetTable\":\"dst\",\"targetColumns\":[\"id\",\"name\"],\"writeMode\":\"UPSERT\",\"keyColumns\":[\"id\"]}"), LocalDate.of(2026, 11, 16));
        }
        assertEquals("new", new org.springframework.jdbc.core.JdbcTemplate(target).queryForObject("SELECT name FROM dst WHERE id=1", String.class));
    }
}
