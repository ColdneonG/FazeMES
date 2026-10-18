-- Native, declarative report designer. No executable report expressions or UReport dependency.
SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS mes_report_definition (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  latest_version INT NOT NULL DEFAULT 0,
  published_version INT DEFAULT NULL,
  tenant_id BIGINT NOT NULL,
  creator VARCHAR(64) NOT NULL DEFAULT '',
  updater VARCHAR(64) NOT NULL DEFAULT '',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted BIT(1) NOT NULL DEFAULT b'0',
  KEY idx_report_tenant (tenant_id,deleted,update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS mes_report_version (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  report_id BIGINT NOT NULL,
  version INT NOT NULL,
  name VARCHAR(100) NOT NULL,
  config_json MEDIUMTEXT NOT NULL,
  tenant_id BIGINT NOT NULL,
  creator VARCHAR(64) NOT NULL DEFAULT '',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted BIT(1) NOT NULL DEFAULT b'0',
  UNIQUE KEY uk_report_version (tenant_id,report_id,version),
  KEY idx_report_version_tenant (tenant_id,deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted) VALUES
(971180,'报表设计','',2,80,959,'report','ep:data-analysis','mes/report/index','MesReportDesigner',0,b'1',b'1',b'1','1','1',b'0'),
(971181,'报表查看','mes:report:query',3,1,971180,'','','','',0,b'1',b'1',b'1','1','1',b'0'),
(971182,'报表设计保存','mes:report:design',3,2,971180,'','','','',0,b'1',b'1',b'1','1','1',b'0'),
(971183,'报表发布','mes:report:publish',3,3,971180,'','','','',0,b'1',b'1',b'1','1','1',b'0'),
(971184,'报表导出','mes:report:export',3,4,971180,'','','','',0,b'1',b'1',b'1','1','1',b'0'),
(971190,'生产大屏','mes:dashboard:query',2,81,959,'dashboard','ep:monitor','mes/dashboard/index','MesProductionDashboard',0,b'1',b'0',b'1','1','1',b'0')
ON DUPLICATE KEY UPDATE name=VALUES(name),permission=VALUES(permission),parent_id=VALUES(parent_id),path=VALUES(path),component=VALUES(component),component_name=VALUES(component_name),keep_alive=VALUES(keep_alive),status=0,deleted=b'0';
INSERT INTO system_role_menu (role_id,menu_id,tenant_id,creator,updater)
SELECT r.id,m.id,r.tenant_id,'1','1' FROM system_role r JOIN system_menu m ON m.id IN(971180,971181,971182,971183,971184,971190)
WHERE r.tenant_id=1 AND r.code IN('super_admin','tenant_admin') AND r.deleted=b'0'
AND NOT EXISTS(SELECT 1 FROM system_role_menu rm WHERE rm.role_id=r.id AND rm.menu_id=m.id AND rm.tenant_id=r.tenant_id AND rm.deleted=b'0');
UPDATE system_tenant_package SET menu_ids=JSON_ARRAY_APPEND(menu_ids,'$',971180) WHERE id IN(SELECT package_id FROM system_tenant WHERE id=1 AND deleted=b'0') AND JSON_VALID(menu_ids) AND NOT JSON_CONTAINS(menu_ids,CAST('971180' AS JSON));
UPDATE system_tenant_package SET menu_ids=JSON_ARRAY_APPEND(menu_ids,'$',971181) WHERE id IN(SELECT package_id FROM system_tenant WHERE id=1 AND deleted=b'0') AND JSON_VALID(menu_ids) AND NOT JSON_CONTAINS(menu_ids,CAST('971181' AS JSON));
UPDATE system_tenant_package SET menu_ids=JSON_ARRAY_APPEND(menu_ids,'$',971182) WHERE id IN(SELECT package_id FROM system_tenant WHERE id=1 AND deleted=b'0') AND JSON_VALID(menu_ids) AND NOT JSON_CONTAINS(menu_ids,CAST('971182' AS JSON));
UPDATE system_tenant_package SET menu_ids=JSON_ARRAY_APPEND(menu_ids,'$',971183) WHERE id IN(SELECT package_id FROM system_tenant WHERE id=1 AND deleted=b'0') AND JSON_VALID(menu_ids) AND NOT JSON_CONTAINS(menu_ids,CAST('971183' AS JSON));
UPDATE system_tenant_package SET menu_ids=JSON_ARRAY_APPEND(menu_ids,'$',971184) WHERE id IN(SELECT package_id FROM system_tenant WHERE id=1 AND deleted=b'0') AND JSON_VALID(menu_ids) AND NOT JSON_CONTAINS(menu_ids,CAST('971184' AS JSON));
UPDATE system_tenant_package SET menu_ids=JSON_ARRAY_APPEND(menu_ids,'$',971190) WHERE id IN(SELECT package_id FROM system_tenant WHERE id=1 AND deleted=b'0') AND JSON_VALID(menu_ids) AND NOT JSON_CONTAINS(menu_ids,CAST('971190' AS JSON));
