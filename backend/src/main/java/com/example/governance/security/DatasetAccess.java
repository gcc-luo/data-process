package com.example.governance.security;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/** Produces a restrictive query policy; callers must enforce it in the query layer. */
public final class DatasetAccess {
    public enum Field { FULL, MASKED }
    public record Rule(Set<String> allowedRoles, AccessPolicy.Scope scope, Map<String, Field> fields) {
        public Rule {
            allowedRoles = Set.copyOf(allowedRoles);
            fields = Map.copyOf(fields);
            if (scope == null) throw new IllegalArgumentException("Scope is required");
        }
    }
    public record Decision(Set<String> departments, Map<String, Field> fields) {
        public Decision { departments = Set.copyOf(departments); fields = Map.copyOf(fields); }
    }
    private final AccessPolicy policy;
    public DatasetAccess(AccessPolicy policy) { this.policy = policy; }

    public Decision decide(AccessPolicy.User user, Rule rule) {
        if (user == null || rule == null || !policy.allowed(user, "datasource.query.read") ||
                Collections.disjoint(user.roles(), rule.allowedRoles())) throw new SecurityException("Dataset access denied");
        Set<String> departments = policy.departments(user, rule.scope());
        if (departments.isEmpty()) throw new SecurityException("Department scope unavailable");
        return new Decision(departments, rule.fields());
    }
}
