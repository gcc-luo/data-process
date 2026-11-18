package com.example.governance.security;

import java.util.function.Supplier;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/** Maps every protected API request to one registered operation and denies unmapped routes. */
@Component
public class PermissionAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {
    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication, RequestAuthorizationContext context) {
        Authentication current = authentication.get();
        if (current == null || !current.isAuthenticated()) return new AuthorizationDecision(false);
        var request = context.getRequest();
        if ("GET".equals(request.getMethod()) && "/api/organization".equals(request.getRequestURI())) {
            return new AuthorizationDecision(true); // The controller filters every snapshot collection by its read grant.
        }
        HttpMethod method;
        try { method = HttpMethod.valueOf(request.getMethod()); }
        catch (IllegalArgumentException unsupported) { return new AuthorizationDecision(false); }
        String permission = PermissionCatalog.permissionFor(method, request.getRequestURI());
        boolean allowed = permission != null && current.getAuthorities().stream().anyMatch(authority -> permission.equals(authority.getAuthority()));
        return new AuthorizationDecision(allowed);
    }
}
