-- flyway:executeInTransaction=false
-- 等待关系跨来源遍历以租户与不可变 ID 为键，避免深分页及跨租户扫描。
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_calendar_dependency_wait_tenant_id ON batch.calendar_dependency_wait (tenant_id, id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_job_dependency_wait_tenant_id ON batch.job_dependency_waiting_launch (tenant_id, id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_readiness_wait_tenant_id ON batch.readiness_wait (tenant_id, id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_workflow_launch_wait_tenant_id ON batch.workflow_launch_wait (tenant_id, id);
