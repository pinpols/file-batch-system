-- RERUN 仅从不可变 WorkflowRun 快照解析历史 Job 配置。
-- 租户和单 Job 前缀限定 JSONB 版本查询范围，排序后返回
-- 选取记录了所需版本的最新一次运行。

CREATE INDEX IF NOT EXISTS idx_workflow_run_job_version_lookup
    ON batch.workflow_run (tenant_id, created_at DESC, id DESC)
    WHERE execution_snapshot IS NOT NULL;

COMMENT ON INDEX batch.idx_workflow_run_job_version_lookup IS
    '按租户查询单作业及组合 WorkflowRun 中 JobDefinition 快照，并按时间倒序读取。';
