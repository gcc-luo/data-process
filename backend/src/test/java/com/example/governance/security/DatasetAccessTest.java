package com.example.governance.security;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DatasetAccessTest {
    private final AccessPolicy policy = new AccessPolicy(Map.of("team", "company", "child", "team"), Map.of("analyst", Set.of("dataset.read")));
    private final DatasetAccess access = new DatasetAccess(policy);
    private final AccessPolicy.User user = new AccessPolicy.User("u", "team", Set.of("analyst"), true);
    private final DatasetAccess.Rule rule = new DatasetAccess.Rule(Set.of("analyst"), AccessPolicy.Scope.OWN_AND_DESCENDANTS,
            Map.of("name", DatasetAccess.Field.FULL, "phone", DatasetAccess.Field.MASKED));

    @Test void grantsOnlyConfiguredFieldsAndTrustedDepartmentScope() {
        var decision = access.decide(user, rule);
        assertEquals(Set.of("team", "child"), decision.departments());
        assertEquals(DatasetAccess.Field.FULL, decision.fields().get("name"));
        assertEquals(DatasetAccess.Field.MASKED, decision.fields().get("phone"));
        assertFalse(decision.fields().containsKey("salary"));
    }

    @Test void deniesUsersWithoutDatasetRoleOrFunctionPermission() {
        assertThrows(SecurityException.class, () -> access.decide(user,
            new DatasetAccess.Rule(Set.of("admin"), AccessPolicy.Scope.OWN, Map.of("name", DatasetAccess.Field.FULL))));
        assertThrows(SecurityException.class, () -> access.decide(new AccessPolicy.User("u", "team", Set.of("other"), true), rule));
        assertThrows(SecurityException.class, () -> access.decide(null, rule));
    }
}
