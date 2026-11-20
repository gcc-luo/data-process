-- Register workflow.task.delete for task directory removal (reuses the task module grant).
INSERT INTO permission_catalog (permission_code,module_code,module_name,action_code,action_name,description,sort_order)
SELECT 'workflow.task.delete','workflow.task','任务管理','delete','删除','删除任务',COALESCE(MAX(sort_order),0)+1 FROM permission_catalog;
INSERT INTO role_permissions (role_id, permission)
SELECT 1, catalog.permission_code FROM permission_catalog catalog
WHERE EXISTS (SELECT 1 FROM roles WHERE id=1)
  AND NOT EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id=1 AND rp.permission=catalog.permission_code);
