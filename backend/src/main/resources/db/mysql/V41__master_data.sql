-- Main-data record history is kept independently of object registration so deleting an object does not erase its audit trail.
CREATE TABLE master_data_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    object_id BIGINT NOT NULL,
    source_id BIGINT NOT NULL,
    identity_hash CHAR(64) NOT NULL,
    catalog_name VARCHAR(255) NOT NULL DEFAULT '',
    schema_name VARCHAR(255) NOT NULL DEFAULT '',
    table_name VARCHAR(255) NOT NULL,
    operation_type VARCHAR(24) NOT NULL,
    operation_status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS',
    record_key_json LONGTEXT NOT NULL,
    before_json LONGTEXT,
    after_json LONGTEXT,
    actor_id BIGINT,
    actor_name VARCHAR(160) NOT NULL DEFAULT '',
    error_message VARCHAR(1000),
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX master_data_history_object_idx (object_id, id),
    INDEX master_data_history_identity_idx (identity_hash, id)
);

INSERT INTO permission_catalog(permission_code,module_code,module_name,action_code,action_name,description,sort_order) VALUES
('datasource.master_data.read','datasource.master_data','主数据','read','查看','查看所有已登记数据对象的记录',401),
('datasource.master_data.create','datasource.master_data','主数据','create','新增','新增主数据记录',402),
('datasource.master_data.update','datasource.master_data','主数据','update','修改','修改主数据记录',403),
('datasource.master_data.delete','datasource.master_data','主数据','delete','删除','删除主数据记录',404),
('datasource.master_data.import','datasource.master_data','主数据','import','导入','导入主数据记录',405),
('datasource.master_data.export','datasource.master_data','主数据','export','导出','导出主数据记录',406),
('datasource.master_data.history','datasource.master_data','主数据','history','查看版本','查看主数据版本历史',407);

INSERT INTO role_permissions(role_id,permission)
SELECT 1,permission_code
FROM permission_catalog
WHERE module_code='datasource.master_data'
  AND EXISTS (SELECT 1 FROM roles WHERE id=1)
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions rp
      WHERE rp.role_id=1 AND rp.permission=permission_catalog.permission_code
  );

INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order,visible,enabled)
SELECT next_id,2,'主数据','M','/assets/master-data','assets/master-data','','dataset',60,TRUE,TRUE
FROM (SELECT COALESCE(MAX(id),95)+1 AS next_id FROM menus) menu_ids
WHERE NOT EXISTS (SELECT 1 FROM menus WHERE component='assets/master-data');

INSERT INTO role_menus(role_id,menu_id)
SELECT DISTINCT rp.role_id,m.id
FROM role_permissions rp
JOIN permission_catalog pc ON pc.permission_code=rp.permission
JOIN menus m ON m.component='assets/master-data'
WHERE pc.module_code='datasource.master_data'
  AND NOT EXISTS (
      SELECT 1 FROM role_menus rm
      WHERE rm.role_id=rp.role_id AND rm.menu_id=m.id
  );
