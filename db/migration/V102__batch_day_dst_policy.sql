-- =========================================================
-- V102: Explicit DST policy for batch-day boundary calculation
-- =========================================================

ALTER TABLE batch.business_calendar
    ADD COLUMN IF NOT EXISTS dst_gap_policy VARCHAR(32) NOT NULL DEFAULT 'RUN_AT_NEXT_VALID_TIME',
    ADD COLUMN IF NOT EXISTS dst_overlap_policy VARCHAR(32) NOT NULL DEFAULT 'RUN_ONCE_EARLIER_OFFSET';

ALTER TABLE batch.batch_day_instance
    ADD COLUMN IF NOT EXISTS dst_policy_snapshot VARCHAR(96) NOT NULL
        DEFAULT 'gap=RUN_AT_NEXT_VALID_TIME;overlap=RUN_ONCE_EARLIER_OFFSET';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_business_calendar_dst_gap_policy'
    ) THEN
        ALTER TABLE batch.business_calendar
            ADD CONSTRAINT ck_business_calendar_dst_gap_policy
            CHECK (dst_gap_policy IN ('RUN_AT_NEXT_VALID_TIME', 'SKIP', 'FAIL_FAST'));
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_business_calendar_dst_overlap_policy'
    ) THEN
        ALTER TABLE batch.business_calendar
            ADD CONSTRAINT ck_business_calendar_dst_overlap_policy
            CHECK (dst_overlap_policy IN ('RUN_ONCE_EARLIER_OFFSET', 'RUN_ONCE_LATER_OFFSET', 'RUN_TWICE'));
    END IF;
END $$;

COMMENT ON COLUMN batch.business_calendar.dst_gap_policy IS
    'How to resolve local cutoff_time when it falls into a DST gap.';
COMMENT ON COLUMN batch.business_calendar.dst_overlap_policy IS
    'How to resolve local cutoff_time when it falls into a DST overlap.';
COMMENT ON COLUMN batch.batch_day_instance.dst_policy_snapshot IS
    'Snapshot of business_calendar DST policies used to calculate cutoff_at/sla_deadline_at.';
