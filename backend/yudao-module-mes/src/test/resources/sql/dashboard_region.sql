-- Minimal independent H2 schema for MesDashboardRegionServiceTest; not a production migration.
CREATE TABLE mes_md_client (
    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, deleted INT NOT NULL DEFAULT 0,
    name VARCHAR(255), address VARCHAR(1000)
);
CREATE TABLE mes_pro_work_order (
    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, deleted INT NOT NULL DEFAULT 0,
    client_id BIGINT
);
CREATE TABLE mes_pro_task (
    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, deleted INT NOT NULL DEFAULT 0,
    work_order_id BIGINT, workstation_id BIGINT, status INT,
    start_time TIMESTAMP, end_time TIMESTAMP
);
CREATE TABLE mes_md_workstation (
    id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, deleted INT NOT NULL DEFAULT 0,
    workshop_id BIGINT
);
