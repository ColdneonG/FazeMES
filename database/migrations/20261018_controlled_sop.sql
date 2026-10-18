-- Controlled SOP: product/process binding, immutable versions and task execution evidence.
-- MySQL 8; repeatable. No existing production records are changed.
SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS mes_sop_document (
  id bigint NOT NULL AUTO_INCREMENT,
  code varchar(64) NOT NULL,
  name varchar(200) NOT NULL,
  item_id bigint NOT NULL,
  process_id bigint NOT NULL,
  creator varchar(64) NOT NULL DEFAULT '',
  create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updater varchar(64) NOT NULL DEFAULT '',
  update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted bit(1) NOT NULL DEFAULT b'0',
  tenant_id bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sop_binding (tenant_id,item_id,process_id),
  UNIQUE KEY uk_sop_code (tenant_id,code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mes_sop_version (
  id bigint NOT NULL AUTO_INCREMENT,
  document_id bigint NOT NULL,
  version_no varchar(64) NOT NULL,
  title varchar(200) NOT NULL,
  change_note text NULL,
  status varchar(20) NOT NULL DEFAULT 'DRAFT',
  steps_json longtext NOT NULL,
  attachments_json text NOT NULL,
  model_url varchar(2048) NOT NULL DEFAULT '',
  submitted_by bigint NULL,
  submitted_at datetime NULL,
  reviewed_by bigint NULL,
  reviewed_at datetime NULL,
  review_comment text NULL,
  published_by bigint NULL,
  published_at datetime NULL,
  disabled_by bigint NULL,
  disabled_at datetime NULL,
  disable_reason text NULL,
  creator varchar(64) NOT NULL DEFAULT '',
  create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updater varchar(64) NOT NULL DEFAULT '',
  update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted bit(1) NOT NULL DEFAULT b'0',
  tenant_id bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sop_version (tenant_id,document_id,version_no),
  KEY idx_sop_release (tenant_id,document_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mes_sop_task_snapshot (
  id bigint NOT NULL AUTO_INCREMENT,
  task_id bigint NOT NULL,
  item_id bigint NOT NULL,
  process_id bigint NOT NULL,
  document_id bigint NOT NULL,
  version_id bigint NOT NULL,
  version_no varchar(64) NOT NULL,
  title varchar(200) NOT NULL,
  steps_json longtext NOT NULL,
  attachments_json text NOT NULL,
  model_url varchar(2048) NOT NULL DEFAULT '',
  creator varchar(64) NOT NULL DEFAULT '',
  create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updater varchar(64) NOT NULL DEFAULT '',
  update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted bit(1) NOT NULL DEFAULT b'0',
  tenant_id bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sop_task (tenant_id,task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mes_sop_step_record (
  id bigint NOT NULL AUTO_INCREMENT,
  snapshot_id bigint NOT NULL,
  step_id varchar(64) NOT NULL,
  viewed_at datetime NOT NULL,
  viewed_by bigint NOT NULL,
  confirmed_at datetime NULL,
  confirmed_by bigint NULL,
  parameters_json text NULL,
  photos_json text NULL,
  creator varchar(64) NOT NULL DEFAULT '',
  create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updater varchar(64) NOT NULL DEFAULT '',
  update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted bit(1) NOT NULL DEFAULT b'0',
  tenant_id bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sop_step_record (tenant_id,snapshot_id,step_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 971100,'受控SOP','',2,15,959,'controlled-sop','ep:document-checked','mes/pro/sop/index','MesControlledSop',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=971100);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 971101,'查询SOP','mes:sop:query',3,1,971100,'','','','',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=971101);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 971102,'新建SOP','mes:sop:create',3,2,971100,'','','','',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=971102);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 971103,'编辑SOP草稿','mes:sop:update',3,3,971100,'','','','',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=971103);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 971104,'提交SOP','mes:sop:submit',3,4,971100,'','','','',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=971104);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 971105,'审批SOP','mes:sop:approve',3,5,971100,'','','','',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=971105);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 971106,'发布SOP','mes:sop:publish',3,6,971100,'','','','',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=971106);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 971107,'停用SOP','mes:sop:disable',3,7,971100,'','','','',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=971107);
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
SELECT 971108,'执行任务SOP','mes:sop:execute',3,8,971100,'','','','',0,b'1',b'0',b'1','1','1',b'0'
WHERE NOT EXISTS (SELECT 1 FROM system_menu WHERE id=971108);
INSERT INTO system_role_menu (role_id,menu_id,creator,updater,deleted,tenant_id)
SELECT 1,m.id,'1','1',b'0',1 FROM system_menu m
WHERE m.id BETWEEN 971100 AND 971108
AND NOT EXISTS (SELECT 1 FROM system_role_menu rm WHERE rm.role_id=1 AND rm.menu_id=m.id AND rm.tenant_id=1 AND rm.deleted=b'0');
