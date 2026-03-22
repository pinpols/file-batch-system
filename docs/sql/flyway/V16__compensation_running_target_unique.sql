-- One in-flight manual compensation per target (when target_id is set), to avoid duplicate dispatch.
CREATE UNIQUE INDEX IF NOT EXISTS uk_compensation_command_running_target
    ON batch.compensation_command (tenant_id, compensation_type, target_id)
    WHERE command_status = 'RUNNING' AND target_id IS NOT NULL;
