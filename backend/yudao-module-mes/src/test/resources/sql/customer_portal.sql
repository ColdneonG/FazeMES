-- Customer portal adapted from yunshu-mes-all client catalog/order flow.
-- Isolated H2 schema. These records are test fixtures only, never production seeds.
CREATE TABLE system_tenant(id BIGINT PRIMARY KEY, status INT DEFAULT 0, deleted INT DEFAULT 0, expire_time TIMESTAMP);
CREATE TABLE mes_md_client(id BIGINT PRIMARY KEY, tenant_id BIGINT, code VARCHAR(128), name VARCHAR(255), status INT DEFAULT 0, deleted INT DEFAULT 0, address VARCHAR(255), credit_code VARCHAR(255));
CREATE TABLE mes_md_item(id BIGINT PRIMARY KEY, tenant_id BIGINT, code VARCHAR(128), name VARCHAR(255), specification VARCHAR(255), unit_measure_id BIGINT, status INT DEFAULT 0, deleted INT DEFAULT 0, remark VARCHAR(255));
CREATE TABLE mes_md_unit_measure(id BIGINT PRIMARY KEY, tenant_id BIGINT, name VARCHAR(255), deleted INT DEFAULT 0);
CREATE TABLE mes_pro_work_order(id BIGINT PRIMARY KEY, tenant_id BIGINT, client_id BIGINT, product_id BIGINT, code VARCHAR(128), name VARCHAR(255), quantity DECIMAL(20,6), quantity_produced DECIMAL(20,6), request_date TIMESTAMP, finish_date TIMESTAMP, status INT, deleted INT DEFAULT 0, remark VARCHAR(255), vendor_id BIGINT);

CREATE TABLE IF NOT EXISTS mes_portal_account (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  client_id BIGINT NOT NULL,
  username VARCHAR(64) NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  enabled BIT(1) NOT NULL DEFAULT 1,
  auth_version BIGINT NOT NULL DEFAULT 1,
  failed_attempts INT NOT NULL DEFAULT 0,
  locked_until DATETIME DEFAULT NULL,
  expire_time DATETIME DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted BIT(1) NOT NULL DEFAULT 0,
  CONSTRAINT uk_portal_username UNIQUE (tenant_id,username)
);
CREATE TABLE IF NOT EXISTS mes_portal_session (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  token_hash CHAR(64) NOT NULL,
  auth_version BIGINT NOT NULL,
  expires_at DATETIME NOT NULL,
  revoked BIT(1) NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_portal_session_hash UNIQUE (token_hash)
);
CREATE TABLE IF NOT EXISTS mes_portal_catalog (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  description VARCHAR(1000) NOT NULL DEFAULT '',
  enabled BIT(1) NOT NULL DEFAULT 1,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_portal_catalog UNIQUE (tenant_id,product_id)
);
CREATE TABLE IF NOT EXISTS mes_portal_request (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  client_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  code VARCHAR(64) NOT NULL,
  request_key VARCHAR(64) NOT NULL,
  delivery_date DATE NOT NULL,
  remark VARCHAR(1000) NOT NULL DEFAULT '',
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  review_note VARCHAR(1000) NOT NULL DEFAULT '',
  reviewer_id BIGINT DEFAULT NULL,
  review_time DATETIME DEFAULT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_portal_request UNIQUE (tenant_id,account_id,request_key),
  CONSTRAINT uk_portal_request_code UNIQUE (tenant_id,code)
);
CREATE TABLE IF NOT EXISTS mes_portal_request_line (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  request_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  product_code VARCHAR(128) NOT NULL,
  product_name VARCHAR(255) NOT NULL,
  specification VARCHAR(255) NOT NULL DEFAULT '',
  unit_name VARCHAR(255) NOT NULL DEFAULT '',
  quantity DECIMAL(20,6) NOT NULL
);
CREATE TABLE mes_cost_profile(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT NOT NULL,item_id BIGINT NOT NULL,version INT NOT NULL,sale_price DECIMAL(18,6),deleted INT DEFAULT 0);
