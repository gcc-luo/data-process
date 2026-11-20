package com.example.governance.organization;

import com.example.governance.security.PermissionCatalog;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Navigation exposes the Data Assets directory for datasource grants, plus System Management and its pages. */
class OrganizationNavigationTest {
    private record Fixture(JdbcTemplate jdbc, OrganizationService org) {}

    private Fixture fixture() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db);
        jdbc.update("INSERT INTO departments(id,name) VALUES (1,'默认部门')");
        return new Fixture(jdbc, new OrganizationService(jdbc, new DataSourceTransactionManager(db), new BCryptPasswordEncoder()));
    }

    private void grantUser(Fixture f, long userId, String username, long roleId, boolean roleEnabled, Set<String> permissions) {
        f.jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,deleted,security_version) VALUES (?,?,?,1,TRUE,FALSE,?)", userId, username, "hash", UUID.randomUUID().toString());
        f.jdbc.update("INSERT INTO roles(id,name,role_key,enabled) VALUES (?,?,?,?)", roleId, username + "-role", "role_" + roleId, roleEnabled);
        f.jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (?,?)", userId, roleId);
        for (String permission : permissions) f.jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (?,?)", roleId, permission);
    }

    private List<String> components(Fixture f, String username) {
        return f.org.navigation(username).stream().map(OrganizationService.Menu::component).toList();
    }

    @Test void fullGrantsSeeAssetDirectoryAndSystemManagement() {
        var f = fixture();
        grantUser(f, 10, "admin", 10, true, PermissionCatalog.codes());
        var rows = f.org.navigation("admin");
        var roots = rows.stream().filter(menu -> menu.parentId() == null).map(OrganizationService.Menu::component).toList();
        assertEquals(List.of("assets", "processing", "system"), roots);
        var components = components(f, "admin");
        assertEquals(12, components.size());
        assertTrue(components.containsAll(List.of("assets", "assets/sources", "assets/drivers", "processing", "processing/tasks", "system/departments", "system/roles", "system/users", "system/menus", "system/permissions", "system/audit")));
        assertFalse(components.contains("assets/datasets"));
    }

    @Test void singleSystemGrantOnlyOpensItsOwnPage() {
        var f = fixture();
        grantUser(f, 10, "dept", 10, true, Set.of("system.department.read"));
        var components = components(f, "dept");
        assertEquals(2, components.size());
        assertTrue(components.containsAll(List.of("system", "system/departments")));
    }

    @Test void platformGrantsOpenMatchingAssetPages() {
        var f = fixture();
        grantUser(f, 10, "operator", 10, true, Set.of("datasource.source.create", "workflow.task.execute"));
        var components = components(f, "operator");
        assertEquals(4, components.size());
        assertTrue(components.containsAll(List.of("assets", "assets/sources", "processing", "processing/tasks")));
    }

    @Test void taskGrantOpensProcessingPageOnly() {
        var f = fixture();
        grantUser(f, 10, "task", 10, true, Set.of("workflow.task.read"));
        var components = components(f, "task");
        assertEquals(2, components.size());
        assertTrue(components.containsAll(List.of("processing", "processing/tasks")));
    }

    @Test void datasetGrantDoesNotShowHiddenDatasetPage() {
        var f = fixture();
        grantUser(f, 10, "dataset", 10, true, Set.of("datasource.dataset.read"));
        assertEquals(List.of(), components(f, "dataset"));
    }

    @Test void noGrantsOrDisabledRoleMeansEmptyNavigation() {
        var f = fixture();
        grantUser(f, 10, "none", 10, true, Set.of());
        assertEquals(List.of(), components(f, "none"));
        grantUser(f, 11, "disabled", 11, false, PermissionCatalog.codes());
        assertEquals(List.of(), components(f, "disabled"));
    }

    @Test void menuManagementCannotRegisterPlatformPages() {
        var f = fixture();
        assertThrows(IllegalArgumentException.class, () -> f.org.createMenu("资产副本", null, "M", 0, "/assets-copy", "assets", "database", true, true));
    }
}
