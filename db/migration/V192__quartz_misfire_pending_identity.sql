-- Quartz 没有对应的 trigger_runtime_state 记录。允许新增 MANUAL_APPROVAL
-- 允许相关记录独立存在，同时保留旧 Wheel 记录及其外键关系。
ALTER TABLE batch.trigger_misfire_pending
    ALTER COLUMN trigger_runtime_state_id DROP NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_trigger_misfire_pending_quartz_fire
    ON batch.trigger_misfire_pending (tenant_id, job_code, scheduled_fire_time)
    WHERE trigger_runtime_state_id IS NULL;

COMMENT ON COLUMN batch.trigger_misfire_pending.trigger_runtime_state_id IS
    'Legacy Wheel runtime-state reference; Quartz-created rows leave this column NULL';
