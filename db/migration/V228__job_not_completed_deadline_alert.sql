-- 保留早期部署已写入记录使用的 claim 类型。
ALTER TABLE batch.job_monitoring_alert_claim
    ADD CONSTRAINT ck_job_monitoring_alert_claim_type_deadline
    CHECK (violation_type IN (
        'RUNNING_TOO_LONG', 'NOT_STARTED', 'COMPLETED_LATE',
        'NOT_COMPLETED_BY_DEADLINE', 'FAILED_PARTITION'
    )) NOT VALID;

ALTER TABLE batch.job_monitoring_alert_claim
    VALIDATE CONSTRAINT ck_job_monitoring_alert_claim_type_deadline;

ALTER TABLE batch.job_monitoring_alert_claim
    DROP CONSTRAINT ck_job_monitoring_alert_claim_type;

ALTER TABLE batch.job_monitoring_alert_claim
    RENAME CONSTRAINT ck_job_monitoring_alert_claim_type_deadline
    TO ck_job_monitoring_alert_claim_type;
