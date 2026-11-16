package com.example.governance.organization;

import com.example.governance.security.AccountCredentials;
import com.example.governance.security.AuditLog;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/organization")
public class OrganizationController {
    private final OrganizationService organization;
    private final AccountCredentials credentials;
    private final AuditLog audit;
    public OrganizationController(OrganizationService organization, AccountCredentials credentials, AuditLog audit) { this.organization = organization; this.credentials = credentials; this.audit = audit; }
    record DepartmentRequest(String name, Long parentId) {}
    record MoveRequest(Long parentId) {}
    record RoleRequest(String name) {}
    record UserRequest(String username, String password, long departmentId) {}
    record RoleIds(Set<Long> roles) {}
    record Permissions(Set<String> permissions) {}
    record Enabled(Boolean enabled) {}
    record Password(String password) {}
    @GetMapping("/audit") java.util.List<AuditLog.Event> audit(@RequestParam(required=false) String action, @RequestParam(required=false) Long beforeId, @RequestParam(defaultValue="50") int limit) { return audit.list(action, beforeId, limit); }
    @GetMapping OrganizationService.Snapshot snapshot() { return organization.snapshot(); }
    @PostMapping("/departments") @Transactional
    ResponseEntity<Map<String, Long>> department(@RequestBody DepartmentRequest request, Authentication auth) {
        long id = organization.createDepartment(request.name(), request.parentId()); audit.success(auth, "department.create", "department", id);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }
    @PostMapping("/departments/{id}/parent") @Transactional
    ResponseEntity<Void> move(@PathVariable long id, @RequestBody MoveRequest request, Authentication auth) {
        organization.moveDepartment(id, request.parentId()); audit.success(auth, "department.move", "department", id); return ResponseEntity.noContent().build();
    }
    @PostMapping("/roles") @Transactional
    ResponseEntity<Map<String, Long>> role(@RequestBody RoleRequest request, Authentication auth) {
        long id = organization.createRole(request.name()); audit.success(auth, "role.create", "role", id);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }
    @PostMapping("/roles/{id}/permissions") @Transactional
    ResponseEntity<Void> permissions(@PathVariable long id, @RequestBody Permissions request, Authentication auth) {
        organization.setPermissions(id, request.permissions()); audit.success(auth, "role.permissions", "role", id); return ResponseEntity.noContent().build();
    }
    @PostMapping("/users") @Transactional
    ResponseEntity<Map<String, Long>> user(@RequestBody UserRequest request, Authentication auth) {
        long id = organization.createUser(request.username(), request.password(), request.departmentId()); audit.success(auth, "user.create", "user", id);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }
    @PostMapping("/users/{id}/roles") @Transactional
    ResponseEntity<Void> roles(@PathVariable long id, @RequestBody RoleIds request, Authentication auth) {
        organization.setRoles(id, request.roles()); audit.success(auth, "user.roles", "user", id); return ResponseEntity.noContent().build();
    }
    @PostMapping("/users/{id}/enabled") @Transactional
    ResponseEntity<Void> enabled(@PathVariable long id, @RequestBody Enabled request, Authentication auth) {
        if (request.enabled() == null) throw new IllegalArgumentException("Invalid state");
        credentials.setEnabled(id, request.enabled()); audit.success(auth, "user.enabled", "user", id); return ResponseEntity.noContent().build();
    }
    @PostMapping("/users/{id}/password") @Transactional
    ResponseEntity<Void> password(@PathVariable long id, @RequestBody Password request, Authentication auth) {
        credentials.resetPassword(id, request.password()); audit.success(auth, "user.password", "user", id); return ResponseEntity.noContent().build();
    }
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Map<String, String>> invalid() { return ResponseEntity.badRequest().body(Map.of("error", "Invalid organization configuration")); }
    @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<Map<String, String>> conflict() { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "Organization configuration conflicts with existing data")); }
}
