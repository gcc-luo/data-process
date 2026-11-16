package com.example.governance.storage;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SecurityVersionMigrationTest {
    @Test void upgradesExistingUsersWithDistinctVersions() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("2").load().migrate();
        try (var conn = DriverManager.getConnection(url, "sa", ""); var stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO departments (id, name) VALUES (1, 'Sales')");
            stmt.executeUpdate("INSERT INTO users (id, username, password_hash, department_id, enabled) VALUES (1, 'a', 'hash', 1, TRUE), (2, 'b', 'hash', 1, TRUE)");
        }
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        try (var conn = DriverManager.getConnection(url, "sa", ""); var stmt = conn.createStatement();
             var rows = stmt.executeQuery("SELECT security_version FROM users ORDER BY id")) {
            assertTrue(rows.next()); String first = rows.getString(1); assertNotNull(first);
            assertTrue(rows.next()); String second = rows.getString(1); assertNotNull(second);
            assertNotEquals(first, second);
        }
    }
}
