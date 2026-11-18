CREATE TABLE permission_catalog (
    permission_code VARCHAR(160) PRIMARY KEY,
    module_code VARCHAR(120) NOT NULL,
    module_name VARCHAR(120) NOT NULL,
    action_code VARCHAR(80) NOT NULL,
    action_name VARCHAR(120) NOT NULL,
    description VARCHAR(255) NOT NULL,
    sort_order INTEGER NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE KEY permission_catalog_module_action_unique (module_code, action_code)
);

INSERT INTO permission_catalog (permission_code,module_code,module_name,action_code,action_name,description,sort_order) VALUES
('system.department.read','system.department','部门管理','read','查看','读取部门列表与详情',1),
('system.department.create','system.department','部门管理','create','新增','创建部门',2),
('system.department.update','system.department','部门管理','update','修改','修改部门资料或层级',3),
('system.department.delete','system.department','部门管理','delete','删除','删除部门',4),
('system.role.read','system.role','角色管理','read','查看','读取角色及授权',5),
('system.role.create','system.role','角色管理','create','新增','创建角色',6),
('system.role.update','system.role','角色管理','update','修改','修改角色资料',7),
('system.role.delete','system.role','角色管理','delete','删除','删除角色',8),
('system.role.assign_permissions','system.role','角色管理','assign_permissions','配置权限','为角色分配操作权限',9),
('system.user.read','system.user','用户管理','read','查看','读取用户资料',10),
('system.user.create','system.user','用户管理','create','新增','创建用户',11),
('system.user.update','system.user','用户管理','update','修改','修改用户资料',12),
('system.user.delete','system.user','用户管理','delete','删除','删除用户',13),
('system.user.assign_roles','system.user','用户管理','assign_roles','分配角色','调整用户关联的角色',14),
('system.user.set_status','system.user','用户管理','set_status','启停账号','启用或停用用户账号',15),
('system.user.reset_password','system.user','用户管理','reset_password','重置密码','为用户设置新密码',16),
('system.menu.read','system.menu','菜单管理','read','查看','读取菜单配置',17),
('system.menu.create','system.menu','菜单管理','create','新增','新增菜单项',18),
('system.menu.update','system.menu','菜单管理','update','修改','修改菜单项和图标',19),
('system.menu.delete','system.menu','菜单管理','delete','删除','删除菜单项',20),
('system.permission.read','system.permission','权限管理','read','查看','查看系统登记的模块与操作权限',21),
('system.audit.read','system.audit','审计记录','read','查看','查询平台审计记录',22),
('datasource.source.read','datasource.source','数据源管理','read','查看','读取数据源配置',23),
('datasource.source.create','datasource.source','数据源管理','create','新增','创建数据源',24),
('datasource.source.update','datasource.source','数据源管理','update','修改','修改数据源配置',25),
('datasource.source.delete','datasource.source','数据源管理','delete','删除','删除数据源',26),
('datasource.source.test','datasource.source','数据源管理','test','测试连接','测试数据源连接并读取元数据',27),
('datasource.dataset.read','datasource.dataset','数据集管理','read','查看','读取已登记数据集',28),
('datasource.dataset.create','datasource.dataset','数据集管理','create','新增','登记数据集',29),
('datasource.dataset.delete','datasource.dataset','数据集管理','delete','删除','删除数据集登记',30),
('datasource.dataset.refresh','datasource.dataset','数据集管理','refresh','刷新','刷新数据集元数据',31),
('datasource.dataset.manage_policy','datasource.dataset','数据集管理','manage_policy','配置数据范围','配置角色的数据集与字段范围',32),
('datasource.query.read','datasource.query','数据查询','read','查询','按数据策略读取数据集',33),
('workflow.task.read','workflow.task','任务管理','read','查看','读取任务定义与运行记录',34),
('workflow.task.create','workflow.task','任务管理','create','新增','创建任务',35),
('workflow.task.update','workflow.task','任务管理','update','修改','保存任务版本',36),
('workflow.task.execute','workflow.task','任务管理','execute','执行','运行任务',37),
('workflow.task.retry','workflow.task','任务管理','retry','重试','重试失败任务',38),
('workflow.workflow.read','workflow.workflow','工作流管理','read','查看','读取工作流及运行记录',39),
('workflow.workflow.create','workflow.workflow','工作流管理','create','新增','创建工作流',40),
('workflow.workflow.update','workflow.workflow','工作流管理','update','修改','保存工作流版本',41),
('workflow.workflow.execute','workflow.workflow','工作流管理','execute','执行','运行工作流',42),
('workflow.workflow.retry','workflow.workflow','工作流管理','retry','重试','重试失败工作流',43),
('workflow.workflow.validate','workflow.workflow','工作流管理','validate','校验','校验工作流定义',44),
('workflow.schedule.read','workflow.schedule','调度管理','read','查看','读取调度配置',45),
('workflow.schedule.create','workflow.schedule','调度管理','create','新增','创建调度',46),
('workflow.schedule.update','workflow.schedule','调度管理','update','修改','启停或修改调度',47),
('workflow.schedule.backfill','workflow.schedule','调度管理','backfill','补跑','按日期补跑工作流',48),
('workflow.realtime.read','workflow.realtime','实时同步','read','查看','读取实时同步任务',49),
('workflow.realtime.create','workflow.realtime','实时同步','create','新增','创建实时同步任务',50),
('workflow.realtime.update','workflow.realtime','实时同步','update','修改','修改实时同步配置',51),
('workflow.realtime.start','workflow.realtime','实时同步','start','启动','启动实时同步任务',52),
('workflow.realtime.stop','workflow.realtime','实时同步','stop','停止','停止实时同步任务',53),
('service.service.read','service.service','数据服务','read','查看','读取服务定义',54),
('service.service.create','service.service','数据服务','create','新增','创建数据服务',55),
('service.service.submit','service.service','数据服务','submit','提交与撤回','提交或撤回服务发布申请',56),
('service.service.approve','service.service','数据服务','approve','审批','审批服务发布申请',57),
('service.caller.read','service.caller','调用方管理','read','查看','读取服务调用方与授权',58),
('service.caller.create','service.caller','调用方管理','create','新增','创建调用方',59),
('service.caller.manage_grant','service.caller','调用方管理','manage_grant','管理授权','授予或撤销调用方的服务访问权',60),
('service.caller.disable','service.caller','调用方管理','disable','停用','停用调用方',61),
('report.report.read','report.report','报表管理','read','查看','读取报表',62),
('report.report.create','report.report','报表管理','create','新增','创建报表',63),
('report.report.update','report.report','报表管理','update','修改','修改报表',64),
('report.report.delete','report.report','报表管理','delete','删除','删除报表',65),
('report.report.execute','report.report','报表管理','execute','运行','运行报表查询',66),
('report.dashboard.read','report.dashboard','看板管理','read','查看','读取看板',67),
('report.dashboard.create','report.dashboard','看板管理','create','新增','创建看板',68),
('report.dashboard.update','report.dashboard','看板管理','update','修改','修改看板',69),
('report.dashboard.delete','report.dashboard','看板管理','delete','删除','删除看板',70);

INSERT IGNORE INTO role_permissions (role_id, permission)
SELECT legacy.role_id, catalog.permission_code
FROM role_permissions legacy
JOIN permission_catalog catalog ON
    (legacy.permission='organization.manage' AND catalog.module_code LIKE 'system.%') OR
    (legacy.permission='datasource.manage' AND catalog.module_code IN ('datasource.source','datasource.dataset')) OR
    (legacy.permission='dataset.read' AND catalog.permission_code='datasource.query.read') OR
    (legacy.permission='workflow.validate' AND catalog.permission_code='workflow.workflow.validate') OR
    (legacy.permission='workflow.manage' AND catalog.module_code LIKE 'workflow.%') OR
    (legacy.permission='service.manage' AND catalog.module_code LIKE 'service.%') OR
    (legacy.permission='report.manage' AND catalog.module_code LIKE 'report.%');

DELETE FROM role_permissions WHERE permission IN ('organization.manage','datasource.manage','dataset.read','workflow.validate','workflow.manage','service.manage','report.manage');

INSERT IGNORE INTO role_permissions (role_id, permission)
SELECT 1, permission_code FROM permission_catalog;

SET @permission_menu_id = (SELECT COALESCE(MAX(id),95) + 1 FROM menus);
INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order,visible,enabled)
VALUES (@permission_menu_id,9,'权限管理','M','/system/permissions','system/permissions','','shield',60,TRUE,TRUE);
UPDATE menus SET permission='' WHERE id=9 OR parent_id=9;
DELETE FROM role_menus;
