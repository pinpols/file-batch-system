-- =========================================================
-- V31：批量日（batch day）支持
-- 说明：
-- 1) 扩展 business_calendar：cutoff、晚到容忍、SLA 偏移
-- 2) 新增 batch_day_instance：单个业务日的生命周期投影
-- =========================================================

ALTER TABLE batch.business_calendar
    ADD COLUMN IF NOT EXISTS cutoff_time TIME NOT NULL DEFAULT TIME '06:00:00',
    ADD COLUMN IF NOT EXISTS late_arrival_tolerance_min INTEGER NOT NULL DEFAULT 60,
    ADD COLUMN IF NOT EXISTS sla_offset_min INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN batch.business_calendar.cutoff_time IS
    '批量日切换时间。在该时间之前触发的批次，biz_date 归属前一个业务日。';
COMMENT ON COLUMN batch.business_calendar.late_arrival_tolerance_min IS
    'cutoff 之后的容忍窗口（分钟），用于接收晚到数据。';
COMMENT ON COLUMN batch.business_calendar.sla_offset_min IS
    '批量日 SLA deadline = cutoff_time + sla_offset_min。';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_business_calendar_late_arrival_tolerance_min'
    ) THEN
        ALTER TABLE batch.business_calendar
            ADD CONSTRAINT ck_business_calendar_late_arrival_tolerance_min
                CHECK (late_arrival_tolerance_min >= 0);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_business_calendar_sla_offset_min'
    ) THEN
        ALTER TABLE batch.business_calendar
            ADD CONSTRAINT ck_business_calendar_sla_offset_min
                CHECK (sla_offset_min >= 0);
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS batch.batch_day_instance (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    calendar_code VARCHAR(128) NOT NULL,
    biz_date DATE NOT NULL,
    day_status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    open_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cutoff_at TIMESTAMPTZ,
    settled_at TIMESTAMPTZ,
    sla_deadline_at TIMESTAMPTZ,
    late_count INTEGER NOT NULL DEFAULT 0,
    catchup_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_batch_day_instance UNIQUE (tenant_id, calendar_code, biz_date),
    CONSTRAINT ck_batch_day_instance_status CHECK (day_status IN ('OPEN', 'CUTOFF', 'IN_FLIGHT', 'SETTLED', 'FAILED')),
    CONSTRAINT ck_batch_day_instance_late_count CHECK (late_count >= 0),
    CONSTRAINT ck_batch_day_instance_catchup_count CHECK (catchup_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_batch_day_instance_status
    ON batch.batch_day_instance (day_status, biz_date DESC);
