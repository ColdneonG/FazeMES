-- Customer portal adapted from yunshu-mes-all client catalog/order flow.
-- No default customer, account, password, catalog entry or order is installed.
SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS mes_portal_account (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  client_id BIGINT NOT NULL,
  username VARCHAR(64) NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  enabled BIT(1) NOT NULL DEFAULT b'1',
  auth_version BIGINT NOT NULL DEFAULT 1,
  failed_attempts INT NOT NULL DEFAULT 0,
  locked_until DATETIME DEFAULT NULL,
  expire_time DATETIME DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted BIT(1) NOT NULL DEFAULT b'0',
  UNIQUE KEY uk_portal_username (tenant_id,username),
  KEY idx_portal_client (tenant_id,client_id,deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS mes_portal_session (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  token_hash CHAR(64) NOT NULL,
  auth_version BIGINT NOT NULL,
  expires_at DATETIME NOT NULL,
  revoked BIT(1) NOT NULL DEFAULT b'0',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_portal_session_hash (token_hash),
  KEY idx_portal_session_account (tenant_id,account_id,revoked)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS mes_portal_catalog (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  description VARCHAR(1000) NOT NULL DEFAULT '',
  enabled BIT(1) NOT NULL DEFAULT b'1',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_portal_catalog (tenant_id,product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS mes_portal_request (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  client_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  code VARCHAR(64) NOT NULL,
  request_key VARCHAR(64) NOT NULL,
  delivery_date DATE NOT NULL,
  remark VARCHAR(1000) NOT NULL DEFAULT '',
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  review_note VARCHAR(1000) NOT NULL DEFAULT '',
  reviewer_id BIGINT DEFAULT NULL,
  review_time DATETIME DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_portal_request (tenant_id,account_id,request_key),
  UNIQUE KEY uk_portal_request_code (tenant_id,code),
  KEY idx_portal_request_client (tenant_id,client_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS mes_portal_request_line (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  request_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  product_code VARCHAR(128) NOT NULL,
  product_name VARCHAR(255) NOT NULL,
  specification VARCHAR(255) NOT NULL DEFAULT '',
  unit_name VARCHAR(255) NOT NULL DEFAULT '',
  quantity DECIMAL(20,6) NOT NULL,
  KEY idx_portal_request_line (tenant_id,request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted) VALUES
(971270,'客户门户管理','',2,88,959,'portal-admin','ep:shop','mes/portal-admin/index','MesPortalAdmin',0,b'1',b'1',b'1','1','1',b'0'),
(971271,'客户门户查看','mes:portal:query',3,1,971270,'','','','',0,b'1',b'1',b'1','1','1',b'0'),
(971272,'客户门户维护','mes:portal:manage',3,2,971270,'','','','',0,b'1',b'1',b'1','1','1',b'0')
ON DUPLICATE KEY UPDATE name=VALUES(name),permission=VALUES(permission),parent_id=VALUES(parent_id),path=VALUES(path),component=VALUES(component),component_name=VALUES(component_name),status=0,deleted=b'0';
INSERT INTO system_role_menu (role_id,menu_id,tenant_id,creator,updater)
SELECT r.id,m.id,r.tenant_id,'1','1' FROM system_role r JOIN system_menu m ON m.id IN(971270,971271,971272)
WHERE r.tenant_id=1 AND r.code IN('super_admin','tenant_admin') AND r.deleted=b'0'
AND NOT EXISTS(SELECT 1 FROM system_role_menu rm WHERE rm.role_id=r.id AND rm.menu_id=m.id AND rm.tenant_id=r.tenant_id AND rm.deleted=b'0');
UPDATE system_tenant_package SET menu_ids=JSON_ARRAY_APPEND(menu_ids,'$',971270) WHERE id IN(SELECT package_id FROM system_tenant WHERE id=1 AND deleted=b'0') AND JSON_VALID(menu_ids) AND NOT JSON_CONTAINS(menu_ids,CAST('971270' AS JSON));
UPDATE system_tenant_package SET menu_ids=JSON_ARRAY_APPEND(menu_ids,'$',971271) WHERE id IN(SELECT package_id FROM system_tenant WHERE id=1 AND deleted=b'0') AND JSON_VALID(menu_ids) AND NOT JSON_CONTAINS(menu_ids,CAST('971271' AS JSON));
UPDATE system_tenant_package SET menu_ids=JSON_ARRAY_APPEND(menu_ids,'$',971272) WHERE id IN(SELECT package_id FROM system_tenant WHERE id=1 AND deleted=b'0') AND JSON_VALID(menu_ids) AND NOT JSON_CONTAINS(menu_ids,CAST('971272' AS JSON));
