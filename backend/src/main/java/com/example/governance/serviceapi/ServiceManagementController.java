package com.example.governance.serviceapi;

import com.example.governance.query.DatasetPolicyStore;
import com.example.governance.security.AccountPrincipal;
import com.example.governance.security.AuditLog;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ServiceManagementController {
    private final ServiceCatalogStore services;
    private final DatasetPolicyStore policies;
    private final AuditLog audit;
    public ServiceManagementController(ServiceCatalogStore services, DatasetPolicyStore policies, AuditLog audit) { this.services = services; this.policies = policies; this.audit = audit; }
    record CreateService(long datasetId, String name, List<String> fields, int limit) {}
    record CreateCaller(String name, long userId, int limitPerMinute) {}
    private static long user(Authentication auth) { if (!(auth.getPrincipal() instanceof AccountPrincipal principal)) throw new ResponseStatusException(HttpStatus.FORBIDDEN); return principal.id(); }
    @GetMapping("/api/services") List<ServiceCatalogStore.ServiceView> services() { return services.services(); }
    @GetMapping("/api/service-callers") List<ServiceCatalogStore.CallerView> callers() { return services.callers(); }
    @GetMapping("/api/service-grants") List<ServiceCatalogStore.Grant> grants() { return services.grants(); }
    @GetMapping("/api/service-usage") List<ServiceCatalogStore.Usage> usage() { return services.usage(); }
    @PostMapping("/api/services") @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    ResponseEntity<Map<String, Long>> create(@RequestBody CreateService request, Authentication auth) {
        try {
            long userId = user(auth); var access = policies.resolve(userId, request.datasetId());
            if (request.fields() == null || !access.policy().fields().keySet().containsAll(request.fields())) throw new SecurityException();
            long id = services.createService(userId, request.datasetId(), request.name(), request.fields(), request.limit());
            audit.success(auth, "service.create", "service", id);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
        } catch (SecurityException ex) { throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid service configuration"); }
    }
    @PostMapping("/api/services/{id}/submit") @Transactional
    ResponseEntity<Void> submit(@PathVariable long id, Authentication auth) { services.submit(id); audit.success(auth, "service.submit", "service", id); return ResponseEntity.noContent().build(); }
    @PostMapping("/api/services/{id}/approve") @Transactional
    ResponseEntity<Void> approve(@PathVariable long id, Authentication auth) { services.publish(id); audit.success(auth, "service.approve", "service", id); return ResponseEntity.noContent().build(); }
    @PostMapping("/api/service-callers") @Transactional
    ResponseEntity<ServiceCatalogStore.CallerSecret> caller(@RequestBody CreateCaller request, Authentication auth) {
        var caller = services.createCaller(request.name(), request.userId(), request.limitPerMinute());
        audit.success(auth, "service.caller.create", "caller", caller.id());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(caller);
    }
    @PostMapping("/api/services/{service}/callers/{caller}") @Transactional
    ResponseEntity<Void> grant(@PathVariable long service, @PathVariable long caller, Authentication auth) {
        services.grant(service, caller); audit.success(auth, "service.grant", "service", service); return ResponseEntity.noContent().build();
    }
    @PostMapping("/api/services/{id}/withdraw") @Transactional
    ResponseEntity<Void> withdraw(@PathVariable long id, Authentication auth) { services.withdraw(id); audit.success(auth, "service.withdraw", "service", id); return ResponseEntity.noContent().build(); }
    @PostMapping("/api/services/{service}/callers/{caller}/revoke") @Transactional
    ResponseEntity<Void> revoke(@PathVariable long service, @PathVariable long caller, Authentication auth) { services.revoke(service, caller); audit.success(auth, "service.revoke", "service", service); return ResponseEntity.noContent().build(); }
    @PostMapping("/api/service-callers/{id}/disable") @Transactional
    ResponseEntity<Void> disable(@PathVariable long id, Authentication auth) { services.disableCaller(id); audit.success(auth, "service.caller.disable", "caller", id); return ResponseEntity.noContent().build(); }
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Map<String, String>> invalid() { return ResponseEntity.badRequest().body(Map.of("error", "Invalid service configuration")); }
    @ExceptionHandler({IllegalStateException.class, DataIntegrityViolationException.class}) ResponseEntity<Map<String, String>> conflict() { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Service state or configuration conflicts")); }
}
