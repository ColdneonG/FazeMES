-- Add input-context budgeting separately: the initial assistant migration may already be applied.
SET @assistant_context_ddl = IF(
 (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='mes_assistant_model_config' AND column_name='context_tokens')=0,
 'ALTER TABLE mes_assistant_model_config ADD COLUMN context_tokens INT NOT NULL DEFAULT 8192',
 'SELECT 1');
PREPARE assistant_context_statement FROM @assistant_context_ddl;
EXECUTE assistant_context_statement;
DEALLOCATE PREPARE assistant_context_statement;
