-- FazeMES workstation resource bindings; MySQL 8; re-runnable.
-- NULL/empty bindings explicitly preserve legacy unrestricted staffing/tool configuration.
-- Existing required quantities, actual tool quantities and calendar records are unchanged.
SET @fazemes_resource_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'mes_md_workstation_worker' AND column_name = 'user_ids') = 0,
  'ALTER TABLE mes_md_workstation_worker ADD COLUMN user_ids varchar(2048) DEFAULT NULL COMMENT ''Actual staff IDs; null/empty means unrestricted''',
  'SELECT 1');
PREPARE fazemes_resource_stmt FROM @fazemes_resource_ddl;
EXECUTE fazemes_resource_stmt;
DEALLOCATE PREPARE fazemes_resource_stmt;

SET @fazemes_resource_ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'mes_md_workstation_tool' AND column_name = 'tool_ids') = 0,
  'ALTER TABLE mes_md_workstation_tool ADD COLUMN tool_ids varchar(2048) DEFAULT NULL COMMENT ''Actual tool ledger IDs; null/empty means unrestricted''',
  'SELECT 1');
PREPARE fazemes_resource_stmt FROM @fazemes_resource_ddl;
EXECUTE fazemes_resource_stmt;
DEALLOCATE PREPARE fazemes_resource_stmt;
