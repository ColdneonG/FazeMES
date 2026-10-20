-- MySQL 8. Technical serialization only; preserve every existing config and barcode.
-- Registry/config mutations lock the existing global (-1) PK, then their tenant PK.
-- This intentionally serializes registry writes across tenants to avoid RR empty-range
-- gap deadlocks while checking legacy tables without a historical unique constraint.
-- Label-context and other printing reads do not obtain this lock or write this table.
CREATE TABLE IF NOT EXISTS mes_wm_barcode_mutex (
  tenant_id BIGINT NOT NULL,
  PRIMARY KEY (tenant_id)
) ENGINE=InnoDB;
INSERT IGNORE INTO mes_wm_barcode_mutex(tenant_id) VALUES(-1);
