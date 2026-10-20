-- MySQL 8: repeatable and recoverable process-source evidence upgrade.
SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS mes_pro_feedback_source (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 feedback_id BIGINT NOT NULL,
 source_feedback_id BIGINT NOT NULL,
 quantity DECIMAL(20,6) NOT NULL,
 frozen BIT NOT NULL DEFAULT b'0',
 created_by VARCHAR(64),
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 frozen_at DATETIME(6),
 deleted BIT NOT NULL DEFAULT b'0',
 UNIQUE KEY uk_feedback_source(tenant_id,feedback_id,source_feedback_id),
 KEY idx_source_capacity(tenant_id,source_feedback_id,frozen),
 CHECK(quantity>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Only approval writes this immutable closure, in the same transaction as frozen transfers and consumption.
CREATE TABLE IF NOT EXISTS mes_pro_feedback_ancestor (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 feedback_id BIGINT NOT NULL,
 ancestor_feedback_id BIGINT NOT NULL,
 deleted BIT NOT NULL DEFAULT b'0',
 UNIQUE KEY uk_feedback_ancestor(tenant_id,feedback_id,ancestor_feedback_id),
 KEY idx_ancestor(tenant_id,ancestor_feedback_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


DROP PROCEDURE IF EXISTS fazemes_feedback_flow_20261019;
DELIMITER $$
CREATE PROCEDURE fazemes_feedback_flow_20261019()
BEGIN
 DECLARE migration_lock INT DEFAULT 0;
 DECLARE EXIT HANDLER FOR SQLEXCEPTION
 BEGIN
  ROLLBACK;
  IF migration_lock=1 THEN DO RELEASE_LOCK(CONCAT(DATABASE(), ':feedback-flow-v1')); END IF;
  RESIGNAL;
 END;
 SELECT GET_LOCK(CONCAT(DATABASE(), ':feedback-flow-v1'),30) INTO migration_lock;
 IF migration_lock IS NULL OR migration_lock<>1 THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Feedback flow migration lock unavailable';
 END IF;
 -- A default of false classifies existing posted records atomically with ADD COLUMN.
 -- Restart after any DDL interruption never downgrades a newly posted required=true report.
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mes_pro_feedback' AND column_name='flow_required') THEN
  ALTER TABLE mes_pro_feedback ADD COLUMN flow_required BIT NOT NULL DEFAULT b'0';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mes_pro_feedback' AND column_name='flow_complete') THEN
  ALTER TABLE mes_pro_feedback ADD COLUMN flow_complete BIT NOT NULL DEFAULT b'0';
 END IF;
 START TRANSACTION;
 UPDATE mes_pro_feedback SET flow_required=b'1' WHERE status NOT IN(3,4) OR status IS NULL;
 COMMIT;
 ALTER TABLE mes_pro_feedback ALTER COLUMN flow_required SET DEFAULT b'1';
 DO RELEASE_LOCK(CONCAT(DATABASE(), ':feedback-flow-v1'));
END$$
DELIMITER ;
CALL fazemes_feedback_flow_20261019();
DROP PROCEDURE fazemes_feedback_flow_20261019;
