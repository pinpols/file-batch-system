-- 未启动的 start-grace 扫描只覆盖仍活跃且尚未开始的实例。
-- job_instance 是分区表，沿用既有普通索引写法。
CREATE INDEX IF NOT EXISTS idx_job_instance_start_grace_pending
    ON batch.job_instance (tenant_id, job_definition_id, biz_date, id)
    WHERE dry_run = false
      AND execution_started_at IS NULL
      AND finished_at IS NULL
      AND instance_status IN ('CREATED', 'WAITING', 'READY', 'RUNNING');
