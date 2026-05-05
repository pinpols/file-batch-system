-- =========================================================
-- V101: Batch day operation governance
-- 1) Extend batch_day_instance state machine with manual terminal states.
-- 2) Add freeze and operator snapshot fields for governance actions.
-- =========================================================

ALTER TABLE batch.batch_day_instance
    ADD COLUMN IF NOT EXISTS frozen BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS operation_reason VARCHAR(256),
    ADD COLUMN IF NOT EXISTS operated_by VARCHAR(128),
    ADD COLUMN IF NOT EXISTS operated_at TIMESTAMPTZ;

ALTER TABLE batch.batch_day_instance DROP CONSTRAINT IF EXISTS ck_batch_day_instance_status;
ALTER TABLE batch.batch_day_instance ADD CONSTRAINT ck_batch_day_instance_status
    CHECK (day_status IN (
        'OPEN', 'CUTOFF', 'IN_FLIGHT', 'SETTLED', 'FAILED', 'SKIPPED', 'MANUAL_RELEASED'
    ));

CREATE INDEX IF NOT EXISTS idx_batch_day_instance_frozen
    ON batch.batch_day_instance (tenant_id, frozen, biz_date DESC)
    WHERE frozen = true;

COMMENT ON COLUMN batch.batch_day_instance.frozen IS
    'Manual governance freeze flag. Frozen batch days are skipped by cutoff/settle schedulers until released/reopened/closed.';
COMMENT ON COLUMN batch.batch_day_instance.operation_reason IS
    'Latest manual governance reason code or free-form reason.';
COMMENT ON COLUMN batch.batch_day_instance.operated_by IS
    'Latest manual governance operator id.';
COMMENT ON COLUMN batch.batch_day_instance.operated_at IS
    'Latest manual governance operation timestamp.';
