CREATE TABLE jdbc_drivers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    driver_key VARCHAR(80) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    db_type VARCHAR(40) NOT NULL,
    driver_class VARCHAR(200) NOT NULL,
    version_label VARCHAR(60),
    original_filename VARCHAR(255),
    jar_path VARCHAR(500) NOT NULL,
    sha256 CHAR(64) NOT NULL UNIQUE,
    builtin BOOLEAN NOT NULL DEFAULT FALSE,
    uploaded_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE data_sources ADD COLUMN version_profile VARCHAR(80);
ALTER TABLE data_sources ADD COLUMN driver_id BIGINT;
ALTER TABLE data_sources ADD COLUMN extra_options TEXT;
UPDATE data_sources SET version_profile = CASE source_type WHEN 'MYSQL' THEN 'MYSQL:8.x' WHEN 'POSTGRESQL' THEN 'POSTGRESQL:general' WHEN 'ORACLE' THEN 'ORACLE:12c+' END, extra_options = '{}' WHERE version_profile IS NULL;
ALTER TABLE data_sources MODIFY version_profile VARCHAR(80) NOT NULL;
ALTER TABLE data_sources ADD CONSTRAINT fk_data_sources_driver FOREIGN KEY (driver_id) REFERENCES jdbc_drivers(id);
INSERT INTO permission_catalog (permission_code,module_code,module_name,action_code,action_name,description,sort_order)
SELECT 'datasource.driver.read','datasource.driver','驱动管理','read','查看','读取 JDBC 驱动列表',COALESCE(MAX(sort_order),0)+1 FROM permission_catalog;
INSERT INTO permission_catalog (permission_code,module_code,module_name,action_code,action_name,description,sort_order)
SELECT 'datasource.driver.upload','datasource.driver','驱动管理','upload','上传','上传 JDBC 驱动 jar',COALESCE(MAX(sort_order),0)+1 FROM permission_catalog;
INSERT INTO permission_catalog (permission_code,module_code,module_name,action_code,action_name,description,sort_order)
SELECT 'datasource.driver.delete','datasource.driver','驱动管理','delete','删除','删除上传的 JDBC 驱动',COALESCE(MAX(sort_order),0)+1 FROM permission_catalog;
INSERT IGNORE INTO role_permissions (role_id, permission) SELECT 1, permission_code FROM permission_catalog;
INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order,visible,enabled)
VALUES (23,2,'驱动管理','M','/assets/drivers','assets/drivers','','driver',30,TRUE,TRUE);
