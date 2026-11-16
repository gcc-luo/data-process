package com.example.governance.workflow;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskDefinitionValidatorTest {
    @Test void validatesSqlAndRejectsMissingOrUnknownParameters() {
        var validator = new TaskDefinitionValidator();
        assertDoesNotThrow(() -> validator.validate("SQL", "{\"sourceId\":1,\"sql\":\"UPDATE x SET day=?\",\"parameters\":[{\"type\":\"BUSINESS_DATE\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("SQL", "{\"sourceId\":1,\"sql\":\"select 1\"}"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("SQL", "{\"sourceId\":1,\"sql\":\"select 1\",\"parameters\":[{\"type\":\"UNKNOWN\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("SQL", "{\"sourceId\":1,\"sql\":\"select 1\",\"parameters\":[],\"retry\":{\"safe\":false,\"maxRetries\":1,\"delaySeconds\":30}}"));
    }
    @Test void rejectsUnsafeSyncTargets() {
        var validator = new TaskDefinitionValidator();
        assertThrows(IllegalArgumentException.class, () -> validator.validate("SYNC", "{\"sourceId\":1,\"targetId\":2,\"sourceQuery\":\"select * from x\",\"targetTable\":\"x;delete\",\"targetColumns\":[\"id\"]}"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("SYNC", "{\"sourceId\":1,\"targetId\":2,\"sourceQuery\":\"select * from x\",\"sourceParameters\":[{\"type\":\"UNKNOWN\"}],\"targetTable\":\"x\",\"targetColumns\":[\"id\"]}"));
    }
}
