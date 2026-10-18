-- Optional header OQC reference; item receipt already has iqc_id.
-- A header reference never exempts notice/line inspections. Rerunnable MySQL 8.
SET NAMES utf8mb4;
SET @wm_qc_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mes_wm_product_sales' AND column_name='oqc_id'), 'SELECT 1', 'ALTER TABLE mes_wm_product_sales ADD COLUMN oqc_id BIGINT NULL COMMENT ''Completed OQC header reference; line QC remains mandatory''');
PREPARE wm_qc_stmt FROM @wm_qc_ddl;
EXECUTE wm_qc_stmt;
DEALLOCATE PREPARE wm_qc_stmt;
