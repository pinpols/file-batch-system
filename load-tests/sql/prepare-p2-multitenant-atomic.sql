BEGIN;

INSERT INTO batch.tenant (
  tenant_id, tenant_name, status, description, created_by, created_at, updated_at
)
VALUES
  ('p2fa', 'P2 Fairness Profile A', 'ACTIVE', 'Ephemeral P2 fairness load-test tenant', 'load-test', now(), now()),
  ('p2fb', 'P2 Fairness Profile B', 'ACTIVE', 'Ephemeral P2 fairness load-test tenant', 'load-test', now(), now()),
  ('p2fc', 'P2 Fairness Profile C', 'ACTIVE', 'Ephemeral P2 fairness load-test tenant', 'load-test', now(), now())
ON CONFLICT (tenant_id) DO UPDATE SET
  status = 'ACTIVE',
  description = EXCLUDED.description,
  updated_at = now();

INSERT INTO batch.job_definition (
  tenant_id, job_code, job_name, job_type, biz_type,
  schedule_type, schedule_expr, timezone, priority, queue_code, worker_group,
  calendar_code, window_code, trigger_mode, dag_enabled, shard_strategy,
  retry_policy, retry_max_count, timeout_seconds, execution_handler, param_schema, default_params,
  version, enabled, description, created_by, updated_by, created_at, updated_at
)
SELECT
  tenant.tenant_id,
  'atomic_sql_demo',
  'Atomic SQL Demo ' || tenant.tenant_id,
  'ATOMIC',
  'GENERAL',
  'MANUAL',
  NULL,
  'Asia/Shanghai',
  5,
  'atomic_queue',
  'atomic',
  'default_calendar',
  'always_open',
  'MANUAL',
  false,
  'STATIC',
  'EXPONENTIAL',
  1,
  300,
  NULL,
  jsonb_build_object('type', 'object'),
  jsonb_build_object('taskType', 'sql', 'sql', 'SELECT 1'),
  1,
  true,
  'P2 local multi-tenant fairness definition',
  'load-test',
  'load-test',
  now(),
  now()
FROM (VALUES ('p2fa'), ('p2fb'), ('p2fc')) AS tenant(tenant_id)
ON CONFLICT (tenant_id, job_code) DO UPDATE SET
  job_name = EXCLUDED.job_name,
  job_type = EXCLUDED.job_type,
  biz_type = EXCLUDED.biz_type,
  schedule_type = EXCLUDED.schedule_type,
  schedule_expr = EXCLUDED.schedule_expr,
  timezone = EXCLUDED.timezone,
  priority = EXCLUDED.priority,
  enabled = true,
  worker_group = EXCLUDED.worker_group,
  queue_code = EXCLUDED.queue_code,
  calendar_code = EXCLUDED.calendar_code,
  window_code = EXCLUDED.window_code,
  trigger_mode = EXCLUDED.trigger_mode,
  dag_enabled = EXCLUDED.dag_enabled,
  shard_strategy = EXCLUDED.shard_strategy,
  retry_policy = EXCLUDED.retry_policy,
  retry_max_count = EXCLUDED.retry_max_count,
  timeout_seconds = EXCLUDED.timeout_seconds,
  execution_handler = EXCLUDED.execution_handler,
  param_schema = EXCLUDED.param_schema,
  default_params = EXCLUDED.default_params,
  version = EXCLUDED.version,
  description = EXCLUDED.description,
  updated_by = EXCLUDED.updated_by,
  updated_at = now();

-- 本档案显式创建共享且有容量上限的准入组。若缺少这些策略，公平性压测只能证明三个租户最终完成，
-- 无法让分区进入使用 fairnessScore 的 WAITING 调度队列。
INSERT INTO batch.tenant_quota_policy (
  tenant_id,
  policy_code,
  max_running_jobs_per_tenant,
  max_partitions_per_tenant,
  max_qps_per_tenant,
  fair_share_weight,
  fair_share_group,
  burst_limit,
  partition_burst_limit,
  quota_reset_policy,
  group_shared_max_running_jobs,
  enabled,
  exceeded_strategy,
  description,
  created_at,
  updated_at
)
VALUES
  ('p2fa', 'p2-fairness-profile', 0, 0, 0, 3, 'p2-load-fairness', 0, 0, 'NONE', :fairness_group_cap,
   true, 'QUEUE_DEFER', 'Ephemeral P2 fairness load-test policy', now(), now()),
  ('p2fb', 'p2-fairness-profile', 0, 0, 0, 1, 'p2-load-fairness', 0, 0, 'NONE', :fairness_group_cap,
   true, 'QUEUE_DEFER', 'Ephemeral P2 fairness load-test policy', now(), now()),
  ('p2fc', 'p2-fairness-profile', 0, 0, 0, 1, 'p2-load-fairness', 0, 0, 'NONE', :fairness_group_cap,
   true, 'QUEUE_DEFER', 'Ephemeral P2 fairness load-test policy', now(), now())
ON CONFLICT (tenant_id, policy_code) DO UPDATE SET
  max_running_jobs_per_tenant = EXCLUDED.max_running_jobs_per_tenant,
  max_partitions_per_tenant = EXCLUDED.max_partitions_per_tenant,
  max_qps_per_tenant = EXCLUDED.max_qps_per_tenant,
  fair_share_weight = EXCLUDED.fair_share_weight,
  fair_share_group = EXCLUDED.fair_share_group,
  burst_limit = EXCLUDED.burst_limit,
  partition_burst_limit = EXCLUDED.partition_burst_limit,
  quota_reset_policy = EXCLUDED.quota_reset_policy,
  group_shared_max_running_jobs = EXCLUDED.group_shared_max_running_jobs,
  enabled = EXCLUDED.enabled,
  exceeded_strategy = EXCLUDED.exceeded_strategy,
  description = EXCLUDED.description,
  updated_at = now();

COMMIT;
