-- flyway:executeInTransaction=false
-- 根机会首次违约时可能尚无 Run；归档后仍按原触发请求核对恢复，避免逐条扫描整个冷表。

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_workflow_run_archive_trigger_request
    ON archive.workflow_run_archive (tenant_id, trigger_request_id)
    WHERE trigger_request_id IS NOT NULL;
