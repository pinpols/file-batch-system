-- 扩展异步作业监控告警的 claim 类型，不修改已应用的 V224。
ALTER TABLE batch.job_monitoring_alert_claim
    ADD CONSTRAINT ck_job_monitoring_alert_claim_type_expanded
    CHECK (violation_type IN (
        'RUNNING_TOO_LONG', 'NOT_STARTED', 'COMPLETED_LATE', 'FAILED_PARTITION'
    )) NOT VALID;

ALTER TABLE batch.job_monitoring_alert_claim
    VALIDATE CONSTRAINT ck_job_monitoring_alert_claim_type_expanded;

ALTER TABLE batch.job_monitoring_alert_claim
    DROP CONSTRAINT ck_job_monitoring_alert_claim_type;

ALTER TABLE batch.job_monitoring_alert_claim
    RENAME CONSTRAINT ck_job_monitoring_alert_claim_type_expanded
    TO ck_job_monitoring_alert_claim_type;

-- 部分索引服务于有界的近期失败扫描，并排除健康实例和历史记录。
CREATE INDEX IF NOT EXISTS idx_job_instance_failed_partition_monitoring
    ON batch.job_instance (finished_at, tenant_id, id)
    WHERE failed_partition_count > 0
      AND instance_status IN ('FAILED', 'PARTIAL_FAILED')
      AND dry_run = false;
