-- Individual SN traceability. Allocation uses completed actual consumption only;
-- these tables do not generate stock movements or change existing business rows.
-- MySQL 8, additive and rerunnable. Snapshot rows are append-only through the API.
SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS mes_pro_sn_trace (
 id bigint NOT NULL AUTO_INCREMENT, sn_id bigint NOT NULL, sn_code varchar(128) NOT NULL,
 work_order_id bigint NOT NULL, product_id bigint NOT NULL, product_batch_code varchar(128) NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'REGISTERED',
 creator varchar(64) DEFAULT '', create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updater varchar(64) DEFAULT '', update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 deleted bit(1) NOT NULL DEFAULT b'0', tenant_id bigint NOT NULL DEFAULT 0,
 PRIMARY KEY (id), UNIQUE KEY uk_sn_trace_sn (tenant_id,sn_id),
 UNIQUE KEY uk_sn_trace_code (tenant_id,sn_code), KEY idx_sn_trace_order (tenant_id,work_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Explicit single-piece SN identity';
CREATE TABLE IF NOT EXISTS mes_pro_sn_station (
 id bigint NOT NULL AUTO_INCREMENT, trace_id bigint NOT NULL,
 request_key varchar(64) NOT NULL, request_hash varchar(64) NOT NULL,
 work_order_id bigint NOT NULL, feedback_id bigint NOT NULL, feedback_code varchar(128) DEFAULT NULL,
 task_id bigint NOT NULL, route_id bigint NOT NULL, process_id bigint NOT NULL, process_sort int NOT NULL,
 workstation_id bigint DEFAULT NULL, operator_id bigint NOT NULL, feedback_user_id bigint DEFAULT NULL,
 station_time datetime NOT NULL, feedback_time datetime DEFAULT NULL,
 creator varchar(64) DEFAULT '', create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updater varchar(64) DEFAULT '', update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 deleted bit(1) NOT NULL DEFAULT b'0', tenant_id bigint NOT NULL DEFAULT 0,
 PRIMARY KEY (id), UNIQUE KEY uk_sn_station_request (tenant_id,trace_id,request_key),
 UNIQUE KEY uk_sn_station_process (tenant_id,trace_id,process_id),
 KEY idx_sn_station_feedback (tenant_id,feedback_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Immutable approved-feedback station snapshots';
CREATE TABLE IF NOT EXISTS mes_pro_sn_material (
 id bigint NOT NULL AUTO_INCREMENT, trace_id bigint NOT NULL, station_id bigint NOT NULL,
 feedback_id bigint NOT NULL, consume_id bigint NOT NULL, consume_line_id bigint NOT NULL,
 consume_detail_id bigint NOT NULL, material_stock_id bigint NOT NULL, item_id bigint NOT NULL,
 batch_id bigint DEFAULT NULL, batch_code varchar(128) DEFAULT NULL, quantity decimal(24,6) NOT NULL,
 creator varchar(64) DEFAULT '', create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updater varchar(64) DEFAULT '', update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 deleted bit(1) NOT NULL DEFAULT b'0', tenant_id bigint NOT NULL DEFAULT 0,
 PRIMARY KEY (id), UNIQUE KEY uk_sn_material_station_detail (tenant_id,station_id,consume_detail_id),
 KEY idx_sn_material_source (tenant_id,feedback_id,consume_detail_id),
 KEY idx_sn_material_batch (tenant_id,batch_id), KEY idx_sn_material_batch_code (tenant_id,batch_code),
 KEY idx_sn_material_trace (tenant_id,trace_id), CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Single-piece shares of completed WIP batch consumption';

INSERT INTO system_menu
 (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 971140,'单件SN追溯','',2,26,959,'sntrace','ep:connection','mes/pro/sntrace/index','MesProSnTrace',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=971140);
INSERT INTO system_menu
 (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 971141,'查询SN及批次影响','mes:sn-trace:query',3,1,971140,'','','','',0,b'1',b'0',b'0','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=971141);
INSERT INTO system_menu
 (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 971142,'登记单件SN','mes:sn-trace:create',3,2,971140,'','','','',0,b'1',b'0',b'0','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=971142);
INSERT INTO system_menu
 (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 971143,'单件过站与用料分配','mes:sn-trace:station',3,3,971140,'','','','',0,b'1',b'0',b'0','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=971143);
INSERT INTO system_role_menu (role_id,menu_id,creator,updater,deleted,tenant_id)
SELECT 1,m.id,'1','1',b'0',1 FROM system_menu m WHERE m.id BETWEEN 971140 AND 971143
AND NOT EXISTS (SELECT 1 FROM system_role_menu rm WHERE rm.role_id=1 AND rm.menu_id=m.id AND rm.tenant_id=1 AND rm.deleted=b'0');
