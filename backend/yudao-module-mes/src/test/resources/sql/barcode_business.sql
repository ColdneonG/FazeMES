-- Independent resolver fixture; only actual source columns are present (no invented generic status).
CREATE TABLE mes_wm_warehouse(id BIGINT PRIMARY KEY,code VARCHAR(128),name VARCHAR(255),frozen INT,tenant_id BIGINT,deleted INT);
CREATE TABLE mes_wm_warehouse_location(id BIGINT PRIMARY KEY,code VARCHAR(128),name VARCHAR(255),frozen INT,tenant_id BIGINT,deleted INT);
CREATE TABLE mes_wm_warehouse_area(id BIGINT PRIMARY KEY,code VARCHAR(128),name VARCHAR(255),status INT,frozen INT,tenant_id BIGINT,deleted INT);
CREATE TABLE mes_wm_package(id BIGINT PRIMARY KEY,code VARCHAR(128),status INT,tenant_id BIGINT,deleted INT);
CREATE TABLE mes_wm_material_stock(id BIGINT PRIMARY KEY,item_id BIGINT,quantity DECIMAL(20,6),frozen INT,quality_hold INT,tenant_id BIGINT,deleted INT);
CREATE TABLE mes_wm_batch(id BIGINT PRIMARY KEY,code VARCHAR(128),item_id BIGINT,quality_status INT,tenant_id BIGINT,deleted INT);
CREATE TABLE mes_wm_sn(id BIGINT PRIMARY KEY,code VARCHAR(128),item_id BIGINT,uuid VARCHAR(128),tenant_id BIGINT,deleted INT);
CREATE TABLE mes_pro_card(id BIGINT PRIMARY KEY,code VARCHAR(128),status INT,tenant_id BIGINT,deleted INT);
CREATE TABLE mes_pro_work_order(id BIGINT PRIMARY KEY,code VARCHAR(128),name VARCHAR(255),status INT,tenant_id BIGINT,deleted INT);
CREATE TABLE mes_dv_machinery(id BIGINT PRIMARY KEY,code VARCHAR(128),name VARCHAR(255),status INT,tenant_id BIGINT,deleted INT);
CREATE TABLE mes_tm_tool(id BIGINT PRIMARY KEY,code VARCHAR(128),name VARCHAR(255),status INT,tenant_id BIGINT,deleted INT);
CREATE TABLE mes_md_item(id BIGINT PRIMARY KEY,code VARCHAR(128),name VARCHAR(255),status INT,tenant_id BIGINT,deleted INT);
CREATE TABLE mes_md_vendor(id BIGINT PRIMARY KEY,code VARCHAR(128),name VARCHAR(255),status INT,tenant_id BIGINT,deleted INT);
CREATE TABLE mes_md_workstation(id BIGINT PRIMARY KEY,code VARCHAR(128),name VARCHAR(255),status INT,tenant_id BIGINT,deleted INT);
CREATE TABLE mes_md_workshop(id BIGINT PRIMARY KEY,code VARCHAR(128),name VARCHAR(255),status INT,tenant_id BIGINT,deleted INT);
CREATE TABLE system_users(id BIGINT PRIMARY KEY,username VARCHAR(128),nickname VARCHAR(255),status INT,password VARCHAR(255),tenant_id BIGINT,deleted INT);
CREATE TABLE mes_md_client(id BIGINT PRIMARY KEY,code VARCHAR(128),name VARCHAR(255),status INT,tenant_id BIGINT,deleted INT);
