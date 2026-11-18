package com.example.governance.organization;

import com.example.governance.security.AccountCredentials;
import com.example.governance.security.AuditCatalog;
import com.example.governance.security.AuditLog;
import com.example.governance.security.OnlineSessions;
import com.example.governance.security.PermissionGrantDeniedException;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/organization")
public class OrganizationController {
    private final OrganizationService organization;
    private final AccountCredentials credentials;
    private final AuditLog audit;
    private final OnlineSessions onlineSessions;
    private final org.springframework.security.core.session.SessionRegistry sessionRegistry;

    public OrganizationController(OrganizationService organization, AccountCredentials credentials, AuditLog audit, OnlineSessions onlineSessions, org.springframework.security.core.session.SessionRegistry sessionRegistry) {
        this.organization = organization; this.credentials = credentials; this.audit = audit; this.onlineSessions = onlineSessions; this.sessionRegistry = sessionRegistry;
    }

    record DepartmentRequest(String name, Long parentId, Integer sortOrder, String leader, String phone, String email, Boolean enabled) {}
    record MoveRequest(Long parentId) {}
    record RoleRequest(String name, String roleKey, String description, Integer sortOrder, Boolean enabled) {}
    record UserRequest(String username, String nickname, String email, String phone, String password, Long departmentId, Boolean enabled, Set<Long> roles) {}
    record RoleIds(Set<Long> roles) {}
    record Permissions(Set<String> permissions) {}
    record Enabled(Boolean enabled) {}
    record Password(String password) {}
    record MenuRequest(String name, Long parentId, String menuType, Integer sortOrder, String routePath, String component, String icon, Boolean visible, Boolean enabled) {}

    @GetMapping("/audit") ResponseEntity<java.util.List<AuditLog.Event>> audit(@RequestParam(required=false) String action, @RequestParam(required=false) String actor, @RequestParam(required=false) String result, @RequestParam(required=false) String from, @RequestParam(required=false) String to, @RequestParam(required=false) Long beforeId, @RequestParam(defaultValue="0") int offset, @RequestParam(defaultValue="50") int limit) {
        var filter = auditFilter(action, actor, result, from, to, beforeId);
        return ResponseEntity.ok().header("X-Total-Count", String.valueOf(audit.count(filter))).body(audit.list(filter, offset, limit));
    }
    @GetMapping("/audit/export") ResponseEntity<String> auditExport(@RequestParam(required=false) String action, @RequestParam(required=false) String actor, @RequestParam(required=false) String result, @RequestParam(required=false) String from, @RequestParam(required=false) String to) {
        var events = audit.listAll(auditFilter(action, actor, result, from, to, null), 10000);
        var rows = new java.util.ArrayList<String[]>();
        rows.add(new String[]{"时间", "操作者", "操作", "对象类型", "对象编号", "结果", "IP", "终端", "说明"});
        for (var event : events) rows.add(new String[]{event.occurredAtText(), event.username() == null ? "系统或未知账号" : event.username(), event.actionLabel(), event.objectTypeLabel(), event.objectId(), event.resultLabel(), event.ip(), event.userAgent(), event.message()});
        return csv("audit.csv", rows);
    }
    @GetMapping("/online") java.util.List<OnlineSessions.Online> online() { return onlineSessions.list(); }
    @PostMapping("/online/{sessionId}/kick") @Transactional
    ResponseEntity<Void> kick(@PathVariable String sessionId, Authentication auth) {
        var information = sessionRegistry.getSessionInformation(sessionId);
        if (information == null) throw new IllegalArgumentException("会话不存在或已离线");
        information.expireNow();
        onlineSessions.remove(sessionId);
        audit.success(auth, "user.kick", "user", sessionId);
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/roles/{id}/users") java.util.List<OrganizationService.RoleUser> roleUsers(@PathVariable long id) { return organization.roleUsers(id); }
    @GetMapping("/users/export") ResponseEntity<String> usersExport() {
        var snapshot = organization.snapshot(com.example.governance.security.PermissionCatalog.codes());
        var departments = snapshot.departments().stream().collect(java.util.stream.Collectors.toMap(OrganizationService.Department::id, OrganizationService.Department::name));
        var roles = snapshot.roles().stream().collect(java.util.stream.Collectors.toMap(OrganizationService.Role::id, OrganizationService.Role::name));
        var rows = new java.util.ArrayList<String[]>();
        rows.add(new String[]{"账号", "姓名", "部门", "手机", "邮箱", "角色", "状态", "创建时间", "最后登录"});
        for (var user : organization.allUsers()) rows.add(new String[]{user.username(), user.nickname(), departments.getOrDefault(user.departmentId(), ""), user.phone() == null ? "" : user.phone(), user.email() == null ? "" : user.email(), user.roles().stream().map(role -> roles.getOrDefault(role, "")).filter(name -> !name.isEmpty()).collect(java.util.stream.Collectors.joining("/")), user.enabled() ? "启用" : "停用", timeText(user.createdAt()), timeText(user.lastLoginAt())});
        return csv("users.csv", rows);
    }
    @PostMapping("/users/import") @Transactional
    ResponseEntity<Map<String, Object>> usersImport(@RequestParam("file") org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        String text = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        var rows = CsvSupport.parse(text);
        if (!rows.isEmpty() && "账号".equals(rows.get(0)[0].trim())) rows.remove(0);
        if (rows.isEmpty()) throw new IllegalArgumentException("导入文件为空");
        if (rows.size() > 500) throw new IllegalArgumentException("单次导入不超过 500 行");
        return ResponseEntity.ok(organization.importUsers(rows));
    }
    private AuditLog.Filter auditFilter(String action, String actor, String result, String from, String to, Long beforeId) {
        return new AuditLog.Filter(action, actor, result, parseTime(from), parseTime(to), beforeId);
    }
    private java.time.LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().length() == 16 ? value.trim() + ":00" : value.trim();
        try { return java.time.LocalDateTime.parse(normalized); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("时间格式应为 yyyy-MM-ddTHH:mm"); }
    }
    private String timeText(java.time.LocalDateTime value) { return value == null ? "" : value.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); }
    private ResponseEntity<String> csv(String filename, java.util.List<String[]> rows) {
        return ResponseEntity.ok()
                .header("Content-Type", "text/csv;charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body("\uFEFF" + CsvSupport.write(rows));
    }
    @GetMapping("/audit/catalog") Map<String, Object> auditCatalog() {
        return Map.of("actions", AuditCatalog.actions(), "objectTypes", AuditCatalog.objectTypes(), "results", AuditCatalog.results());
    }
    @GetMapping OrganizationService.Snapshot snapshot(Authentication auth) {
        Set<String> granted = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toUnmodifiableSet());
        return organization.snapshot(granted);
    }

    @PostMapping("/departments") @Transactional
    ResponseEntity<Map<String, Long>> department(@RequestBody DepartmentRequest request, Authentication auth) {
        long id = organization.createDepartment(request.name(), request.parentId(), value(request.sortOrder()), request.leader(), request.phone(), request.email(), state(request.enabled()));
        audit.success(auth, "department.create", "department", id); return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }
    @PostMapping("/departments/{id}") @Transactional
    ResponseEntity<Void> updateDepartment(@PathVariable long id, @RequestBody DepartmentRequest request, Authentication auth) {
        organization.updateDepartment(id, request.name(), request.parentId(), value(request.sortOrder()), request.leader(), request.phone(), request.email(), state(request.enabled()));
        audit.success(auth, "department.update", "department", id); return ResponseEntity.noContent().build();
    }
    @PostMapping("/departments/{id}/parent") @Transactional
    ResponseEntity<Void> move(@PathVariable long id, @RequestBody MoveRequest request, Authentication auth) {
        organization.moveDepartment(id, request.parentId()); audit.success(auth, "department.move", "department", id); return ResponseEntity.noContent().build();
    }
    @DeleteMapping("/departments/{id}") @Transactional
    ResponseEntity<Void> deleteDepartment(@PathVariable long id, Authentication auth) {
        organization.deleteDepartment(id); audit.success(auth, "department.delete", "department", id); return ResponseEntity.noContent().build();
    }

    @PostMapping("/roles") @Transactional
    ResponseEntity<Map<String, Long>> role(@RequestBody RoleRequest request, Authentication auth) {
        long id = organization.createRole(request.name(), request.roleKey(), request.description(), value(request.sortOrder()), state(request.enabled()));
        audit.success(auth, "role.create", "role", id); return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }
    @PostMapping("/roles/{id}") @Transactional
    ResponseEntity<Void> updateRole(@PathVariable long id, @RequestBody RoleRequest request, Authentication auth) {
        organization.updateRole(id, request.name(), request.roleKey(), request.description(), value(request.sortOrder()), state(request.enabled()));
        audit.success(auth, "role.update", "role", id); return ResponseEntity.noContent().build();
    }
    @DeleteMapping("/roles/{id}") @Transactional
    ResponseEntity<Void> deleteRole(@PathVariable long id, Authentication auth) {
        organization.deleteRole(id); audit.success(auth, "role.delete", "role", id); return ResponseEntity.noContent().build();
    }
    @PostMapping("/roles/{id}/permissions") @Transactional
    ResponseEntity<Void> permissions(@PathVariable long id, @RequestBody Permissions request, Authentication auth) {
        organization.setPermissions(id, request.permissions(), permissionCodes(auth)); audit.success(auth, "role.permissions", "role", id); return ResponseEntity.noContent().build();
    }
    @PostMapping("/users") @Transactional
    ResponseEntity<Map<String, Long>> user(@RequestBody UserRequest request, Authentication auth) {
        Set<Long> roles = hasPermission(auth, "system.user.assign_roles") ? values(request.roles()) : Set.of();
        boolean enabled = hasPermission(auth, "system.user.set_status") && request.enabled() != null ? request.enabled() : true;
        long id = organization.createUser(request.username(), request.nickname(), request.email(), request.phone(), request.password(), requiredDepartment(request.departmentId()), enabled, roles, permissionCodes(auth));
        audit.success(auth, "user.create", "user", id); return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }
    @PostMapping("/users/{id}") @Transactional
    ResponseEntity<Void> updateUser(@PathVariable long id, @RequestBody UserRequest request, Authentication auth) {
        Set<Long> roles = hasPermission(auth, "system.user.assign_roles") && request.roles() != null ? values(request.roles()) : organization.userRoles(id);
        boolean enabled = hasPermission(auth, "system.user.set_status") && request.enabled() != null ? request.enabled() : organization.userEnabled(id);
        organization.updateUser(id, request.username(), request.nickname(), request.email(), request.phone(), requiredDepartment(request.departmentId()), enabled, roles, permissionCodes(auth));
        audit.success(auth, "user.update", "user", id); return ResponseEntity.noContent().build();
    }
    @PostMapping("/users/{id}/roles") @Transactional
    ResponseEntity<Void> roles(@PathVariable long id, @RequestBody RoleIds request, Authentication auth) {
        organization.setRoles(id, request.roles(), permissionCodes(auth)); audit.success(auth, "user.roles", "user", id); return ResponseEntity.noContent().build();
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
    @DeleteMapping("/users/{id}") @Transactional
    ResponseEntity<Void> deleteUser(@PathVariable long id, Authentication auth) {
        organization.deleteUser(id, auth.getName()); audit.success(auth, "user.delete", "user", id); return ResponseEntity.noContent().build();
    }

    @PostMapping("/menus") @Transactional
    ResponseEntity<Map<String, Long>> menu(@RequestBody MenuRequest request, Authentication auth) {
        long id = organization.createMenu(request.name(), request.parentId(), request.menuType(), value(request.sortOrder()), request.routePath(), request.component(), request.icon(), state(request.visible()), state(request.enabled()));
        audit.success(auth, "menu.create", "menu", id); return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }
    @PostMapping("/menus/{id}") @Transactional
    ResponseEntity<Void> updateMenu(@PathVariable long id, @RequestBody MenuRequest request, Authentication auth) {
        organization.updateMenu(id, request.name(), request.parentId(), request.menuType(), value(request.sortOrder()), request.routePath(), request.component(), request.icon(), state(request.visible()), state(request.enabled()));
        audit.success(auth, "menu.update", "menu", id); return ResponseEntity.noContent().build();
    }
    @DeleteMapping("/menus/{id}") @Transactional
    ResponseEntity<Void> deleteMenu(@PathVariable long id, Authentication auth) {
        organization.deleteMenu(id); audit.success(auth, "menu.delete", "menu", id); return ResponseEntity.noContent().build();
    }

    private int value(Integer value) { return value == null ? 0 : value; }
    private boolean state(Boolean value) { return value == null || value; }
    private long requiredDepartment(Long id) { if (id == null) throw new IllegalArgumentException("Department is required"); return id; }
    private Set<Long> values(Set<Long> value) { return value == null ? Set.of() : value; }
    private boolean hasPermission(Authentication auth, String permission) { return auth.getAuthorities().stream().anyMatch(authority -> permission.equals(authority.getAuthority())); }
    private Set<String> permissionCodes(Authentication auth) { return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).filter(com.example.governance.security.PermissionCatalog.codes()::contains).collect(Collectors.toUnmodifiableSet()); }

    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) { return ResponseEntity.badRequest().body(Map.of("error", error.getMessage() == null ? "Invalid organization configuration" : error.getMessage())); }
    @ExceptionHandler(IllegalStateException.class) ResponseEntity<Map<String, String>> blocked(IllegalStateException error) { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", error.getMessage() == null ? "Operation conflicts with existing data" : error.getMessage())); }
    @ExceptionHandler(PermissionGrantDeniedException.class) ResponseEntity<Map<String, String>> grantDenied(PermissionGrantDeniedException error) { return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", error.getMessage())); }
    @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<Map<String, String>> conflict() { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "名称、账号或路由已存在，或数据仍有关联")); }
}
