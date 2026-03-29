-- =========================================================
-- V16 - Ensure only one running compensation per target
-- Notes:
-- 1) Prevent duplicate manual compensation dispatch for the same target.
-- 2) Apply the uniqueness rule only when target_id is present and status is RUNNING.
-- =========================================================

CREATE UNIQUE INDEX IF NOT EXISTS uk_compensation_command_running_target
    ON batch.compensation_command (tenant_id, compensation_type, target_id)
    WHERE command_status = 'RUNNING' AND target_id IS NOT NULL;
