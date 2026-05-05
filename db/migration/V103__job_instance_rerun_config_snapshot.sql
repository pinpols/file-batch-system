-- =========================================================
-- V103: Rerun result and config-version traceability
-- =========================================================

ALTER TABLE batch.job_instance
    ADD COLUMN IF NOT EXISTS job_definition_version INTEGER,
    ADD COLUMN IF NOT EXISTS rerun_policy_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN batch.job_instance.job_definition_version IS
    'Snapshot of job_definition.version used when this instance was created.';
COMMENT ON COLUMN batch.job_instance.rerun_policy_snapshot IS
    'Snapshot of rerun semantics: runAttempt, parentInstanceId, triggerType, rerun flags and reason.';
