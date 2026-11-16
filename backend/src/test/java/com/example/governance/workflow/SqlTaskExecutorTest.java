package com.example.governance.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SqlTaskExecutorTest {
    @Test void executesParameterizedSqlAndPropagatesDatabaseFailures() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE orders (id BIGINT, business_day DATE)");
        }
        try (var executor = new SqlTaskExecutor()) {
            int count = executor.execute(source, "INSERT INTO orders(id, business_day) VALUES (?, ?)", List.of(42L, Date.valueOf(LocalDate.of(2026, 11, 16))));
            assertEquals(1, count);
            assertEquals(1, executor.execute(source, "INSERT INTO orders(id, business_day) VALUES (?, ?)", java.util.Arrays.asList(43L, null)));
            assertThrows(Exception.class, () -> executor.execute(source, "INSERT INTO missing_table(id) VALUES (?)", List.of(1)));
        }
        try (var connection = source.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT id FROM orders")) {
            assertTrue(rows.next()); assertEquals(42L, rows.getLong(1)); assertTrue(rows.next()); assertEquals(43L, rows.getLong(1)); assertFalse(rows.next());
        }
    }
}
