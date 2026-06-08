BEGIN;

INSERT INTO batch.job_definition (
  tenant_id, job_code, job_name, job_type, biz_type,
  schedule_type, schedule_expr, timezone, priority, queue_code, worker_group,
  calendar_code, window_code, trigger_mode, dag_enabled, shard_strategy,
  retry_policy, retry_max_count, timeout_seconds, execution_handler, param_schema, default_params,
  version, enabled, description, created_by, updated_by, created_at, updated_at
)
SELECT
  tenant.tenant_id,
  src.job_code,
  src.job_name || ' ' || tenant.tenant_id,
  src.job_type,
  src.biz_type,
  src.schedule_type,
  src.schedule_expr,
  src.timezone,
  src.priority,
  src.queue_code,
  src.worker_group,
  src.calendar_code,
  src.window_code,
  src.trigger_mode,
  src.dag_enabled,
  src.shard_strategy,
  src.retry_policy,
  src.retry_max_count,
  src.timeout_seconds,
  src.execution_handler,
  src.param_schema,
  src.default_params,
  src.version,
  true,
  'P2 local multi-tenant fairness clone of default atomic_sql_demo',
  'load-test',
  'load-test',
  now(),
  now()
FROM batch.job_definition src
CROSS JOIN (VALUES ('ta'), ('tb'), ('tc')) AS tenant(tenant_id)
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

COMMIT;
