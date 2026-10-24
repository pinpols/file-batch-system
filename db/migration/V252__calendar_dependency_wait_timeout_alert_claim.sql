-- =====================================================================
-- V252：为持久化的日历依赖等待首次领取超时告警。
-- =====================================================================

ALTER TABLE batch.calendar_dependency_wait
    ADD COLUMN IF NOT EXISTS timeout_alerted_at TIMESTAMPTZ;

COMMENT ON COLUMN batch.calendar_dependency_wait.timeout_alerted_at IS
    '首次超过等待超时并成功认领告警的时刻；CAS 防止周期扫描重复发射同一等待告警';
