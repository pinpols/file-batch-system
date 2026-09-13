BEGIN;

INSERT INTO batch.tenant (
  tenant_id, tenant_name, status, description, created_by, created_at, updated_at
)
VALUES (
  :'capacity_tenant_id', 'P2 Capacity Profile', 'ACTIVE',
  'Ephemeral P2 unbounded-admission load-test tenant', 'load-test', now(), now()
)
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
VALUES (
  :'capacity_tenant_id', 'atomic_sql_demo', 'Atomic SQL Demo P2 capacity', 'ATOMIC', 'GENERAL',
  'MANUAL', NULL, 'Asia/Shanghai', 5, 'atomic_queue', 'atomic',
  'default_calendar', 'always_open', 'MANUAL', false, 'STATIC',
  'EXPONENTIAL', 1, :'capacity_job_timeout_seconds'::integer,
  NULL, jsonb_build_object('type', 'object'),
  jsonb_build_object('taskType', 'sql', 'sql', 'SELECT 1'), 1, true,
  'P2 local capacity definition with unbounded admission policy',
  'load-test', 'load-test', now(), now()
)
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

INSERT INTO batch.tenant_quota_policy (
  tenant_id, policy_code, max_running_jobs_per_tenant, max_partitions_per_tenant,
  max_qps_per_tenant, fair_share_weight, fair_share_group, burst_limit, partition_burst_limit,
  quota_reset_policy, group_shared_max_running_jobs, enabled, exceeded_strategy, description,
  created_at, updated_at
)
VALUES (
  :'capacity_tenant_id', 'p2-capacity-profile', 0, 0, 0, 1, NULL, 0, 0, 'NONE', 0,
  true, 'QUEUE_DEFER', 'Ephemeral P2 unbounded-admission capacity policy', now(), now()
)
ON CONFLICT (tenant_id, policy_code) DO UPDATE SET
  max_running_jobs_per_tenant = 0,
  max_partitions_per_tenant = 0,
  max_qps_per_tenant = 0,
  fair_share_group = NULL,
  group_shared_max_running_jobs = 0,
  enabled = true,
  exceeded_strategy = 'QUEUE_DEFER',
  description = EXCLUDED.description,
  updated_at = now();

COMMIT;
