CREATE TABLE IF NOT EXISTS mes_face_credential (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 template_cipher TEXT NOT NULL,
 enabled TINYINT NOT NULL DEFAULT 1,
 creator VARCHAR(64) NOT NULL DEFAULT '',
 updater VARCHAR(64) NOT NULL DEFAULT '',
 create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 deleted BIT(1) NOT NULL DEFAULT b'0',
 UNIQUE KEY uk_face_user(tenant_id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO system_menu (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted) VALUES
(971290,'本人人脸登录','mes:face:enroll',2,92,959,'face','ep:camera','mes/face/index','MesFaceEnrollment',0,b'1',b'0',b'1','1','1',b'0')
ON DUPLICATE KEY UPDATE name=VALUES(name),permission=VALUES(permission),component=VALUES(component),path=VALUES(path),status=0,deleted=b'0';
INSERT INTO system_role_menu(role_id,menu_id,tenant_id,creator,updater)
SELECT r.id,971290,r.tenant_id,'1','1' FROM system_role r WHERE r.tenant_id=1 AND r.code IN('super_admin','tenant_admin') AND r.deleted=b'0'
AND NOT EXISTS(SELECT 1 FROM system_role_menu rm WHERE rm.role_id=r.id AND rm.menu_id=971290 AND rm.tenant_id=r.tenant_id AND rm.deleted=b'0');
UPDATE system_tenant_package SET menu_ids=JSON_ARRAY_APPEND(menu_ids,'$',971290) WHERE id IN(SELECT package_id FROM system_tenant WHERE id=1 AND deleted=b'0') AND JSON_VALID(menu_ids) AND NOT JSON_CONTAINS(menu_ids,CAST('971290' AS JSON));
INSERT INTO system_dict_data(id,sort,label,value,dict_type,status,color_type,css_class,remark,creator,updater,deleted)
SELECT 971290,105,'人脸登录','105','system_login_type',0,'success','','本地已绑定人脸一对一核验','1','1',b'0'
WHERE NOT EXISTS(SELECT 1 FROM system_dict_data WHERE dict_type='system_login_type' AND value='105' AND deleted=b'0');
