-- flyway:executeInTransaction=false
-- 子请求 start-grace 扫描按父工作流过滤。CONCURRENTLY 不能和普通 DDL 放在同一脚本。
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_trigger_request_parent_workflow
    ON batch.trigger_request (tenant_id, parent_workflow_run_id)
    WHERE parent_workflow_run_id IS NOT NULL;
