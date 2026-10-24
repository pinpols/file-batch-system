-- 为作业监控策略的运行时长、启动宽限和完成时限告警配置严重级别。

ALTER TABLE batch.job_monitoring_policy
    ADD COLUMN soft_runtime_severity VARCHAR(16) NOT NULL DEFAULT 'WARN',
    ADD COLUMN start_grace_severity VARCHAR(16) NOT NULL DEFAULT 'WARN',
    ADD COLUMN completion_deadline_severity VARCHAR(16) NOT NULL DEFAULT 'WARN';

ALTER TABLE batch.job_monitoring_policy
    ADD CONSTRAINT ck_job_monitoring_policy_soft_runtime_severity
        CHECK (soft_runtime_severity IN ('WARN', 'ERROR', 'CRITICAL')) NOT VALID,
    ADD CONSTRAINT ck_job_monitoring_policy_start_grace_severity
        CHECK (start_grace_severity IN ('WARN', 'ERROR', 'CRITICAL')) NOT VALID,
    ADD CONSTRAINT ck_job_monitoring_policy_completion_deadline_severity
        CHECK (completion_deadline_severity IN ('WARN', 'ERROR', 'CRITICAL')) NOT VALID;

ALTER TABLE batch.job_monitoring_policy
    VALIDATE CONSTRAINT ck_job_monitoring_policy_soft_runtime_severity;
ALTER TABLE batch.job_monitoring_policy
    VALIDATE CONSTRAINT ck_job_monitoring_policy_start_grace_severity;
ALTER TABLE batch.job_monitoring_policy
    VALIDATE CONSTRAINT ck_job_monitoring_policy_completion_deadline_severity;

COMMENT ON COLUMN batch.job_monitoring_policy.soft_runtime_severity IS
    '运行时长软阈值违规告警级别；默认 WARN 以保持现有行为。';
COMMENT ON COLUMN batch.job_monitoring_policy.start_grace_severity IS
    '定时作业错过启动宽限期时使用的告警级别。';
COMMENT ON COLUMN batch.job_monitoring_policy.completion_deadline_severity IS
    '作业晚于配置截止时间完成时使用的告警级别。';
