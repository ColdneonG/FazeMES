-- Operational loss closure preserves the original production plan and all report/stock records.
-- An immutable per-order record captures the reason, operator, time and verified process quantities.
SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS mes_pro_work_order_loss (
 tenant_id BIGINT NOT NULL,
 work_order_id BIGINT NOT NULL,
 planned_quantity DECIMAL(20,6) NOT NULL,
 produced_quantity DECIMAL(20,6) NOT NULL,
 qualified_quantity DECIMAL(20,6) NOT NULL,
 loss_quantity DECIMAL(20,6) NOT NULL,
 reason VARCHAR(500) NOT NULL,
 closed_by BIGINT NOT NULL,
 closed_at DATETIME(6) NOT NULL,
 process_evidence TEXT NOT NULL,
 allocation_method VARCHAR(40) NULL,
 allocation_by BIGINT NULL,
 allocation_at DATETIME(6) NULL,
 allocation_reason VARCHAR(500) NULL,
 PRIMARY KEY(tenant_id,work_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
