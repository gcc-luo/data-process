-- Processing directory menu plus task directory tree and task metadata columns.
INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order,visible,enabled)
VALUES (3,NULL,'数据处理','D','','processing','','processing',30,TRUE,TRUE);
INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order,visible,enabled)
VALUES (31,3,'处理任务','M','/processing/tasks','processing/tasks','','workflow',10,TRUE,TRUE);
CREATE TABLE task_directories (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    parent_id BIGINT,
    name VARCHAR(120) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (parent_id) REFERENCES task_directories(id)
);
ALTER TABLE processing_tasks ADD COLUMN directory_id BIGINT;
ALTER TABLE processing_tasks ADD CONSTRAINT fk_tasks_directory FOREIGN KEY (directory_id) REFERENCES task_directories(id);
ALTER TABLE processing_tasks ADD COLUMN description VARCHAR(500);
