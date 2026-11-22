package com.example.governance.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CamelTaskRunnerTest {
    @Test void executesPinnedSqlWithBusinessDate() throws Exception {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (var c = db.getConnection(); var s = c.createStatement()) { s.execute("CREATE TABLE report(business_day DATE)"); }
        try (var runner = new CamelTaskRunner(id -> { assertEquals(7L, id); return db; })) {
            runner.execute(new TaskVersionStore.Version(1, 1, "ORCH", OrchFixtures.dml(7, "INSERT INTO report(business_day) VALUES (:business_date)")), LocalDate.of(2026, 11, 16));
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
            runner.execute(new TaskVersionStore.Version(1, 1, "ORCH",
                    OrchFixtures.sync(1, 2, "SELECT id FROM src WHERE business_day=:business_date", "dst", List.of("id"), "APPEND", List.of())), LocalDate.of(2026, 11, 16));
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
            runner.execute(new TaskVersionStore.Version(1, 1, "ORCH",
                    OrchFixtures.sync(1, 2, "SELECT id,name FROM src", "dst", List.of("id", "name"), "UPSERT", List.of("id"))), LocalDate.of(2026, 11, 16));
        }
        assertEquals("new", new org.springframework.jdbc.core.JdbcTemplate(target).queryForObject("SELECT name FROM dst WHERE id=1", String.class));
    }
}
