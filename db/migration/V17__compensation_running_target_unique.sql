-- =========================================================
-- V17：确保每个目标最多只有一条运行中的补偿命令。
-- 说明：
-- 1) 防止同一目标被重复发起人工补偿。
-- 2) 仅当 target_id 非空且 status 为 RUNNING 时应用此唯一性规则。
-- =========================================================

CREATE UNIQUE INDEX IF NOT EXISTS uk_compensation_command_running_target
    ON batch.compensation_command (tenant_id, compensation_type, target_id)
    WHERE command_status = 'RUNNING' AND target_id IS NOT NULL;
