-- Independent business assistant history. No stock, operational documents or RAG sessions are changed.
CREATE TABLE IF NOT EXISTS mes_insight_conversation (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 title VARCHAR(160) NOT NULL, version INT NOT NULL DEFAULT 0, turn_count INT NOT NULL DEFAULT 0,
 context_json LONGTEXT, pending_json LONGTEXT, permissions_json TEXT,
 processing_token VARCHAR(64), processing_until DATETIME,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_insight_conversation_owner(tenant_id,user_id,update_time,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS mes_insight_turn (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 conversation_id BIGINT NOT NULL, version INT NOT NULL, question TEXT NOT NULL, answer_json LONGTEXT NOT NULL,
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_insight_turn_version(tenant_id,conversation_id,version),
 KEY idx_insight_turn_owner(tenant_id,user_id,conversation_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS mes_insight_evidence (
 turn_id BIGINT NOT NULL PRIMARY KEY, tenant_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 conversation_id BIGINT NOT NULL, evidence_json LONGTEXT NOT NULL,
 KEY idx_insight_evidence_owner(tenant_id,user_id,conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
