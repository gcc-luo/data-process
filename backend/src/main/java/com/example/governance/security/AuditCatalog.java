package com.example.governance.security;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Code-owned audit display registry: rows keep stable codes, Chinese labels are centralized here. */
public final class AuditCatalog {
    public record Entry(String code, String label) {}

    private static final Map<String, String> ACTIONS = Map.ofEntries(
            Map.entry("auth.login", "登录"),
            Map.entry("department.create", "新增部门"), Map.entry("department.update", "修改部门"), Map.entry("department.move", "移动部门"), Map.entry("department.delete", "删除部门"),
            Map.entry("role.create", "新增角色"), Map.entry("role.update", "修改角色"), Map.entry("role.delete", "删除角色"), Map.entry("role.permissions", "配置角色权限"), Map.entry("role.menus", "配置角色菜单"),
            Map.entry("user.create", "新增用户"), Map.entry("user.update", "修改用户"), Map.entry("user.delete", "删除用户"), Map.entry("user.roles", "分配用户角色"), Map.entry("user.enabled", "启停用户账号"), Map.entry("user.password", "重置用户密码"), Map.entry("user.password.self", "修改本人密码"), Map.entry("user.kick", "踢出在线会话"),
            Map.entry("menu.create", "新增菜单"), Map.entry("menu.update", "修改菜单"), Map.entry("menu.delete", "删除菜单"), Map.entry("menu.reorder", "菜单排序"),
            Map.entry("datasource.create", "新增数据源"), Map.entry("datasource.update", "修改数据源"), Map.entry("datasource.delete", "删除数据源"),
            Map.entry("driver.upload", "上传驱动"), Map.entry("driver.delete", "删除驱动"),
            Map.entry("dictionary.directory.create", "新增字典目录"), Map.entry("dictionary.directory.update", "修改字典目录"), Map.entry("dictionary.directory.delete", "删除字典目录"),
            Map.entry("dictionary.create", "新增数据字典"), Map.entry("dictionary.update", "修改数据字典"), Map.entry("dictionary.delete", "删除数据字典"), Map.entry("dictionary.import", "导入数据字典"),
            Map.entry("dataset.create", "登记数据集"), Map.entry("dataset.delete", "删除数据集"), Map.entry("dataset.refresh", "刷新数据集"), Map.entry("dataset.policy", "配置数据范围"), Map.entry("dataset.policy.revoke", "撤销数据范围"),
            Map.entry("task.create", "新增任务"), Map.entry("task.update", "修改任务信息"), Map.entry("task.draft", "保存任务草稿"), Map.entry("task.publish", "发布任务"), Map.entry("task.version", "保存任务版本"), Map.entry("task.copy", "复制任务"), Map.entry("task.delete", "删除任务"), Map.entry("task.run", "执行任务"), Map.entry("task.stop", "停止任务"), Map.entry("task.retry", "重试任务"), Map.entry("task.schedule", "配置任务定时"), Map.entry("task.schedule.delete", "删除任务定时"), Map.entry("task.schedule.fire", "任务调度触发"),
            Map.entry("directory.create", "新增任务目录"), Map.entry("directory.update", "修改任务目录"), Map.entry("directory.delete", "删除任务目录"), Map.entry("directory.sort", "任务目录排序"),
            Map.entry("workflow.create", "新增工作流"), Map.entry("workflow.version", "保存工作流版本"), Map.entry("workflow.run", "执行工作流"), Map.entry("workflow.retry", "重试工作流"), Map.entry("workflow.backfill", "补跑工作流"), Map.entry("workflow.schedule.fire", "调度触发"),
            Map.entry("schedule.create", "新增调度"),
            Map.entry("realtime.create", "新增实时同步"), Map.entry("realtime.settings", "修改实时同步配置"), Map.entry("realtime.start", "启动实时同步"), Map.entry("realtime.stop", "停止实时同步"),
            Map.entry("service.create", "新增数据服务"), Map.entry("service.submit", "提交发布申请"), Map.entry("service.withdraw", "撤回发布申请"), Map.entry("service.approve", "审批发布申请"), Map.entry("service.grant", "授予调用方访问"), Map.entry("service.revoke", "撤销调用方访问"), Map.entry("service.caller.create", "新增调用方"), Map.entry("service.caller.disable", "停用调用方"),
            Map.entry("report.create", "新增报表"), Map.entry("report.update", "修改报表"), Map.entry("report.delete", "删除报表"),
            Map.entry("dashboard.create", "新增看板"), Map.entry("dashboard.update", "修改看板"), Map.entry("dashboard.delete", "删除看板"),
            Map.entry("audit.purge", "审计清理"));

    private static final Map<String, String> OBJECT_TYPES = Map.ofEntries(
            Map.entry("user", "用户"), Map.entry("account", "账号"), Map.entry("department", "部门"), Map.entry("role", "角色"), Map.entry("menu", "菜单"), Map.entry("dictionary", "数据字典"), Map.entry("dictionary_directory", "字典目录"),
            Map.entry("datasource", "数据源"), Map.entry("driver", "JDBC 驱动"), Map.entry("dataset", "数据集"), Map.entry("task", "任务"), Map.entry("task_directory", "任务目录"), Map.entry("task_run", "任务运行"), Map.entry("workflow", "工作流"), Map.entry("run", "运行实例"),
            Map.entry("schedule", "调度"), Map.entry("realtime", "实时同步"), Map.entry("service", "数据服务"), Map.entry("caller", "调用方"), Map.entry("report", "报表"), Map.entry("dashboard", "看板"));

    private static final Map<String, String> RESULTS = Map.of("SUCCESS", "成功", "FAILURE", "失败");

    private AuditCatalog() {}

    public static String actionLabel(String code) { return ACTIONS.getOrDefault(code, code); }
    public static String objectTypeLabel(String code) { return OBJECT_TYPES.getOrDefault(code, code); }
    public static String resultLabel(String code) { return RESULTS.getOrDefault(code, code); }

    public static List<Entry> actions() { return entries(ACTIONS); }
    public static List<Entry> objectTypes() { return entries(OBJECT_TYPES); }
    public static List<Entry> results() { return entries(RESULTS); }

    private static List<Entry> entries(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> new Entry(entry.getKey(), entry.getValue())).sorted(Comparator.comparing(Entry::code)).toList();
    }
}
