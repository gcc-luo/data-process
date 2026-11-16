package com.example.governance.assets;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DataSourceDefinitionTest {
    @Test void constructsDialectSpecificJdbcUrlsWithoutSecrets() {
        assertEquals("jdbc:mysql://db.example:3306/sales", new DataSourceDefinition("sales", DataSourceDefinition.Type.MYSQL, "db.example", 3306, "sales", "reader").jdbcUrl());
        assertEquals("jdbc:postgresql://localhost:5432/sales", new DataSourceDefinition("sales", DataSourceDefinition.Type.POSTGRESQL, "localhost", 5432, "sales", "reader").jdbcUrl());
        assertEquals("jdbc:oracle:thin:@//db.example:1521/ORCL", new DataSourceDefinition("oracle", DataSourceDefinition.Type.ORACLE, "db.example", 1521, "ORCL", "reader").jdbcUrl());
    }
    @Test void rejectsUrlInjectionAndInvalidPorts() {
        assertThrows(IllegalArgumentException.class, () -> new DataSourceDefinition("x", DataSourceDefinition.Type.MYSQL, "host?user=evil", 3306, "db", "reader"));
        assertThrows(IllegalArgumentException.class, () -> new DataSourceDefinition("x", DataSourceDefinition.Type.MYSQL, "host", 0, "db", "reader"));
        assertThrows(IllegalArgumentException.class, () -> new DataSourceDefinition("x", DataSourceDefinition.Type.MYSQL, "host", 3306, "db?password=secret", "reader"));
    }
}
