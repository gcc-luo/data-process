package com.example.governance.security;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;

@Configuration
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean SessionRegistry sessionRegistry() { return new SessionRegistryImpl(); }
    @Bean HttpSessionEventPublisher httpSessionEventPublisher() { return new HttpSessionEventPublisher(); }
    @Bean org.springframework.boot.web.servlet.ServletListenerRegistrationBean<OnlineSessions> onlineSessionListener(OnlineSessions sessions) { return new org.springframework.boot.web.servlet.ServletListenerRegistrationBean<>(sessions); }

    @Bean UserDetailsService userDetailsService(JdbcTemplate jdbc) {
        return username -> jdbc.query("SELECT id, security_version, username, password_hash, enabled, nickname, must_change_password FROM users WHERE username=? AND deleted=FALSE", (rs, row) -> {
            String name = rs.getString("username");
            var permissions = jdbc.queryForList("SELECT DISTINCT rp.permission FROM role_permissions rp JOIN user_roles ur ON ur.role_id=rp.role_id JOIN roles r ON r.id=ur.role_id AND r.enabled=TRUE JOIN users u ON u.id=ur.user_id WHERE u.username=? AND u.deleted=FALSE", String.class, name);
            return new AccountPrincipal(rs.getLong("id"), rs.getString("security_version"), name, rs.getString("password_hash"), rs.getBoolean("enabled"), rs.getString("nickname"), rs.getBoolean("must_change_password"), permissions.stream().map(SimpleGrantedAuthority::new).toList());
        }, username).stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }

    @Bean @Order(1) SecurityFilterChain externalServiceChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api/service-call/**")
                .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.GET, "/api/service-call/**").permitAll().anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable()).requestCache(cache -> cache.disable()).build();
    }
    @Bean @Order(2) SecurityFilterChain securityFilterChain(HttpSecurity http, UserDetailsService userDetailsService, AuditLog audit, PermissionAuthorizationManager permissions, JdbcTemplate jdbc, SessionRegistry sessionRegistry, OnlineSessions onlineSessions) throws Exception {
        return http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/health", "/api/csrf", "/api/login", "/api/captcha").permitAll()
                .requestMatchers("/api/session", "/api/session/**", "/api/navigation", "/api/logout").authenticated()
                .requestMatchers("/api/**").access(permissions)
                .anyRequest().denyAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .formLogin(form -> form.loginProcessingUrl("/api/login")
                    .successHandler((request, response, authentication) -> {
                        try {
                            var principal = (AccountPrincipal) authentication.getPrincipal();
                            jdbc.update("UPDATE users SET failed_attempts=0, locked_until=NULL, last_login_at=CURRENT_TIMESTAMP WHERE id=?", principal.id());
                            onlineSessions.register(request.getSession(), principal, request.getRemoteAddr(), request.getHeader("User-Agent") == null ? "" : request.getHeader("User-Agent"));
                            var session = request.getSession(false);
                            if (session != null) { session.removeAttribute(CaptchaController.SESSION_CODE); session.removeAttribute(CaptchaController.SESSION_EXPIRES); }
                            audit.success(authentication, "auth.login", "user", principal.id());
                            response.setStatus(204);
                        } catch (RuntimeException failure) {
                            SecurityContextHolder.clearContext();
                            var session = request.getSession(false); if (session != null) session.invalidate();
                            response.sendError(503);
                        }
                    })
                    .failureHandler((request, response, exception) -> {
                        try {
                            String username = request.getParameter("username");
                            String reason = exception instanceof DisabledException ? "账号已停用"
                                    : exception instanceof LockedException ? "账号已锁定"
                                    : "用户名或密码错误";
                            var counts = username == null || username.isBlank() ? java.util.List.<Integer>of()
                                    : jdbc.queryForList("SELECT failed_attempts FROM users WHERE username=? AND deleted=FALSE", Integer.class, username.trim());
                            Integer attempts = counts.isEmpty() ? null : counts.get(0);
                            int next = (attempts == null ? 0 : attempts) + 1;
                            if (attempts != null) {
                                if (next >= LoginGuardFilter.MAX_FAILURES)
                                    jdbc.update("UPDATE users SET failed_attempts=?, locked_until=? WHERE username=? AND deleted=FALSE", next, Timestamp.from(Instant.now().plus(LoginGuardFilter.LOCK_MINUTES, ChronoUnit.MINUTES)), username.trim());
                                else
                                    jdbc.update("UPDATE users SET failed_attempts=? WHERE username=? AND deleted=FALSE", next, username.trim());
                            }
                            audit.loginFailure(username, reason);
                            response.setStatus(401);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"error\":\"" + reason + "\",\"captchaRequired\":" + (next >= LoginGuardFilter.CAPTCHA_AFTER) + "}");
                        } catch (RuntimeException failure) { response.sendError(503); }
                    }).permitAll())
                .logout(logout -> logout.logoutUrl("/api/logout").logoutSuccessHandler((request, response, auth) -> response.setStatus(204)))
                .sessionManagement(session -> session.maximumSessions(1).sessionRegistry(sessionRegistry)
                        .expiredSessionStrategy(event -> event.getResponse().sendError(401)))
                .addFilterBefore(new LoginGuardFilter(jdbc, audit), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new ActiveUserFilter(userDetailsService), UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
