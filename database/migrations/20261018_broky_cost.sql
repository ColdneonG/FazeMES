-- Apply before enabling the new cost API. No demo execution or balances are fabricated.
-- MySQL 8; all prices are CNY exclusive of tax. NULL is unknown, never zero.
CREATE TABLE IF NOT EXISTS mes_cost_profile (
 id bigint NOT NULL AUTO_INCREMENT, tenant_id bigint NOT NULL, item_id bigint NOT NULL,
 version int NOT NULL, purchase_price decimal(18,6) NULL, sale_price decimal(18,6) NULL,
 labor_cost decimal(18,6) NULL, overhead_cost decimal(18,6) NULL, remark varchar(500) NULL,
 creator varchar(64) NOT NULL DEFAULT '', create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
 deleted bit NOT NULL DEFAULT b'0', PRIMARY KEY(id), UNIQUE KEY uk_profile_version(tenant_id,item_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Append-only cost/price versions; CNY tax exclusive';
CREATE TABLE IF NOT EXISTS mes_cost_workorder (
 id bigint NOT NULL AUTO_INCREMENT, tenant_id bigint NOT NULL, workorder_id bigint NOT NULL,
 plan_snapshot longtext NOT NULL, plan_quantity decimal(18,6) NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'PROVISIONAL', actual_material_cost decimal(24,6) NULL,
 actual_labor_cost decimal(24,6) NULL, actual_overhead_cost decimal(24,6) NULL,
 actual_total_cost decimal(24,6) NULL, produced_quantity decimal(18,6) NULL,
 actual_unit_cost decimal(24,8) NULL, settled_time datetime NULL, settled_by varchar(64) NULL,
 create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP, deleted bit NOT NULL DEFAULT b'0',
 PRIMARY KEY(id), UNIQUE KEY uk_cost_order(tenant_id,workorder_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS mes_cost_effort (
 id bigint NOT NULL AUTO_INCREMENT, tenant_id bigint NOT NULL, workorder_id bigint NOT NULL,
 process_id bigint NOT NULL, hours decimal(12,4) NOT NULL, hourly_rate decimal(18,6) NOT NULL,
 overhead_amount decimal(18,6) NOT NULL, remark varchar(500) NOT NULL, request_id varchar(64) NOT NULL,
 creator varchar(64) NOT NULL DEFAULT '', create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
 voided bit NOT NULL DEFAULT b'0', void_reason varchar(500) NULL, void_time datetime NULL, void_by varchar(64) NULL,
 deleted bit NOT NULL DEFAULT b'0', PRIMARY KEY(id), UNIQUE KEY uk_effort_request(tenant_id,request_id),
 KEY idx_effort_order(tenant_id,workorder_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS mes_cost_line_price (
 id bigint NOT NULL AUTO_INCREMENT, tenant_id bigint NOT NULL, kind varchar(16) NOT NULL,
 line_id bigint NOT NULL, unit_price decimal(18,6) NOT NULL, remark varchar(500) NULL,
 creator varchar(64) NOT NULL DEFAULT '', create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 deleted bit NOT NULL DEFAULT b'0', PRIMARY KEY(id), UNIQUE KEY uk_line_price(tenant_id,kind,line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS mes_cost_stock_layer (
 id bigint NOT NULL AUTO_INCREMENT, tenant_id bigint NOT NULL, material_stock_id bigint NOT NULL,
 inbound_transaction_id bigint NULL, origin_workorder_id bigint NULL,
 quantity decimal(18,6) NOT NULL, remaining_quantity decimal(18,6) NOT NULL,
 unit_cost decimal(24,8) NULL, status varchar(20) NOT NULL, create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
 deleted bit NOT NULL DEFAULT b'0', PRIMARY KEY(id), KEY idx_cost_stock(tenant_id,material_stock_id,id),
 KEY idx_cost_origin(tenant_id,origin_workorder_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='FIFO quantity layers, transfers preserve provenance';
CREATE TABLE IF NOT EXISTS mes_cost_allocation (
 id bigint NOT NULL AUTO_INCREMENT, tenant_id bigint NOT NULL, transaction_id bigint NOT NULL,
 layer_id bigint NOT NULL, quantity decimal(18,6) NOT NULL, unit_cost decimal(24,8) NULL,
 material_amount decimal(24,8) NULL, labor_amount decimal(24,8) NULL, overhead_amount decimal(24,8) NULL,
 status varchar(20) NOT NULL, create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
 deleted bit NOT NULL DEFAULT b'0', PRIMARY KEY(id), UNIQUE KEY uk_cost_allocation(tenant_id,transaction_id,layer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Immutable outbound valuation snapshot';
-- Cost permissions deliberately separate from ordinary inventory and customer roles.
SET @cost_parent = (SELECT id FROM system_menu WHERE component='mes/md/item/index' AND deleted=b'0' ORDER BY id LIMIT 1);
INSERT INTO system_menu(name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT '价格与成本','mes:cost:query',2,90,IFNULL((SELECT parent_id FROM system_menu WHERE id=@cost_parent),0),'cost','ep:coin','mes/cost/index','MesCost',0,b'1',b'1',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE permission='mes:cost:query' AND deleted=b'0');
SET @cost_menu = (SELECT id FROM system_menu WHERE permission='mes:cost:query' AND deleted=b'0' ORDER BY id LIMIT 1);
INSERT INTO system_menu(name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT '成本维护','mes:cost:update',3,1,@cost_menu,'','','','',0,b'1',b'1',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE permission='mes:cost:update' AND deleted=b'0');
INSERT INTO system_menu(name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT '成本结算','mes:cost:settle',3,2,@cost_menu,'','','','',0,b'1',b'1',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE permission='mes:cost:settle' AND deleted=b'0');
