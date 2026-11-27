package com.example.governance.organization;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class OrganizationP1Test {
    private record Fixture(JdbcTemplate jdbc, OrganizationService org) {}

    private Fixture fixture() {
        var db = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(db).load().migrate();
        var jdbc = new JdbcTemplate(db);
        jdbc.update("INSERT INTO departments(id,name) VALUES (1,'总部')");
        return new Fixture(jdbc, new OrganizationService(jdbc, new DataSourceTransactionManager(db), new BCryptPasswordEncoder()));
    }

    @Test void disablingDepartmentWithStaffOrActiveChildrenIsBlocked() {
        var f = fixture();
        long other = f.org.createDepartment("其他部门", null);
        f.jdbc.update("INSERT INTO users(id,username,password_hash,department_id,enabled,deleted,security_version) VALUES (1,'admin','hash',?,TRUE,FALSE,'v')", other);
        long child = f.org.createDepartment("研发部", 1L);
        f.org.updateDepartment(child, "研发部", 1L, 0, null, null, null, false);
        f.org.updateDepartment(1L, "总部", null, 0, null, null, null, false);
        f.org.updateDepartment(1L, "总部", null, 0, null, null, null, true);
        f.org.updateDepartment(child, "研发部", 1L, 0, null, null, null, true);
        long user = f.org.createUser("alice", "Str0ng-Pass!1", child);
        assertThrows(IllegalStateException.class, () -> f.org.updateDepartment(1L, "总部", null, 0, null, null, null, false));
        assertThrows(IllegalStateException.class, () -> f.org.updateDepartment(child, "研发部", 1L, 0, null, null, null, false));
        f.org.deleteUser(user, "admin");
        f.org.updateDepartment(child, "研发部", 1L, 0, null, null, null, false);
        f.org.updateDepartment(1L, "总部", null, 0, null, null, null, false);
    }

    @Test void menuButtonTypeIsRetired() {
        var f = fixture();
        assertThrows(IllegalArgumentException.class, () -> f.org.createMenu("新增按钮", 9L, "F", 0, "", "", "database", true, true));
    }

    @Test void csvImportCreatesValidRowsAndReportsInvalidOnes() {
        var f = fixture();
        var rows = CsvSupport.parse("账号,姓名,密码,部门ID,手机,邮箱\nbob,鲍勃,Str0ng-Pass!1,1,13800000000,bob@example.com\nweak,弱密码,admin,1,,\nbad,坏部门,Str0ng-Pass!1,999,,\n");
        rows.remove(0);
        Map<String, Object> result = f.org.importUsers(rows);
        assertEquals(1, result.get("created"));
        var errors = (List<?>) result.get("errors");
        assertEquals(2, errors.size());
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username='bob' AND must_change_password=TRUE", Integer.class));
    }

    @Test void csvRoundTripPreservesQuotedFields() {
        var rows = List.<String[]>of(new String[]{"a,b", "say \"hi\"", "plain"});
        var parsed = CsvSupport.parse(CsvSupport.write(rows));
        assertArrayEquals(rows.get(0), parsed.get(0));
    }

    @Test void roleUsersListsAssignedAccounts() {
        var f = fixture();
        long role = f.org.createRole("分析员");
        long user = f.org.createUser("alice", "Str0ng-Pass!1", 1L);
        f.org.setRoles(user, java.util.Set.of(role));
        var listed = f.org.roleUsers(role);
        assertEquals(1, listed.size());
        assertEquals("alice", listed.get(0).username());
        assertThrows(IllegalArgumentException.class, () -> f.org.roleUsers(999L));
    }

    @Test void userTimestampsAreExposed() {
        var f = fixture();
        long user = f.org.createUser("alice", "Str0ng-Pass!1", 1L);
        var snapshot = f.org.snapshot(com.example.governance.security.PermissionCatalog.codes());
        var found = snapshot.users().stream().filter(u -> u.id() == user).findFirst().orElseThrow();
        assertNotNull(found.createdAt());
        assertNull(found.lastLoginAt());
    }
}
