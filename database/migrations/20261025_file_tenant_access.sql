-- File URLs are private tenant resources. Do not infer ownership from path names or a single-tenant install.
SET @file_tenant_ddl = IF(
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='infra_file' AND column_name='tenant_id'),
  'SELECT 1',
  'ALTER TABLE infra_file ADD COLUMN tenant_id BIGINT NULL COMMENT ''Verified file tenant; NULL is unassigned legacy data'''
);
PREPARE file_tenant_statement FROM @file_tenant_ddl;
EXECUTE file_tenant_statement;
DEALLOCATE PREPARE file_tenant_statement;

ALTER TABLE infra_file MODIFY COLUMN tenant_id BIGINT NULL DEFAULT NULL COMMENT 'Verified file tenant; NULL is unassigned legacy data';

-- A numeric creator is a globally unique user identity. Other historical records remain unassigned.
-- Compare exact identity bytes: CAST(... AS CHAR) inherits the connection collation,
-- which can differ from creator's column collation on MySQL 8. Do not coerce legacy
-- creator text to a number, since values such as '1-old' must not claim user 1's tenant.
UPDATE infra_file f
JOIN system_users u ON CAST(f.creator AS BINARY) = CAST(u.id AS BINARY)
SET f.tenant_id = u.tenant_id
WHERE (f.tenant_id IS NULL OR f.tenant_id = 0) AND u.tenant_id > 0;
UPDATE infra_file SET tenant_id = NULL WHERE tenant_id = 0;

-- Before tenant-scoped upload paths, identical names could point multiple tenants at the same
-- physical object. Creator metadata proves who uploaded a record, not whose bytes survived.
-- Keep all such collisions inaccessible until the actual object/backup has been verified.
UPDATE infra_file f
JOIN (
  SELECT config_id, path FROM infra_file
  GROUP BY config_id, path
  HAVING COUNT(DISTINCT tenant_id) > 1
     OR (SUM(tenant_id IS NULL) > 0 AND SUM(tenant_id IS NOT NULL) > 0)
) ambiguous ON ambiguous.config_id = f.config_id AND ambiguous.path = f.path
SET f.tenant_id = NULL;

SET @file_tenant_index = IF(
  EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='infra_file' AND index_name='idx_file_tenant_path'),
  'SELECT 1',
  'CREATE INDEX idx_file_tenant_path ON infra_file(tenant_id, config_id, path(191))'
);
PREPARE file_tenant_statement FROM @file_tenant_index;
EXECUTE file_tenant_statement;
DEALLOCATE PREPARE file_tenant_statement;

-- Administrator verification: SELECT id,creator,path FROM infra_file WHERE tenant_id IS NULL OR tenant_id=0;
-- Assign a tenant only after checking the actual business reference. The migration never guesses a tenant.
