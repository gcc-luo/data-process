package com.example.governance.assets;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DataSourceDefinitionTest {
    @Test void derivesTypeFromProfileAndKeepsOptionsImmutable() {
        var definition = new DataSourceDefinition("sales", "MYSQL:8.x", "db.example", 3306, "sales", "reader");
        assertEquals("MYSQL", definition.type());
        assertEquals(Map.of(), definition.options());
        assertNull(definition.driverId());
        var options = new HashMap<String, String>();
        options.put("serverTimezone", "Asia/Shanghai");
        var withOptions = new DataSourceDefinition("sales", "ORACLE:12c+", "db.example", 1521, "ORCL", "reader", 7L, options);
        options.put("evil", "x");
        assertEquals(Map.of("serverTimezone", "Asia/Shanghai"), withOptions.options());
        assertThrows(UnsupportedOperationException.class, () -> withOptions.options().put("x", "y"));
        assertEquals("ORACLE", withOptions.type());
        assertEquals(7L, withOptions.driverId());
    }
    @Test void rejectsUrlInjectionAndInvalidPorts() {
        assertThrows(IllegalArgumentException.class, () -> new DataSourceDefinition("x", "MYSQL:8.x", "host?user=evil", 3306, "db", "reader"));
        assertThrows(IllegalArgumentException.class, () -> new DataSourceDefinition("x", "MYSQL:8.x", "host", 0, "db", "reader"));
        assertThrows(IllegalArgumentException.class, () -> new DataSourceDefinition("x", "MYSQL:8.x", "host", 3306, "db?password=secret", "reader"));
    }
    @Test void rejectsInvalidProfileAndDriver() {
        assertThrows(IllegalArgumentException.class, () -> new DataSourceDefinition("x", "mysql 8", "host", 3306, "db", "reader"));
        assertThrows(IllegalArgumentException.class, () -> new DataSourceDefinition("x", null, "host", 3306, "db", "reader"));
        assertThrows(IllegalArgumentException.class, () -> new DataSourceDefinition("x", "MYSQL:8.x", "host", 3306, "db", "reader", 0L, Map.of()));
    }
}
