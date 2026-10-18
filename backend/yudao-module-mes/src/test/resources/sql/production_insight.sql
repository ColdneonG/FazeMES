-- Additive production decision support. No operational orders or stock balances are modified.
CREATE TABLE IF NOT EXISTS mes_insight_incident (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
 title VARCHAR(160) NOT NULL, category VARCHAR(30) NOT NULL,
 work_order_id BIGINT NULL, workstation_id BIGINT NULL, andon_id BIGINT NULL, owner_id BIGINT NULL,
 due_at DATETIME NULL, description TEXT, status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
 root_cause TEXT, corrective_action TEXT, evidence TEXT, version INT NOT NULL DEFAULT 0,
 creator BIGINT NOT NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updater BIGINT NOT NULL, update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 closed_at DATETIME NULL, deleted BIT(1) NOT NULL DEFAULT 0,
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

CREATE TABLE system_users(id BIGINT PRIMARY KEY,tenant_id BIGINT,nickname VARCHAR(100),status INT,deleted BIT);
CREATE TABLE mes_pro_work_order(id BIGINT PRIMARY KEY,tenant_id BIGINT,code VARCHAR(100),create_time DATETIME DEFAULT CURRENT_TIMESTAMP,deleted BIT);
CREATE TABLE mes_md_workstation(id BIGINT PRIMARY KEY,tenant_id BIGINT,name VARCHAR(100),deleted BIT);
CREATE TABLE mes_pro_andon_record(id BIGINT PRIMARY KEY,tenant_id BIGINT,work_order_id BIGINT,workstation_id BIGINT,status INT,deleted BIT,handle_time DATETIME,handler_user_id BIGINT,remark TEXT,updater VARCHAR(64),reason VARCHAR(100),level INT,create_time DATETIME DEFAULT CURRENT_TIMESTAMP);
