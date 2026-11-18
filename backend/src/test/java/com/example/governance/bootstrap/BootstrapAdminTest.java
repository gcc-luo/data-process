package com.example.governance.bootstrap;

import com.example.governance.GovernanceApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=GovernanceApplication.class)
class BootstrapAdminTest {
    @Autowired BootstrapAdmin bootstrap;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM audit_events"); jdbc.update("DELETE FROM user_roles"); jdbc.update("DELETE FROM role_permissions"); jdbc.update("DELETE FROM role_menus");
        jdbc.update("DELETE FROM users"); jdbc.update("DELETE FROM roles"); jdbc.update("DELETE FROM departments");
    }
    @Test void initializesOnceWithHashedPasswordAndExplicitPermission() {
        bootstrap.create("admin", "L0ng-Unique-Pass!");
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE username='admin'", String.class);
        assertTrue(encoder.matches("L0ng-Unique-Pass!", hash));
        assertNotEquals("L0ng-Unique-Pass!", hash);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM role_permissions WHERE permission='workflow.workflow.validate'", Integer.class));
        assertEquals(com.example.governance.security.PermissionCatalog.codes().size(), jdbc.queryForObject("SELECT COUNT(*) FROM role_permissions WHERE role_id=1", Integer.class));
        assertThrows(IllegalStateException.class, () -> bootstrap.create("other", "An0ther-Long-Pass!"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
    }
    @Test void rejectsWeakOrBlankInputWithoutWriting() {
        assertThrows(IllegalArgumentException.class, () -> bootstrap.create(" ", "L0ng-Unique-Pass!"));
        assertThrows(IllegalArgumentException.class, () -> bootstrap.create("admin", " "));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
    }
    @Test void rejectsWeakAdministratorPassword() {
        assertThrows(IllegalArgumentException.class, () -> bootstrap.create("admin", "admin"));
        bootstrap.create("admin", "L0ng-Unique-Pass!");
        assertTrue(encoder.matches("L0ng-Unique-Pass!", jdbc.queryForObject("SELECT password_hash FROM users WHERE username='admin'", String.class)));
    }
    @Test void onlyOneConcurrentInitializationCanSucceed() throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        var start=new CountDownLatch(1);
        Callable<Boolean> job=() -> { start.await(); try { bootstrap.create("admin", "L0ng-Unique-Pass!"); return true; } catch (IllegalStateException e) { return false; } };
        try {
            var a=pool.submit(job); var b=pool.submit(job); start.countDown();
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
        } finally { pool.shutdownNow(); }
    }
}
