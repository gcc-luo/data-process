package com.example.governance.storage;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class MetadataMigrationTest {
    @Test void migrationIsRepeatableAndDataSurvivesReconnect(@TempDir Path tempDir) throws Exception {
        String url = "jdbc:h2:file:" + tempDir.resolve("metadata").toAbsolutePath();
        Flyway flyway = Flyway.configure().dataSource(url, "sa", "").load();
        assertTrue(flyway.migrate().migrationsExecuted >= 1);
        try (var conn = DriverManager.getConnection(url, "sa", ""); var stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO platform_settings (setting_key, setting_value) VALUES ('test', 'persisted')");
        }
        assertEquals(0, flyway.migrate().migrationsExecuted);
        try (var conn = DriverManager.getConnection(url, "sa", ""); var stmt = conn.createStatement();
             var rows = stmt.executeQuery("SELECT setting_value FROM platform_settings WHERE setting_key = 'test'")) {
            assertTrue(rows.next());
            assertEquals("persisted", rows.getString(1));
        }
    }
    @Test void missingMigrationLocationFails() {
        String url = "jdbc:h2:mem:missing_" + java.util.UUID.randomUUID();
        var flyway = Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:missing-migration-location").failOnMissingLocations(true).load();
        assertThrows(org.flywaydb.core.api.FlywayException.class, flyway::migrate);
    }
}
