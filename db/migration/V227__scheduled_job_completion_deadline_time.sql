ALTER TABLE batch.job_monitoring_policy
    ADD COLUMN completion_deadline_local_time TIME WITHOUT TIME ZONE,
    ADD COLUMN completion_deadline_day_offset SMALLINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_job_monitoring_policy_completion_day_offset
        CHECK (completion_deadline_day_offset BETWEEN 0 AND 1) NOT VALID;

ALTER TABLE batch.job_monitoring_policy
    VALIDATE CONSTRAINT ck_job_monitoring_policy_completion_day_offset;

COMMENT ON COLUMN batch.job_monitoring_policy.completion_deadline_local_time IS
    'Local wall-clock completion deadline for scheduled jobs, interpreted in job_definition.timezone; NULL disables this scheduled-job alert.';
COMMENT ON COLUMN batch.job_monitoring_policy.completion_deadline_day_offset IS
    'Calendar-day offset from the scheduled fire date for the local completion deadline: 0 same day, 1 next day.';
COMMENT ON COLUMN batch.job_monitoring_policy.completion_deadline_seconds IS
    'Deprecated legacy field retained for compatibility; scheduled-job not-completed-by-deadline monitoring now uses completion_deadline_local_time and completion_deadline_day_offset. Existing seconds values are not auto-converted.';
