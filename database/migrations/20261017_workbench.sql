-- Responsive production workstation: ordered tasks, SOP and self-reporting.
-- MySQL 8; rerunnable. Existing business records and role assignments are retained.
SET NAMES utf8mb4;
INSERT INTO system_menu
  (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 970101,'工位工作台','',2,5,959,'workbench','ep:monitor','mes/pro/workbench/index','MesProWorkbench',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=970101);
INSERT INTO system_menu
  (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 970102,'查看工位任务','mes:pro-task:query',3,1,970101,'','','','',0,b'1',b'0',b'0','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=970102);
INSERT INTO system_menu
  (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 970103,'提交工位报工','mes:pro-feedback:create',3,2,970101,'','','','',0,b'1',b'0',b'0','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=970103);
-- Tenant 1 administrators see the new entry. Other operator roles can be granted
INSERT INTO system_menu
  (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 970104,'查看设备任务','mes:dv-check-plan:query',3,3,970101,'','','','',0,b'1',b'0',b'0','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=970104);
INSERT INTO system_menu
  (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 970105,'执行点检任务','mes:dv-check-record:update',3,4,970101,'','','','',0,b'1',b'0',b'0','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=970105);
INSERT INTO system_menu
  (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 970106,'执行保养任务','mes:dv-mainten-record:update',3,5,970101,'','','','',0,b'1',b'0',b'0','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=970106);
-- this menu and its query/report children without any master-data maintenance permission.
INSERT INTO system_role_menu (role_id,menu_id,creator,updater,deleted,tenant_id)
SELECT 1,m.id,'1','1',b'0',1 FROM system_menu m
WHERE m.id BETWEEN 970101 AND 970106
AND NOT EXISTS (SELECT 1 FROM system_role_menu rm WHERE rm.role_id=1 AND rm.menu_id=m.id AND rm.tenant_id=1 AND rm.deleted=b'0');
