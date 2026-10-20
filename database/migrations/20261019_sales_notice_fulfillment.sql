-- MySQL 8. Add supporting indexes and the actual-fulfilled status; never rewrite business history.
SET NAMES utf8mb4;
DROP PROCEDURE IF EXISTS fazemes_sales_fulfillment_20261019;
DELIMITER $$
CREATE PROCEDURE fazemes_sales_fulfillment_20261019()
BEGIN
 DECLARE migration_lock INT DEFAULT 0;
 DECLARE EXIT HANDLER FOR SQLEXCEPTION
 BEGIN
  ROLLBACK;
  IF migration_lock=1 THEN DO RELEASE_LOCK(CONCAT(DATABASE(), ':sales-fulfillment-v1')); END IF;
  RESIGNAL;
 END;
 SELECT GET_LOCK(CONCAT(DATABASE(), ':sales-fulfillment-v1'),30) INTO migration_lock;
 IF migration_lock IS NULL OR migration_lock<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Sales fulfillment migration lock unavailable'; END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='mes_wm_product_sales' AND index_name='idx_sales_notice_fulfillment') THEN
  ALTER TABLE mes_wm_product_sales ADD INDEX idx_sales_notice_fulfillment(tenant_id,notice_id,id);
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='mes_wm_product_sales_line' AND index_name='idx_sales_notice_line_fulfillment') THEN
  ALTER TABLE mes_wm_product_sales_line ADD INDEX idx_sales_notice_line_fulfillment(tenant_id,notice_line_id,sales_id);
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='mes_wm_product_sales_line' AND index_name='idx_sales_parent_fulfillment') THEN
  ALTER TABLE mes_wm_product_sales_line ADD INDEX idx_sales_parent_fulfillment(tenant_id,sales_id,id);
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='mes_wm_sales_notice_line' AND index_name='idx_notice_parent_fulfillment') THEN
  ALTER TABLE mes_wm_sales_notice_line ADD INDEX idx_notice_parent_fulfillment(tenant_id,notice_id,id);
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='mes_wm_transaction' AND index_name='idx_sales_posted_document') THEN
  ALTER TABLE mes_wm_transaction ADD INDEX idx_sales_posted_document(tenant_id,biz_type,biz_id,id);
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='mes_wm_transaction' AND index_name='idx_sales_posted_line') THEN
  ALTER TABLE mes_wm_transaction ADD INDEX idx_sales_posted_line(tenant_id,biz_type,biz_line_id,id);
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='mes_qc_oqc' AND index_name='idx_oqc_sales_source') THEN
  ALTER TABLE mes_qc_oqc ADD INDEX idx_oqc_sales_source(tenant_id,source_doc_type,source_doc_id,id);
 END IF;
 START TRANSACTION;
 IF EXISTS(SELECT 1 FROM system_dict_data WHERE dict_type='mes_wm_sales_notice_status' AND value='4' AND deleted=b'0' AND label<>'已完成') THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Sales notice FINISHED dictionary value conflicts; resolve explicitly';
 END IF;
 INSERT INTO system_dict_type(name,type,status,remark,creator,updater,deleted)
 SELECT 'MES 发货通知单状态','mes_wm_sales_notice_status',0,'按实际出库流水确认履约','sales-fulfillment-v1','sales-fulfillment-v1',b'0'
 WHERE NOT EXISTS(SELECT 1 FROM system_dict_type WHERE type='mes_wm_sales_notice_status' AND deleted=b'0');
 INSERT INTO system_dict_data(sort,label,value,dict_type,status,color_type,css_class,remark,creator,updater,deleted)
 SELECT seed.sort,seed.label,seed.value,'mes_wm_sales_notice_status',0,seed.color_type,'','按实际出库流水确认履约','sales-fulfillment-v1','sales-fulfillment-v1',b'0'
 FROM(SELECT 0 sort,'草稿' label,'0' value,'info' color_type UNION ALL SELECT 3,'待出库','3','primary' UNION ALL SELECT 4,'已完成','4','success') seed
 WHERE NOT EXISTS(SELECT 1 FROM system_dict_data d WHERE d.dict_type='mes_wm_sales_notice_status' AND d.value=seed.value AND d.deleted=b'0');
 COMMIT;
 DO RELEASE_LOCK(CONCAT(DATABASE(), ':sales-fulfillment-v1'));
END$$
DELIMITER ;
CALL fazemes_sales_fulfillment_20261019();
DROP PROCEDURE fazemes_sales_fulfillment_20261019;
