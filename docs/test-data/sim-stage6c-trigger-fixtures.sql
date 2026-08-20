-- Stage 6c Quartz trigger fixtures。
-- 必需的 psql 变量:batch_no, biz_date

-- 这组 fixture 会被 stage6c/stage6d 多次复用。sim reset 保留 job/pipeline 定义，
-- 因此上一轮用其他租户跑出来的同名测试定义不能继续留在 Quartz 调度面；否则
-- misfire 扫描会同时处理多个历史租户，污染本轮 ta 的时序和断言。
CREATE TEMP TABLE sim_stage6c_jobs AS
SELECT tenant_id, job_code
FROM batch.job_definition
WHERE job_code IN ('TA_TRIGGER_STAGE6C_SCHEDULED', 'TA_TRIGGER_STAGE6C_MISFIRE');

CREATE TEMP TABLE sim_stage6c_old_jobs AS
SELECT tenant_id, job_code
FROM sim_stage6c_jobs
WHERE tenant_id <> 'ta';

CREATE TEMP TABLE sim_stage6c_old_triggers AS
SELECT trigger_row.sched_name, trigger_row.trigger_name, trigger_row.trigger_group
FROM quartz.qrtz_triggers trigger_row
JOIN sim_stage6c_jobs job
  ON trigger_row.job_name = job.tenant_id || ':' || job.job_code;

DELETE FROM quartz.qrtz_fired_triggers fired
USING sim_stage6c_old_triggers old_trigger
WHERE fired.sched_name = old_trigger.sched_name
  AND fired.trigger_name = old_trigger.trigger_name
  AND fired.trigger_group = old_trigger.trigger_group;

DELETE FROM quartz.qrtz_blob_triggers child
USING sim_stage6c_old_triggers old_trigger
WHERE child.sched_name = old_trigger.sched_name
  AND child.trigger_name = old_trigger.trigger_name
  AND child.trigger_group = old_trigger.trigger_group;

DELETE FROM quartz.qrtz_cron_triggers child
USING sim_stage6c_old_triggers old_trigger
WHERE child.sched_name = old_trigger.sched_name
  AND child.trigger_name = old_trigger.trigger_name
  AND child.trigger_group = old_trigger.trigger_group;

DELETE FROM quartz.qrtz_simple_triggers child
USING sim_stage6c_old_triggers old_trigger
WHERE child.sched_name = old_trigger.sched_name
  AND child.trigger_name = old_trigger.trigger_name
  AND child.trigger_group = old_trigger.trigger_group;

DELETE FROM quartz.qrtz_simprop_triggers child
USING sim_stage6c_old_triggers old_trigger
WHERE child.sched_name = old_trigger.sched_name
  AND child.trigger_name = old_trigger.trigger_name
  AND child.trigger_group = old_trigger.trigger_group;

DELETE FROM quartz.qrtz_triggers trigger_row
USING sim_stage6c_old_triggers old_trigger
WHERE trigger_row.sched_name = old_trigger.sched_name
  AND trigger_row.trigger_name = old_trigger.trigger_name
  AND trigger_row.trigger_group = old_trigger.trigger_group;

DELETE FROM quartz.qrtz_job_details job
USING sim_stage6c_jobs stage_job
WHERE job.job_name = stage_job.tenant_id || ':' || stage_job.job_code;

-- 运行态表可能仍保留这些测试定义的历史实例，不能物理删除定义；禁用后由
-- TriggerReconciler 维持 Quartz 不再注册，避免违反 job_instance 外键。
UPDATE batch.job_definition job
SET enabled = false,
    updated_by = 'sim',
    updated_at = CURRENT_TIMESTAMP
FROM sim_stage6c_old_jobs old_job
WHERE job.tenant_id = old_job.tenant_id
  AND job.job_code = old_job.job_code;

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

DELETE FROM batch.trigger_misfire_pending
WHERE tenant_id = 'ta'
  AND job_code IN ('TA_TRIGGER_STAGE6C_SCHEDULED', 'TA_TRIGGER_STAGE6C_MISFIRE');

DELETE FROM batch.trigger_request
WHERE tenant_id = 'ta'
  AND request_id LIKE :'batch_no' || '%';

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
