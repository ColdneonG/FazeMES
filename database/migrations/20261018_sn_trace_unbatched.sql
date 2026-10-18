-- Follow-up to the already applied 20261018_sn_trace.sql.
-- Non-batch-managed products can use individually tracked SNs without a product
-- batch. Preserve genuine NULL values; do not create substitute batch codes.
-- MySQL 8; additive schema relaxation and safe to re-run.
ALTER TABLE mes_pro_sn_trace
  MODIFY COLUMN product_batch_code varchar(128) NULL DEFAULT NULL;
