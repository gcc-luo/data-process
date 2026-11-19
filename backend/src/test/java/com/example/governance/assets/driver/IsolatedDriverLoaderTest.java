package com.example.governance.assets.driver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class IsolatedDriverLoaderTest {
    private static Path h2Jar() throws Exception {
        return Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
    private static Properties props() {
        var props = new Properties();
        props.setProperty("user", "sa");
        props.setProperty("password", "");
        return props;
    }

    @Test void loadsSameJarInTwoIsolatedLoaders() throws Exception {
        try (var loader = new IsolatedDriverLoader()) {
            try (Connection a = loader.connect(1, h2Jar(), "org.h2.Driver", "jdbc:h2:mem:iso1", props());
                 Connection b = loader.connect(2, h2Jar(), "org.h2.Driver", "jdbc:h2:mem:iso2", props())) {
                assertTrue(a.isValid(2));
                assertTrue(b.isValid(2));
                var driverA = a.unwrap(Connection.class).getClass();
                var driverB = b.unwrap(Connection.class).getClass();
                assertNotSame(driverA.getClassLoader(), driverB.getClassLoader());
                assertNotSame(driverA.getClassLoader(), IsolatedDriverLoaderTest.class.getClassLoader());
            }
        }
    }
    @Test void retireWaitsForOpenConnectionsThenClosesLoader() throws Exception {
        var loader = new IsolatedDriverLoader();
        Connection connection = loader.connect(7, h2Jar(), "org.h2.Driver", "jdbc:h2:mem:retire", props());
        loader.retire(7);
        assertFalse(loader.isCached(7));
        assertTrue(connection.isValid(2));
        connection.close();
        try (Connection fresh = loader.connect(7, h2Jar(), "org.h2.Driver", "jdbc:h2:mem:retire2", props())) {
            assertTrue(fresh.isValid(2));
        }
        loader.close();
    }
    @Test void probeReturnsVersionAndRejectsMissingClass() throws Exception {
        try (var loader = new IsolatedDriverLoader()) {
            String version = loader.probe(h2Jar(), "org.h2.Driver");
            assertTrue(version.matches("\\d+\\.\\d+"), version);
            assertThrows(IllegalArgumentException.class, () -> loader.probe(h2Jar(), "com.example.Missing"));
        }
    }
    @Test void builtinMysql5And8DriversLoadSideBySide(@TempDir Path temp) throws Exception {
        Path mysql5 = temp.resolve("mysql5.jar");
        Path mysql8 = temp.resolve("mysql8.jar");
        try (var in = IsolatedDriverLoaderTest.class.getResourceAsStream("/drivers-builtin/builtin-mysql-5.jar")) { Files.copy(in, mysql5); }
        try (var in = IsolatedDriverLoaderTest.class.getResourceAsStream("/drivers-builtin/builtin-mysql-8.jar")) { Files.copy(in, mysql8); }
        try (var loader = new IsolatedDriverLoader()) {
            String v5 = loader.probe(mysql5, "com.mysql.jdbc.Driver");
            String v8 = loader.probe(mysql8, "com.mysql.cj.jdbc.Driver");
            assertTrue(v5.startsWith("5.1"), v5);
            assertTrue(v8.startsWith("9.") || v8.startsWith("8."), v8);
            assertNotEquals(v5, v8);
        }
    }
    @Test void failedConnectDoesNotLeakReference() throws Exception {
        try (var loader = new IsolatedDriverLoader()) {
            assertThrows(java.sql.SQLException.class, () -> loader.connect(3, h2Jar(), "org.h2.Driver", "jdbc:unknown:url", props()));
            loader.retire(3);
            assertFalse(loader.isCached(3));
        }
    }
}
