-- Preserve existing balances. Historical WIP without an attributable order remains unassigned.
-- New transactions never consume unassigned WIP or another order's stock.
SET NAMES utf8mb4;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='mes_wm_material_stock' AND column_name='work_order_id'),
 'SELECT 1', 'ALTER TABLE mes_wm_material_stock ADD COLUMN work_order_id BIGINT NULL COMMENT ''线边库存所属工单；空为实物仓或历史待核对''');
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name='mes_wm_transaction' AND column_name='work_order_id'),
 'SELECT 1', 'ALTER TABLE mes_wm_transaction ADD COLUMN work_order_id BIGINT NULL COMMENT ''生产工单追溯''');
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics
 WHERE table_schema=DATABASE() AND table_name='mes_wm_material_stock' AND index_name='idx_stock_order_scope'),
 'SELECT 1', 'CREATE INDEX idx_stock_order_scope ON mes_wm_material_stock(tenant_id,work_order_id,item_id,warehouse_id,area_id,batch_id,vendor_id,deleted)');
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;
