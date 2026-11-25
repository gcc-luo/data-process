CREATE TABLE dictionary_directories (id BIGINT AUTO_INCREMENT PRIMARY KEY, parent_id BIGINT, name VARCHAR(120) NOT NULL, sort_order INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(parent_id) REFERENCES dictionary_directories(id));
CREATE TABLE dictionary_entries (id BIGINT AUTO_INCREMENT PRIMARY KEY, directory_id BIGINT NOT NULL, code VARCHAR(100) NOT NULL UNIQUE, name VARCHAR(200) NOT NULL, meaning VARCHAR(200) NOT NULL, data_type VARCHAR(20) NOT NULL, field_length INTEGER, field_precision INTEGER, description VARCHAR(1000), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(directory_id) REFERENCES dictionary_directories(id));
INSERT INTO permission_catalog(permission_code,module_code,module_name,action_code,action_name,description,sort_order) VALUES
('datasource.dictionary.read','datasource.dictionary','数据字典','read','查看','查看数据字典',201),
('datasource.dictionary.create','datasource.dictionary','数据字典','create','新增','新增目录和字典',202),
('datasource.dictionary.update','datasource.dictionary','数据字典','update','修改','修改目录和字典',203),
('datasource.dictionary.delete','datasource.dictionary','数据字典','delete','删除','删除目录和字典',204),
('datasource.dictionary.import','datasource.dictionary','数据字典','import','导入','导入数据字典',205);
INSERT IGNORE INTO role_permissions(role_id,permission) SELECT 1,permission_code FROM permission_catalog WHERE module_code='datasource.dictionary' AND EXISTS (SELECT 1 FROM roles WHERE id=1);
INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order,visible,enabled) SELECT COALESCE(MAX(id),0)+1,2,'数据字典','M','/assets/dictionary','assets/dictionary','','dataset',40,TRUE,TRUE FROM menus;
