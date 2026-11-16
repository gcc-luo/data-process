package com.example.governance.security;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccessPolicyTest {
    private final AccessPolicy policy = new AccessPolicy(
        Map.of("sales", "root", "east", "sales", "west", "sales", "finance", "root"),
        Map.of("analyst", Set.of("dataset.read"), "editor", Set.of("task.run")));

    @Test void requiresExplicitRoleGrantAndDeniesDisabledOrUnknownUsers() {
        var user = new AccessPolicy.User("u1", "sales", Set.of("analyst"), true);
        assertTrue(policy.allowed(user, "dataset.read"));
        assertFalse(policy.allowed(user, "task.run"));
        assertFalse(policy.allowed(new AccessPolicy.User("u2", "sales", Set.of("unknown"), true), "dataset.read"));
        assertFalse(policy.allowed(new AccessPolicy.User("u3", "sales", Set.of("analyst"), false), "dataset.read"));
        assertFalse(policy.allowed(null, "dataset.read"));
    }

    @Test void derivesDepartmentScopeFromTrustedOrganizationTree() {
        var user = new AccessPolicy.User("u1", "sales", Set.of("analyst"), true);
        assertEquals(Set.of("sales"), policy.departments(user, AccessPolicy.Scope.OWN));
        assertEquals(Set.of("sales", "east", "west"), policy.departments(user, AccessPolicy.Scope.OWN_AND_DESCENDANTS));
        assertTrue(policy.departments(new AccessPolicy.User("u", "unknown", Set.of(), true), AccessPolicy.Scope.OWN).isEmpty());
        assertTrue(policy.departments(new AccessPolicy.User("u", "sales", Set.of(), false), AccessPolicy.Scope.OWN).isEmpty());
    }

    @Test void grantsScopeForStandaloneDepartment() {
        var standalone = new AccessPolicy(Set.of("only"), Map.of(), Map.of("reader", Set.of("dataset.read")));
        var user = new AccessPolicy.User("u", "only", Set.of("reader"), true);
        assertEquals(Set.of("only"), standalone.departments(user, AccessPolicy.Scope.OWN));
        assertEquals(Set.of("only"), standalone.departments(user, AccessPolicy.Scope.OWN_AND_DESCENDANTS));
    }

    @Test void rejectsCyclicOrganizationTree() {
        assertThrows(IllegalArgumentException.class, () -> new AccessPolicy(Map.of("a", "b", "b", "a"), Map.of()));
    }
}
