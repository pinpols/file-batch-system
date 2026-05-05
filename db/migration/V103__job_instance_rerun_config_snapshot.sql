-- =========================================================
-- V103: Rerun result and config-version traceability
-- =========================================================

ALTER TABLE batch.job_instance
    ADD COLUMN IF NOT EXISTS job_definition_version INTEGER,
    ADD COLUMN IF NOT EXISTS rerun_policy_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE archive.job_instance_archive
    ADD COLUMN IF NOT EXISTS job_definition_version INTEGER,
    ADD COLUMN IF NOT EXISTS rerun_policy_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN batch.job_instance.job_definition_version IS
    'Snapshot of job_definition.version used when this instance was created.';
COMMENT ON COLUMN batch.job_instance.rerun_policy_snapshot IS
    'Snapshot of rerun semantics: runAttempt, parentInstanceId, triggerType, rerun flags and reason.';

COMMENT ON COLUMN archive.job_instance_archive.job_definition_version IS
    'V103 mirror column: job_definition.version snapshot copied from batch.job_instance.';
COMMENT ON COLUMN archive.job_instance_archive.rerun_policy_snapshot IS
    'V103 mirror column: rerun policy snapshot copied from batch.job_instance.';
