ALTER TABLE departments ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;
ALTER TABLE departments ADD COLUMN leader VARCHAR(160);
ALTER TABLE departments ADD COLUMN phone VARCHAR(48);
ALTER TABLE departments ADD COLUMN email VARCHAR(160);
ALTER TABLE departments ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE users ADD COLUMN nickname VARCHAR(160);
ALTER TABLE users ADD COLUMN phone VARCHAR(48);
ALTER TABLE users ADD COLUMN email VARCHAR(160);
ALTER TABLE users ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE users SET nickname=username WHERE nickname IS NULL;

ALTER TABLE roles ADD COLUMN role_key VARCHAR(160);
UPDATE roles SET role_key=CONCAT('role_', id) WHERE role_key IS NULL;
ALTER TABLE roles ADD CONSTRAINT roles_role_key_unique UNIQUE (role_key);
ALTER TABLE roles ADD COLUMN description VARCHAR(500);
ALTER TABLE roles ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;
ALTER TABLE roles ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE menus (
    id BIGINT PRIMARY KEY,
    parent_id BIGINT,
    name VARCHAR(160) NOT NULL,
    menu_type VARCHAR(16) NOT NULL,
    route_path VARCHAR(255) NOT NULL DEFAULT '',
    component VARCHAR(160) NOT NULL DEFAULT '',
    permission VARCHAR(160) NOT NULL DEFAULT '',
    icon VARCHAR(80) NOT NULL DEFAULT '',
    sort_order INTEGER NOT NULL DEFAULT 0,
    visible BOOLEAN NOT NULL DEFAULT TRUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT menu_not_own_parent CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT menu_valid_type CHECK (menu_type IN ('D','M','F')),
    FOREIGN KEY (parent_id) REFERENCES menus(id)
);

CREATE TABLE role_menus (
    role_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, menu_id),
    FOREIGN KEY (role_id) REFERENCES roles(id),
    FOREIGN KEY (menu_id) REFERENCES menus(id)
);

INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order) VALUES (1,NULL,'平台概览','M','/overview','overview','workflow.validate','grid',10);
INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order) VALUES (2,NULL,'数据资产','M','/assets','assets','datasource.manage','database',20);
INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order) VALUES (3,NULL,'任务与工作流','M','/workflows','tasks','workflow.manage','workflow',30);
INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order) VALUES (4,NULL,'实时同步','M','/realtime','realtime','workflow.manage','pulse',40);
INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order) VALUES (5,NULL,'运行运维','M','/operations','operations','workflow.manage','settings',50);
INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order) VALUES (6,NULL,'数据服务','M','/services','services','service.manage','box',60);
INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order) VALUES (7,NULL,'数据查询','M','/query','query','dataset.read','search',70);
INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order) VALUES (8,NULL,'数据报表','M','/reports','reports','report.manage','chart',80);
INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order) VALUES (9,NULL,'系统管理','D','','system','organization.manage','system',90);
INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order) VALUES (91,9,'部门管理','M','/system/departments','system/departments','organization.manage','building',10);
INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order) VALUES (92,9,'角色管理','M','/system/roles','system/roles','organization.manage','roles',20);
INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order) VALUES (93,9,'用户管理','M','/system/users','system/users','organization.manage','users',30);
INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order) VALUES (94,9,'菜单管理','M','/system/menus','system/menus','organization.manage','menu',40);
INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order) VALUES (95,9,'审计记录','M','/system/audit','system/audit','organization.manage','audit',50);

INSERT INTO role_menus(role_id,menu_id) SELECT r.id,m.id FROM roles r CROSS JOIN menus m WHERE r.id=1;
