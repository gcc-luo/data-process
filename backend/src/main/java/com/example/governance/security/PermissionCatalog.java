package com.example.governance.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;

/** Code-owned permission registry. Database rows describe these permissions; they do not create authority. */
public final class PermissionCatalog {
    public record Entry(String code, String moduleCode, String moduleName, String actionName, String description, int sortOrder) {}
    private record Route(String method, String pattern, String permission) {}
    private static final AntPathMatcher PATHS = new AntPathMatcher();
    private static final List<Entry> ENTRIES = buildEntries();
    private static final Set<String> CODES = ENTRIES.stream().map(Entry::code).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final List<Route> ROUTES = buildRoutes();

    private PermissionCatalog() {}

    public static List<Entry> entries() { return ENTRIES; }
    public static Set<String> codes() { return CODES; }

    public static String moduleForComponent(String component) {
        if (component == null) return null;
        return switch (component) {
            case "system/departments" -> "system.department";
            case "system/roles" -> "system.role";
            case "system/users" -> "system.user";
            case "system/menus" -> "system.menu";
            case "system/permissions" -> "system.permission";
            case "system/audit" -> "system.audit";
            case "assets", "assets/sources" -> "datasource.source";
            case "assets/datasets" -> "datasource.dataset";
            case "assets/drivers" -> "datasource.driver";
            case "assets/dictionary" -> "datasource.dictionary";
            case "assets/objects" -> "datasource.object";
            case "assets/master-data" -> "datasource.master_data";
            case "query" -> "datasource.query";
            case "processing/tasks" -> "workflow.task";
            case "tasks" -> "workflow.workflow";
            case "realtime" -> "workflow.realtime";
            case "services" -> "service.service";
            case "reports" -> "report.report";
            default -> null;
        };
    }

    public static Set<String> modulesForComponent(String component) {
        return switch (component == null ? "" : component) {
            case "assets" -> Set.of("datasource.source", "datasource.dataset", "datasource.driver", "datasource.dictionary", "datasource.object", "datasource.object_directory", "datasource.master_data");
            case "assets/objects" -> Set.of("datasource.object", "datasource.object_directory");
            case "assets/master-data" -> Set.of("datasource.master_data");
            case "tasks" -> Set.of("workflow.task", "workflow.workflow", "workflow.schedule");
            case "services" -> Set.of("service.service", "service.caller");
            case "reports" -> Set.of("report.report", "report.dashboard");
            default -> {
                String module = moduleForComponent(component);
                yield module == null ? Set.of() : Set.of(module);
            }
        };
    }

    public static String permissionFor(HttpMethod method, String path) {
        if (method == null || path == null) return null;
        return ROUTES.stream().filter(route -> route.method().equals(method.name()) && PATHS.match(route.pattern(), path))
                .map(Route::permission).findFirst().orElse(null);
    }

    private static List<Entry> buildEntries() {
        List<Entry> values = new ArrayList<>();
        add(values, "system.department", "部门管理", "查看", "读取部门列表与详情");
        add(values, "system.department", "部门管理", "新增", "创建部门");
        add(values, "system.department", "部门管理", "修改", "修改部门资料或层级");
        add(values, "system.department", "部门管理", "删除", "删除部门");
        add(values, "system.role", "角色管理", "查看", "读取角色及授权");
        add(values, "system.role", "角色管理", "新增", "创建角色");
        add(values, "system.role", "角色管理", "修改", "修改角色资料");
        add(values, "system.role", "角色管理", "删除", "删除角色");
        add(values, "system.role", "角色管理", "配置权限", "为角色分配操作权限");
        add(values, "system.user", "用户管理", "查看", "读取用户资料");
        add(values, "system.user", "用户管理", "新增", "创建用户");
        add(values, "system.user", "用户管理", "修改", "修改用户资料");
        add(values, "system.user", "用户管理", "删除", "删除用户");
        add(values, "system.user", "用户管理", "分配角色", "调整用户关联的角色");
        add(values, "system.user", "用户管理", "启停账号", "启用或停用用户账号");
        add(values, "system.user", "用户管理", "重置密码", "为用户设置新密码");
        add(values, "system.menu", "菜单管理", "查看", "读取菜单配置");
        add(values, "system.menu", "菜单管理", "新增", "新增菜单项");
        add(values, "system.menu", "菜单管理", "修改", "修改菜单项和图标");
        add(values, "system.menu", "菜单管理", "删除", "删除菜单项");
        add(values, "system.permission", "权限管理", "查看", "查看系统登记的模块与操作权限");
        add(values, "system.audit", "审计记录", "查看", "查询平台审计记录");
        add(values, "datasource.source", "数据源管理", "查看", "读取数据源配置");
        add(values, "datasource.source", "数据源管理", "新增", "创建数据源");
        add(values, "datasource.source", "数据源管理", "修改", "修改数据源配置");
        add(values, "datasource.source", "数据源管理", "删除", "删除数据源");
        add(values, "datasource.source", "数据源管理", "测试连接", "测试数据源连接并读取元数据");
        add(values, "datasource.driver", "驱动管理", "查看", "读取 JDBC 驱动列表");
        add(values, "datasource.driver", "驱动管理", "上传", "上传 JDBC 驱动 jar");
        add(values, "datasource.driver", "驱动管理", "删除", "删除上传的 JDBC 驱动");
        add(values, "datasource.dataset", "数据集管理", "查看", "读取已登记数据集");
        add(values, "datasource.dictionary", "数据字典", "查看", "查看数据字典");
        add(values, "datasource.dictionary", "数据字典", "新增", "新增目录和字典");
        add(values, "datasource.dictionary", "数据字典", "修改", "修改目录和字典");
        add(values, "datasource.dictionary", "数据字典", "删除", "删除目录和字典");
        add(values, "datasource.dictionary", "数据字典", "导入", "导入数据字典");
        add(values, "datasource.object_directory", "数据对象目录", "查看", "查看数据对象目录");
        add(values, "datasource.object_directory", "数据对象目录", "新增", "新增数据对象目录");
        add(values, "datasource.object_directory", "数据对象目录", "修改", "修改数据对象目录");
        add(values, "datasource.object_directory", "数据对象目录", "删除", "删除数据对象目录");
        add(values, "datasource.object", "数据对象", "查看", "查看数据对象");
        add(values, "datasource.object", "数据对象", "注册", "注册物理对象");
        add(values, "datasource.object", "数据对象", "建表", "创建物理表");
        add(values, "datasource.object", "数据对象", "修改", "修改对象信息");
        add(values, "datasource.object", "数据对象", "发布结构", "发布物理表结构");
        add(values, "datasource.object", "数据对象", "删除", "删除对象登记");
        add(values, "datasource.object", "数据对象", "预览", "预览对象数据");
        add(values, "datasource.object", "数据对象", "导出", "导出对象元数据");
        add(values, "datasource.master_data", "主数据", "查看", "查看所有已登记数据对象的记录");
        add(values, "datasource.master_data", "主数据", "新增", "新增主数据记录");
        add(values, "datasource.master_data", "主数据", "修改", "修改主数据记录");
        add(values, "datasource.master_data", "主数据", "删除", "删除主数据记录");
        add(values, "datasource.master_data", "主数据", "导入", "导入主数据记录");
        add(values, "datasource.master_data", "主数据", "导出", "导出主数据记录");
        add(values, "datasource.master_data", "主数据", "查看版本", "查看主数据记录版本历史");
        add(values, "datasource.dataset", "数据集管理", "新增", "登记数据集");
        add(values, "datasource.dataset", "数据集管理", "删除", "删除数据集登记");
        add(values, "datasource.dataset", "数据集管理", "刷新", "刷新数据集元数据");
        add(values, "datasource.dataset", "数据集管理", "配置数据范围", "配置角色的数据集与字段范围");
        add(values, "datasource.query", "数据查询", "查询", "按数据策略读取数据集");
        add(values, "workflow.task", "任务管理", "查看", "读取任务定义与运行记录");
        add(values, "workflow.task", "任务管理", "新增", "创建任务");
        add(values, "workflow.task", "任务管理", "修改", "保存任务草稿");
        add(values, "workflow.task", "任务管理", "发布", "校验并发布任务版本");
        add(values, "workflow.task", "任务管理", "执行", "运行任务");
        add(values, "workflow.task", "任务管理", "重试", "重试失败任务");
        add(values, "workflow.task", "任务管理", "删除", "删除任务");
        add(values, "workflow.workflow", "工作流管理", "查看", "读取工作流及运行记录");
        add(values, "workflow.workflow", "工作流管理", "新增", "创建工作流");
        add(values, "workflow.workflow", "工作流管理", "修改", "保存工作流版本");
        add(values, "workflow.workflow", "工作流管理", "执行", "运行工作流");
        add(values, "workflow.workflow", "工作流管理", "重试", "重试失败工作流");
        add(values, "workflow.workflow", "工作流管理", "校验", "校验工作流定义");
        add(values, "workflow.schedule", "调度管理", "查看", "读取调度配置");
        add(values, "workflow.schedule", "调度管理", "新增", "创建调度");
        add(values, "workflow.schedule", "调度管理", "修改", "启停或修改调度");
        add(values, "workflow.schedule", "调度管理", "补跑", "按日期补跑工作流");
        add(values, "workflow.realtime", "实时同步", "查看", "读取实时同步任务");
        add(values, "workflow.realtime", "实时同步", "新增", "创建实时同步任务");
        add(values, "workflow.realtime", "实时同步", "修改", "修改实时同步配置");
        add(values, "workflow.realtime", "实时同步", "启动", "启动实时同步任务");
        add(values, "workflow.realtime", "实时同步", "停止", "停止实时同步任务");
        add(values, "service.service", "数据服务", "查看", "读取服务定义");
        add(values, "service.service", "数据服务", "新增", "创建数据服务");
        add(values, "service.service", "数据服务", "提交与撤回", "提交或撤回服务发布申请");
        add(values, "service.service", "数据服务", "审批", "审批服务发布申请");
        add(values, "service.caller", "调用方管理", "查看", "读取服务调用方与授权");
        add(values, "service.caller", "调用方管理", "新增", "创建调用方");
        add(values, "service.caller", "调用方管理", "管理授权", "授予或撤销调用方的服务访问权");
        add(values, "service.caller", "调用方管理", "停用", "停用调用方");
        add(values, "report.report", "报表管理", "查看", "读取报表");
        add(values, "report.report", "报表管理", "新增", "创建报表");
        add(values, "report.report", "报表管理", "修改", "修改报表");
        add(values, "report.report", "报表管理", "删除", "删除报表");
        add(values, "report.report", "报表管理", "运行", "运行报表查询");
        add(values, "report.dashboard", "看板管理", "查看", "读取看板");
        add(values, "report.dashboard", "看板管理", "新增", "创建看板");
        add(values, "report.dashboard", "看板管理", "修改", "修改看板");
        add(values, "report.dashboard", "看板管理", "删除", "删除看板");
        return List.copyOf(values);
    }

    private static void add(List<Entry> values, String module, String moduleName, String action, String description) {
        String actionCode = switch (action) {
            case "查看" -> "read"; case "新增" -> "create"; case "修改" -> "update"; case "删除" -> "delete";
            case "配置权限" -> "assign_permissions"; case "分配角色" -> "assign_roles"; case "启停账号" -> "set_status"; case "重置密码" -> "reset_password";
            case "测试连接" -> "test"; case "上传" -> "upload"; case "导入" -> "import"; case "导出" -> "export"; case "注册" -> "register"; case "建表" -> "create_table"; case "发布结构" -> "publish_schema"; case "预览" -> "preview"; case "刷新" -> "refresh"; case "配置数据范围" -> "manage_policy"; case "查询" -> "read";
            case "执行", "运行" -> "execute"; case "重试" -> "retry"; case "校验" -> "validate"; case "发布" -> "publish";
            case "补跑" -> "backfill"; case "启动" -> "start"; case "停止" -> "stop";
            case "提交与撤回" -> "submit"; case "审批" -> "approve"; case "管理授权" -> "manage_grant"; case "停用" -> "disable";
            default -> throw new IllegalArgumentException("Unsupported permission action: " + action);
        };
        String code = module + "." + actionCode;
        values.add(new Entry(code, module, moduleName, action, description, values.size() + 1));
    }

    private static List<Route> buildRoutes() {
        List<Route> routes = new ArrayList<>();
        route(routes, "GET", "/api/organization/audit/catalog", "system.audit.read");
        route(routes, "GET", "/api/organization/audit/export", "system.audit.read");
        route(routes, "GET", "/api/organization/audit", "system.audit.read");
        route(routes, "GET", "/api/organization/online", "system.user.read");
        route(routes, "POST", "/api/organization/online/*/kick", "system.user.set_status");
        route(routes, "GET", "/api/organization/users/export", "system.user.read");
        route(routes, "POST", "/api/organization/users/import", "system.user.create");
        route(routes, "GET", "/api/organization/roles/*/users", "system.role.read");
        route(routes, "POST", "/api/organization/departments", "system.department.create");
        route(routes, "POST", "/api/organization/departments/*/parent", "system.department.update");
        route(routes, "POST", "/api/organization/departments/*", "system.department.update");
        route(routes, "DELETE", "/api/organization/departments/*", "system.department.delete");
        route(routes, "POST", "/api/organization/roles", "system.role.create");
        route(routes, "POST", "/api/organization/roles/*/permissions", "system.role.assign_permissions");
        route(routes, "POST", "/api/organization/roles/*", "system.role.update");
        route(routes, "DELETE", "/api/organization/roles/*", "system.role.delete");
        route(routes, "POST", "/api/organization/users", "system.user.create");
        route(routes, "POST", "/api/organization/users/*/roles", "system.user.assign_roles");
        route(routes, "POST", "/api/organization/users/*/enabled", "system.user.set_status");
        route(routes, "POST", "/api/organization/users/*/password", "system.user.reset_password");
        route(routes, "POST", "/api/organization/users/*", "system.user.update");
        route(routes, "DELETE", "/api/organization/users/*", "system.user.delete");
        route(routes, "POST", "/api/organization/menus", "system.menu.create");
        route(routes, "POST", "/api/organization/menus/*", "system.menu.update");
        route(routes, "DELETE", "/api/organization/menus/*", "system.menu.delete");

        route(routes, "GET", "/api/data-sources/profiles", "datasource.source.read");
        route(routes, "GET", "/api/data-sources", "datasource.source.read");
        route(routes, "POST", "/api/data-sources", "datasource.source.create");
        route(routes, "POST", "/api/data-sources/batch-delete", "datasource.source.delete");
        route(routes, "GET", "/api/data-sources/*/tables", "datasource.source.test");
        route(routes, "GET", "/api/data-sources/*/columns", "datasource.source.test");
        route(routes, "POST", "/api/data-sources/*/test", "datasource.source.test");
        route(routes, "POST", "/api/data-sources/test-config", "datasource.source.test");
        route(routes, "PUT", "/api/data-sources/*", "datasource.source.update");
        route(routes, "DELETE", "/api/data-sources/*", "datasource.source.delete");
        route(routes, "GET", "/api/drivers", "datasource.driver.read");
        route(routes, "GET", "/api/object-directories", "datasource.object_directory.read");
        route(routes, "POST", "/api/object-directories/sort", "datasource.object_directory.update");
        route(routes, "POST", "/api/object-directories", "datasource.object_directory.create");
        route(routes, "PUT", "/api/object-directories/*", "datasource.object_directory.update");
        route(routes, "DELETE", "/api/object-directories/*", "datasource.object_directory.delete");
        route(routes, "GET", "/api/data-objects", "datasource.object.read");
        route(routes, "GET", "/api/data-objects/discovery", "datasource.object.register");
        route(routes, "POST", "/api/data-objects/discovery-fields", "datasource.object.register");
        route(routes, "POST", "/api/data-objects/register", "datasource.object.register");
        route(routes, "GET", "/api/data-objects/export", "datasource.object.export");
        route(routes, "GET", "/api/data-objects/*", "datasource.object.read");
        route(routes, "PUT", "/api/data-objects/*/dataset", "datasource.object.update");
        route(routes, "POST", "/api/data-objects/*/preview", "datasource.object.preview");
        route(routes, "POST", "/api/data-objects/*/schema-preview", "datasource.object.publish_schema");
        route(routes, "GET", "/api/data-object-schema-operations", "datasource.object.publish_schema");
        route(routes, "GET", "/api/data-object-schema-operations/*/actual", "datasource.object.publish_schema");
        route(routes, "POST", "/api/data-object-schema-operations/*/acknowledge", "datasource.object.publish_schema");
        route(routes, "POST", "/api/data-object-schema-operations/*/execute", "datasource.object.publish_schema");
        route(routes, "DELETE", "/api/data-object-schema-operations/*", "datasource.object.publish_schema");
        route(routes, "PUT", "/api/data-objects/*", "datasource.object.update");
        route(routes, "DELETE", "/api/data-objects/*", "datasource.object.delete");
        route(routes, "POST", "/api/data-object-operations/create-preview", "datasource.object.create_table");
        route(routes, "GET", "/api/data-object-operations", "datasource.object.create_table");
        route(routes, "POST", "/api/data-object-operations/*/execute", "datasource.object.create_table");
        route(routes, "POST", "/api/data-object-operations/*/recover", "datasource.object.create_table");
        route(routes, "POST", "/api/data-object-operations/*/resolve-absent", "datasource.object.create_table");
        route(routes, "DELETE", "/api/data-object-operations/*", "datasource.object.create_table");

        route(routes, "GET", "/api/master-data/tree", "datasource.master_data.read");
        route(routes, "GET", "/api/master-data/objects/*", "datasource.master_data.read");
        route(routes, "POST", "/api/master-data/objects/*/query", "datasource.master_data.read");
        route(routes, "POST", "/api/master-data/objects/*/records", "datasource.master_data.create");
        route(routes, "PUT", "/api/master-data/objects/*/records", "datasource.master_data.update");
        route(routes, "DELETE", "/api/master-data/objects/*/records", "datasource.master_data.delete");
        route(routes, "GET", "/api/master-data/objects/*/template", "datasource.master_data.import");
        route(routes, "POST", "/api/master-data/objects/*/import", "datasource.master_data.import");
        route(routes, "GET", "/api/master-data/objects/*/export", "datasource.master_data.export");
        route(routes, "GET", "/api/master-data/objects/*/history", "datasource.master_data.history");
        route(routes, "GET", "/api/master-data/history/*", "datasource.master_data.history");
        route(routes, "GET", "/api/dictionary/**", "datasource.dictionary.read");
        route(routes, "POST", "/api/dictionary/directories/sort", "datasource.dictionary.update");
        route(routes, "POST", "/api/dictionary/import", "datasource.dictionary.import");
        route(routes, "POST", "/api/dictionary/**", "datasource.dictionary.create");
        route(routes, "PUT", "/api/dictionary/**", "datasource.dictionary.update");
        route(routes, "DELETE", "/api/dictionary/**", "datasource.dictionary.delete");
        route(routes, "POST", "/api/drivers", "datasource.driver.upload");
        route(routes, "DELETE", "/api/drivers/*", "datasource.driver.delete");
        route(routes, "GET", "/api/datasets/*/policies", "datasource.dataset.manage_policy");
        route(routes, "POST", "/api/datasets/*/policies", "datasource.dataset.manage_policy");
        route(routes, "DELETE", "/api/datasets/*/policies/*", "datasource.dataset.manage_policy");
        route(routes, "GET", "/api/datasets", "datasource.dataset.read");
        route(routes, "POST", "/api/datasets", "datasource.dataset.create");
        route(routes, "POST", "/api/datasets/*/refresh", "datasource.dataset.refresh");
        route(routes, "DELETE", "/api/datasets/*", "datasource.dataset.delete");
        route(routes, "GET", "/api/accessible-datasets", "datasource.query.read");
        route(routes, "POST", "/api/datasets/*/preview", "datasource.query.read");
        route(routes, "POST", "/api/datasets/*/analysis", "datasource.query.read");

        route(routes, "POST", "/api/workflows/validate", "workflow.workflow.validate");
        route(routes, "GET", "/api/task-directories", "workflow.task.read");
        route(routes, "POST", "/api/task-directories", "workflow.task.create");
        route(routes, "POST", "/api/task-directories/sort", "workflow.task.update");
        route(routes, "PUT", "/api/task-directories/*", "workflow.task.update");
        route(routes, "DELETE", "/api/task-directories/*", "workflow.task.delete");
        route(routes, "GET", "/api/orch/components", "workflow.task.read");
        route(routes, "GET", "/api/tasks", "workflow.task.read");
        route(routes, "POST", "/api/tasks", "workflow.task.create");
        route(routes, "GET", "/api/tasks/*/draft", "workflow.task.read");
        route(routes, "PUT", "/api/tasks/*/draft", "workflow.task.update");
        route(routes, "POST", "/api/tasks/*/validate", "workflow.task.update");
        route(routes, "POST", "/api/tasks/*/publish", "workflow.task.publish");
        route(routes, "GET", "/api/tasks/*/versions", "workflow.task.read");
        route(routes, "GET", "/api/tasks/*/versions/*", "workflow.task.read");
        route(routes, "POST", "/api/tasks/*/copy", "workflow.task.create");
        route(routes, "PUT", "/api/tasks/*", "workflow.task.update");
        route(routes, "DELETE", "/api/tasks/*", "workflow.task.delete");
        route(routes, "POST", "/api/tasks/*/runs", "workflow.task.execute");
        route(routes, "GET", "/api/tasks/*/runs", "workflow.task.read");
        route(routes, "POST", "/api/tasks/*/runs/*/retry", "workflow.task.retry");
        route(routes, "POST", "/api/tasks/*/stop", "workflow.task.execute");
        route(routes, "GET", "/api/tasks/*/schedule", "workflow.task.read");
        route(routes, "PUT", "/api/tasks/*/schedule", "workflow.task.update");
        route(routes, "DELETE", "/api/tasks/*/schedule", "workflow.task.update");
        route(routes, "POST", "/api/orch/test-run", "workflow.task.execute");
        route(routes, "POST", "/api/orch/query-preview", "workflow.task.execute");
        route(routes, "POST", "/api/orch/schema", "workflow.task.read");
        route(routes, "POST", "/api/orch/validate", "workflow.task.update");
        route(routes, "GET", "/api/task-runs/*/logs", "workflow.task.read");
        route(routes, "GET", "/api/task-runs/*/nodes/*/preview", "workflow.task.read");
        route(routes, "GET", "/api/task-runs/*", "workflow.task.read");
        route(routes, "GET", "/api/workflows", "workflow.workflow.read");
        route(routes, "POST", "/api/workflows", "workflow.workflow.create");
        route(routes, "POST", "/api/workflows/*/versions", "workflow.workflow.update");
        route(routes, "GET", "/api/workflows/*/versions/*", "workflow.workflow.read");
        route(routes, "POST", "/api/workflows/*/runs", "workflow.workflow.execute");
        route(routes, "GET", "/api/workflows/*/runs", "workflow.workflow.read");
        route(routes, "POST", "/api/workflows/*/schedules", "workflow.schedule.create");
        route(routes, "POST", "/api/workflows/*/backfill", "workflow.schedule.backfill");
        route(routes, "GET", "/api/schedules", "workflow.schedule.read");
        route(routes, "POST", "/api/schedules/*/enabled", "workflow.schedule.update");
        route(routes, "GET", "/api/runs/*", "workflow.workflow.read");
        route(routes, "POST", "/api/runs/*/retry", "workflow.workflow.retry");

        route(routes, "GET", "/api/realtime-jobs", "workflow.realtime.read");
        route(routes, "POST", "/api/realtime-jobs", "workflow.realtime.create");
        route(routes, "PUT", "/api/realtime-jobs/*/settings", "workflow.realtime.update");
        route(routes, "POST", "/api/realtime-jobs/*/start", "workflow.realtime.start");
        route(routes, "POST", "/api/realtime-jobs/*/stop", "workflow.realtime.stop");

        route(routes, "GET", "/api/services", "service.service.read");
        route(routes, "POST", "/api/services", "service.service.create");
        route(routes, "POST", "/api/services/*/submit", "service.service.submit");
        route(routes, "POST", "/api/services/*/withdraw", "service.service.submit");
        route(routes, "POST", "/api/services/*/approve", "service.service.approve");
        route(routes, "GET", "/api/service-callers", "service.caller.read");
        route(routes, "POST", "/api/service-callers", "service.caller.create");
        route(routes, "POST", "/api/service-callers/*/disable", "service.caller.disable");
        route(routes, "GET", "/api/service-grants", "service.caller.read");
        route(routes, "GET", "/api/service-usage", "service.service.read");
        route(routes, "POST", "/api/services/*/callers/*/revoke", "service.caller.manage_grant");
        route(routes, "POST", "/api/services/*/callers/*", "service.caller.manage_grant");

        route(routes, "GET", "/api/reports", "report.report.read");
        route(routes, "POST", "/api/reports", "report.report.create");
        route(routes, "PUT", "/api/reports/*", "report.report.update");
        route(routes, "DELETE", "/api/reports/*", "report.report.delete");
        route(routes, "POST", "/api/reports/*/run", "report.report.execute");
        route(routes, "GET", "/api/dashboards", "report.dashboard.read");
        route(routes, "POST", "/api/dashboards", "report.dashboard.create");
        route(routes, "PUT", "/api/dashboards/*", "report.dashboard.update");
        route(routes, "DELETE", "/api/dashboards/*", "report.dashboard.delete");
        return List.copyOf(routes);
    }

    private static void route(List<Route> routes, String method, String pattern, String permission) {
        if (!CODES.contains(permission)) throw new IllegalStateException("Unregistered API permission: " + permission);
        routes.add(new Route(method, pattern, permission));
    }
}
