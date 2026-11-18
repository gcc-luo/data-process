package com.example.governance.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/** Blocks locked accounts and enforces the progressive captcha before credential authentication runs. */
public class LoginGuardFilter extends OncePerRequestFilter {
    public static final int CAPTCHA_AFTER = 2;
    public static final int MAX_FAILURES = 5;
    public static final int LOCK_MINUTES = 15;
    private final JdbcTemplate jdbc;
    private final AuditLog audit;

    public LoginGuardFilter(JdbcTemplate jdbc, AuditLog audit) { this.jdbc = jdbc; this.audit = audit; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if ("POST".equals(request.getMethod()) && "/api/login".equals(request.getRequestURI())) {
            String username = request.getParameter("username");
            if (username != null && !username.isBlank()) {
                var rows = jdbc.query("SELECT failed_attempts, locked_until FROM users WHERE username=? AND deleted=FALSE",
                        (rs, row) -> new Object[]{rs.getInt(1), rs.getTimestamp(2)}, username.trim());
                if (!rows.isEmpty()) {
                    int attempts = (Integer) rows.get(0)[0];
                    var lockedUntil = (java.sql.Timestamp) rows.get(0)[1];
                    if (lockedUntil != null && lockedUntil.toInstant().isAfter(Instant.now())) {
                        audit.loginFailure(username, "账号锁定中拒绝登录");
                        deny(response, "账号已锁定，请 " + LOCK_MINUTES + " 分钟后再试");
                        return;
                    }
                    if (attempts >= CAPTCHA_AFTER) {
                        HttpSession session = request.getSession(false);
                        String expected = session == null ? null : (String) session.getAttribute(CaptchaController.SESSION_CODE);
                        Long expires = session == null ? null : (Long) session.getAttribute(CaptchaController.SESSION_EXPIRES);
                        String given = request.getParameter("captcha");
                        boolean valid = expected != null && expires != null && expires > System.currentTimeMillis() && given != null && given.trim().equalsIgnoreCase(expected);
                        if (session != null) { session.removeAttribute(CaptchaController.SESSION_CODE); session.removeAttribute(CaptchaController.SESSION_EXPIRES); }
                        if (!valid) {
                            audit.loginFailure(username, "验证码错误或已失效");
                            deny(response, "验证码错误或已失效");
                            return;
                        }
                    }
                }
            }
        }
        chain.doFilter(request, response);
    }

    private void deny(HttpServletResponse response, String error) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + error + "\",\"captchaRequired\":true}");
    }
}
