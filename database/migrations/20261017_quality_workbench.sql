-- FazeMES responsive quality terminal. MySQL 8; re-runnable.
-- New menu is assigned only to tenant 1 administrator role 1.
-- Existing common QC endpoints reuse IQC-result and QC-defect permissions; grant
-- operator roles deliberately through role management, not to every existing role.
SET NAMES utf8mb4;
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 970140,'现场质检','',2,6,959,'quality-workbench','ep:document-checked','mes/qc/mobile/QualityWorkbench','MesQualityWorkbench',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=970140);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 970141,'查看来料检验','mes:qc-iqc:query',3,1,970140,'','','','',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=970141);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 970142,'新建来料检验','mes:qc-iqc:create',3,2,970140,'','','','',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=970142);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 970143,'填报来料检验','mes:qc-iqc:update',3,3,970140,'','','','',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=970143);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 970144,'完成来料检验','mes:qc-iqc:finish',3,4,970140,'','','','',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=970144);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 970145,'查看过程检验','mes:qc-ipqc:query',3,5,970140,'','','','',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=970145);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 970146,'新建过程检验','mes:qc-ipqc:create',3,6,970140,'','','','',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=970146);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 970147,'填报过程检验','mes:qc-ipqc:update',3,7,970140,'','','','',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=970147);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 970148,'完成过程检验','mes:qc-ipqc:finish',3,8,970140,'','','','',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=970148);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 970149,'查看出货检验','mes:qc-oqc:query',3,9,970140,'','','','',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=970149);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 970150,'新建出货检验','mes:qc-oqc:create',3,10,970140,'','','','',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=970150);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 970151,'填报出货检验','mes:qc-oqc:update',3,11,970140,'','','','',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=970151);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 970152,'完成出货检验','mes:qc-oqc:finish',3,12,970140,'','','','',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=970152);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 970153,'查看待检任务','mes:qc-pending-inspect:query',3,13,970140,'','','','',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=970153);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 970154,'登记质检缺陷','mes:qc-defect:create',3,14,970140,'','','','',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=970154);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 970155,'更新质检缺陷','mes:qc-defect:update',3,15,970140,'','','','',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=970155);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 970156,'删除质检缺陷','mes:qc-defect:delete',3,16,970140,'','','','',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=970156);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 970157,'查看质检缺陷','mes:qc-defect:query',3,17,970140,'','','','',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=970157);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 970158,'删除检测值','mes:qc-iqc:delete',3,18,970140,'','','','',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=970158);
INSERT INTO system_role_menu (role_id,menu_id,creator,updater,deleted,tenant_id)
SELECT 1,m.id,'1','1',b'0',1 FROM system_menu m
WHERE m.id BETWEEN 970140 AND 970158
AND NOT EXISTS (SELECT 1 FROM system_role_menu rm WHERE rm.role_id=1 AND rm.menu_id=m.id AND rm.tenant_id=1 AND rm.deleted=b'0');
