-- FazeMES MD/QC coverage, MySQL 8. Select the dedicated FazeMES database first.
-- Re-runnable. Existing templates keep NULL ipqc_types = generic IPQC template.
-- Existing defect records remain manual snapshots; conversion rates are never guessed.
SET @fazemes_md_qc_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'mes_qc_template' AND column_name = 'ipqc_types') = 0,
  'ALTER TABLE mes_qc_template ADD COLUMN ipqc_types varchar(255) DEFAULT NULL COMMENT ''IPQC stages; null/empty means generic''',
  'SELECT 1');
PREPARE fazemes_md_qc_stmt FROM @fazemes_md_qc_ddl;
EXECUTE fazemes_md_qc_stmt;
DEALLOCATE PREPARE fazemes_md_qc_stmt;

SET @fazemes_md_qc_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'mes_qc_defect_record' AND column_name = 'defect_id') = 0,
  'ALTER TABLE mes_qc_defect_record ADD COLUMN defect_id bigint DEFAULT NULL COMMENT ''Optional common defect source; name and level are snapshots''',
  'SELECT 1');
PREPARE fazemes_md_qc_stmt FROM @fazemes_md_qc_ddl;
EXECUTE fazemes_md_qc_stmt;
DEALLOCATE PREPARE fazemes_md_qc_stmt;

-- Preserve historical stage values 1..5; append LAST inspection as 6.
-- Preflight result must be empty: a custom meaning of value 6 requires manual mapping.
SELECT id, label, value FROM system_dict_data
WHERE dict_type = 'mes_ipqc_type' AND value = '6' AND label <> '末检' AND deleted = FALSE;

INSERT INTO system_dict_data
  (sort, label, value, dict_type, status, color_type, css_class, remark, creator, updater)
SELECT 6, '末检', '6', 'mes_ipqc_type', 0, 'info', '', 'FazeMES过程检验末检阶段', 'system', 'system'
WHERE NOT EXISTS (SELECT 1 FROM system_dict_data
  WHERE dict_type = 'mes_ipqc_type' AND value = '6' AND deleted = FALSE);
