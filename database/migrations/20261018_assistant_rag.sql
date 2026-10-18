-- Native tenant-isolated assistant and document retrieval. No model credentials or sample answers are seeded.
SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS mes_assistant_knowledge_base (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL, description VARCHAR(1000) NOT NULL DEFAULT '',
 tenant_id BIGINT NOT NULL, creator BIGINT NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, deleted BIT(1) NOT NULL DEFAULT b'0',
 KEY idx_assistant_base_tenant(tenant_id,deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS mes_assistant_document (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, knowledge_base_id BIGINT NOT NULL, title VARCHAR(200) NOT NULL,
 file_name VARCHAR(200) NOT NULL, file_type VARCHAR(20) NOT NULL, file_size BIGINT NOT NULL, file_sha256 CHAR(64) NOT NULL,
 original_bytes LONGBLOB NOT NULL, content_text MEDIUMTEXT NOT NULL, chunk_count INT NOT NULL, version INT NOT NULL DEFAULT 1,
 status VARCHAR(20) NOT NULL DEFAULT 'DRAFT', tenant_id BIGINT NOT NULL, creator BIGINT NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 deleted BIT(1) NOT NULL DEFAULT b'0', KEY idx_assistant_document_base(tenant_id,knowledge_base_id,deleted,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS mes_assistant_chunk (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, document_id BIGINT NOT NULL, chunk_index INT NOT NULL,
 heading VARCHAR(300) NOT NULL, page_number INT DEFAULT NULL, start_offset INT NOT NULL, end_offset INT NOT NULL,
 content_text TEXT NOT NULL, content_sha256 CHAR(64) NOT NULL, tenant_id BIGINT NOT NULL,
 deleted BIT(1) NOT NULL DEFAULT b'0', UNIQUE KEY uk_assistant_chunk(tenant_id,document_id,chunk_index),
 KEY idx_assistant_chunk_tenant(tenant_id,deleted,document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS mes_assistant_session (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, title VARCHAR(100) NOT NULL, user_id BIGINT NOT NULL, tenant_id BIGINT NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 deleted BIT(1) NOT NULL DEFAULT b'0', KEY idx_assistant_session_user(tenant_id,user_id,deleted,update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS mes_assistant_message (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, session_id BIGINT NOT NULL, user_id BIGINT NOT NULL, role VARCHAR(20) NOT NULL,
 content_text MEDIUMTEXT NOT NULL, status VARCHAR(40) NOT NULL, citations_json MEDIUMTEXT NOT NULL,
 model_name VARCHAR(200) NOT NULL DEFAULT '', tenant_id BIGINT NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 deleted BIT(1) NOT NULL DEFAULT b'0', KEY idx_assistant_message_session(tenant_id,user_id,session_id,deleted,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS mes_assistant_model_config (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, provider VARCHAR(20) NOT NULL,
 base_url VARCHAR(500) NOT NULL, model_name VARCHAR(200) NOT NULL, secret_ciphertext TEXT DEFAULT NULL,
 temperature DECIMAL(4,2) NOT NULL DEFAULT 0.2, max_tokens INT NOT NULL DEFAULT 1024, enabled BIT(1) NOT NULL DEFAULT b'0',
 updater BIGINT NOT NULL, update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_assistant_model_tenant(tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted) VALUES
(971250,'AI助手与知识库','',2,85,959,'assistant','ep:chat-dot-round','mes/assistant/index','MesAssistant',0,b'1',b'1',b'1','1','1',b'0'),
(971251,'助手查看','mes:assistant:query',3,1,971250,'','','','',0,b'1',b'1',b'1','1','1',b'0'),
(971252,'助手会话','mes:assistant:chat',3,2,971250,'','','','',0,b'1',b'1',b'1','1','1',b'0'),
(971253,'知识检索','mes:assistant:knowledge',3,3,971250,'','','','',0,b'1',b'1',b'1','1','1',b'0'),
(971254,'知识维护发布','mes:assistant:manage',3,4,971250,'','','','',0,b'1',b'1',b'1','1','1',b'0'),
(971255,'模型配置','mes:assistant:config',3,5,971250,'','','','',0,b'1',b'1',b'1','1','1',b'0')
ON DUPLICATE KEY UPDATE name=VALUES(name),permission=VALUES(permission),parent_id=VALUES(parent_id),path=VALUES(path),component=VALUES(component),component_name=VALUES(component_name),status=0,deleted=b'0';
INSERT INTO system_role_menu(role_id,menu_id,tenant_id,creator,updater)
SELECT r.id,m.id,r.tenant_id,'1','1' FROM system_role r JOIN system_menu m ON m.id BETWEEN 971250 AND 971255
WHERE r.tenant_id=1 AND r.code IN('super_admin','tenant_admin') AND r.deleted=b'0'
AND NOT EXISTS(SELECT 1 FROM system_role_menu rm WHERE rm.role_id=r.id AND rm.menu_id=m.id AND rm.tenant_id=r.tenant_id AND rm.deleted=b'0');
UPDATE system_tenant_package SET menu_ids=JSON_ARRAY_APPEND(menu_ids,'$',971250, '$',971251, '$',971252, '$',971253, '$',971254, '$',971255)
WHERE id IN(SELECT package_id FROM system_tenant WHERE id=1 AND deleted=b'0') AND JSON_VALID(menu_ids)
AND NOT JSON_CONTAINS(menu_ids,CAST('971250' AS JSON));
