package com.example.governance.assets.driver;

import com.example.governance.assets.DataSourceDefinition;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseProfileTest {
    private final DatabaseProfileRegistry registry = new DatabaseProfileRegistry(List.of(
        new MySql5Profile(), new MySql8Profile(), new PostgreSqlProfile(), new Oracle11Profile(), new Oracle12Profile()));
    private DataSourceDefinition def(String profileId, String db) { return new DataSourceDefinition("n", profileId, "db.example", 1521, db, "reader"); }

    @Test void buildsVersionSpecificUrls() {
        assertEquals("jdbc:mysql://db.example:1521/sales", registry.resolve("MYSQL:5.x").jdbcUrl(def("MYSQL:5.x", "sales")));
        assertEquals("jdbc:mysql://db.example:1521/sales", registry.resolve("MYSQL:8.x").jdbcUrl(def("MYSQL:8.x", "sales")));
        assertEquals("jdbc:postgresql://db.example:1521/sales", registry.resolve("POSTGRESQL:general").jdbcUrl(def("POSTGRESQL:general", "sales")));
        assertEquals("jdbc:oracle:thin:@db.example:1521:ORCL", registry.resolve("ORACLE:11g").jdbcUrl(def("ORACLE:11g", "ORCL")));
        assertEquals("jdbc:oracle:thin:@//db.example:1521/ORCL", registry.resolve("ORACLE:12c+").jdbcUrl(def("ORACLE:12c+", "ORCL")));
    }
    @Test void oracleConnectTypeSwitchesUrlForm() {
        var sid = new DataSourceDefinition("n", "ORACLE:12c+", "db.example", 1521, "ORCL", "reader", null, Map.of("connectType", "SID"));
        assertEquals("jdbc:oracle:thin:@db.example:1521:ORCL", registry.resolve("ORACLE:12c+").jdbcUrl(sid));
        var service = new DataSourceDefinition("n", "ORACLE:11g", "db.example", 1521, "ORCL", "reader", null, Map.of("connectType", "SERVICE"));
        assertEquals("jdbc:oracle:thin:@//db.example:1521/ORCL", registry.resolve("ORACLE:11g").jdbcUrl(service));
    }
    @Test void defaultsPreserveExistingTimeouts() {
        assertEquals("5000", registry.resolve("MYSQL:8.x").defaultProperties().get("connectTimeout"));
        assertEquals("10000", registry.resolve("MYSQL:5.x").defaultProperties().get("socketTimeout"));
        assertEquals("5", registry.resolve("POSTGRESQL:general").defaultProperties().get("connectTimeout"));
        assertEquals("10", registry.resolve("POSTGRESQL:general").defaultProperties().get("socketTimeout"));
        assertEquals("5000", registry.resolve("ORACLE:11g").defaultProperties().get("oracle.net.CONNECT_TIMEOUT"));
        assertEquals("10000", registry.resolve("ORACLE:12c+").defaultProperties().get("oracle.jdbc.ReadTimeout"));
    }
    @Test void whitelistAcceptsKnownParamsAndRejectsDangerousOnes() {
        var mysql8 = registry.resolve("MYSQL:8.x");
        assertDoesNotThrow(() -> ProfileParams.validate(mysql8, Map.of("serverTimezone", "Asia/Shanghai", "allowPublicKeyRetrieval", "true")));
        assertDoesNotThrow(() -> ProfileParams.validate(mysql8, Map.of("serverTimezone", "+08:00")));
        assertThrows(IllegalArgumentException.class, () -> ProfileParams.validate(mysql8, Map.of("allowLoadLocalInfile", "true")));
        assertThrows(IllegalArgumentException.class, () -> ProfileParams.validate(mysql8, Map.of("autoDeserialize", "true")));
        assertThrows(IllegalArgumentException.class, () -> ProfileParams.validate(mysql8, Map.of("queryInterceptors", "x")));
        assertThrows(IllegalArgumentException.class, () -> ProfileParams.validate(mysql8, Map.of("user", "evil")));
        assertThrows(IllegalArgumentException.class, () -> ProfileParams.validate(mysql8, Map.of("password", "evil")));
        assertThrows(IllegalArgumentException.class, () -> ProfileParams.validate(mysql8, Map.of("serverTimezone", "Not/AZone")));
        assertThrows(IllegalArgumentException.class, () -> ProfileParams.validate(mysql8, Map.of("connectTimeout", "-1")));
        assertThrows(IllegalArgumentException.class, () -> ProfileParams.validate(mysql8, Map.of("useSSL", "yes")));
        assertThrows(IllegalArgumentException.class, () -> ProfileParams.validate(mysql8, Map.of("characterEncoding", "UTF-16")));
        var mysql5 = registry.resolve("MYSQL:5.x");
        assertThrows(IllegalArgumentException.class, () -> ProfileParams.validate(mysql5, Map.of("allowPublicKeyRetrieval", "true")));
        assertDoesNotThrow(() -> ProfileParams.validate(mysql5, Map.of("zeroDateTimeBehavior", "convertToNull")));
        assertThrows(IllegalArgumentException.class, () -> ProfileParams.validate(mysql8, Map.of("zeroDateTimeBehavior", "convertToNull")));
        assertDoesNotThrow(() -> ProfileParams.validate(mysql8, Map.of("zeroDateTimeBehavior", "CONVERT_TO_NULL")));
        var pg = registry.resolve("POSTGRESQL:general");
        assertThrows(IllegalArgumentException.class, () -> ProfileParams.validate(pg, Map.of("socketFactory", "x")));
        assertThrows(IllegalArgumentException.class, () -> ProfileParams.validate(pg, Map.of("sslmode", "bogus")));
        assertDoesNotThrow(() -> ProfileParams.validate(pg, Map.of("sslmode", "require", "socketTimeout", "10")));
        assertThrows(IllegalArgumentException.class, () -> ProfileParams.validate(pg, Map.of("socketTimeout", "10000")));
        var oracle = registry.resolve("ORACLE:11g");
        assertDoesNotThrow(() -> ProfileParams.validate(oracle, Map.of("oracle.jdbc.defaultRowPrefetch", "100")));
        assertThrows(IllegalArgumentException.class, () -> ProfileParams.validate(oracle, Map.of("oracle.jdbc.defaultRowPrefetch", "0")));
    }
    @Test void registryRejectsUnknownProfileAndListsTypes() {
        assertThrows(IllegalArgumentException.class, () -> registry.resolve("DB2:1"));
        assertEquals(java.util.Set.of("MYSQL", "POSTGRESQL", "ORACLE"), registry.types());
        assertEquals(5, registry.all().size());
        assertEquals(5, registry.views().size());
        var mysql8View = registry.views().stream().filter(v -> v.id().equals("MYSQL:8.x")).findFirst().orElseThrow();
        assertTrue(mysql8View.extraParams().stream().anyMatch(p -> p.key().equals("allowPublicKeyRetrieval")));
        var oracle11View = registry.views().stream().filter(v -> v.id().equals("ORACLE:11g")).findFirst().orElseThrow();
        assertEquals("SID", oracle11View.extraFields().get(0).defaultValue());
    }
    @Test void oracleProfileRejectsInvalidConnectType() {
        var bad = new DataSourceDefinition("n", "ORACLE:11g", "h", 1521, "ORCL", "u", null, Map.of("connectType", "BOGUS"));
        assertThrows(IllegalArgumentException.class, () -> registry.resolve("ORACLE:11g").validate(bad));
    }
    @Test void duplicateProfileIdsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DatabaseProfileRegistry(List.of(new MySql8Profile(), new MySql8Profile())));
    }
}
