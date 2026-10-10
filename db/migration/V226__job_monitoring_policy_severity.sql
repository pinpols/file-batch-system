ALTER TABLE batch.job_monitoring_policy
    ADD COLUMN soft_runtime_severity VARCHAR(16) NOT NULL DEFAULT 'WARN',
    ADD COLUMN start_grace_severity VARCHAR(16) NOT NULL DEFAULT 'WARN',
    ADD COLUMN completion_deadline_severity VARCHAR(16) NOT NULL DEFAULT 'WARN';

ALTER TABLE batch.job_monitoring_policy
    ADD CONSTRAINT ck_job_monitoring_policy_soft_runtime_severity
        CHECK (soft_runtime_severity IN ('WARN', 'ERROR', 'CRITICAL')) NOT VALID,
    ADD CONSTRAINT ck_job_monitoring_policy_start_grace_severity
        CHECK (start_grace_severity IN ('WARN', 'ERROR', 'CRITICAL')) NOT VALID,
    ADD CONSTRAINT ck_job_monitoring_policy_completion_deadline_severity
        CHECK (completion_deadline_severity IN ('WARN', 'ERROR', 'CRITICAL')) NOT VALID;

ALTER TABLE batch.job_monitoring_policy
    VALIDATE CONSTRAINT ck_job_monitoring_policy_soft_runtime_severity;
ALTER TABLE batch.job_monitoring_policy
    VALIDATE CONSTRAINT ck_job_monitoring_policy_start_grace_severity;
ALTER TABLE batch.job_monitoring_policy
    VALIDATE CONSTRAINT ck_job_monitoring_policy_completion_deadline_severity;

COMMENT ON COLUMN batch.job_monitoring_policy.soft_runtime_severity IS
    'Alert severity for soft running-duration violations; default WARN preserves existing behavior.';
COMMENT ON COLUMN batch.job_monitoring_policy.start_grace_severity IS
    'Alert severity for scheduled jobs that miss their start grace period.';
COMMENT ON COLUMN batch.job_monitoring_policy.completion_deadline_severity IS
    'Alert severity for jobs completing after their configured deadline.';
