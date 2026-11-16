package com.example.governance.storage;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class OrganizationSchemaTest {
    @Test void usersAndRolesRequireValidOrganizationReferences() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        try (var conn = DriverManager.getConnection(url, "sa", ""); var stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO departments (id, name) VALUES (1, 'Sales')");
            stmt.executeUpdate("INSERT INTO users (id, username, password_hash, department_id, enabled, security_version) VALUES (1, 'alice', 'hash', 1, TRUE,'ab14c35b-8678-4724-9b3c-a0f4a2738b96')");
            assertThrows(SQLException.class, () -> stmt.executeUpdate("INSERT INTO users (id, username, password_hash, department_id, enabled, security_version) VALUES (2, 'bad', 'hash', 99, TRUE,'39aa0cd1-3d2b-4814-8d5f-e3d9b1fb7b6e')"));
            assertThrows(SQLException.class, () -> stmt.executeUpdate("INSERT INTO users (id, username, password_hash, department_id, enabled, security_version) VALUES (3, 'alice', 'hash', 1, TRUE,'ab14c35b-8678-4724-9b3c-a0f4a2738b96')"));
        }
    }
}
