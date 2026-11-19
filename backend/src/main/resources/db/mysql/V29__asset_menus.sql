-- Restore Data Assets as a top-level directory with datasource and dataset pages.
INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order,visible,enabled)
VALUES (2,NULL,'数据资产','D','','assets','','assets',20,TRUE,TRUE);
INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order,visible,enabled)
VALUES (21,2,'数据源','M','/assets/sources','assets/sources','','database',10,TRUE,TRUE);
INSERT INTO menus(id,parent_id,name,menu_type,route_path,component,permission,icon,sort_order,visible,enabled)
VALUES (22,2,'数据集','M','/assets/datasets','assets/datasets','','dataset',20,TRUE,TRUE);
