-- flyway:executeInTransaction=false
-- 未启动的 start-grace 扫描只覆盖仍活跃且尚未开始的实例。
-- job_instance 是分区表，沿用既有索引写法，不在父表上使用 CONCURRENTLY。
CREATE INDEX IF NOT EXISTS idx_job_instance_start_grace_pending
    ON batch.job_instance (tenant_id, job_definition_id, biz_date, id)
    WHERE dry_run = false
      AND execution_started_at IS NULL
      AND finished_at IS NULL
      AND instance_status IN ('CREATED', 'WAITING', 'READY', 'RUNNING');

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_trigger_request_parent_workflow
    ON batch.trigger_request (tenant_id, parent_workflow_run_id)
    WHERE parent_workflow_run_id IS NOT NULL;
