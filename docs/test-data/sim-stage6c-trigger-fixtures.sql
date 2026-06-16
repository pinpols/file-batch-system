-- Stage 6c Trigger wheel fixtures。
-- 必需的 psql 变量:batch_no, biz_date

INSERT INTO batch.business_calendar (
    tenant_id, calendar_code, calendar_name, timezone, holiday_roll_rule,
    catch_up_policy, catch_up_max_days, enabled, cutoff_time,
    late_arrival_tolerance_min, sla_offset_min, day_rollover_policy,
    dst_gap_policy, dst_overlap_policy, description
)
VALUES (
    'ta', 'stage6c_manual_catchup', 'Stage6c manual catch-up calendar',
    'Asia/Shanghai', 'SKIP', 'MANUAL_APPROVAL', 1, true, '06:00:00',
    60, 0, 'ALLOW_OVERLAP', 'RUN_AT_NEXT_VALID_TIME', 'RUN_ONCE_EARLIER_OFFSET',
    'Stage6c trigger manual approval misfire calendar'
)
ON CONFLICT (tenant_id, calendar_code) DO UPDATE
SET calendar_name = EXCLUDED.calendar_name,
    timezone = EXCLUDED.timezone,
    holiday_roll_rule = EXCLUDED.holiday_roll_rule,
    catch_up_policy = EXCLUDED.catch_up_policy,
    catch_up_max_days = EXCLUDED.catch_up_max_days,
    enabled = EXCLUDED.enabled,
    cutoff_time = EXCLUDED.cutoff_time,
    late_arrival_tolerance_min = EXCLUDED.late_arrival_tolerance_min,
    sla_offset_min = EXCLUDED.sla_offset_min,
    day_rollover_policy = EXCLUDED.day_rollover_policy,
    dst_gap_policy = EXCLUDED.dst_gap_policy,
    dst_overlap_policy = EXCLUDED.dst_overlap_policy,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

WITH src AS (
  SELECT *
  FROM batch.job_definition
  WHERE tenant_id = 'ta'
    AND job_code = 'TA_PROCESS_STAGE4_EMPTY_SUCCESS'
),
jobs(job_code, job_name, calendar_code) AS (
  VALUES
    ('TA_TRIGGER_STAGE6C_SCHEDULED', 'Stage6c scheduled trigger process', 'default-calendar'),
    ('TA_TRIGGER_STAGE6C_MISFIRE', 'Stage6c manual misfire trigger process', 'stage6c_manual_catchup')
)
INSERT INTO batch.job_definition (
    tenant_id, job_code, job_name, job_type, biz_type,
    schedule_type, schedule_expr, timezone, priority, queue_code, worker_group,
    calendar_code, window_code, trigger_mode, dag_enabled, shard_strategy,
    retry_policy, retry_max_count, timeout_seconds, execution_handler,
    param_schema, default_params, version, enabled, description,
    created_by, updated_by, execution_mode, previous_day_dependency_scope
)
SELECT
    src.tenant_id, jobs.job_code, jobs.job_name, src.job_type, 'TRIGGER_STAGE6C',
    'CRON', '0/10 * * * * ?', src.timezone, src.priority, src.queue_code, src.worker_group,
    jobs.calendar_code, src.window_code, 'SCHEDULED', src.dag_enabled, src.shard_strategy,
    src.retry_policy, src.retry_max_count, src.timeout_seconds, src.execution_handler,
    src.param_schema, src.default_params, 1, true, jobs.job_name,
    'sim', 'sim', src.execution_mode, src.previous_day_dependency_scope
FROM src CROSS JOIN jobs
ON CONFLICT (tenant_id, job_code) DO UPDATE
SET job_name = EXCLUDED.job_name,
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
    updated_at = CURRENT_TIMESTAMP,
    execution_mode = EXCLUDED.execution_mode,
    previous_day_dependency_scope = EXCLUDED.previous_day_dependency_scope;

WITH src_pd AS (
  SELECT *
  FROM batch.pipeline_definition
  WHERE tenant_id = 'ta'
    AND job_code = 'TA_PROCESS_STAGE4_EMPTY_SUCCESS'
    AND version = 1
),
jobs(job_code, pipeline_name) AS (
  VALUES
    ('TA_TRIGGER_STAGE6C_SCHEDULED', 'Stage6c scheduled trigger process pipeline'),
    ('TA_TRIGGER_STAGE6C_MISFIRE', 'Stage6c manual misfire trigger process pipeline')
)
INSERT INTO batch.pipeline_definition (
    tenant_id, job_code, pipeline_name, pipeline_type, biz_type,
    worker_group, version, enabled, description
)
SELECT src_pd.tenant_id, jobs.job_code, jobs.pipeline_name, src_pd.pipeline_type,
       'TRIGGER_STAGE6C', src_pd.worker_group, 1, true, jobs.pipeline_name
FROM src_pd CROSS JOIN jobs
ON CONFLICT (tenant_id, job_code, version) DO UPDATE
SET pipeline_name = EXCLUDED.pipeline_name,
    pipeline_type = EXCLUDED.pipeline_type,
    biz_type = EXCLUDED.biz_type,
    worker_group = EXCLUDED.worker_group,
    enabled = EXCLUDED.enabled,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

WITH src_pd AS (
  SELECT id
  FROM batch.pipeline_definition
  WHERE tenant_id = 'ta'
    AND job_code = 'TA_PROCESS_STAGE4_EMPTY_SUCCESS'
    AND version = 1
),
src_steps AS (
  SELECT psd.*
  FROM batch.pipeline_step_definition psd
  JOIN src_pd ON src_pd.id = psd.pipeline_definition_id
),
dst_pd AS (
  SELECT id, job_code
  FROM batch.pipeline_definition
  WHERE tenant_id = 'ta'
    AND job_code IN ('TA_TRIGGER_STAGE6C_SCHEDULED', 'TA_TRIGGER_STAGE6C_MISFIRE')
    AND version = 1
)
INSERT INTO batch.pipeline_step_definition (
    pipeline_definition_id, step_code, step_name, stage_code, step_order,
    impl_code, step_params, timeout_seconds, retry_policy, retry_max_count, enabled
)
SELECT dst_pd.id, src_steps.step_code, src_steps.step_name, src_steps.stage_code,
       src_steps.step_order, src_steps.impl_code, src_steps.step_params,
       src_steps.timeout_seconds, src_steps.retry_policy, src_steps.retry_max_count,
       src_steps.enabled
FROM dst_pd CROSS JOIN src_steps
ON CONFLICT (pipeline_definition_id, step_code) DO UPDATE
SET step_name = EXCLUDED.step_name,
    stage_code = EXCLUDED.stage_code,
    step_order = EXCLUDED.step_order,
    impl_code = EXCLUDED.impl_code,
    step_params = EXCLUDED.step_params,
    timeout_seconds = EXCLUDED.timeout_seconds,
    retry_policy = EXCLUDED.retry_policy,
    retry_max_count = EXCLUDED.retry_max_count,
    enabled = EXCLUDED.enabled,
    updated_at = CURRENT_TIMESTAMP;

WITH targets AS (
  SELECT id
  FROM batch.job_definition
  WHERE tenant_id = 'ta'
    AND job_code IN ('TA_TRIGGER_STAGE6C_SCHEDULED', 'TA_TRIGGER_STAGE6C_MISFIRE')
),
old_states AS (
  SELECT id
  FROM batch.trigger_runtime_state
  WHERE job_definition_id IN (SELECT id FROM targets)
)
DELETE FROM batch.trigger_misfire_pending p
USING old_states s
WHERE p.trigger_runtime_state_id = s.id;

DELETE FROM batch.trigger_runtime_state
WHERE job_definition_id IN (
  SELECT id
  FROM batch.job_definition
  WHERE tenant_id = 'ta'
    AND job_code IN ('TA_TRIGGER_STAGE6C_SCHEDULED', 'TA_TRIGGER_STAGE6C_MISFIRE')
);

DELETE FROM batch.trigger_request
WHERE tenant_id = 'ta'
  AND request_id LIKE :'batch_no' || '%';

WITH clock AS (
  SELECT now() AS ts
),
fires AS (
  SELECT
    date_trunc('minute', ts)
      + (((floor(extract(second from ts) / 10)::int + 1) * 10)::text || ' seconds')::interval AS scheduled_fire,
    date_trunc('minute', ts) - interval '120 seconds' AS misfire
  FROM clock
),
defs AS (
  SELECT id, tenant_id, job_code, timezone
  FROM batch.job_definition
  WHERE tenant_id = 'ta'
    AND job_code IN ('TA_TRIGGER_STAGE6C_SCHEDULED', 'TA_TRIGGER_STAGE6C_MISFIRE')
)
INSERT INTO batch.trigger_runtime_state (
    job_definition_id, tenant_id, job_code, next_fire_time,
    misfire_count, version, schedule_timezone,
    scheduled_local_date, scheduled_local_time, fire_sequence
)
SELECT defs.id,
       defs.tenant_id,
       defs.job_code,
       CASE WHEN defs.job_code = 'TA_TRIGGER_STAGE6C_MISFIRE' THEN fires.misfire ELSE fires.scheduled_fire END,
       0,
       1,
       defs.timezone,
       (CASE WHEN defs.job_code = 'TA_TRIGGER_STAGE6C_MISFIRE' THEN fires.misfire ELSE fires.scheduled_fire END AT TIME ZONE defs.timezone)::date,
       (CASE WHEN defs.job_code = 'TA_TRIGGER_STAGE6C_MISFIRE' THEN fires.misfire ELSE fires.scheduled_fire END AT TIME ZONE defs.timezone)::time,
       1
FROM defs CROSS JOIN fires;

INSERT INTO batch.trigger_request (
    tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key,
    request_status, trace_id, dry_run
)
VALUES (
    'ta',
    :'batch_no' || '-replay',
    'CATCH_UP',
    'TA_TRIGGER_STAGE6C_SCHEDULED',
    :'biz_date'::date,
    :'batch_no' || '-replay',
    'ACCEPTED',
    :'batch_no' || '-replay-trace',
    false
)
ON CONFLICT (tenant_id, request_id) DO UPDATE
SET trigger_type = EXCLUDED.trigger_type,
    job_code = EXCLUDED.job_code,
    biz_date = EXCLUDED.biz_date,
    dedup_key = EXCLUDED.dedup_key,
    request_status = EXCLUDED.request_status,
    trace_id = EXCLUDED.trace_id,
    related_job_instance_id = null,
    updated_at = CURRENT_TIMESTAMP;
