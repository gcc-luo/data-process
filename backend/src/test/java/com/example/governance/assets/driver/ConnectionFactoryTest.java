package com.example.governance.assets.driver;

import com.example.governance.assets.CredentialCipher;
import com.example.governance.assets.DataSourceDefinition;
import com.example.governance.assets.DataSourceStore;
import com.example.governance.assets.DatabaseInspector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.h2.tools.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionFactoryTest {
    static Server tcp;
    static Path h2Jar;
    @BeforeAll static void start() throws Exception {
        tcp = Server.createTcpServer("-tcpPort", "0", "-ifNotExists").start();
        h2Jar = Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
    @AfterAll static void stop() { tcp.stop(); }

    private record Fixture(DriverStore drivers, ConnectionFactory factory, JdbcTemplate jdbc) {}
    private Fixture fixture() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db);
        var loader = new IsolatedDriverLoader();
        var drivers = new DriverStore(jdbc, loader, Path.of(System.getProperty("java.io.tmpdir"), "gov-drivers-" + UUID.randomUUID()));
        var registry = new DatabaseProfileRegistry(List.of(new H2TestProfile()));
        return new Fixture(drivers, new ConnectionFactory(registry, drivers), jdbc);
    }

    /** Test-only profile: H2 over TCP; the mem: prefix lives in the URL template so the database field stays a plain identifier. */
    static final class H2TestProfile implements DatabaseProfile {
        public String id() { return "H2:test"; }
        public String type() { return "H2"; }
        public String displayName() { return "H2 (test)"; }
        public int defaultPort() { return tcp.getPort(); }
        public String driverClassName() { return "org.h2.Driver"; }
        public String defaultDriverKey() { return "builtin-h2-test"; }
        public String jdbcUrl(DataSourceDefinition d) { return "jdbc:h2:tcp://" + d.host() + ":" + d.port() + "/mem:" + d.database() + ";DB_CLOSE_DELAY=-1"; }
        public Map<String, String> defaultProperties() { return Map.of(); }
        public Map<String, ParamSpec> extraParams() { return Map.of("ACCESS_MODE_DATA", ParamSpec.enumOf("ACCESS_MODE_DATA", "访问模式", List.of("r", "rw"), "r/rw")); }
        public List<ExtraField> extraFields() { return List.of(); }
    }

    private DriverStore.DriverRecord uploadH2(Fixture f) throws Exception {
        return f.drivers.upload("H2 驱动", "H2", "org.h2.Driver", null, "h2.jar", Files.readAllBytes(h2Jar), null, Set.of("H2"));
    }

    @Test void uploadedDriverConnectsThroughIsolatedLoaderAndInspector() throws Exception {
        var f = fixture();
        var record = uploadH2(f);
        var definition = new DataSourceDefinition("h2", "H2:test", "localhost", tcp.getPort(), "chaindb", "sa", record.id(), Map.of());
        try (var connection = f.factory.open(definition, "testpwd"); var st = connection.createStatement()) {
            st.execute("CREATE TABLE T (ID INT PRIMARY KEY)");
        }
        var inspector = new DatabaseInspector(f.factory);
        assertTrue(inspector.test(definition, "testpwd"));
        assertTrue(inspector.tables(definition, "testpwd", null, "PUBLIC").stream().anyMatch(t -> t.name().equals("T")));
        assertTrue(inspector.columns(definition, "testpwd", null, "PUBLIC", "T").stream().anyMatch(c -> c.name().equals("ID")));
    }
    @Test void whitelistedOptionReachesDriverAndUnknownProfileIsRejected() throws Exception {
        var f = fixture();
        var record = uploadH2(f);
        // ACCESS_MODE_DATA=r only takes effect at database creation: opening a fresh DB read-only must fail, proving the option reached the driver.
        var readOnly = new DataSourceDefinition("ro", "H2:test", "localhost", tcp.getPort(), "rodb", "sa", record.id(), Map.of("ACCESS_MODE_DATA", "r"));
        assertThrows(java.sql.SQLException.class, () -> f.factory.open(readOnly, "pwd"));
        var plain = new DataSourceDefinition("ro", "H2:test", "localhost", tcp.getPort(), "rodb", "sa", record.id(), Map.of());
        try (var connection = f.factory.open(plain, "pwd")) { assertTrue(connection.isValid(2)); }
        var unknown = new DataSourceDefinition("x", "MYSQL:8.x", "localhost", 3306, "db", "u", record.id(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> f.factory.open(unknown, "p"));
        var badParam = new DataSourceDefinition("x", "H2:test", "localhost", tcp.getPort(), "db2", "sa", record.id(), Map.of("allowLoadLocalInfile", "true"));
        assertThrows(IllegalArgumentException.class, () -> f.factory.open(badParam, "p"));
    }
    @Test void driverTypeMustMatchProfileType() throws Exception {
        var f = fixture();
        var record = uploadH2(f);
        var registry = new DatabaseProfileRegistry(List.of(new H2TestProfile(), new MySql8Profile()));
        var factory = new ConnectionFactory(registry, f.drivers);
        var mismatch = new DataSourceDefinition("x", "MYSQL:8.x", "localhost", 3306, "db", "u", record.id(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> factory.open(mismatch, "p"));
    }
    @Test void storeRoundTripsProfileDriverAndOptions() throws Exception {
        var f = fixture();
        var record = uploadH2(f);
        var store = new DataSourceStore(f.jdbc, new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32])));
        var definition = new DataSourceDefinition("rt", "H2:test", "localhost", tcp.getPort(), "rt", "sa", record.id(), Map.of("ACCESS_MODE_DATA", "rw"));
        long id = store.create(definition, "secret");
        assertEquals(definition, store.find(id).orElseThrow());
        assertEquals("H2", f.jdbc.queryForObject("SELECT source_type FROM data_sources WHERE id=?", String.class, id));
        assertEquals(definition, store.list().get(0).definition());
        var updated = new DataSourceDefinition("rt2", "H2:test", "localhost", tcp.getPort(), "rt", "sa", null, Map.of());
        String cipherBefore = f.jdbc.queryForObject("SELECT password_cipher FROM data_sources WHERE id=?", String.class, id);
        assertTrue(store.update(id, updated, null));
        assertEquals(updated, store.find(id).orElseThrow());
        assertEquals(cipherBefore, f.jdbc.queryForObject("SELECT password_cipher FROM data_sources WHERE id=?", String.class, id));
    }
}
