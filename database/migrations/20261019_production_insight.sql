-- Additive production decision support. No operational orders or stock balances are modified.
CREATE TABLE IF NOT EXISTS mes_insight_incident (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
 title VARCHAR(160) NOT NULL, category VARCHAR(30) NOT NULL,
 work_order_id BIGINT NULL, workstation_id BIGINT NULL, andon_id BIGINT NULL, owner_id BIGINT NULL,
 due_at DATETIME NULL, description TEXT, status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
 root_cause TEXT, corrective_action TEXT, evidence TEXT, version INT NOT NULL DEFAULT 0,
 creator BIGINT NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updater BIGINT NOT NULL, update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 closed_at DATETIME NULL, deleted BIT(1) NOT NULL DEFAULT b'0',
 UNIQUE KEY uk_insight_andon(tenant_id,andon_id), KEY idx_insight_status(tenant_id,deleted,status,due_at),
 KEY idx_insight_order(tenant_id,work_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS mes_insight_incident_event (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, incident_id BIGINT NOT NULL,
 from_status VARCHAR(20) NOT NULL, to_status VARCHAR(20) NOT NULL, note TEXT NOT NULL,
 root_cause TEXT, corrective_action TEXT, evidence TEXT, owner_id BIGINT NULL, due_at DATETIME NULL,
 actor_id BIGINT NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_insight_event(tenant_id,incident_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS mes_insight_action_draft (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
 title VARCHAR(160) NOT NULL, content TEXT NOT NULL, work_order_id BIGINT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'DRAFT', incident_id BIGINT NULL, creator BIGINT NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, KEY idx_insight_draft(tenant_id,creator,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted) VALUES
(971400,'生产决策中心','',2,8,959,'insight','ep:data-analysis','mes/insight/index','MesInsight',0,b'1',b'0',b'1','1','1',b'0'),
(971401,'决策数据查询','mes:insight:query',3,1,971400,'','','','',0,b'1',b'0',b'1','1','1',b'0'),
(971402,'排产方案仿真','mes:insight:simulate',3,2,971400,'','','','',0,b'1',b'0',b'1','1','1',b'0'),
(971403,'异常与建议管理','mes:insight:manage',3,3,971400,'','','','',0,b'1',b'0',b'1','1','1',b'0'),
(971404,'实时业务问答','mes:insight:chat',3,4,971400,'','','','',0,b'1',b'0',b'1','1','1',b'0')
ON DUPLICATE KEY UPDATE name=VALUES(name),permission=VALUES(permission),component=VALUES(component),status=0,deleted=b'0';
INSERT INTO system_role_menu(role_id,menu_id,tenant_id,creator,updater)
SELECT r.id,m.id,r.tenant_id,'1','1' FROM system_role r JOIN system_menu m ON m.id BETWEEN 971400 AND 971404
WHERE r.tenant_id=1 AND r.code IN('super_admin','tenant_admin') AND r.deleted=b'0'
AND NOT EXISTS(SELECT 1 FROM system_role_menu rm WHERE rm.role_id=r.id AND rm.menu_id=m.id AND rm.tenant_id=r.tenant_id AND rm.deleted=b'0');
UPDATE system_tenant_package SET menu_ids=JSON_ARRAY_APPEND(menu_ids,'$',971400,'$',971401,'$',971402,'$',971403,'$',971404)
WHERE id IN(SELECT package_id FROM system_tenant WHERE id=1 AND deleted=b'0') AND JSON_VALID(menu_ids)
AND NOT JSON_CONTAINS(menu_ids,CAST('971400' AS JSON));
