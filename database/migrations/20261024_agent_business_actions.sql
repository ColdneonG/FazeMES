-- Additive Agent execution audit. Historical quota tables are retained as read-only history.
CREATE TABLE IF NOT EXISTS mes_agent_action (
 id VARCHAR(36) NOT NULL PRIMARY KEY, tenant_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 kind VARCHAR(40) NOT NULL, source_turn_id BIGINT NULL, payload_json LONGTEXT NOT NULL, token_hash CHAR(64) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING', result_json LONGTEXT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, expires_at DATETIME NOT NULL,
 executed_at DATETIME NULL, KEY idx_agent_owner(tenant_id,user_id,create_time), KEY idx_agent_turn(tenant_id,user_id,source_turn_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Reviewed Agent commands and successful execution receipts';
