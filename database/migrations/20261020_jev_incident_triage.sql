-- Additive Jev pilot; no changes to operational orders, stock or existing incidents.
CREATE TABLE IF NOT EXISTS mes_insight_jev_config (
 tenant_id BIGINT PRIMARY KEY, model_name VARCHAR(80) NOT NULL,
 secret_ciphertext TEXT NULL, enabled BIT(1) NOT NULL DEFAULT b'0',
 updater BIGINT NOT NULL, update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS mes_insight_jev_triage (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, creator BIGINT NOT NULL,
 input_hash CHAR(64) NOT NULL, model_name VARCHAR(80) NOT NULL,
 category VARCHAR(30) NOT NULL, department_id BIGINT NULL,
 category_confidence DOUBLE NOT NULL, department_confidence DOUBLE NOT NULL,
 incident_id BIGINT NULL, final_category VARCHAR(30) NULL, final_department_id BIGINT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, reviewed_at DATETIME NULL,
 KEY idx_jev_feedback(tenant_id,creator,create_time), UNIQUE KEY uk_jev_incident(tenant_id,incident_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS mes_insight_incident_assignment (
 incident_id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, department_id BIGINT NULL,
 triage_id BIGINT NULL, UNIQUE KEY uk_jev_triage(tenant_id,triage_id),
 KEY idx_assignment_department(tenant_id,department_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
