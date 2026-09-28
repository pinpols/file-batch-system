-- =========================================================
-- V11 - Add SLA and arrival governance columns
-- 说明：
-- 1) Track deadline, expected duration, and SLA alert state on job_instance.
-- 2) 支持到达与处理治理查询及告警。
-- =========================================================

ALTER TABLE batch.job_instance
    ADD COLUMN IF NOT EXISTS deadline_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS expected_duration_seconds INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS sla_alerted_at TIMESTAMPTZ;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_job_instance_expected_duration_seconds'
    ) THEN
        ALTER TABLE batch.job_instance
            ADD CONSTRAINT ck_job_instance_expected_duration_seconds
                CHECK (expected_duration_seconds >= 0);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_job_instance_sla_tracking
    ON batch.job_instance (instance_status, deadline_at, sla_alerted_at);
