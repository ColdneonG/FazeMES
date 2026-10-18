-- Local sample presentation data only. Run after system SQL, MES seed and local storage setup.
-- Accounts/passwords, IDs, permissions, table names and Java/API identifiers are unchanged.
-- No DROP/DELETE/TRUNCATE. Predicates target known upstream sample records/display values.
-- This migration is safe to repeat and never selects the former messys database.
USE `yudao_mes`;
SET NAMES utf8mb4;
SET @example_avatar = 'http://127.0.0.1:5173/placeholder-avatar.svg';
SET @example_logo = 'http://127.0.0.1:5173/placeholder-logo.svg';
SET @example_image = 'http://127.0.0.1:5173/placeholder-image.svg';
START TRANSACTION;

-- Keep the default tenant and login account identifiers intact.
UPDATE `system_tenant`
SET `name` = '示例企业', `contact_name` = '示例联系人'
WHERE `id` = 1;
UPDATE `system_tenant`
SET `name` = CONCAT('示例企业', `id`), `contact_name` = CONCAT('示例联系人', `id`)
WHERE `id` IN (121, 122)
  AND `name` IN ('小租户', '测试租户', '示例企业121', '示例企业122');
UPDATE `system_tenant`
SET `websites` = REPLACE(REPLACE(REPLACE(`websites`, 'www.iocoder.cn', '127.0.0.1:5173'),
                                  'zsxq.iocoder.cn', 'tenant121.example.invalid'),
                                  'test.iocoder.cn', 'tenant122.example.invalid')
WHERE `id` IN (1, 121, 122) AND `websites` REGEXP '(iocoder[.]cn)';

UPDATE `system_users`
SET `nickname` = '示例管理员', `avatar` = @example_avatar
WHERE `id` = 1 AND `username` = 'admin';
UPDATE `system_users`
SET `nickname` = CONCAT('示例用户', `id`)
WHERE (`id` = 100 AND `nickname` = '芋道')
   OR (`id` = 103 AND `nickname` = '源码')
   OR (`id` = 104 AND `nickname` = '测试号')
   OR (`id` IN (107, 108, 109) AND `nickname` = '芋艿')
   OR (`id` = 110 AND `nickname` = '小王')
   OR (`id` = 111 AND `nickname` = '测试用户')
   OR (`id` = 112 AND `nickname` = '新对象')
   OR (`id` = 113 AND `nickname` = '芋道1')
   OR (`id` = 114 AND `nickname` = 'hr 小姐姐')
   OR (`id` = 115 AND `nickname` = '阿呆')
   OR (`id` = 117 AND `nickname` = '测试号02')
   OR (`id` = 118 AND `nickname` = '狗蛋')
   OR (`id` = 139 AND `nickname` = '小秃头')
   OR (`id` = 141 AND `nickname` = '新用户')
   OR (`id` = 142 AND `nickname` = 'test01')
   OR (`id` = 143 AND `nickname` = 'a00001')
   OR (`id` = 144 AND `nickname` = 'aoteman00112')
   OR (`id` = 240 AND `nickname` = 'HRM团队负责人');
UPDATE `system_users`
SET `avatar` = @example_avatar
WHERE `id` IN (1,100,103,104,107,108,109,110,111,112,113,114,115,117,118,139,141,142,143,144,240)
  AND (`avatar` IS NULL OR `avatar` = '' OR `avatar` REGEXP '(iocoder[.]cn|yudao|yunai)');
UPDATE `system_users`
SET `email` = CONCAT('user', `id`, '@example.invalid')
WHERE `id` IN (1,100,103,104,115)
  AND `email` IN ('13aoteman@126.com', 'yudao@iocoder.cn', 'yuanma@iocoder.cn', '111@qq.com', '7648@qq.com');
UPDATE `system_users`
SET `remark` = '示例用户'
WHERE `id` IN (100,115,117,139,240)
  AND `remark` IN ('不要吓我', '11222', '1111', '123', '本地团队工作台演示账号');

UPDATE `system_dept` SET `name` = '示例企业'
WHERE `id` = 100 AND `tenant_id` = 1 AND `name` IN ('芋道源码', '示例企业');
UPDATE `system_dept` SET `name` = '示例总公司'
WHERE `id` = 101 AND `tenant_id` = 1 AND `name` = '深圳总公司';
UPDATE `system_dept` SET `name` = '示例分公司'
WHERE `id` = 102 AND `tenant_id` = 1 AND `name` = '长沙分公司';

-- Keep promotion menu rows/IDs/permissions for traceability, with no active external links.
UPDATE `system_menu`
SET `name` = CONCAT('示例帮助入口', `id`), `path` = CONCAT('example-reference-', `id`),
    `status` = 1, `visible` = b'0'
WHERE `id` IN (194, 347, 348) AND `path` REGEXP '(iocoder[.]cn)';

UPDATE `system_notice` SET `title` = '示例公告', `content` = '<p>这是一条示例公告。</p>'
WHERE `id` = 1 AND `title` = '芋道的公众';
UPDATE `system_notice`
SET `title` = '示例维护通知',
    `content` = CONCAT('<p>这是一条示例维护通知。</p><p><img src="', @example_image, '" alt="示例图片"></p>')
WHERE `id` = 2 AND (`title` = '维护通知：2018-07-01 系统凌晨维护' OR `content` REGEXP '(iocoder[.]cn|yudao|yunai)');
UPDATE `system_notice` SET `title` = '示例通知', `content` = '<p>这是一条示例通知。</p>'
WHERE `id` = 4 AND `title` = '我是测试标题';

-- OAuth client_id / secret / grant types are functional identifiers and stay unchanged.
UPDATE `system_oauth2_client` SET `name` = '示例应用', `description` = '示例应用说明'
WHERE `id` = 1 AND `name` IN ('芋道源码', '示例应用');
UPDATE `system_oauth2_client` SET `name` = CONCAT('示例应用', `id`), `description` = '示例应用说明'
WHERE (`id` = 40 AND `name` = 'biubiu')
   OR (`id` = 41 AND `name` = '基于授权码模式，如何实现 SSO 单点登录？')
   OR (`id` = 42 AND `name` = '基于密码模式，如何实现 SSO 单点登录？');
UPDATE `system_oauth2_client` SET `logo` = @example_logo
WHERE `id` IN (1,40,41,42) AND (`logo` REGEXP '(iocoder[.]cn|yudao|yunai)' OR `logo` = '');
UPDATE `system_oauth2_client` SET `redirect_uris` = '["http://127.0.0.1:5173"]'
WHERE `id` IN (1,40) AND `redirect_uris` REGEXP '(iocoder[.]cn)';
UPDATE `system_social_client` SET `name` = '示例社交应用'
WHERE `id` = 2 AND `name` = '钉钉（王土豆）';
UPDATE `system_mail_template` SET `nickname` = '示例发件人'
WHERE `id` = 14 AND `nickname` = '芋艿';
UPDATE `system_mail_account` SET `host` = 'mail.example.invalid'
WHERE `id` = 3 AND `host` = 'yunai1.cn';
UPDATE `system_notify_message`
SET `template_content` = REPLACE(`template_content`, '芋艿', '示例用户'),
    `template_params` = REPLACE(`template_params`, '芋艿', '示例用户')
WHERE `id` IN (5,6) AND `template_content` LIKE '%芋艿%';
UPDATE `system_notify_message`
SET `template_params` = REPLACE(`template_params`, '芋道源码', '示例管理员')
WHERE `id` IN (58,59) AND `template_params` LIKE '%芋道源码%';

-- Only upstream sample storage presentation values are substituted. Credentials,
-- providers/storage types and polymorphic @class values must not be string-replaced.
UPDATE `infra_file_config` SET `name` = '示例本地附件'
WHERE `id` = 29;
UPDATE `infra_file_config`
SET `name` = CONCAT('示例存储配置', `id`), `remark` = '示例存储配置'
WHERE `id` IN (4,22,24,25,26,27,28,30,34,35)
  AND `name` IN ('数据库（示例）','七牛存储器（示例）','腾讯云存储（示例）','阿里云存储（示例）',
                '火山云存储（示例）','华为云存储（示例）','MinIO 存储（示例）','SFTP 存储（示例）',
                '七牛云存储【私有】（示例）','1');
UPDATE `infra_file_config`
SET `config` = JSON_SET(`config`, '$.domain', 'http://127.0.0.1:5173')
WHERE `id` IN (22,24,25,26,27,28,34,35) AND JSON_VALID(`config`)
  AND JSON_UNQUOTE(JSON_EXTRACT(`config`, '$.domain')) REGEXP '(iocoder[.]cn|yudao|yunai|clouddn[.]com|xxx[.]com)';
UPDATE `infra_file_config`
SET `config` = JSON_SET(`config`, '$.bucket', 'example-bucket')
WHERE `id` IN (22,25,26,27,28,34) AND JSON_VALID(`config`)
  AND JSON_UNQUOTE(JSON_EXTRACT(`config`, '$.bucket')) IN ('ruoyi-vue-pro','yunai-aoteman','yunai','yudao','ruoyi-vue-pro-private');
UPDATE `infra_file_config`
SET `config` = JSON_SET(`config`, '$.basePath', 'D:/indus_app/yudao-mes/.runtime/uploads')
WHERE `id` = 29 AND JSON_VALID(`config`)
  AND JSON_UNQUOTE(JSON_EXTRACT(`config`, '$.basePath')) = '/Users/yunai/tmp/file';

-- Known public code-generation samples; technical table names intentionally stay.
UPDATE `yudao_demo01_contact`
SET `name` = '示例联系人1', `description` = '<p>示例联系人说明。</p>'
WHERE `id` = 1 AND `name` = '土豆';
UPDATE `yudao_demo02_category` SET `name` = '示例分类1'
WHERE `id` = 1 AND `name` = '土豆';

COMMIT;
