-- Exact sales-return provenance and independent capacity mutexes. No historical ledger or stock rewrite.
SET NAMES utf8mb4;
DROP PROCEDURE IF EXISTS fazemes_return_source_20261020;
DELIMITER $$
CREATE PROCEDURE fazemes_return_source_20261020()
BEGIN
 DECLARE migration_lock INT DEFAULT 0;
 DECLARE EXIT HANDLER FOR SQLEXCEPTION
 BEGIN
  IF migration_lock=1 THEN DO RELEASE_LOCK(CONCAT('return-source-v1:',LEFT(SHA2(DATABASE(),256),40))); END IF;
  RESIGNAL;
 END;
 SELECT GET_LOCK(CONCAT('return-source-v1:',LEFT(SHA2(DATABASE(),256),40)),30) INTO migration_lock;
 IF migration_lock IS NULL OR migration_lock<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Return source migration lock unavailable'; END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mes_wm_return_sales_line' AND column_name='origin_transaction_id') THEN
  ALTER TABLE mes_wm_return_sales_line ADD COLUMN origin_transaction_id BIGINT NULL COMMENT 'Explicit original negative118; historical unknown remains null';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mes_wm_transaction' AND column_name='return_source_transaction_id') THEN
  ALTER TABLE mes_wm_transaction ADD COLUMN return_source_transaction_id BIGINT NULL COMMENT '119 original118; not equal-quantity related_transaction_id';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mes_wm_transaction' AND column_name='return_detail_id') THEN
  ALTER TABLE mes_wm_transaction ADD COLUMN return_detail_id BIGINT NULL COMMENT 'Own actual putaway detail; one119 per detail';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='mes_wm_transaction' AND index_name='uk_return_posted_detail') THEN
  ALTER TABLE mes_wm_transaction ADD UNIQUE INDEX uk_return_posted_detail(tenant_id,biz_type,return_detail_id);
 END IF;
 CREATE TABLE IF NOT EXISTS mes_wm_return_source_budget(
  tenant_id BIGINT NOT NULL,origin_transaction_id BIGINT NOT NULL,reserved_quantity DECIMAL(20,6) NOT NULL DEFAULT 0,returned_quantity DECIMAL(20,6) NOT NULL DEFAULT 0,create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id,origin_transaction_id)
 ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mes_wm_return_source_budget' AND column_name='reserved_quantity') THEN
  ALTER TABLE mes_wm_return_source_budget ADD COLUMN reserved_quantity DECIMAL(20,6) NOT NULL DEFAULT 0;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mes_wm_return_source_budget' AND column_name='returned_quantity') THEN
  ALTER TABLE mes_wm_return_source_budget ADD COLUMN returned_quantity DECIMAL(20,6) NOT NULL DEFAULT 0;
 END IF;
 CREATE TABLE IF NOT EXISTS mes_wm_return_source_reservation(
  tenant_id BIGINT NOT NULL,return_line_id BIGINT NOT NULL,return_id BIGINT NOT NULL,origin_transaction_id BIGINT NOT NULL,
  quantity DECIMAL(20,6) NOT NULL,posted_quantity DECIMAL(20,6) NOT NULL DEFAULT 0,state VARCHAR(16) NOT NULL,create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id,return_line_id),KEY idx_return_reservation_origin(tenant_id,origin_transaction_id,state,return_line_id),
  KEY idx_return_reservation_parent(tenant_id,return_id,return_line_id)
 ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
 CREATE TABLE IF NOT EXISTS mes_wm_return_posted_evidence(
  tenant_id BIGINT NOT NULL,return_transaction_id BIGINT NOT NULL,return_id BIGINT NOT NULL,return_line_id BIGINT NOT NULL,
  return_detail_id BIGINT NOT NULL,origin_transaction_id BIGINT NOT NULL,quantity DECIMAL(20,6) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(tenant_id,return_transaction_id),UNIQUE KEY uk_return_evidence_detail(tenant_id,return_detail_id)
 ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mes_wm_return_source_reservation' AND column_name='posted_quantity') THEN
  ALTER TABLE mes_wm_return_source_reservation ADD COLUMN posted_quantity DECIMAL(20,6) NOT NULL DEFAULT 0;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='mes_wm_return_sales_line' AND index_name='idx_return_line_origin') THEN
  ALTER TABLE mes_wm_return_sales_line ADD INDEX idx_return_line_origin(tenant_id,origin_transaction_id,deleted);
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='mes_wm_transaction' AND index_name='idx_return_posted_origin') THEN
  ALTER TABLE mes_wm_transaction ADD INDEX idx_return_posted_origin(tenant_id,biz_type,return_source_transaction_id,id);
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='mes_qc_rqc' AND index_name='idx_rqc_return_source') THEN
  ALTER TABLE mes_qc_rqc ADD INDEX idx_rqc_return_source(tenant_id,source_doc_type,source_doc_id,source_line_id,deleted,id);
 END IF;
 DO RELEASE_LOCK(CONCAT('return-source-v1:',LEFT(SHA2(DATABASE(),256),40)));
END$$
DELIMITER ;
CALL fazemes_return_source_20261020();
DROP PROCEDURE fazemes_return_source_20261020;
