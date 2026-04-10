-- 扩展 job_execution_log.log_type 约束，增加 COMPENSATION 类型支持。
ALTER TABLE batch.job_execution_log DROP CONSTRAINT IF EXISTS ck_job_execution_log_type;
ALTER TABLE batch.job_execution_log ADD CONSTRAINT ck_job_execution_log_type
    CHECK (log_type IN ('SYSTEM', 'BUSINESS', 'RETRY', 'ALARM', 'AUDIT', 'COMPENSATION'));
