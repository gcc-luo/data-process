package com.example.governance.security;

import java.security.Principal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final AuditLog audit;
    private final OnlineSessions onlineSessions;
    public SessionController(JdbcTemplate jdbc, PasswordEncoder encoder, AuditLog audit, OnlineSessions onlineSessions) { this.jdbc = jdbc; this.encoder = encoder; this.audit = audit; this.onlineSessions = onlineSessions; }

    @GetMapping("/api/csrf") Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
    public record Session(String username, String nickname, boolean mustChangePassword, Set<String> permissions, String department, java.util.List<String> roles, String email, String phone, java.time.LocalDateTime createdAt, java.time.LocalDateTime prevLoginAt, String loginIp, Long loginAt, String userAgent) {}
    @GetMapping("/api/session") Session session(Authentication authentication, jakarta.servlet.http.HttpServletRequest request) {
        var principal = (AccountPrincipal) authentication.getPrincipal();
        var rows = jdbc.query("SELECT u.email,u.phone,u.created_at,u.prev_login_at,d.name FROM users u LEFT JOIN departments d ON d.id=u.department_id WHERE u.id=? AND u.deleted=FALSE",
                (rs, row) -> new Object[]{rs.getString(1), rs.getString(2), rs.getTimestamp(3), rs.getTimestamp(4), rs.getString(5)}, principal.id());
        Object[] profile = rows.isEmpty() ? new Object[]{null, null, null, null, null} : rows.get(0);
        var roles = jdbc.queryForList("SELECT r.name FROM roles r JOIN user_roles ur ON ur.role_id=r.id WHERE ur.user_id=? ORDER BY r.sort_order,r.id", String.class, principal.id());
        var online = onlineSessions.list().stream().filter(entry -> entry.sessionId().equals(request.getSession().getId())).findFirst().orElse(null);
        return new Session(principal.getUsername(), principal.nickname(), principal.mustChangePassword(),
                authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toUnmodifiableSet()),
                (String) profile[4], roles, (String) profile[0], (String) profile[1],
                profile[2] == null ? null : ((java.sql.Timestamp) profile[2]).toLocalDateTime(),
                profile[3] == null ? null : ((java.sql.Timestamp) profile[3]).toLocalDateTime(),
                online == null ? null : online.ip(), online == null ? null : online.loginAt(), online == null ? null : online.userAgent());
    }

    public record SelfPassword(String oldPassword, String newPassword) {}
    @PostMapping("/api/session/password") ResponseEntity<Void> changeOwnPassword(@RequestBody SelfPassword request, Authentication authentication) {
        var principal = (AccountPrincipal) authentication.getPrincipal();
        var rows = jdbc.query("SELECT username, password_hash FROM users WHERE id=? AND deleted=FALSE", (rs, row) -> new String[]{rs.getString(1), rs.getString(2)}, principal.id());
        if (rows.isEmpty()) throw new IllegalArgumentException("Unknown account");
        String username = rows.get(0)[0];
        if (request.oldPassword() == null || !encoder.matches(request.oldPassword(), rows.get(0)[1])) throw new IllegalArgumentException("原密码错误");
        PasswordPolicy.validate(request.newPassword(), username);
        String hash = encoder.encode(request.newPassword());
        String version = UUID.randomUUID().toString();
        jdbc.update("UPDATE users SET password_hash=?, security_version=?, must_change_password=FALSE WHERE id=?", hash, version, principal.id());
        audit.success(authentication, "user.password.self", "user", principal.id());
        var refreshed = new AccountPrincipal(principal.id(), version, username, hash, true, principal.nickname(), false, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(refreshed, null, refreshed.getAuthorities()));
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage() == null ? "Invalid password change" : error.getMessage()));
    }
}
