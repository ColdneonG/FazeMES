-- Every external model call is admitted and settled independently. No old usage is guessed or reset.
CREATE TABLE IF NOT EXISTS mes_model_usage_policy (
 tenant_id BIGINT NOT NULL PRIMARY KEY,version INT NOT NULL DEFAULT 0,enabled BOOLEAN NOT NULL DEFAULT FALSE,
 limits_json LONGTEXT,rates_json LONGTEXT,coverage_start DATETIME,updater BIGINT,update_time DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS mes_model_usage_bucket (
 tenant_id BIGINT NOT NULL,subject_type VARCHAR(10) NOT NULL,subject_id BIGINT NOT NULL,
 window_type VARCHAR(10) NOT NULL,window_start DATETIME NOT NULL,
 used_calls BIGINT NOT NULL DEFAULT 0,reserved_calls BIGINT NOT NULL DEFAULT 0,unknown_calls BIGINT NOT NULL DEFAULT 0,
 known_tokens DECIMAL(38,0) NOT NULL DEFAULT 0,reserved_tokens DECIMAL(38,0) NOT NULL DEFAULT 0,held_unknown_tokens DECIMAL(38,0) NOT NULL DEFAULT 0,
 unknown_chat_token_calls BIGINT NOT NULL DEFAULT 0,unknown_jev_token_calls BIGINT NOT NULL DEFAULT 0,
 actual_total_tokens DECIMAL(38,0) NOT NULL DEFAULT 0,
 calculated_amount DECIMAL(65,8) NOT NULL DEFAULT 0,reserved_amount DECIMAL(65,8) NOT NULL DEFAULT 0,
 held_unknown_amount DECIMAL(65,8) NOT NULL DEFAULT 0,unpriced_calls BIGINT NOT NULL DEFAULT 0,
 active_count BIGINT NOT NULL DEFAULT 0,admitted BIGINT NOT NULL DEFAULT 0,version INT NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,subject_type,subject_id,window_type,window_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS mes_model_usage_log (
 id VARCHAR(36) NOT NULL PRIMARY KEY,tenant_id BIGINT NOT NULL,user_id BIGINT NOT NULL,
 purpose VARCHAR(40) NOT NULL,channel VARCHAR(10) NOT NULL,status VARCHAR(20) NOT NULL,version INT NOT NULL,
 state_json LONGTEXT NOT NULL,created_at DATETIME NOT NULL,dispatched_at DATETIME,settled_at DATETIME,stale_after DATETIME,
 KEY idx_model_usage_owner(tenant_id,created_at,id),
 KEY idx_model_usage_filter(tenant_id,purpose,status,created_at,id),
 KEY idx_model_usage_user(tenant_id,user_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS mes_model_usage_event (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT NOT NULL,request_id VARCHAR(36),
 event_type VARCHAR(40) NOT NULL,actor_id BIGINT,prior_version INT,new_version INT,
 reason VARCHAR(1000),state_json LONGTEXT,create_time DATETIME NOT NULL,
 KEY idx_model_usage_event(tenant_id,request_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Guard each additive session ALTER independently so interrupted rollout can be retried safely.
SET @mes_session_version = IF((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='mes_assistant_session')=1 AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mes_assistant_session' AND column_name='version')=0,'ALTER TABLE mes_assistant_session ADD COLUMN version INT NOT NULL DEFAULT 0','SELECT 1');
PREPARE mes_usage_ddl FROM @mes_session_version;
EXECUTE mes_usage_ddl;
DEALLOCATE PREPARE mes_usage_ddl;
SET @mes_session_pending = IF((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='mes_assistant_session')=1 AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mes_assistant_session' AND column_name='pending_request_id')=0,'ALTER TABLE mes_assistant_session ADD COLUMN pending_request_id VARCHAR(36) NULL','SELECT 1');
PREPARE mes_usage_ddl FROM @mes_session_pending;
EXECUTE mes_usage_ddl;
DEALLOCATE PREPARE mes_usage_ddl;
SET @mes_session_started = IF((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='mes_assistant_session')=1 AND (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mes_assistant_session' AND column_name='pending_started_at')=0,'ALTER TABLE mes_assistant_session ADD COLUMN pending_started_at DATETIME NULL','SELECT 1');
PREPARE mes_usage_ddl FROM @mes_session_started;
EXECUTE mes_usage_ddl;
DEALLOCATE PREPARE mes_usage_ddl;
