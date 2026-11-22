package com.example.governance.workflow;

import com.example.governance.assets.CredentialCipher;
import com.example.governance.assets.DataSourceDefinition;
import com.example.governance.assets.DataSourceStore;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

class TaskDefinitionValidatorTest {
    private static TaskDefinitionValidator validator() throws Exception {
        var jdbc = new JdbcTemplate(OrchFixtures.h2());
        var sources = new DataSourceStore(jdbc, new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32])));
        sources.create(new DataSourceDefinition("src", "MYSQL:8.x", "localhost", 3306, "db", "u"), "secret"); // id=1
        sources.create(new DataSourceDefinition("dst", "MYSQL:8.x", "localhost", 3306, "db", "u"), "secret"); // id=2
        return new TaskDefinitionValidator(sources);
    }

    @Test void acceptsValidDmlAndSyncDefinitions() throws Exception {
        var validator = validator();
        assertDoesNotThrow(() -> validator.validate("ORCH", OrchFixtures.dml(1, "UPDATE x SET day=:business_date")));
        assertDoesNotThrow(() -> validator.validate("ORCH", OrchFixtures.sync(1, 2, "SELECT id FROM x WHERE day=:business_date", "x_copy", List.of("id"), "UPSERT", List.of("id"))));
    }

    @Test void rejectsLegacyTypesAndMalformedJson() throws Exception {
        var validator = validator();
        String def = OrchFixtures.dml(1, "UPDATE x SET day=:business_date");
        assertThrows(IllegalArgumentException.class, () -> validator.validate("SQL", def));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("SYNC", def));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("FLOW", def));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("ORCH", "not json"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("ORCH", null));
    }

    @Test void rejectsUndeclaredSqlPlaceholdersAndUnsafeTargetTables() throws Exception {
        var validator = validator();
        var undeclared = assertThrows(IllegalArgumentException.class, () -> validator.validate("ORCH", OrchFixtures.dml(1, "UPDATE x SET day=:unknown_param")));
        assertTrue(undeclared.getMessage().contains("unknown_param"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("ORCH", OrchFixtures.sync(1, 2, "SELECT id FROM x", "x;delete", List.of("id"), "APPEND", List.of())));
        // 引用的数据源不存在 → 拒绝
        assertThrows(IllegalArgumentException.class, () -> validator.validate("ORCH", OrchFixtures.dml(99, "UPDATE x SET day=:business_date")));
    }

    @Test void rejectsCyclicDefinitions() throws Exception {
        var validator = validator();
        String cyclic = "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" +
                "{\"id\":\"a\",\"component\":\"transform.filter\",\"name\":\"甲\",\"enabled\":true,\"config\":{\"expression\":\"id > 0\"}}," +
                "{\"id\":\"b\",\"component\":\"transform.filter\",\"name\":\"乙\",\"enabled\":true,\"config\":{\"expression\":\"id > 1\"}}]," +
                "\"edges\":[{\"id\":\"e1\",\"from\":\"a\",\"fromPort\":\"out\",\"to\":\"b\",\"toPort\":\"in\",\"kind\":\"DATA\"}," +
                "{\"id\":\"e2\",\"from\":\"b\",\"fromPort\":\"out\",\"to\":\"a\",\"toPort\":\"in\",\"kind\":\"DATA\"}]}";
        var ex = assertThrows(IllegalArgumentException.class, () -> validator.validate("ORCH", cyclic));
        assertTrue(ex.getMessage().contains("环"), ex.getMessage());
    }

    @Test void rejectsDisabledComponents() throws Exception {
        var validator = validator();
        String python = "{\"schemaVersion\":1,\"variables\":[],\"nodes\":[" +
                "{\"id\":\"p\",\"component\":\"transform.python\",\"name\":\"脚本\",\"enabled\":true,\"config\":{}}],\"edges\":[]}";
        var ex = assertThrows(IllegalArgumentException.class, () -> validator.validate("ORCH", python));
        assertTrue(ex.getMessage().contains("即将支持"), ex.getMessage());
    }
}
