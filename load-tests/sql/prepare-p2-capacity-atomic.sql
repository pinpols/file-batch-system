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
SELECT
  :'capacity_tenant_id', src.job_code, src.job_name || ' P2 capacity', src.job_type, src.biz_type,
  src.schedule_type, src.schedule_expr, src.timezone, src.priority, src.queue_code, src.worker_group,
  src.calendar_code, src.window_code, src.trigger_mode, src.dag_enabled, src.shard_strategy,
  src.retry_policy, src.retry_max_count, src.timeout_seconds, src.execution_handler, src.param_schema,
  src.default_params, src.version, true,
  'P2 local capacity clone with unbounded admission policy', 'load-test', 'load-test', now(), now()
FROM batch.job_definition src
WHERE src.tenant_id = 'default-tenant'
  AND src.job_code = 'atomic_sql_demo'
ON CONFLICT (tenant_id, job_code) DO UPDATE SET
  enabled = true,
  worker_group = EXCLUDED.worker_group,
  queue_code = EXCLUDED.queue_code,
  retry_policy = EXCLUDED.retry_policy,
  retry_max_count = EXCLUDED.retry_max_count,
  timeout_seconds = EXCLUDED.timeout_seconds,
  default_params = EXCLUDED.default_params,
  updated_at = now();

INSERT INTO batch.tenant_quota_policy (
  tenant_id, policy_code, max_running_jobs_per_tenant, max_partitions_per_tenant,
  max_qps_per_tenant, fair_share_weight, fair_share_group, burst_limit, partition_burst_limit,
  quota_reset_policy, group_shared_max_running_jobs, enabled, exceeded_strategy, description,
  created_at, updated_at
)
VALUES (
  :'capacity_tenant_id', 'p2-capacity-profile', 0, 0, 0, 1, NULL, 0, 0, 'NONE', NULL,
  true, 'QUEUE_DEFER', 'Ephemeral P2 unbounded-admission capacity policy', now(), now()
)
ON CONFLICT (tenant_id, policy_code) DO UPDATE SET
  max_running_jobs_per_tenant = 0,
  max_partitions_per_tenant = 0,
  max_qps_per_tenant = 0,
  fair_share_group = NULL,
  group_shared_max_running_jobs = NULL,
  enabled = true,
  exceeded_strategy = 'QUEUE_DEFER',
  description = EXCLUDED.description,
  updated_at = now();

COMMIT;
