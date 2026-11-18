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
    public SessionController(JdbcTemplate jdbc, PasswordEncoder encoder, AuditLog audit) { this.jdbc = jdbc; this.encoder = encoder; this.audit = audit; }

    @GetMapping("/api/csrf") Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
    public record Session(String username, String nickname, boolean mustChangePassword, Set<String> permissions) {}
    @GetMapping("/api/session") Session session(Authentication authentication) {
        var principal = (AccountPrincipal) authentication.getPrincipal();
        return new Session(principal.getUsername(), principal.nickname(), principal.mustChangePassword(),
                authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toUnmodifiableSet()));
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
