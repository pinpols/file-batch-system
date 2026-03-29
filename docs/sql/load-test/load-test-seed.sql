-- ============================================================
-- 压测专用种子数据
-- 在目标环境（staging / prod-like）执行一次，压测前无需重复执行。
-- 幂等：使用 ON CONFLICT DO NOTHING。
-- ============================================================

-- 1. 专用作业定义（jobCode = E2E_IMPORT_LOAD）
INSERT INTO batch.job_definition (
    tenant_id, job_code, job_name, job_type, biz_type,
    schedule_type, timezone, priority, queue_code, worker_group,
    trigger_mode, dag_enabled, shard_strategy,
    retry_policy, retry_max_count, timeout_seconds, enabled, version
) VALUES (
    't1', 'E2E_IMPORT_LOAD', 'Load Test Import Job', 'IMPORT', 'LOAD_TEST',
    'MANUAL', 'UTC', 5, 'load-q', 'import',
    'API', false, 'NONE',
    'NONE', 0, 0, true, 1
) ON CONFLICT (tenant_id, job_code) DO NOTHING;

-- 2. 对应工作流定义
INSERT INTO batch.workflow_definition (
    tenant_id, workflow_code, workflow_name, workflow_type, version, enabled
) VALUES (
    't1', 'E2E_IMPORT_LOAD', 'Load Test Workflow', 'DAG', 1, true
) ON CONFLICT (tenant_id, workflow_code, version) DO NOTHING;

-- 3. 多租户扩展（t2 / t3，压测多租户隔离场景时使用）
INSERT INTO batch.job_definition (
    tenant_id, job_code, job_name, job_type, biz_type,
    schedule_type, timezone, priority, queue_code, worker_group,
    trigger_mode, dag_enabled, shard_strategy,
    retry_policy, retry_max_count, timeout_seconds, enabled, version
) VALUES
    ('t2', 'E2E_IMPORT_LOAD', 'Load Test Import Job (t2)', 'IMPORT', 'LOAD_TEST',
     'MANUAL', 'UTC', 5, 'load-q', 'import',
     'API', false, 'NONE', 'NONE', 0, 0, true, 1),
    ('t3', 'E2E_IMPORT_LOAD', 'Load Test Import Job (t3)', 'IMPORT', 'LOAD_TEST',
     'MANUAL', 'UTC', 5, 'load-q', 'import',
     'API', false, 'NONE', 'NONE', 0, 0, true, 1)
ON CONFLICT (tenant_id, job_code) DO NOTHING;

INSERT INTO batch.workflow_definition (
    tenant_id, workflow_code, workflow_name, workflow_type, version, enabled
) VALUES
    ('t2', 'E2E_IMPORT_LOAD', 'Load Test Workflow (t2)', 'DAG', 1, true),
    ('t3', 'E2E_IMPORT_LOAD', 'Load Test Workflow (t3)', 'DAG', 1, true)
ON CONFLICT (tenant_id, workflow_code, version) DO NOTHING;

-- 注意：trigger_request 行由 Gatling 在运行时通过 API 调用动态创建，
--       不需要预先在此处手动插入（LaunchService 会在 launch 时读取已有行，
--       或通过 /api/triggers/launch 端点的前置验证处理）。
