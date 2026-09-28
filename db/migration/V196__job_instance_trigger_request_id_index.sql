-- Trigger API 清理和保留策略会在关联实例删除后清理 trigger_request 记录。
-- 关联的 job_instance。PostgreSQL 不会为外键引用端自动创建索引；缺少索引时，每次删除 trigger_request
-- 都会扫描 job_instance。
CREATE INDEX IF NOT EXISTS idx_job_instance_trigger_request_id
    ON batch.job_instance (trigger_request_id)
    WHERE trigger_request_id IS NOT NULL;
