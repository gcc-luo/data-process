package com.example.governance.assets.driver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class DriverStoreTest {
    private record Fixture(DriverStore drivers, JdbcTemplate jdbc, IsolatedDriverLoader loader) {}
    private Fixture fixture(Path dir) {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db);
        var loader = new IsolatedDriverLoader();
        return new Fixture(new DriverStore(jdbc, loader, dir), jdbc, loader);
    }
    private static byte[] h2Bytes() throws Exception {
        return Files.readAllBytes(Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
    }

    @Test void registerBuiltinsIsIdempotent(@TempDir Path dir) {
        var f = fixture(dir);
        f.drivers.registerBuiltins();
        f.drivers.registerBuiltins();
        assertEquals(5, f.jdbc.queryForObject("SELECT COUNT(*) FROM jdbc_drivers", Integer.class));
        assertEquals(5, f.jdbc.queryForObject("SELECT COUNT(*) FROM jdbc_drivers WHERE builtin=TRUE", Integer.class));
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM jdbc_drivers WHERE version_label IS NULL", Integer.class));
        assertTrue(Files.exists(dir.resolve("builtin/builtin-mysql-5.jar")));
    }
    @Test void uploadDeduplicatesByShaAndValidatesInput(@TempDir Path dir) throws Exception {
        var f = fixture(dir);
        byte[] bytes = h2Bytes();
        var record = f.drivers.upload("H2", "MYSQL", "org.h2.Driver", null, "h2.jar", bytes, 42L, Set.of("MYSQL"));
        assertFalse(record.builtin());
        assertEquals(42L, record.uploadedBy());
        assertNotNull(record.versionLabel());
        assertThrows(IllegalArgumentException.class, () -> f.drivers.upload("H2 again", "MYSQL", "org.h2.Driver", null, "h2.jar", bytes, null, Set.of("MYSQL")));
        assertThrows(IllegalArgumentException.class, () -> f.drivers.upload("bad", "DB2", "org.h2.Driver", null, "x.jar", bytes, null, Set.of("MYSQL")));
        assertThrows(IllegalArgumentException.class, () -> f.drivers.upload("bad", "MYSQL", "com.example.Missing", null, "x.jar", bytes, null, Set.of("MYSQL")));
        assertThrows(IllegalArgumentException.class, () -> f.drivers.upload("bad", "MYSQL", "org.h2.Driver", null, "x.jar", "not a jar".getBytes(), null, Set.of("MYSQL")));
        assertThrows(IllegalArgumentException.class, () -> f.drivers.upload("bad", "MYSQL", "org.h2.Driver", null, "x.jar", new byte[0], null, Set.of("MYSQL")));
        assertThrows(IllegalArgumentException.class, () -> f.drivers.upload(" ", "MYSQL", "org.h2.Driver", null, "x.jar", bytes, null, Set.of("MYSQL")));
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM jdbc_drivers", Integer.class));
    }
    @Test void deleteRemovesRowFileAndLoaderEntry(@TempDir Path dir) throws Exception {
        var f = fixture(dir);
        var record = f.drivers.upload("H2", "MYSQL", "org.h2.Driver", null, "h2.jar", h2Bytes(), null, Set.of("MYSQL"));
        Path jar = Path.of(record.jarPath());
        assertTrue(Files.exists(jar));
        f.drivers.delete(record.id());
        assertTrue(f.drivers.find(record.id()).isEmpty());
        assertFalse(Files.exists(jar));
        assertFalse(f.loader.isCached(record.id()));
    }
    @Test void builtinCannotBeDeletedAndReferencedDriverListsNames(@TempDir Path dir) throws Exception {
        var f = fixture(dir);
        f.drivers.registerBuiltins();
        long builtinId = f.drivers.findByKey("builtin-mysql-8").orElseThrow().id();
        assertThrows(IllegalStateException.class, () -> f.drivers.delete(builtinId));
        var record = f.drivers.upload("H2", "MYSQL", "org.h2.Driver", null, "h2.jar", h2Bytes(), null, Set.of("MYSQL"));
        f.jdbc.update("INSERT INTO data_sources(id,name,source_type,version_profile,host,port,database_name,username,driver_id,password_cipher) VALUES (50,'引用库','MYSQL','MYSQL:8.x','localhost',3306,'db','u',?,'cipher')", record.id());
        var ex = assertThrows(DriverStore.DriverInUseException.class, () -> f.drivers.delete(record.id()));
        assertEquals(List.of("引用库"), ex.references());
        f.jdbc.update("DELETE FROM data_sources WHERE id=50");
        f.drivers.delete(record.id());
        assertTrue(f.drivers.find(record.id()).isEmpty());
    }
    @Test void resolveForProfileUsesBuiltinDefaultAndChecksType(@TempDir Path dir) throws Exception {
        var f = fixture(dir);
        f.drivers.registerBuiltins();
        var registry = new DatabaseProfileRegistry(List.of(new MySql8Profile(), new PostgreSqlProfile()));
        var resolved = f.drivers.resolveForProfile(registry.resolve("MYSQL:8.x"), null);
        assertEquals("builtin-mysql-8", resolved.driverKey());
        var pgDriver = f.drivers.findByKey("builtin-postgresql").orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> f.drivers.resolveForProfile(registry.resolve("MYSQL:8.x"), pgDriver.id()));
        assertThrows(IllegalArgumentException.class, () -> f.drivers.resolveForProfile(registry.resolve("MYSQL:8.x"), 99999L));
    }
}
