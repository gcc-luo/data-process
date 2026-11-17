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
        bootstrap.create("admin", "long-unique-password");
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE username='admin'", String.class);
        assertTrue(encoder.matches("long-unique-password", hash));
        assertNotEquals("long-unique-password", hash);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM role_permissions WHERE permission='workflow.validate'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM role_permissions WHERE permission='datasource.manage'", Integer.class));
        assertThrows(IllegalStateException.class, () -> bootstrap.create("other", "another-long-password"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
    }
    @Test void rejectsWeakOrBlankInputWithoutWriting() {
        assertThrows(IllegalArgumentException.class, () -> bootstrap.create(" ", "long-unique-password"));
        assertThrows(IllegalArgumentException.class, () -> bootstrap.create("admin", " "));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
    }
    @Test void acceptsShortAdministratorPassword() {
        bootstrap.create("admin", "admin");
        assertTrue(encoder.matches("admin", jdbc.queryForObject("SELECT password_hash FROM users WHERE username='admin'", String.class)));
    }
    @Test void onlyOneConcurrentInitializationCanSucceed() throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        var start=new CountDownLatch(1);
        Callable<Boolean> job=() -> { start.await(); try { bootstrap.create("admin", "long-unique-password"); return true; } catch (IllegalStateException e) { return false; } };
        try {
            var a=pool.submit(job); var b=pool.submit(job); start.countDown();
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
        } finally { pool.shutdownNow(); }
    }
}
