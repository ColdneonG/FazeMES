-- Order-specific reservations: allocation is independent from the physical stock balance.
CREATE TABLE IF NOT EXISTS mes_pro_material_reservation (
 id bigint NOT NULL AUTO_INCREMENT, work_order_id bigint NOT NULL, item_id bigint NOT NULL,
 material_stock_id bigint NOT NULL, quantity decimal(24,6) NOT NULL DEFAULT 0,
 allocated_quantity decimal(24,6) NOT NULL DEFAULT 0, consumed_quantity decimal(24,6) NOT NULL DEFAULT 0,
 released_quantity decimal(24,6) NOT NULL DEFAULT 0,
 creator varchar(64) NOT NULL DEFAULT '', create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updater varchar(64) NOT NULL DEFAULT '', update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 deleted bit(1) NOT NULL DEFAULT b'0', tenant_id bigint NOT NULL DEFAULT 0,
 PRIMARY KEY(id), UNIQUE KEY uk_reservation_order_stock(tenant_id,work_order_id,material_stock_id),
 KEY idx_reservation_stock(tenant_id,material_stock_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS mes_pro_shortage_followup (
 id bigint NOT NULL AUTO_INCREMENT, work_order_id bigint NOT NULL, item_id bigint NOT NULL,
 expected_date date NULL, tracking_status varchar(20) NOT NULL DEFAULT 'OPEN', remark varchar(500) NULL,
 creator varchar(64) NOT NULL DEFAULT '', create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updater varchar(64) NOT NULL DEFAULT '', update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 deleted bit(1) NOT NULL DEFAULT b'0', tenant_id bigint NOT NULL DEFAULT 0,
 PRIMARY KEY(id), UNIQUE KEY uk_shortage_order_item(tenant_id,work_order_id,item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS mes_pro_reservation_event (
 id bigint NOT NULL AUTO_INCREMENT, work_order_id bigint NOT NULL, item_id bigint NOT NULL,
 material_stock_id bigint NOT NULL, reservation_id bigint NOT NULL, action varchar(32) NOT NULL,
 quantity decimal(24,6) NOT NULL, reference_id bigint NULL, operator_id bigint NULL,
 creator varchar(64) NOT NULL DEFAULT '', create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updater varchar(64) NOT NULL DEFAULT '', update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 deleted bit(1) NOT NULL DEFAULT b'0', tenant_id bigint NOT NULL DEFAULT 0,
 PRIMARY KEY(id), KEY idx_reservation_event_order(tenant_id,work_order_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 971000,'齐套缺料分析','',2,14,959,'kitting','ep:box','mes/pro/kitting/index','MesProKitting',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=971000);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 971001,'查询齐套','mes:kitting:query',3,1,971000,'','','','',0,b'1',b'0',b'1','1','1',b'0' WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=971001);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 971002,'工单预占','mes:kitting:reserve',3,2,971000,'','','','',0,b'1',b'0',b'1','1','1',b'0' WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=971002);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 971003,'释放预占','mes:kitting:release',3,3,971000,'','','','',0,b'1',b'0',b'1','1','1',b'0' WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=971003);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 971004,'缺料跟进','mes:kitting:followup',3,4,971000,'','','','',0,b'1',b'0',b'1','1','1',b'0' WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=971004);
INSERT INTO system_role_menu (role_id,menu_id,creator,updater,deleted,tenant_id)
SELECT 1,m.id,'1','1',b'0',1 FROM system_menu m WHERE m.id BETWEEN 971000 AND 971004
AND NOT EXISTS (SELECT 1 FROM system_role_menu rm WHERE rm.role_id=1 AND rm.menu_id=m.id AND rm.tenant_id=1 AND rm.deleted=b'0');
