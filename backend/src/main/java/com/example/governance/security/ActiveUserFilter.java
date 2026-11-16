package com.example.governance.security;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class ActiveUserFilter extends OncePerRequestFilter {
    private final UserDetailsService users;
    public ActiveUserFilter(UserDetailsService users) { this.users = users; }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            AccountPrincipal current = null;
            try { current = (AccountPrincipal) users.loadUserByUsername(auth.getName()); }
            catch (UsernameNotFoundException ignored) { }
            if (!(auth.getPrincipal() instanceof AccountPrincipal original) || current == null || !current.isEnabled() || current.id() != original.id() || !current.securityVersion().equals(original.securityVersion())) {
                SecurityContextHolder.clearContext();
                var session = req.getSession(false);
                if (session != null) session.invalidate();
                res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            var refreshed = new UsernamePasswordAuthenticationToken(current, null, current.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(refreshed);
        }
        chain.doFilter(req, res);
    }
}
