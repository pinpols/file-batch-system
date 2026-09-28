-- =========================================================
-- V16 - Ensure only one running compensation per target
-- 说明：
-- 1) 防止同一目标被重复发起人工补偿。
-- 2) Apply the uniqueness rule only when target_id is present and status is RUNNING.
-- =========================================================

CREATE UNIQUE INDEX IF NOT EXISTS uk_compensation_command_running_target
    ON batch.compensation_command (tenant_id, compensation_type, target_id)
    WHERE command_status = 'RUNNING' AND target_id IS NOT NULL;
