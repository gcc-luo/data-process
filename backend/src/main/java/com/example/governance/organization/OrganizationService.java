package com.example.governance.organization;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Organization mutations are serialized for the single-node deployment. */
@Service
public class OrganizationService {
    private static final Set<String> PERMISSIONS = Set.of("organization.manage", "datasource.manage", "dataset.read", "workflow.validate", "workflow.manage", "service.manage", "report.manage");
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;
    public OrganizationService(JdbcTemplate jdbc, PlatformTransactionManager manager, PasswordEncoder encoder) { this.jdbc = jdbc; this.encoder = encoder; this.tx = new TransactionTemplate(manager); }
    public long createDepartment(String name, Long parent) {
        checkName(name);
        return tx.execute(status -> {
            lock();
            if (parent != null && !exists("departments", parent)) throw new IllegalArgumentException("Unknown department");
            long id = next("departments");
            jdbc.update("INSERT INTO departments(id,name,parent_id) VALUES (?,?,?)", id, name, parent);
            return id;
        });
    }
    public void moveDepartment(long id, Long parent) {
        tx.executeWithoutResult(status -> {
            lock();
            if (!exists("departments", id) || (parent != null && !exists("departments", parent))) throw new IllegalArgumentException("Unknown department");
            Map<Long, Long> parents = new HashMap<>();
            jdbc.query("SELECT id,parent_id FROM departments", rs -> { long key = rs.getLong(1), value = rs.getLong(2); if (!rs.wasNull()) parents.put(key, value); });
            var seen = new HashSet<Long>(); Long current = parent;
            while (current != null) {
                if (current == id || !seen.add(current)) throw new IllegalArgumentException("Department hierarchy cycle");
                current = parents.get(current);
            }
            jdbc.update("UPDATE departments SET parent_id=? WHERE id=?", parent, id);
        });
    }
    public long createRole(String name) {
        checkName(name);
        return tx.execute(status -> { lock(); long id = next("roles"); jdbc.update("INSERT INTO roles(id,name) VALUES (?,?)", id, name); return id; });
    }
    public void setPermissions(long roleId, Set<String> permissions) {
        if (permissions == null || !PERMISSIONS.containsAll(permissions)) throw new IllegalArgumentException("Invalid permission");
        tx.executeWithoutResult(status -> {
            lock(); if (!exists("roles", roleId)) throw new IllegalArgumentException("Unknown role");
            jdbc.update("DELETE FROM role_permissions WHERE role_id=?", roleId);
            permissions.forEach(permission -> jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (?,?)", roleId, permission));
        });
    }
    public long createUser(String username, String password, long department) {
        checkName(username);
        if (!username.equals(username.trim()) || password == null || password.length() < 12 || password.isBlank() || password.getBytes(StandardCharsets.UTF_8).length > 72) throw new IllegalArgumentException("Invalid credentials");
        String hash = encoder.encode(password);
        return tx.execute(status -> {
            lock(); if (!exists("departments", department)) throw new IllegalArgumentException("Unknown department");
            long id = next("users");
            jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,security_version) VALUES (?,?,?,?,TRUE,?)", id, username, hash, department, UUID.randomUUID().toString());
            return id;
        });
    }
    public void setRoles(long userId, Set<Long> roles) {
        if (roles == null || roles.size() > 100 || roles.stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException("Invalid roles");
        tx.executeWithoutResult(status -> {
            lock(); if (!exists("users", userId) || roles.stream().anyMatch(id -> !exists("roles", id))) throw new IllegalArgumentException("Unknown user or role");
            jdbc.update("DELETE FROM user_roles WHERE user_id=?", userId);
            roles.forEach(role -> jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (?,?)", userId, role));
        });
    }
    public record Department(long id, String name, Long parentId) {}
    public record Role(long id, String name, Set<String> permissions) {}
    public record User(long id, String username, long departmentId, boolean enabled, Set<Long> roles) {}
    public record Snapshot(java.util.List<Department> departments, java.util.List<Role> roles, java.util.List<User> users) {}
    public Snapshot snapshot() {
        var departments = jdbc.query("SELECT id,name,parent_id FROM departments ORDER BY id", (rs, row) -> { long id = rs.getLong(1); String name = rs.getString(2); long parent = rs.getLong(3); return new Department(id, name, rs.wasNull() ? null : parent); });
        var baseRoles = jdbc.query("SELECT id,name FROM roles ORDER BY id", (rs, row) -> new Role(rs.getLong(1), rs.getString(2), Set.of()));
        var roles = baseRoles.stream().map(r -> new Role(r.id(), r.name(), Set.copyOf(jdbc.queryForList("SELECT permission FROM role_permissions WHERE role_id=?", String.class, r.id())))).toList();
        var baseUsers = jdbc.query("SELECT id,username,department_id,enabled FROM users ORDER BY id", (rs, row) -> new User(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getBoolean(4), Set.of()));
        var users = baseUsers.stream().map(u -> new User(u.id(), u.username(), u.departmentId(), u.enabled(), Set.copyOf(jdbc.queryForList("SELECT role_id FROM user_roles WHERE user_id=?", Long.class, u.id())))).toList();
        return new Snapshot(departments, roles, users);
    }
    private void checkName(String value) { if (value == null || value.isBlank() || value.length() > 160) throw new IllegalArgumentException("Invalid name"); }
    private void lock() { jdbc.queryForObject("SELECT setting_value FROM platform_settings WHERE setting_key='internal.organization.lock' FOR UPDATE", String.class); }
    private boolean exists(String table, long id) { return !jdbc.queryForList("SELECT id FROM " + table + " WHERE id=?", Long.class, id).isEmpty(); }
    private long next(String table) { return Math.addExact(jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM " + table, Long.class), 1); }
}
