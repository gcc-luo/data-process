package com.example.governance.organization;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Organization and menu mutations are serialized for the single-node deployment. */
@Service
public class OrganizationService {
    private static final Set<String> PERMISSIONS = Set.of("organization.manage", "datasource.manage", "dataset.read", "workflow.validate", "workflow.manage", "service.manage", "report.manage");
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
        if (permissions == null || permissions.size() > PERMISSIONS.size() || permissions.stream().anyMatch(permission -> permission == null || !PERMISSIONS.contains(permission))) throw new IllegalArgumentException("Invalid permission");
        if (roleId == DEFAULT_ADMIN_ROLE_ID && !permissions.contains("organization.manage")) throw new IllegalStateException("默认管理员必须保留系统管理权限");
        tx.executeWithoutResult(status -> {
            lock(); if (!exists("roles", roleId)) throw new IllegalArgumentException("Unknown role");
            jdbc.update("DELETE FROM role_permissions WHERE role_id=?", roleId);
            permissions.forEach(permission -> jdbc.update("INSERT INTO role_permissions(role_id,permission) VALUES (?,?)", roleId, permission));
        });
    }

    public void setRoleMenus(long roleId, Set<Long> menuIds) {
        if (menuIds == null || menuIds.size() > 500 || menuIds.stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException("Invalid menu selection");
        if (roleId == DEFAULT_ADMIN_ROLE_ID && !menuIds.containsAll(Set.of(SYSTEM_MENU_ID, MENU_ADMIN_ID))) throw new IllegalStateException("默认管理员必须保留系统管理和菜单管理入口");
        tx.executeWithoutResult(status -> {
            lock(); if (!exists("roles", roleId) || menuIds.stream().anyMatch(id -> !exists("menus", id))) throw new IllegalArgumentException("Unknown role or menu");
            jdbc.update("DELETE FROM role_menus WHERE role_id=?", roleId);
            menuIds.forEach(menu -> jdbc.update("INSERT INTO role_menus(role_id,menu_id) VALUES (?,?)", roleId, menu));
        });
    }

    public long createUser(String username, String password, long department) {
        return createUser(username, username, null, null, password, department, true, Set.of());
    }

    public long createUser(String username, String nickname, String email, String phone, String password, long department, boolean enabled, Set<Long> roles) {
        checkUsername(username); checkOptional(nickname, 160, "nickname"); checkOptional(email, 160, "email"); checkOptional(phone, 48, "phone");
        checkPassword(password);
        validateRoleIds(roles);
        String hash = encoder.encode(password);
        return tx.execute(status -> {
            lock(); if (!exists("departments", department)) throw new IllegalArgumentException("Unknown department");
            if (count("SELECT COUNT(*) FROM departments WHERE id=? AND enabled=TRUE", department) == 0) throw new IllegalArgumentException("Cannot assign a user to a disabled department");
            if (roles.stream().anyMatch(role -> !exists("roles", role))) throw new IllegalArgumentException("Unknown role");
            long id = next("users");
            jdbc.update("INSERT INTO users(id,username,nickname,email,phone,password_hash,department_id,enabled,deleted,security_version) VALUES (?,?,?,?,?,?,?,?,FALSE,?)", id, username.trim(), defaultNickname(username, nickname), blankToNull(email), blankToNull(phone), hash, department, enabled, UUID.randomUUID().toString());
            replaceUserRoles(id, roles);
            return id;
        });
    }

    public void updateUser(long id, String username, String nickname, String email, String phone, long department, boolean enabled, Set<Long> roles) {
        checkUsername(username); checkOptional(nickname, 160, "nickname"); checkOptional(email, 160, "email"); checkOptional(phone, 48, "phone"); validateRoleIds(roles);
        if (id == 1 && (!enabled || !roles.contains(DEFAULT_ADMIN_ROLE_ID))) throw new IllegalStateException("默认管理员账号必须保持启用并保留管理员角色");
        tx.executeWithoutResult(status -> {
            lock();
            List<Long> currentDepartments = jdbc.queryForList("SELECT department_id FROM users WHERE id=? AND deleted=FALSE", Long.class, id);
            if (currentDepartments.isEmpty()) throw new IllegalArgumentException("Unknown user");
            if (!exists("departments", department) || roles.stream().anyMatch(role -> !exists("roles", role))) throw new IllegalArgumentException("Unknown department or role");
            if (!currentDepartments.getFirst().equals(department) && count("SELECT COUNT(*) FROM departments WHERE id=? AND enabled=TRUE", department) == 0) throw new IllegalArgumentException("Cannot assign a user to a disabled department");
            int changed = jdbc.update("UPDATE users SET username=?,nickname=?,email=?,phone=?,department_id=?,enabled=? WHERE id=? AND deleted=FALSE", username.trim(), defaultNickname(username, nickname), blankToNull(email), blankToNull(phone), department, enabled, id);
            if (changed != 1) throw new IllegalArgumentException("Unknown user");
            replaceUserRoles(id, roles);
            if (!enabled) jdbc.update("UPDATE service_callers SET enabled=FALSE WHERE user_id=?", id);
        });
    }

    public void setRoles(long userId, Set<Long> roles) {
        validateRoleIds(roles);
        if (userId == 1 && !roles.contains(DEFAULT_ADMIN_ROLE_ID)) throw new IllegalStateException("默认管理员账号必须保留管理员角色");
        tx.executeWithoutResult(status -> {
            lock(); if (!exists("users", userId) || count("SELECT COUNT(*) FROM users WHERE id=? AND deleted=FALSE", userId) == 0 || roles.stream().anyMatch(id -> !exists("roles", id))) throw new IllegalArgumentException("Unknown user or role");
            replaceUserRoles(userId, roles);
        });
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

    public long createMenu(String name, Long parentId, String menuType, int sortOrder, String routePath, String component, String permission, String icon, boolean visible, boolean enabled) {
        validateMenu(name, parentId, menuType, sortOrder, routePath, component, permission, icon, null);
        return tx.execute(status -> {
            lock(); validateMenuParent(parentId, null); validateMenuPlacement(menuType, parentId, component, null); ensureRouteAvailable(routePath, menuType, null);
            long id = next("menus");
            insertMenu(id, name, parentId, menuType, sortOrder, routePath, component, permission, icon, visible, enabled);
            return id;
        });
    }

    public void updateMenu(long id, String name, Long parentId, String menuType, int sortOrder, String routePath, String component, String permission, String icon, boolean visible, boolean enabled) {
        validateMenu(name, parentId, menuType, sortOrder, routePath, component, permission, icon, id);
        if (id == SYSTEM_MENU_ID && (parentId != null || !"D".equals(menuType) || !"system".equals(component) || !"organization.manage".equals(permission) || !visible || !enabled)) throw new IllegalStateException("系统管理目录需要保持顶级、可见和启用");
        if (id == MENU_ADMIN_ID && (!Long.valueOf(SYSTEM_MENU_ID).equals(parentId) || !"M".equals(menuType) || !"system/menus".equals(component) || !"organization.manage".equals(permission) || !visible || !enabled)) throw new IllegalStateException("菜单管理入口需要保留在系统管理下并保持可见和启用");
        tx.executeWithoutResult(status -> {
            lock(); if (!exists("menus", id)) throw new IllegalArgumentException("Unknown menu");
            validateMenuParent(parentId, id); validateMenuPlacement(menuType, parentId, component, id); ensureRouteAvailable(routePath, menuType, id);
            int changed = jdbc.update("UPDATE menus SET name=?,parent_id=?,menu_type=?,sort_order=?,route_path=?,component=?,permission=?,icon=?,visible=?,enabled=? WHERE id=?", name.trim(), parentId, menuType, sortOrder, normalizedRoute(routePath, menuType), safe(component), safe(permission), safe(icon), visible, enabled, id);
            if (changed != 1) throw new IllegalArgumentException("Unknown menu");
        });
    }

    public void deleteMenu(long id) {
        tx.executeWithoutResult(status -> {
            lock(); if (!exists("menus", id)) throw new IllegalArgumentException("Unknown menu");
            if (id == MENU_ADMIN_ID) throw new IllegalStateException("菜单管理入口不能删除");
            if (count("SELECT COUNT(*) FROM menus WHERE parent_id=?", id) > 0) throw new IllegalStateException("请先删除或移动该菜单下的子菜单");
            jdbc.update("DELETE FROM role_menus WHERE menu_id=?", id);
            jdbc.update("DELETE FROM menus WHERE id=?", id);
        });
    }

    public record Department(long id, String name, Long parentId, int sortOrder, String leader, String phone, String email, boolean enabled) {}
    public record Role(long id, String name, String roleKey, String description, int sortOrder, boolean enabled, Set<String> permissions, Set<Long> menuIds) {}
    public record User(long id, String username, String nickname, String email, String phone, long departmentId, boolean enabled, Set<Long> roles) {}
    public record Menu(long id, Long parentId, String name, String menuType, String routePath, String component, String permission, String icon, int sortOrder, boolean visible, boolean enabled) {}
    public record Snapshot(List<Department> departments, List<Role> roles, List<User> users, List<Menu> menus) {}

    public Snapshot snapshot() {
        List<Department> departments = jdbc.query("SELECT id,name,parent_id,sort_order,leader,phone,email,enabled FROM departments ORDER BY sort_order,id", (rs, row) -> department(rs));
        List<Role> baseRoles = jdbc.query("SELECT id,name,role_key,description,sort_order,enabled FROM roles ORDER BY sort_order,id", (rs, row) -> new Role(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getBoolean(6), Set.of(), Set.of()));
        List<Role> roles = baseRoles.stream().map(r -> new Role(r.id(), r.name(), r.roleKey(), r.description(), r.sortOrder(), r.enabled(), Set.copyOf(jdbc.queryForList("SELECT permission FROM role_permissions WHERE role_id=?", String.class, r.id())), Set.copyOf(jdbc.queryForList("SELECT menu_id FROM role_menus WHERE role_id=?", Long.class, r.id())))).toList();
        List<User> baseUsers = jdbc.query("SELECT id,username,nickname,email,phone,department_id,enabled FROM users WHERE deleted=FALSE ORDER BY id", (rs, row) -> new User(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6), rs.getBoolean(7), Set.of()));
        List<User> users = baseUsers.stream().map(u -> new User(u.id(), u.username(), u.nickname(), u.email(), u.phone(), u.departmentId(), u.enabled(), Set.copyOf(jdbc.queryForList("SELECT role_id FROM user_roles WHERE user_id=?", Long.class, u.id())))).toList();
        return new Snapshot(departments, roles, users, allMenus());
    }

    /** Returns visible menu nodes assigned to the user's enabled roles, including required ancestors. */
    public List<Menu> navigation(String username) {
        List<Menu> all = allMenus();
        Set<Long> permitted = new HashSet<>(jdbc.queryForList("SELECT DISTINCT m.id FROM menus m JOIN role_menus rm ON rm.menu_id=m.id JOIN roles r ON r.id=rm.role_id AND r.enabled=TRUE JOIN user_roles ur ON ur.role_id=r.id JOIN users u ON u.id=ur.user_id WHERE u.username=? AND u.enabled=TRUE AND u.deleted=FALSE AND m.enabled=TRUE AND m.visible=TRUE AND m.menu_type<>'F' AND (m.permission='' OR EXISTS (SELECT 1 FROM role_permissions rp JOIN user_roles ur2 ON ur2.role_id=rp.role_id JOIN roles r2 ON r2.id=ur2.role_id AND r2.enabled=TRUE WHERE ur2.user_id=u.id AND rp.permission=m.permission))", Long.class, username));
        Map<Long, Menu> byId = new HashMap<>(); all.forEach(menu -> byId.put(menu.id(), menu));
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
        return jdbc.query("SELECT id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order,visible,enabled FROM menus ORDER BY sort_order,id", (rs, row) -> menu(rs));
    }

    private Department department(java.sql.ResultSet rs) throws java.sql.SQLException {
        long parent = rs.getLong("parent_id"); Long parentId = rs.wasNull() ? null : parent;
        return new Department(rs.getLong("id"), rs.getString("name"), parentId, rs.getInt("sort_order"), rs.getString("leader"), rs.getString("phone"), rs.getString("email"), rs.getBoolean("enabled"));
    }

    private Menu menu(java.sql.ResultSet rs) throws java.sql.SQLException {
        long parent = rs.getLong("parent_id"); Long parentId = rs.wasNull() ? null : parent;
        return new Menu(rs.getLong("id"), parentId, rs.getString("name"), rs.getString("menu_type"), rs.getString("route_path"), rs.getString("component"), rs.getString("permission"), rs.getString("icon"), rs.getInt("sort_order"), rs.getBoolean("visible"), rs.getBoolean("enabled"));
    }

    private void validateMenu(String name, Long parentId, String type, int order, String path, String component, String permission, String icon, Long id) {
        checkName(name); checkOrder(order); checkOptional(path, 255, "route path"); checkOptional(component, 160, "component"); checkOptional(permission, 160, "permission"); checkOptional(icon, 80, "icon");
        if (type == null || !Set.of("D", "M", "F").contains(type)) throw new IllegalArgumentException("Invalid menu type");
        String normalizedPath = normalizedRoute(path, type);
        if ("M".equals(type) && normalizedPath.isBlank()) throw new IllegalArgumentException("Page route is required");
        if ("M".equals(type) && !normalizedPath.startsWith("/")) throw new IllegalArgumentException("Page route must start with /");
        if ("M".equals(type) && (component == null || component.isBlank())) throw new IllegalArgumentException("Page component is required");
        if (!"D".equals(type) && (permission == null || permission.isBlank())) throw new IllegalArgumentException("Permission is required for menu and button entries");
        if (permission != null && !permission.isBlank() && !PERMISSIONS.contains(permission.trim())) throw new IllegalArgumentException("Unknown permission");
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
        if ("M".equals(type) && !page.startsWith("system/") && parent != null) throw new IllegalArgumentException("平台主页面必须放在顶级菜单下");
    }

    private void ensureRouteAvailable(String path, String type, Long id) {
        if (!"M".equals(type)) return;
        String normalized = normalizedRoute(path, type);
        Integer matches = id == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM menus WHERE menu_type='M' AND route_path=?", Integer.class, normalized)
                : jdbc.queryForObject("SELECT COUNT(*) FROM menus WHERE menu_type='M' AND route_path=? AND id<>?", Integer.class, normalized, id);
        if (matches != null && matches > 0) throw new IllegalArgumentException("Route is already used by another menu");
    }

    private void insertMenu(long id, String name, Long parent, String type, int order, String path, String component, String permission, String icon, boolean visible, boolean enabled) {
        jdbc.update("INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order,visible,enabled) VALUES (?,?,?,?,?,?,?,?,?,?,?)", id, parent, name.trim(), type, normalizedRoute(path, type), safe(component), safe(permission), safe(icon), order, visible, enabled);
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
    private long next(String table) { return Math.addExact(jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM " + table, Long.class), 1); }
}
