BEGIN;

INSERT INTO batch.job_definition (
    tenant_id, job_code, job_name, job_type, biz_type, schedule_type, schedule_expr, timezone, priority,
    queue_code, worker_group, calendar_code, window_code, trigger_mode, dag_enabled, shard_strategy,
    retry_policy, retry_max_count, timeout_seconds, execution_handler, param_schema, default_params,
    version, enabled, description, created_by, updated_by, created_at, updated_at
) VALUES
    ('default-tenant', 'atomic_shell_demo', 'Atomic Shell Demo', 'ATOMIC', 'GENERAL', 'MANUAL', NULL,
     'Asia/Shanghai', 5, 'atomic_queue', 'atomic', 'default_calendar', 'always_open', 'MANUAL', FALSE,
     'STATIC', 'EXPONENTIAL', 1, 300, NULL, jsonb_build_object('type', 'object'),
     jsonb_build_object('taskType', 'shell', 'command', '/bin/echo',
                        'args', jsonb_build_array('hello-from-atomic')),
     1, TRUE, 'Atomic shell task used by local sim', 'system', 'system', current_timestamp, current_timestamp),
    ('default-tenant', 'atomic_sql_demo', 'Atomic SQL Demo', 'ATOMIC', 'GENERAL', 'MANUAL', NULL,
     'Asia/Shanghai', 5, 'atomic_queue', 'atomic', 'default_calendar', 'always_open', 'MANUAL', FALSE,
     'STATIC', 'EXPONENTIAL', 1, 300, NULL, jsonb_build_object('type', 'object'),
     jsonb_build_object('taskType', 'sql', 'sql', 'SELECT 1'),
     1, TRUE, 'Atomic SQL task used by local sim', 'system', 'system', current_timestamp, current_timestamp),
    ('default-tenant', 'atomic_stored_proc_demo', 'Atomic Stored Proc Demo', 'ATOMIC', 'GENERAL', 'MANUAL', NULL,
     'Asia/Shanghai', 5, 'atomic_queue', 'atomic', 'default_calendar', 'always_open', 'MANUAL', FALSE,
     'STATIC', 'EXPONENTIAL', 1, 300, NULL, jsonb_build_object('type', 'object'),
     jsonb_build_object('taskType', 'stored_proc', 'procedureName', 'batch.refresh_metrics'),
     1, TRUE, 'Atomic stored procedure task used by local sim', 'system', 'system', current_timestamp, current_timestamp),
    ('default-tenant', 'atomic_http_demo', 'Atomic HTTP Demo', 'ATOMIC', 'GENERAL', 'MANUAL', NULL,
     'Asia/Shanghai', 5, 'atomic_queue', 'atomic', 'default_calendar', 'always_open', 'MANUAL', FALSE,
     'STATIC', 'EXPONENTIAL', 1, 300, NULL, jsonb_build_object('type', 'object'),
     jsonb_build_object('taskType', 'http', 'url', 'https://example.internal/health', 'method', 'GET'),
     1, TRUE, 'Atomic HTTP task used by local sim', 'system', 'system', current_timestamp, current_timestamp)
ON CONFLICT (tenant_id, job_code) DO UPDATE SET
    job_name = EXCLUDED.job_name,
    job_type = EXCLUDED.job_type,
    biz_type = EXCLUDED.biz_type,
    schedule_type = EXCLUDED.schedule_type,
    schedule_expr = EXCLUDED.schedule_expr,
    timezone = EXCLUDED.timezone,
    priority = EXCLUDED.priority,
    queue_code = EXCLUDED.queue_code,
    worker_group = EXCLUDED.worker_group,
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
    enabled = EXCLUDED.enabled,
    description = EXCLUDED.description,
    updated_by = EXCLUDED.updated_by,
    updated_at = current_timestamp;

CREATE OR REPLACE PROCEDURE batch.refresh_metrics()
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1;
END;
$$;

COMMIT;
