package com.example.governance.security;

import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean UserDetailsService userDetailsService(JdbcTemplate jdbc) {
        return username -> jdbc.query("SELECT id, security_version, username, password_hash, enabled FROM users WHERE username=? AND deleted=FALSE", (rs, row) -> {
            String name = rs.getString("username");
            var permissions = jdbc.queryForList("SELECT DISTINCT rp.permission FROM role_permissions rp JOIN user_roles ur ON ur.role_id=rp.role_id JOIN roles r ON r.id=ur.role_id AND r.enabled=TRUE JOIN users u ON u.id=ur.user_id WHERE u.username=? AND u.deleted=FALSE", String.class, name);
            return new AccountPrincipal(rs.getLong("id"), rs.getString("security_version"), name, rs.getString("password_hash"), rs.getBoolean("enabled"), permissions.stream().map(SimpleGrantedAuthority::new).toList());
        }, username).stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }

    @Bean @Order(1) SecurityFilterChain externalServiceChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api/service-call/**")
                .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.GET, "/api/service-call/**").permitAll().anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable()).requestCache(cache -> cache.disable()).build();
    }
    @Bean @Order(2) SecurityFilterChain securityFilterChain(HttpSecurity http, UserDetailsService userDetailsService, AuditLog audit, PermissionAuthorizationManager permissions) throws Exception {
        return http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/health", "/api/csrf", "/api/login").permitAll()
                .requestMatchers("/api/session", "/api/navigation", "/api/logout").authenticated()
                .requestMatchers("/api/**").access(permissions)
                .anyRequest().denyAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .formLogin(form -> form.loginProcessingUrl("/api/login")
                    .successHandler((request, response, authentication) -> {
                        try {
                            var principal = (AccountPrincipal) authentication.getPrincipal();
                            audit.success(authentication, "auth.login", "user", principal.id());
                            response.setStatus(204);
                        } catch (RuntimeException failure) {
                            org.springframework.security.core.context.SecurityContextHolder.clearContext();
                            var session = request.getSession(false); if (session != null) session.invalidate();
                            response.sendError(503);
                        }
                    })
                    .failureHandler((request, response, exception) -> {
                        try { audit.loginFailure(); response.setStatus(401); }
                        catch (RuntimeException failure) { response.sendError(503); }
                    }).permitAll())
                .logout(logout -> logout.logoutUrl("/api/logout").logoutSuccessHandler((request, response, auth) -> response.setStatus(204)))
                .addFilterAfter(new ActiveUserFilter(userDetailsService), UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
