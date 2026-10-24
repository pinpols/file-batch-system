-- =====================================================================
-- V237: claim the first timeout alert for a persisted calendar dependency wait.
-- =====================================================================

ALTER TABLE batch.calendar_dependency_wait
    ADD COLUMN IF NOT EXISTS timeout_alerted_at TIMESTAMPTZ;

COMMENT ON COLUMN batch.calendar_dependency_wait.timeout_alerted_at IS
    '首次超过等待超时并成功认领告警的时刻；CAS 防止周期扫描重复发射同一等待告警';
