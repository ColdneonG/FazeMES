-- Equipment task occurrences and repair source idempotency. MySQL 8, rerunnable.
-- Existing identifiers and business records are retained. Apply before starting the new backend.
SET NAMES utf8mb4;
SET @dv_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mes_dv_check_plan' AND column_name='next_task_time'), 'SELECT 1', 'ALTER TABLE mes_dv_check_plan ADD COLUMN next_task_time DATETIME NULL COMMENT ''Next ungenerated occurrence''');
PREPARE dv_stmt FROM @dv_ddl;
EXECUTE dv_stmt;
DEALLOCATE PREPARE dv_stmt;

CREATE TABLE IF NOT EXISTS mes_dv_task (
  id BIGINT NOT NULL AUTO_INCREMENT,
  plan_id BIGINT NOT NULL,
  plan_name VARCHAR(255) NULL,
  machinery_id BIGINT NOT NULL,
  machinery_name VARCHAR(255) NULL,
  type INT NOT NULL COMMENT '1 check, 2 maintenance',
  due_time DATETIME NOT NULL,
  status INT NOT NULL DEFAULT 0 COMMENT '0 pending, 1 claimed, 2 completed',
  user_id BIGINT NULL,
  claimed_time DATETIME NULL,
  completed_time DATETIME NULL,
  record_id BIGINT NULL,
  repair_id BIGINT NULL,
  creator VARCHAR(64) NOT NULL DEFAULT '',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updater VARCHAR(64) NOT NULL DEFAULT '',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted BIT(1) NOT NULL DEFAULT b'0',
  tenant_id BIGINT NOT NULL DEFAULT 1,
  PRIMARY KEY(id),
  UNIQUE KEY uk_dv_task_occurrence(tenant_id,plan_id,machinery_id,due_time),
  KEY idx_dv_task_queue(tenant_id,status,due_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mes_dv_task_line (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  subject_id BIGINT NOT NULL,
  subject_name VARCHAR(255) NULL,
  content TEXT NULL,
  standard TEXT NULL,
  result INT NULL,
  description TEXT NULL,
  creator VARCHAR(64) NOT NULL DEFAULT '',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updater VARCHAR(64) NOT NULL DEFAULT '',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted BIT(1) NOT NULL DEFAULT b'0',
  tenant_id BIGINT NOT NULL DEFAULT 1,
  PRIMARY KEY(id),
  UNIQUE KEY uk_dv_task_subject(tenant_id,task_id,subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Only automatic source types are unique; legacy/manual sources remain untouched.
SET @dv_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mes_dv_repair' AND column_name='auto_source_key'), 'SELECT 1', 'ALTER TABLE mes_dv_repair ADD COLUMN auto_source_key VARCHAR(100) GENERATED ALWAYS AS (CASE WHEN source_doc_type IN (101,102) AND source_doc_id IS NOT NULL THEN CONCAT(source_doc_type, '':'', source_doc_id) ELSE NULL END) STORED');
PREPARE dv_stmt FROM @dv_ddl;
EXECUTE dv_stmt;
DEALLOCATE PREPARE dv_stmt;
SET @dv_ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='mes_dv_repair' AND index_name='uk_dv_repair_auto_source'), 'SELECT 1', 'CREATE UNIQUE INDEX uk_dv_repair_auto_source ON mes_dv_repair(tenant_id,auto_source_key)');
PREPARE dv_stmt FROM @dv_ddl;
EXECUTE dv_stmt;
DEALLOCATE PREPARE dv_stmt;

-- Preserve full execution and repair narratives (no existing values are truncated).
ALTER TABLE mes_dv_check_record_line MODIFY COLUMN check_result TEXT NULL;
ALTER TABLE mes_dv_mainten_record_line MODIFY COLUMN result TEXT NULL;
ALTER TABLE mes_dv_repair_line MODIFY COLUMN malfunction TEXT NULL, MODIFY COLUMN description TEXT NULL;
