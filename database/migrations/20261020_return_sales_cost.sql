-- MySQL 8. Sales-return valuation uses immutable original 118 allocations only.
-- Deliberately no foreign keys to old shipments: inserts must not retain old source-row locks.
CREATE TABLE IF NOT EXISTS mes_cost_return_source_balance (
 tenant_id BIGINT NOT NULL, source_transaction_id BIGINT NOT NULL, source_part_key BIGINT NOT NULL,
 original_allocation_id BIGINT NULL, source_quantity DECIMAL(20,6) NOT NULL,
 source_status VARCHAR(20) NOT NULL, source_unit_cost DECIMAL(24,8) NULL,
 source_material_amount DECIMAL(24,8) NULL, source_labor_amount DECIMAL(24,8) NULL, source_overhead_amount DECIMAL(24,8) NULL,
 returned_quantity DECIMAL(20,6) NOT NULL DEFAULT 0,
 returned_material_amount DECIMAL(24,8) NULL, returned_labor_amount DECIMAL(24,8) NULL, returned_overhead_amount DECIMAL(24,8) NULL,
 basis_issue VARCHAR(500) NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,source_transaction_id,source_part_key),
 CHECK(source_quantity>0 AND returned_quantity>=0 AND returned_quantity<=source_quantity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Cumulative restoration of immutable outbound cost portions; key zero means unknown evidence';

CREATE TABLE IF NOT EXISTS mes_cost_return_allocation (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
 source_transaction_id BIGINT NOT NULL, source_part_key BIGINT NOT NULL, original_allocation_id BIGINT NULL,
 return_transaction_id BIGINT NOT NULL, restored_layer_id BIGINT NOT NULL, quantity DECIMAL(20,6) NOT NULL,
 original_status VARCHAR(20) NOT NULL, original_unit_cost DECIMAL(24,8) NULL, original_quantity DECIMAL(20,6) NOT NULL,
 original_material_amount DECIMAL(24,8) NULL, original_labor_amount DECIMAL(24,8) NULL, original_overhead_amount DECIMAL(24,8) NULL,
 material_amount DECIMAL(24,8) NULL, labor_amount DECIMAL(24,8) NULL, overhead_amount DECIMAL(24,8) NULL,
 status VARCHAR(20) NOT NULL, basis_issue VARCHAR(500) NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_return_cost_part(tenant_id,return_transaction_id,source_part_key),
 UNIQUE KEY uk_return_cost_layer(tenant_id,restored_layer_id),
 KEY idx_return_cost_source(tenant_id,source_transaction_id,id),
 CHECK(quantity>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Append-only sales-return portion valuation; never edits original sale allocation';

CREATE TABLE IF NOT EXISTS mes_cost_layer_basis (
 tenant_id BIGINT NOT NULL, layer_id BIGINT NOT NULL, basis_quantity DECIMAL(20,6) NOT NULL,
 material_amount DECIMAL(24,8) NULL, labor_amount DECIMAL(24,8) NULL, overhead_amount DECIMAL(24,8) NULL,
 consumed_quantity DECIMAL(20,6) NOT NULL DEFAULT 0,
 consumed_material_amount DECIMAL(24,8) NULL, consumed_labor_amount DECIMAL(24,8) NULL, consumed_overhead_amount DECIMAL(24,8) NULL,
 status VARCHAR(20) NOT NULL, origin_return_transaction_id BIGINT NOT NULL, source_allocation_id BIGINT NULL,
 basis_issue VARCHAR(500) NULL, create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY(tenant_id,layer_id), KEY idx_return_basis_origin(tenant_id,origin_return_transaction_id,layer_id),
 CHECK(basis_quantity>0 AND consumed_quantity>=0 AND consumed_quantity<=basis_quantity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Immutable component totals and cumulative usage for returned stock and its transfers';

-- Quantity widening: preserve every historical value and permit warehouse DECIMAL(20,6) quantities.
DROP PROCEDURE IF EXISTS fazemes_return_cost_20261020;
DELIMITER $$
CREATE PROCEDURE fazemes_return_cost_20261020()
BEGIN
 DECLARE migration_lock INT DEFAULT 0;
 DECLARE EXIT HANDLER FOR SQLEXCEPTION
 BEGIN
  IF migration_lock=1 THEN DO RELEASE_LOCK(CONCAT(DATABASE(), ':return-cost-v1')); END IF;
  RESIGNAL;
 END;
 SELECT GET_LOCK(CONCAT(DATABASE(), ':return-cost-v1'),30) INTO migration_lock;
 IF migration_lock IS NULL OR migration_lock<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Return cost migration lock unavailable'; END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mes_cost_stock_layer' AND column_name='return_basis_required') THEN
  ALTER TABLE mes_cost_stock_layer ADD COLUMN return_basis_required BIT NOT NULL DEFAULT b'0';
 END IF;
 IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mes_cost_stock_layer' AND column_name='quantity' AND numeric_precision<20) THEN
  ALTER TABLE mes_cost_stock_layer MODIFY quantity DECIMAL(20,6) NOT NULL;
 END IF;
 IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mes_cost_stock_layer' AND column_name='remaining_quantity' AND numeric_precision<20) THEN
  ALTER TABLE mes_cost_stock_layer MODIFY remaining_quantity DECIMAL(20,6) NOT NULL;
 END IF;
 IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mes_cost_allocation' AND column_name='quantity' AND numeric_precision<20) THEN
  ALTER TABLE mes_cost_allocation MODIFY quantity DECIMAL(20,6) NOT NULL;
 END IF;
 DO RELEASE_LOCK(CONCAT(DATABASE(), ':return-cost-v1'));
END$$
DELIMITER ;
CALL fazemes_return_cost_20261020();
DROP PROCEDURE fazemes_return_cost_20261020;
