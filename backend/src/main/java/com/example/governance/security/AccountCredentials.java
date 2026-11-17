package com.example.governance.security;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Password changes must rotate the session version in the same database update. */
@Service
public class AccountCredentials {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    public AccountCredentials(JdbcTemplate jdbc, PasswordEncoder encoder) { this.jdbc = jdbc; this.encoder = encoder; }
    public void resetPassword(long accountId, String password) {
        if (password == null || password.isBlank() || password.length() < 12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) throw new IllegalArgumentException("Password is required");
        int updated = jdbc.update("UPDATE users SET password_hash=?, security_version=? WHERE id=? AND deleted=FALSE",
                encoder.encode(password), UUID.randomUUID().toString(), accountId);
        if (updated != 1) throw new IllegalArgumentException("Unknown account");
    }
    public void setEnabled(long accountId, boolean enabled) {
        if (accountId == 1 && !enabled) throw new IllegalStateException("默认管理员账号不能停用");
        int updated = jdbc.update("UPDATE users SET enabled=?, security_version=? WHERE id=? AND deleted=FALSE",
                enabled, UUID.randomUUID().toString(), accountId);
        if (updated != 1) throw new IllegalArgumentException("Unknown account");
        if (!enabled) jdbc.update("UPDATE service_callers SET enabled=FALSE WHERE user_id=?", accountId);
    }
}
