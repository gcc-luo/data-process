package com.example.governance.security;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure authorization decisions over trusted organization and role data. */
public final class AccessPolicy {
    public enum Scope { OWN, OWN_AND_DESCENDANTS }
    public record User(String id, String department, Set<String> roles, boolean enabled) {
        public User {
            Objects.requireNonNull(id);
            Objects.requireNonNull(department);
            roles = Set.copyOf(roles);
        }
    }

    private final Map<String, String> parents;
    private final Set<String> departments;
    private final Map<String, Set<String>> permissions;

    public AccessPolicy(Map<String, String> parents, Map<String, Set<String>> permissions) {
        this(deriveDepartments(parents), parents, permissions);
    }

    public AccessPolicy(Set<String> departments, Map<String, String> parents, Map<String, Set<String>> permissions) {
        this.parents = Map.copyOf(parents);
        this.departments = Set.copyOf(departments);
        if (!this.departments.containsAll(parents.keySet()) || !this.departments.containsAll(parents.values())) {
            throw new IllegalArgumentException("Department hierarchy references unknown department");
        }
        var nodes = this.departments;
        var copied = new HashMap<String, Set<String>>();
        permissions.forEach((role, values) -> copied.put(role, Set.copyOf(values)));
        this.permissions = Map.copyOf(copied);
        for (String node : nodes) {
            var seen = new HashSet<String>();
            String current = node;
            while (current != null) {
                if (!seen.add(current)) throw new IllegalArgumentException("Department cycle");
                current = this.parents.get(current);
            }
        }
    }

    private static Set<String> deriveDepartments(Map<String, String> parents) {
        var nodes = new HashSet<>(parents.keySet());
        nodes.addAll(parents.values());
        return nodes;
    }

    public boolean allowed(User user, String permission) {
        if (user == null || !user.enabled() || permission == null || permission.isBlank()) return false;
        return user.roles().stream().anyMatch(role -> permissions.getOrDefault(role, Set.of()).contains(permission));
    }

    public Set<String> departments(User user, Scope scope) {
        if (user == null || !user.enabled() || scope == null || !departments.contains(user.department())) return Set.of();
        var result = new HashSet<String>();
        result.add(user.department());
        if (scope == Scope.OWN_AND_DESCENDANTS) {
            for (String node : departments) {
                String current = node;
                while (current != null) {
                    if (current.equals(user.department())) { result.add(node); break; }
                    current = parents.get(current);
                }
            }
        }
        return Set.copyOf(result);
    }
}
