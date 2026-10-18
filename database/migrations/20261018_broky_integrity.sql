-- Additive serialization point for native multi-level BOM edits. No inventory or business results seeded.
SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS mes_pro_integrity_lock (
 tenant_id BIGINT NOT NULL,
 scope VARCHAR(32) NOT NULL,
 PRIMARY KEY(tenant_id,scope)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
