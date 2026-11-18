package com.example.governance.bootstrap;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.example.governance.security.PermissionCatalog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit first-run operation, never exposed through an HTTP endpoint. */
@Service
public class BootstrapAdmin {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final TransactionTemplate transaction;

    public BootstrapAdmin(JdbcTemplate jdbc, PasswordEncoder encoder, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public void create(String username, String password) {
        if (username == null || username.isBlank() || username.length() > 160 || !username.equals(username.trim()) || password == null || password.isBlank() || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("Username and a non-empty password of at most 72 bytes are required");
        String hash = encoder.encode(password);
        transaction.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT setting_value FROM platform_settings WHERE setting_key=? FOR UPDATE", String.class, "internal.bootstrap.lock");
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
            if (count == null || count != 0) throw new IllegalStateException("Administrator initialization requires an empty user table");
            jdbc.update("INSERT INTO departments (id, name) VALUES (?, ?)", 1L, "默认部门");
            jdbc.update("INSERT INTO roles (id, name, role_key, sort_order, enabled) VALUES (?, ?, ?, ?, ?)", 1L, "平台管理员", "platform_admin", 0, true);
            jdbc.update("INSERT INTO users (id, username, nickname, password_hash, department_id, enabled, deleted, security_version) VALUES (?, ?, ?, ?, ?, ?, FALSE, ?)", 1L, username, username, hash, 1L, true, UUID.randomUUID().toString());
            jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?)", 1L, 1L);
            PermissionCatalog.codes().forEach(permission -> jdbc.update("INSERT IGNORE INTO role_permissions (role_id, permission) VALUES (?, ?)", 1L, permission));
        });
    }
}
