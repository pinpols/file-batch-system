-- 一次性领取告警；等待继续保持待处理，上游就绪后仍可恢复。

ALTER TABLE batch.job_dependency_waiting_launch
    ADD COLUMN timeout_alerted_at TIMESTAMPTZ;

COMMENT ON COLUMN batch.job_dependency_waiting_launch.timeout_alerted_at IS
    '一次性超时告警的 CAS 认领时刻；不改变 WAITING 状态。';
