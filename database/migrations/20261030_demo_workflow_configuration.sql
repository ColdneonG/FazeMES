-- Add missing demo configuration without replacing user-defined numbering rules or business data.
SET NAMES utf8mb4;
START TRANSACTION;
INSERT INTO mes_md_auto_code_rule
  (code,name,description,max_length,padded,padded_char,padded_method,status,remark,creator,updater,tenant_id)
SELECT 'WM_PRODUCT_ISSUE_CODE','生产领料出库单编码','前缀+日期+每日五位流水号',64,b'0','0',1,0,
       '20261030 demo repair','system','system',t.id
FROM system_tenant t WHERE t.deleted=b'0' AND t.id>0 AND NOT EXISTS
  (SELECT 1 FROM mes_md_auto_code_rule r WHERE r.tenant_id=t.id AND r.code='WM_PRODUCT_ISSUE_CODE' AND r.deleted=b'0');
INSERT INTO mes_md_auto_code_part
  (rule_id,sort,type,length,fix_character,date_format,serial_start_no,serial_step,cycle_flag,cycle_method,remark,creator,updater,tenant_id)
SELECT r.id,p.sort,p.type,p.length,p.prefix,p.date_format,p.start_no,p.step,p.cycle_flag,p.cycle_method,
       '20261030 demo repair','system','system',r.tenant_id
FROM mes_md_auto_code_rule r CROSS JOIN (
  SELECT 1 sort,3 type,9 length,'WM-ISSUE-' prefix,NULL date_format,NULL start_no,NULL step,b'0' cycle_flag,NULL cycle_method
  UNION ALL SELECT 2,2,8,NULL,'yyyyMMdd',NULL,NULL,b'0',NULL
  UNION ALL SELECT 3,4,5,NULL,NULL,1,1,b'1',3
) p
WHERE r.code='WM_PRODUCT_ISSUE_CODE' AND r.deleted=b'0' AND r.remark='20261030 demo repair'
  AND NOT EXISTS (SELECT 1 FROM mes_md_auto_code_part x WHERE x.rule_id=r.id AND x.sort=p.sort AND x.deleted=b'0');
INSERT INTO system_dict_data
  (sort,label,value,dict_type,status,color_type,css_class,remark,creator,updater)
SELECT 10,'待填写运单','10','mes_wm_product_sales_status',0,'warning','','销售拣货后的运单填写状态','system','system'
WHERE NOT EXISTS (SELECT 1 FROM system_dict_data WHERE dict_type='mes_wm_product_sales_status' AND value='10' AND deleted=b'0');
COMMIT;
