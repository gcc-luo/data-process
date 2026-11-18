package com.example.governance.organization;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.example.governance.security.PermissionCatalog;
import com.example.governance.security.PermissionGrantDeniedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Organization and menu mutations are serialized for the single-node deployment. */
@Service
public class OrganizationService {
    private static final long DEFAULT_ADMIN_ROLE_ID = 1;
    private static final long SYSTEM_MENU_ID = 9;
    private static final long MENU_ADMIN_ID = 94;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;

    public OrganizationService(JdbcTemplate jdbc, PlatformTransactionManager manager, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.tx = new TransactionTemplate(manager);
    }

    public long createDepartment(String name, Long parent) { return createDepartment(name, parent, 0, null, null, null, true); }

    public long createDepartment(String name, Long parent, int sortOrder, String leader, String phone, String email, boolean enabled) {
        checkName(name);
        checkOrder(sortOrder);
        checkOptional(leader, 160, "leader"); checkOptional(phone, 48, "phone"); checkOptional(email, 160, "email");
        return tx.execute(status -> {
            lock(); validateDepartmentParent(null, parent);
            long id = next("departments");
            jdbc.update("INSERT INTO departments(id,name,parent_id,sort_order,leader,phone,email,enabled) VALUES (?,?,?,?,?,?,?,?)", id, name.trim(), parent, sortOrder, blankToNull(leader), blankToNull(phone), blankToNull(email), enabled);
            return id;
        });
    }

    public void updateDepartment(long id, String name, Long parent, int sortOrder, String leader, String phone, String email, boolean enabled) {
        checkName(name); checkOrder(sortOrder);
        checkOptional(leader, 160, "leader"); checkOptional(phone, 48, "phone"); checkOptional(email, 160, "email");
        tx.executeWithoutResult(status -> {
            lock(); validateDepartmentParent(id, parent);
            int changed = jdbc.update("UPDATE departments SET name=?,parent_id=?,sort_order=?,leader=?,phone=?,email=?,enabled=? WHERE id=?", name.trim(), parent, sortOrder, blankToNull(leader), blankToNull(phone), blankToNull(email), enabled, id);
            if (changed != 1) throw new IllegalArgumentException("Unknown department");
        });
    }

    public void moveDepartment(long id, Long parent) {
        tx.executeWithoutResult(status -> {
            lock(); validateDepartmentParent(id, parent);
            jdbc.update("UPDATE departments SET parent_id=? WHERE id=?", parent, id);
        });
    }

    private void validateDepartmentParent(Long id, Long parent) {
        if (id != null && !exists("departments", id)) throw new IllegalArgumentException("Unknown department");
        if (parent != null && !exists("departments", parent)) throw new IllegalArgumentException("Unknown parent department");
        if (parent == null) return;
        Map<Long, Long> parents = new HashMap<>();
        jdbc.query("SELECT id,parent_id FROM departments", rs -> { long child = rs.getLong(1), parentId = rs.getLong(2); if (!rs.wasNull()) parents.put(child, parentId); });
        Set<Long> seen = new HashSet<>(); Long current = parent;
        while (current != null) {
            if ((id != null && current.equals(id)) || !seen.add(current)) throw new IllegalArgumentException("Department hierarchy cycle");
            current = parents.get(current);
        }
    }

    public void deleteDepartment(long id) {
        tx.executeWithoutResult(status -> {
            lock(); if (!exists("departments", id)) throw new IllegalArgumentException("Unknown department");
            if (count("SELECT COUNT(*) FROM departments WHERE parent_id=?", id) > 0) throw new IllegalStateException("请先删除或移动该部门下的子部门");
            if (count("SELECT COUNT(*) FROM users WHERE department_id=?", id) > 0) throw new IllegalStateException("该部门仍关联用户，无法删除");
            jdbc.update("DELETE FROM departments WHERE id=?", id);
        });
    }

    public long createRole(String name) {
        String key = "role_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return createRole(name, key, null, 0, true);
    }

    public long createRole(String name, String roleKey, String description, int sortOrder, boolean enabled) {
        checkName(name); checkRoleKey(roleKey); checkOptional(description, 500, "description"); checkOrder(sortOrder);
        return tx.execute(status -> {
            lock(); long id = next("roles");
            jdbc.update("INSERT INTO roles(id,name,role_key,description,sort_order,enabled) VALUES (?,?,?,?,?,?)", id, name.trim(), roleKey.trim(), blankToNull(description), sortOrder, enabled);
            return id;
        });
    }

    public void updateRole(long id, String name, String roleKey, String description, int sortOrder, boolean enabled) {
        checkName(name); checkRoleKey(roleKey); checkOptional(description, 500, "description"); checkOrder(sortOrder);
        if (id == DEFAULT_ADMIN_ROLE_ID && !enabled) throw new IllegalStateException("默认管理员角色不能停用");
        tx.executeWithoutResult(status -> {
            lock(); int changed = jdbc.update("UPDATE roles SET name=?,role_key=?,description=?,sort_order=?,enabled=? WHERE id=?", name.trim(), roleKey.trim(), blankToNull(description), sortOrder, enabled, id);
            if (changed != 1) throw new IllegalArgumentException("Unknown role");
        });
    }

    public void deleteRole(long id) {
        tx.executeWithoutResult(status -> {
            lock(); if (!exists("roles", id)) throw new IllegalArgumentException("Unknown role");
            if (id == DEFAULT_ADMIN_ROLE_ID) throw new IllegalStateException("默认管理员角色不能删除");
            if (count("SELECT COUNT(*) FROM user_roles ur JOIN users u ON u.id=ur.user_id WHERE ur.role_id=? AND u.deleted=FALSE", id) > 0) throw new IllegalStateException("请先解除该角色与用户的关联");
            jdbc.update("DELETE FROM user_roles WHERE role_id=?", id);
            jdbc.update("DELETE FROM role_permissions WHERE role_id=?", id);
            jdbc.update("DELETE FROM role_menus WHERE role_id=?", id);
            jdbc.update("DELETE FROM roles WHERE id=?", id);
        });
    }

    public void setPermissions(long roleId, Set<String> permissions) {
        setPermissions(roleId, permissions, PermissionCatalog.codes());
    }

    public void setPermissions(long roleId, Set<String> permissions, Set<String> actorPermissions) {
        if (permissions == null || permissions.size() > PermissionCatalog.codes().size() || permissions.stream().anyMatch(permission -> permission == null || !PermissionCatalog.codes().contains(permission))) throw new IllegalArgumentException("包含未登记的权限码");
        Set<String> actor = actorPermissions == null ? Set.of() : Set.copyOf(actorPermissions);
        if (roleId == DEFAULT_ADMIN_ROLE_ID && !permissions.containsAll(PermissionCatalog.codes())) throw new IllegalStateException("默认管理员角色必须保留全部权限");
        tx.executeWithoutResult(status -> {
            lock(); if (!exists("roles", roleId)) throw new IllegalArgumentException("Unknown role");
            Set<String> current = Set.copyOf(jdbc.queryForList("SELECT permission FROM role_permissions WHERE role_id=?", String.class, roleId));
            Set<String> added = new HashSet<>(permissions); added.removeAll(current);
            if (!actor.containsAll(added)) throw new PermissionGrantDeniedException("不能授予当前账号未持有的权限");
            jdbc.update("DELETE FROM role_permissions WHERE role_id=?", roleId);
            permissions.forEach(permission -> jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (?,?)", roleId, permission));
        });
    }

    public long createUser(String username, String password, long department) {
        return createUser(username, username, null, null, password, department, true, Set.of());
    }

    public long createUser(String username, String nickname, String email, String phone, String password, long department, boolean enabled, Set<Long> roles) {
        return createUser(username, nickname, email, phone, password, department, enabled, roles, PermissionCatalog.codes());
    }

    public long createUser(String username, String nickname, String email, String phone, String password, long department, boolean enabled, Set<Long> roles, Set<String> actorPermissions) {
        checkUsername(username); checkOptional(nickname, 160, "nickname"); checkOptional(email, 160, "email"); checkOptional(phone, 48, "phone");
        checkPassword(password);
        validateRoleIds(roles);
        Set<String> actor = actorPermissions == null ? Set.of() : Set.copyOf(actorPermissions);
        String hash = encoder.encode(password);
        return tx.execute(status -> {
            lock(); if (!exists("departments", department)) throw new IllegalArgumentException("Unknown department");
            if (count("SELECT COUNT(*) FROM departments WHERE id=? AND enabled=TRUE", department) == 0) throw new IllegalArgumentException("Cannot assign a user to a disabled department");
            if (roles.stream().anyMatch(role -> !exists("roles", role))) throw new IllegalArgumentException("Unknown role");
            validateRoleAssignments(roles, Set.of(), actor);
            long id = next("users");
            jdbc.update("INSERT INTO users(id,username,nickname,email,phone,password_hash,department_id,enabled,deleted,security_version) VALUES (?,?,?,?,?,?,?,?,FALSE,?)", id, username.trim(), defaultNickname(username, nickname), blankToNull(email), blankToNull(phone), hash, department, enabled, UUID.randomUUID().toString());
            replaceUserRoles(id, roles);
            return id;
        });
    }

    public void updateUser(long id, String username, String nickname, String email, String phone, long department, boolean enabled, Set<Long> roles) {
        updateUser(id, username, nickname, email, phone, department, enabled, roles, PermissionCatalog.codes());
    }

    public void updateUser(long id, String username, String nickname, String email, String phone, long department, boolean enabled, Set<Long> roles, Set<String> actorPermissions) {
        checkUsername(username); checkOptional(nickname, 160, "nickname"); checkOptional(email, 160, "email"); checkOptional(phone, 48, "phone"); validateRoleIds(roles);
        Set<String> actor = actorPermissions == null ? Set.of() : Set.copyOf(actorPermissions);
        if (id == 1 && (!enabled || !roles.contains(DEFAULT_ADMIN_ROLE_ID))) throw new IllegalStateException("默认管理员账号必须保持启用并保留管理员角色");
        tx.executeWithoutResult(status -> {
            lock();
            List<Long> currentDepartments = jdbc.queryForList("SELECT department_id FROM users WHERE id=? AND deleted=FALSE", Long.class, id);
            if (currentDepartments.isEmpty()) throw new IllegalArgumentException("Unknown user");
            if (!exists("departments", department) || roles.stream().anyMatch(role -> !exists("roles", role))) throw new IllegalArgumentException("Unknown department or role");
            Set<Long> currentRoles = Set.copyOf(jdbc.queryForList("SELECT role_id FROM user_roles WHERE user_id=?", Long.class, id));
            validateRoleAssignments(roles, currentRoles, actor);
            if (!currentDepartments.getFirst().equals(department) && count("SELECT COUNT(*) FROM departments WHERE id=? AND enabled=TRUE", department) == 0) throw new IllegalArgumentException("Cannot assign a user to a disabled department");
            int changed = jdbc.update("UPDATE users SET username=?,nickname=?,email=?,phone=?,department_id=?,enabled=? WHERE id=? AND deleted=FALSE", username.trim(), defaultNickname(username, nickname), blankToNull(email), blankToNull(phone), department, enabled, id);
            if (changed != 1) throw new IllegalArgumentException("Unknown user");
            replaceUserRoles(id, roles);
            if (!enabled) jdbc.update("UPDATE service_callers SET enabled=FALSE WHERE user_id=?", id);
        });
    }

    public void setRoles(long userId, Set<Long> roles) {
        setRoles(userId, roles, PermissionCatalog.codes());
    }

    public void setRoles(long userId, Set<Long> roles, Set<String> actorPermissions) {
        validateRoleIds(roles);
        Set<String> actor = actorPermissions == null ? Set.of() : Set.copyOf(actorPermissions);
        if (userId == 1 && !roles.contains(DEFAULT_ADMIN_ROLE_ID)) throw new IllegalStateException("默认管理员账号必须保留管理员角色");
        tx.executeWithoutResult(status -> {
            lock(); if (!exists("users", userId) || count("SELECT COUNT(*) FROM users WHERE id=? AND deleted=FALSE", userId) == 0 || roles.stream().anyMatch(id -> !exists("roles", id))) throw new IllegalArgumentException("Unknown user or role");
            Set<Long> currentRoles = Set.copyOf(jdbc.queryForList("SELECT role_id FROM user_roles WHERE user_id=?", Long.class, userId));
            validateRoleAssignments(roles, currentRoles, actor);
            replaceUserRoles(userId, roles);
        });
    }

    private void validateRoleAssignments(Set<Long> requested, Set<Long> current, Set<String> actorPermissions) {
        for (Long roleId : requested) {
            if (current.contains(roleId)) continue;
            Set<String> rolePermissions = new HashSet<>(jdbc.queryForList("SELECT permission FROM role_permissions WHERE role_id=?", String.class, roleId));
            rolePermissions.retainAll(PermissionCatalog.codes());
            if (!actorPermissions.containsAll(rolePermissions)) throw new PermissionGrantDeniedException("不能分配包含当前账号未持有权限的角色");
        }
    }

    public Set<Long> userRoles(long userId) {
        return Set.copyOf(jdbc.queryForList("SELECT role_id FROM user_roles WHERE user_id=?", Long.class, userId));
    }

    public boolean userEnabled(long userId) {
        Boolean enabled = jdbc.query("SELECT enabled FROM users WHERE id=? AND deleted=FALSE", rs -> rs.next() ? rs.getBoolean(1) : null, userId);
        if (enabled == null) throw new IllegalArgumentException("Unknown user");
        return enabled;
    }

    public void deleteUser(long id, String currentUsername) {
        tx.executeWithoutResult(status -> {
            lock();
            String username = jdbc.query("SELECT username FROM users WHERE id=? AND deleted=FALSE", rs -> rs.next() ? rs.getString(1) : null, id);
            if (username == null) throw new IllegalArgumentException("Unknown user");
            if (id == 1 || username.equals(currentUsername)) throw new IllegalStateException("不能删除默认管理员或当前登录账号");
            jdbc.update("UPDATE users SET deleted=TRUE,enabled=FALSE,security_version=? WHERE id=?", UUID.randomUUID().toString(), id);
            jdbc.update("DELETE FROM user_roles WHERE user_id=?", id);
            jdbc.update("UPDATE service_callers SET enabled=FALSE WHERE user_id=?", id);
        });
    }

    public long createMenu(String name, Long parentId, String menuType, int sortOrder, String routePath, String component, String icon, boolean visible, boolean enabled) {
        validateMenu(name, parentId, menuType, sortOrder, routePath, component, icon, null);
        return tx.execute(status -> {
            lock(); validateMenuParent(parentId, null); validateMenuPlacement(menuType, parentId, component, null); ensureRouteAvailable(routePath, menuType, null);
            long id = next("menus");
            insertMenu(id, name, parentId, menuType, sortOrder, routePath, component, icon, visible, enabled);
            return id;
        });
    }

    public void updateMenu(long id, String name, Long parentId, String menuType, int sortOrder, String routePath, String component, String icon, boolean visible, boolean enabled) {
        validateMenu(name, parentId, menuType, sortOrder, routePath, component, icon, id);
        if (id == SYSTEM_MENU_ID && (parentId != null || !"D".equals(menuType) || !"system".equals(component) || !visible || !enabled)) throw new IllegalStateException("系统管理目录需要保持顶级、可见和启用");
        if (id == MENU_ADMIN_ID && (!Long.valueOf(SYSTEM_MENU_ID).equals(parentId) || !"M".equals(menuType) || !"system/menus".equals(component) || !visible || !enabled)) throw new IllegalStateException("菜单管理入口需要保留在系统管理下并保持可见和启用");
        if (isPermissionMenu(id) && (!Long.valueOf(SYSTEM_MENU_ID).equals(parentId) || !"M".equals(menuType) || !"system/permissions".equals(component) || !visible || !enabled)) throw new IllegalStateException("权限管理入口需要保留在系统管理下并保持可见和启用");
        tx.executeWithoutResult(status -> {
            lock(); if (!exists("menus", id)) throw new IllegalArgumentException("Unknown menu");
            validateMenuParent(parentId, id); validateMenuPlacement(menuType, parentId, component, id); ensureRouteAvailable(routePath, menuType, id);
            int changed = jdbc.update("UPDATE menus SET name=?,parent_id=?,menu_type=?,sort_order=?,route_path=?,component=?,permission='',icon=?,visible=?,enabled=? WHERE id=?", name.trim(), parentId, menuType, sortOrder, normalizedRoute(routePath, menuType), safe(component), safe(icon), visible, enabled, id);
            if (changed != 1) throw new IllegalArgumentException("Unknown menu");
        });
    }

    public void deleteMenu(long id) {
        tx.executeWithoutResult(status -> {
            lock(); if (!exists("menus", id)) throw new IllegalArgumentException("Unknown menu");
            if (id == MENU_ADMIN_ID || isPermissionMenu(id)) throw new IllegalStateException("系统管理入口不能删除");
            if (count("SELECT COUNT(*) FROM menus WHERE parent_id=?", id) > 0) throw new IllegalStateException("请先删除或移动该菜单下的子菜单");
            jdbc.update("DELETE FROM role_menus WHERE menu_id=?", id);
            jdbc.update("DELETE FROM menus WHERE id=?", id);
        });
    }

    public record Department(long id, String name, Long parentId, int sortOrder, String leader, String phone, String email, boolean enabled) {}
    public record Role(long id, String name, String roleKey, String description, int sortOrder, boolean enabled, Set<String> permissions) {}
    public record User(long id, String username, String nickname, String email, String phone, long departmentId, boolean enabled, Set<Long> roles) {}
    public record Menu(long id, Long parentId, String name, String menuType, String routePath, String component, String icon, int sortOrder, boolean visible, boolean enabled) {}
    public record DepartmentOption(long id, String name, Long parentId, boolean enabled) {}
    public record Snapshot(List<Department> departments, List<DepartmentOption> departmentOptions, List<Role> roles, List<User> users, List<Menu> menus, List<PermissionCatalog.Entry> permissionCatalog, Set<String> assignablePermissionCodes) {}

    public Snapshot snapshot(Set<String> granted) {
        boolean mayReadDepartments = granted.contains("system.department.read");
        List<Department> departments = mayReadDepartments ? jdbc.query("SELECT id,name,parent_id,sort_order,leader,phone,email,enabled FROM departments ORDER BY sort_order,id", (rs, row) -> department(rs)) : List.of();
        boolean mayUseDepartmentLookup = mayReadDepartments || granted.stream().anyMatch(code -> Set.of("system.department.create", "system.department.update", "system.user.read", "system.user.create", "system.user.update").contains(code));
        List<DepartmentOption> departmentOptions = mayUseDepartmentLookup ? jdbc.query("SELECT id,name,parent_id,enabled FROM departments ORDER BY sort_order,id", (rs, row) -> {
            long parentId = rs.getLong("parent_id"); Long parent = rs.wasNull() ? null : parentId;
            return new DepartmentOption(rs.getLong("id"), rs.getString("name"), parent, rs.getBoolean("enabled"));
        }) : List.of();
        List<Role> roles = List.of();
        if (granted.contains("system.role.read") || granted.contains("system.role.assign_permissions") || granted.contains("system.user.read") || granted.contains("system.user.assign_roles") || granted.contains("datasource.dataset.manage_policy")) {
            List<Role> baseRoles = jdbc.query("SELECT id,name,role_key,description,sort_order,enabled FROM roles ORDER BY sort_order,id", (rs, row) -> new Role(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getBoolean(6), Set.of()));
            boolean mayReadAssignments = granted.contains("system.role.read") || granted.contains("system.role.assign_permissions");
            roles = baseRoles.stream().map(role -> new Role(role.id(), role.name(), role.roleKey(), role.description(), role.sortOrder(), role.enabled(), mayReadAssignments
                    ? jdbc.queryForList("SELECT permission FROM role_permissions WHERE role_id=?", String.class, role.id()).stream().filter(PermissionCatalog.codes()::contains).collect(java.util.stream.Collectors.toUnmodifiableSet())
                    : Set.of())).toList();
        }
        List<User> users = List.of();
        if (granted.contains("system.user.read")) {
            List<User> baseUsers = jdbc.query("SELECT id,username,nickname,email,phone,department_id,enabled FROM users WHERE deleted=FALSE ORDER BY id", (rs, row) -> new User(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6), rs.getBoolean(7), Set.of()));
            users = baseUsers.stream().map(user -> new User(user.id(), user.username(), user.nickname(), user.email(), user.phone(), user.departmentId(), user.enabled(), Set.copyOf(jdbc.queryForList("SELECT role_id FROM user_roles WHERE user_id=?", Long.class, user.id())))).toList();
        } else if (granted.contains("system.user.assign_roles") || granted.contains("service.caller.create")) {
            List<User> userChoices = jdbc.query("SELECT id,username,department_id,enabled FROM users WHERE deleted=FALSE AND enabled=TRUE ORDER BY id", (rs, row) -> new User(rs.getLong(1), rs.getString(2), rs.getString(2), null, null, rs.getLong(3), rs.getBoolean(4), Set.of()));
            users = userChoices.stream().map(user -> new User(user.id(), user.username(), user.nickname(), null, null, user.departmentId(), user.enabled(), granted.contains("system.user.assign_roles") ? Set.copyOf(jdbc.queryForList("SELECT role_id FROM user_roles WHERE user_id=?", Long.class, user.id())) : Set.of())).toList();
        }
        List<Menu> menus = granted.contains("system.menu.read") ? allMenus() : List.of();
        List<PermissionCatalog.Entry> catalog = (granted.contains("system.permission.read") || granted.contains("system.role.assign_permissions")) ? permissionCatalog() : List.of();
        Set<String> assignablePermissionCodes = granted.stream().filter(PermissionCatalog.codes()::contains).collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new Snapshot(departments, departmentOptions, roles, users, menus, catalog, assignablePermissionCodes);
    }

    /** Returns visible menu nodes whose module has at least one operation grant, including ancestors. */
    public List<Menu> navigation(String username) {
        List<Menu> all = allMenus();
        Set<String> granted = new HashSet<>(jdbc.queryForList("SELECT DISTINCT rp.permission FROM role_permissions rp JOIN user_roles ur ON ur.role_id=rp.role_id JOIN roles r ON r.id=ur.role_id AND r.enabled=TRUE JOIN users u ON u.id=ur.user_id WHERE u.username=? AND u.enabled=TRUE AND u.deleted=FALSE", String.class, username));
        granted.retainAll(PermissionCatalog.codes());
        Set<Long> permitted = new HashSet<>();
        Map<Long, Menu> byId = new HashMap<>(); all.forEach(menu -> byId.put(menu.id(), menu));
        for (Menu menu : all) {
            if (!menu.enabled() || !menu.visible() || "F".equals(menu.menuType())) continue;
            if ("D".equals(menu.menuType())) continue;
            Set<String> modules = PermissionCatalog.modulesForComponent(menu.component());
            if (granted.stream().anyMatch(code -> modules.stream().anyMatch(module -> code.startsWith(module + ".")))) permitted.add(menu.id());
        }
        for (Long id : new ArrayList<>(permitted)) {
            Menu current = byId.get(id);
            while (current != null && current.parentId() != null) {
                Menu parent = byId.get(current.parentId()); if (parent == null || !parent.enabled() || !parent.visible()) break;
                permitted.add(parent.id()); current = parent;
            }
        }
        Set<Long> directoriesWithChildren = new HashSet<>();
        for (Menu menu : all) if (menu.parentId() != null && permitted.contains(menu.id())) directoriesWithChildren.add(menu.parentId());
        permitted.removeIf(id -> { Menu menu = byId.get(id); return menu != null && "D".equals(menu.menuType()) && !directoriesWithChildren.contains(id); });
        return all.stream().filter(menu -> permitted.contains(menu.id()) && menu.enabled() && menu.visible()).toList();
    }

    private List<Menu> allMenus() {
        return jdbc.query("SELECT id,parent_id,name,menu_type,route_path,component,icon,sort_order,visible,enabled FROM menus ORDER BY sort_order,id", (rs, row) -> menu(rs));
    }

    private List<PermissionCatalog.Entry> permissionCatalog() {
        return jdbc.query("SELECT permission_code,module_code,module_name,action_name,description,sort_order FROM permission_catalog WHERE enabled=TRUE ORDER BY sort_order,permission_code", (rs, row) -> new PermissionCatalog.Entry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6)))
                .stream().filter(entry -> PermissionCatalog.codes().contains(entry.code())).toList();
    }

    private Department department(java.sql.ResultSet rs) throws java.sql.SQLException {
        long parent = rs.getLong("parent_id"); Long parentId = rs.wasNull() ? null : parent;
        return new Department(rs.getLong("id"), rs.getString("name"), parentId, rs.getInt("sort_order"), rs.getString("leader"), rs.getString("phone"), rs.getString("email"), rs.getBoolean("enabled"));
    }

    private Menu menu(java.sql.ResultSet rs) throws java.sql.SQLException {
        long parent = rs.getLong("parent_id"); Long parentId = rs.wasNull() ? null : parent;
        return new Menu(rs.getLong("id"), parentId, rs.getString("name"), rs.getString("menu_type"), rs.getString("route_path"), rs.getString("component"), rs.getString("icon"), rs.getInt("sort_order"), rs.getBoolean("visible"), rs.getBoolean("enabled"));
    }

    private void validateMenu(String name, Long parentId, String type, int order, String path, String component, String icon, Long id) {
        checkName(name); checkOrder(order); checkOptional(path, 255, "route path"); checkOptional(component, 160, "component"); checkOptional(icon, 80, "icon");
        if (type == null || !Set.of("D", "M", "F").contains(type)) throw new IllegalArgumentException("Invalid menu type");
        String normalizedPath = normalizedRoute(path, type);
        if ("M".equals(type) && normalizedPath.isBlank()) throw new IllegalArgumentException("Page route is required");
        if ("M".equals(type) && !normalizedPath.startsWith("/")) throw new IllegalArgumentException("Page route must start with /");
        if ("M".equals(type) && (component == null || component.isBlank())) throw new IllegalArgumentException("Page component is required");
        if (parentId != null && id != null && parentId.equals(id)) throw new IllegalArgumentException("Menu cannot be its own parent");
    }

    private void validateMenuParent(Long parent, Long id) {
        if (parent == null) return;
        if (!exists("menus", parent)) throw new IllegalArgumentException("Unknown parent menu");
        String parentType = jdbc.queryForObject("SELECT menu_type FROM menus WHERE id=?", String.class, parent);
        if ("F".equals(parentType)) throw new IllegalArgumentException("A button cannot contain child menus");
        Map<Long, Long> parents = new HashMap<>();
        jdbc.query("SELECT id,parent_id FROM menus", rs -> { long child = rs.getLong(1), parentId = rs.getLong(2); if (!rs.wasNull()) parents.put(child, parentId); });
        Set<Long> seen = new HashSet<>(); Long current = parent;
        while (current != null) {
            if ((id != null && current.equals(id)) || !seen.add(current)) throw new IllegalArgumentException("Menu hierarchy cycle");
            current = parents.get(current);
        }
    }

    private void validateMenuPlacement(String type, Long parent, String component, Long id) {
        String page = safe(component);
        if ("D".equals(type)) {
            if (parent != null || !"system".equals(page)) throw new IllegalArgumentException("平台目录只能作为系统管理根目录维护");
            Integer duplicate = id == null
                    ? jdbc.queryForObject("SELECT COUNT(*) FROM menus WHERE menu_type='D' AND component='system'", Integer.class)
                    : jdbc.queryForObject("SELECT COUNT(*) FROM menus WHERE menu_type='D' AND component='system' AND id<>?", Integer.class, id);
            if (duplicate != null && duplicate > 0) throw new IllegalStateException("系统管理目录已存在");
        }
        if ("M".equals(type) && "system".equals(page)) throw new IllegalArgumentException("系统管理必须作为目录类型维护");
        if ("M".equals(type) && page.startsWith("system/") && !Long.valueOf(SYSTEM_MENU_ID).equals(parent)) throw new IllegalArgumentException("系统管理页面必须放在系统管理目录下");
        if ("M".equals(type) && !page.startsWith("system/")) throw new IllegalArgumentException("菜单管理仅维护系统管理页面");
    }

    private void ensureRouteAvailable(String path, String type, Long id) {
        if (!"M".equals(type)) return;
        String normalized = normalizedRoute(path, type);
        Integer matches = id == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM menus WHERE menu_type='M' AND route_path=?", Integer.class, normalized)
                : jdbc.queryForObject("SELECT COUNT(*) FROM menus WHERE menu_type='M' AND route_path=? AND id<>?", Integer.class, normalized, id);
        if (matches != null && matches > 0) throw new IllegalArgumentException("Route is already used by another menu");
    }

    private void insertMenu(long id, String name, Long parent, String type, int order, String path, String component, String icon, boolean visible, boolean enabled) {
        jdbc.update("INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order,visible,enabled) VALUES (?,?,?,?,?,?,'',?,?,?,?)", id, parent, name.trim(), type, normalizedRoute(path, type), safe(component), safe(icon), order, visible, enabled);
    }

    private String normalizedRoute(String path, String type) { return "D".equals(type) || "F".equals(type) ? "" : (path == null ? "" : path.trim()); }
    private String safe(String value) { return value == null ? "" : value.trim(); }
    private String blankToNull(String value) { String normalized = value == null ? null : value.trim(); return normalized == null || normalized.isEmpty() ? null : normalized; }
    private String defaultNickname(String username, String nickname) { String value = blankToNull(nickname); return value == null ? username.trim() : value; }
    private void checkName(String value) { if (value == null || value.isBlank() || !value.equals(value.trim()) || value.length() > 160) throw new IllegalArgumentException("Invalid name"); }
    private void checkUsername(String value) { checkName(value); if (value.length() < 2 || value.contains(" ")) throw new IllegalArgumentException("Invalid username"); }
    private void checkRoleKey(String value) { if (value == null || !value.trim().matches("[A-Za-z][A-Za-z0-9_.-]{1,159}")) throw new IllegalArgumentException("Invalid role key"); }
    private void checkOrder(int value) { if (value < 0 || value > 100000) throw new IllegalArgumentException("Invalid sort order"); }
    private void checkOptional(String value, int maxLength, String field) { if (value != null && (value.length() > maxLength || value.indexOf('\0') >= 0)) throw new IllegalArgumentException("Invalid " + field); }
    private void checkPassword(String password) { if (password == null || password.isBlank() || password.getBytes(StandardCharsets.UTF_8).length > 72) throw new IllegalArgumentException("Password is required and must not exceed 72 bytes"); }
    private void validateRoleIds(Set<Long> roles) { if (roles == null || roles.size() > 100 || roles.stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException("Invalid roles"); }
    private void replaceUserRoles(long userId, Set<Long> roles) { jdbc.update("DELETE FROM user_roles WHERE user_id=?", userId); roles.forEach(role -> jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (?,?)", userId, role)); }
    private void lock() { jdbc.queryForObject("SELECT setting_value FROM platform_settings WHERE setting_key='internal.organization.lock' FOR UPDATE", String.class); }
    private boolean exists(String table, long id) { return !jdbc.queryForList("SELECT id FROM " + table + " WHERE id=?", Long.class, id).isEmpty(); }
    private int count(String sql, long id) { Integer value = jdbc.queryForObject(sql, Integer.class, id); return value == null ? 0 : value; }
    private boolean isPermissionMenu(long id) { return count("SELECT COUNT(*) FROM menus WHERE id=? AND component='system/permissions'", id) > 0; }
    private long next(String table) { return Math.addExact(jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM " + table, Long.class), 1); }
}
