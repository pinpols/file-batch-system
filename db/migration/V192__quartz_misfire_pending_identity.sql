-- Quartz does not have a trigger_runtime_state row. Allow new MANUAL_APPROVAL
-- records to stand alone while preserving legacy Wheel rows and their foreign key.
ALTER TABLE batch.trigger_misfire_pending
    ALTER COLUMN trigger_runtime_state_id DROP NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_trigger_misfire_pending_quartz_fire
    ON batch.trigger_misfire_pending (tenant_id, job_code, scheduled_fire_time)
    WHERE trigger_runtime_state_id IS NULL;

COMMENT ON COLUMN batch.trigger_misfire_pending.trigger_runtime_state_id IS
    'Legacy Wheel runtime-state reference; Quartz-created rows leave this column NULL';
