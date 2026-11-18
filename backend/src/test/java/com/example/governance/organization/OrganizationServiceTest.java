package com.example.governance.organization;

import com.example.governance.security.PermissionCatalog;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class OrganizationServiceTest {
    @Test void createsOrganizationAndRejectsInvalidHierarchyAndPermission() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db);
        var org = new OrganizationService(jdbc, new DataSourceTransactionManager(db), new BCryptPasswordEncoder());
        long root = org.createDepartment("总部", null), child = org.createDepartment("销售部", root);
        assertNull(org.snapshot(PermissionCatalog.codes()).departments().getFirst().parentId());
        assertEquals(root, org.snapshot(PermissionCatalog.codes()).departments().get(1).parentId());
        assertThrows(IllegalArgumentException.class, () -> org.moveDepartment(root, child));
        jdbc.update("INSERT INTO roles(id,name,role_key) VALUES (1,'平台管理员','platform_admin')");
        jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,deleted,security_version) VALUES (1,'admin','hash',?,TRUE,FALSE,'v')", root);
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (1,1)");
        long role = org.createRole("分析员");
        org.setPermissions(role, Set.of("datasource.query.read"));
        assertThrows(IllegalArgumentException.class, () -> org.setPermissions(role, Set.of("unknown.permission")));
        long user = org.createUser("alice", "Str0ng-Pass!2026", child);
        org.setRoles(user, Set.of(role));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_roles WHERE user_id=? AND role_id=?", Integer.class, user, role));
        assertNotEquals("Str0ng-Pass!2026", jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, user));
        assertNotNull(jdbc.queryForObject("SELECT security_version FROM users WHERE id=?", String.class, user));
    }
    @Test void rejectsWeakPasswordAndFlagsInitialChange() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db);
        var org = new OrganizationService(jdbc, new DataSourceTransactionManager(db), new BCryptPasswordEncoder());
        long department = org.createDepartment("测试部门", null);
        assertThrows(IllegalArgumentException.class, () -> org.createUser("alice", "admin", department));
        assertThrows(IllegalArgumentException.class, () -> org.createUser("alice", "alice12345", department));
        long user = org.createUser("alice", "Str0ng-Pass!2026", department);
        assertTrue(new BCryptPasswordEncoder().matches("Str0ng-Pass!2026", jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, user)));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT must_change_password FROM users WHERE id=?", Boolean.class, user));
    }
}
