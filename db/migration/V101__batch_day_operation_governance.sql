-- =========================================================
-- V101：批量日操作治理。
-- 1) 扩展 batch_day_instance 状态机，支持人工终态。
-- 2) 增加治理操作所需的冻结状态和操作者快照字段。
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
    '人工治理冻结标记；批量日冻结期间，cutoff 和 settle 调度器会跳过该日，直至解冻、重新打开或关闭。';
COMMENT ON COLUMN batch.batch_day_instance.operation_reason IS
    '最近一次人工治理操作的原因码或自由文本原因。';
COMMENT ON COLUMN batch.batch_day_instance.operated_by IS
    '最近一次人工治理操作的操作者 ID。';
COMMENT ON COLUMN batch.batch_day_instance.operated_at IS
    '最近一次人工治理操作的时间。';
