-- Stage 6c/6d Quartz Workflow 触发器测试夹具。
-- 必需的 psql 变量：batch_no、biz_date

-- 数据放在一起；它们不是代码定义阶段的运行时执行来源。
-- 同一模拟 Job 的旧租户副本不得继续启用定时 Workflow，
CREATE TEMP TABLE sim_stage6c_workflows AS
SELECT wf.tenant_id, wf.workflow_code
FROM batch.workflow_definition wf
JOIN batch.job_definition job
  ON job.tenant_id = wf.tenant_id
 AND job.id = wf.single_job_definition_id
WHERE job.job_code IN ('TA_TRIGGER_STAGE6C_SCHEDULED', 'TA_TRIGGER_STAGE6C_MISFIRE');

CREATE TEMP TABLE sim_stage6c_old_workflows AS
SELECT tenant_id, workflow_code
FROM sim_stage6c_workflows
WHERE tenant_id <> 'ta';

CREATE TEMP TABLE sim_stage6c_old_triggers AS
SELECT trigger_row.sched_name, trigger_row.trigger_name, trigger_row.trigger_group
FROM quartz.qrtz_triggers trigger_row
JOIN sim_stage6c_old_workflows wf
  ON trigger_row.job_name = wf.tenant_id || ':WF:' || wf.workflow_code;

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

DELETE FROM quartz.qrtz_job_details quartz_job
USING sim_stage6c_old_workflows wf
WHERE quartz_job.job_name = wf.tenant_id || ':WF:' || wf.workflow_code;

UPDATE batch.workflow_definition wf
SET schedule_enabled = false,
    updated_by = 'sim',
    updated_at = CURRENT_TIMESTAMP
FROM sim_stage6c_old_workflows old_wf
WHERE wf.tenant_id = old_wf.tenant_id
  AND wf.workflow_code = old_wf.workflow_code;

INSERT INTO batch.business_calendar (
    tenant_id, calendar_code, calendar_name, timezone, holiday_roll_rule,
    catch_up_policy, catch_up_max_days, enabled, cutoff_time,
    late_arrival_tolerance_min, sla_offset_min, day_rollover_policy,
    dst_gap_policy, dst_overlap_policy, description
)
VALUES
    ('ta', 'stage6c_manual_catchup', 'Stage6c manual catch-up calendar',
     'Asia/Shanghai', 'SKIP', 'MANUAL_APPROVAL', 1, true, '06:00:00',
     60, 0, 'ALLOW_OVERLAP', 'RUN_AT_NEXT_VALID_TIME', 'RUN_ONCE_EARLIER_OFFSET',
     'Stage6c trigger manual approval misfire calendar'),
    ('ta', 'stage6c_scheduled', 'Stage6c scheduled trigger calendar',
     'Asia/Shanghai', 'SKIP', 'AUTO', 1, true, '06:00:00',
     60, 0, 'ALLOW_OVERLAP', 'RUN_AT_NEXT_VALID_TIME', 'RUN_ONCE_EARLIER_OFFSET',
     'Stage6c deterministic scheduled trigger calendar without holidays')
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

-- 因为此场景只断言 ta 定义。
-- 这些可执行 Job 定义提供节点目标。调度由
WITH src AS (
  SELECT *
  FROM batch.job_definition
  WHERE tenant_id = 'ta'
    AND job_code = 'TA_PROCESS_STAGE4_EMPTY_SUCCESS'
),
jobs(job_code, job_name) AS (
  VALUES
    ('TA_TRIGGER_STAGE6C_SCHEDULED', 'Stage6c scheduled trigger process'),
    ('TA_TRIGGER_STAGE6C_MISFIRE', 'Stage6c manual misfire trigger process')
)
INSERT INTO batch.job_definition (
    tenant_id, job_code, job_name, job_type, biz_type,
    schedule_type, schedule_expr, timezone, priority, queue_code, worker_group,
    calendar_code, window_code, trigger_mode, dag_enabled, shard_strategy,
    retry_policy, retry_max_count, timeout_seconds, execution_handler,
    param_schema, default_params, version, enabled, description,
    created_by, updated_by, execution_mode, previous_day_dependency_scope
)
SELECT src.tenant_id, jobs.job_code, jobs.job_name, src.job_type, 'TRIGGER_STAGE6C',
       'MANUAL', NULL, src.timezone, src.priority, src.queue_code, src.worker_group,
       NULL, src.window_code, 'API', src.dag_enabled, src.shard_strategy,
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

WITH jobs(job_code, workflow_code, workflow_name, calendar_code, catch_up_policy) AS (
  VALUES
    ('TA_TRIGGER_STAGE6C_SCHEDULED', 'WF-TA_TRIGGER_STAGE6C_SCHEDULED',
     'Stage6c scheduled trigger Workflow', 'stage6c_scheduled', 'AUTO'),
    ('TA_TRIGGER_STAGE6C_MISFIRE', 'WF-TA_TRIGGER_STAGE6C_MISFIRE',
     'Stage6c manual misfire trigger Workflow', 'stage6c_manual_catchup', 'MANUAL_APPROVAL')
)
INSERT INTO batch.workflow_definition (
    tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
    description, created_by, updated_by, schedule_type, schedule_expr, timezone,
    trigger_mode, calendar_code, schedule_enabled, single_job_definition_id,
    created_at, updated_at
)
SELECT job.tenant_id, jobs.workflow_code, jobs.workflow_name, 'DAG', 1, true,
       jobs.workflow_name, 'sim', 'sim', 'CRON', '0/10 * * * * ?',
       job.timezone, 'SCHEDULED', jobs.calendar_code, true, job.id, now(), now()
FROM jobs
JOIN batch.job_definition job
  ON job.tenant_id = 'ta'
 AND job.job_code = jobs.job_code
ON CONFLICT (tenant_id, workflow_code, version) DO UPDATE
SET workflow_name = EXCLUDED.workflow_name,
    workflow_type = EXCLUDED.workflow_type,
    enabled = true,
    schedule_type = EXCLUDED.schedule_type,
    schedule_expr = EXCLUDED.schedule_expr,
    timezone = EXCLUDED.timezone,
    trigger_mode = EXCLUDED.trigger_mode,
    calendar_code = EXCLUDED.calendar_code,
    schedule_enabled = true,
    single_job_definition_id = EXCLUDED.single_job_definition_id,
    updated_by = EXCLUDED.updated_by,
    updated_at = CURRENT_TIMESTAMP;

WITH jobs(job_code, workflow_code) AS (
  VALUES
    ('TA_TRIGGER_STAGE6C_SCHEDULED', 'WF-TA_TRIGGER_STAGE6C_SCHEDULED'),
    ('TA_TRIGGER_STAGE6C_MISFIRE', 'WF-TA_TRIGGER_STAGE6C_MISFIRE')
), nodes(node_code, node_name, node_type, node_order) AS (
  VALUES ('START', 'Start', 'START', 0),
         ('JOB', 'Execute Job', 'JOB', 1),
         ('END', 'End', 'END', 2)
)
INSERT INTO batch.workflow_node (
    tenant_id, workflow_definition_id, node_code, node_name, node_type,
    related_job_code, node_order, retry_policy, retry_max_count,
    timeout_seconds, enabled, created_at, updated_at
)
SELECT 'ta', workflow.id, nodes.node_code, nodes.node_name, nodes.node_type,
       CASE WHEN nodes.node_type = 'JOB' THEN jobs.job_code END,
       nodes.node_order, 'NONE', 0, 0, true, now(), now()
FROM jobs
JOIN batch.workflow_definition workflow
  ON workflow.tenant_id = 'ta'
 AND workflow.workflow_code = jobs.workflow_code
 AND workflow.version = 1
CROSS JOIN nodes
ON CONFLICT (tenant_id, workflow_definition_id, node_code) DO UPDATE
SET node_name = EXCLUDED.node_name,
    node_type = EXCLUDED.node_type,
    related_job_code = EXCLUDED.related_job_code,
    node_order = EXCLUDED.node_order,
    enabled = true,
    updated_at = CURRENT_TIMESTAMP;

WITH jobs(workflow_code) AS (
  VALUES ('WF-TA_TRIGGER_STAGE6C_SCHEDULED'),
         ('WF-TA_TRIGGER_STAGE6C_MISFIRE')
)
INSERT INTO batch.workflow_edge (
    tenant_id, workflow_definition_id, from_node_code, to_node_code,
    edge_type, enabled, created_at, updated_at
)
SELECT 'ta', workflow.id, edge.from_node_code, edge.to_node_code,
       'SUCCESS', true, now(), now()
FROM jobs
JOIN batch.workflow_definition workflow
  ON workflow.tenant_id = 'ta'
 AND workflow.workflow_code = jobs.workflow_code
 AND workflow.version = 1
CROSS JOIN (VALUES ('START', 'JOB'), ('JOB', 'END')) AS edge(from_node_code, to_node_code)
ON CONFLICT (tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type)
DO UPDATE SET enabled = true, updated_at = CURRENT_TIMESTAMP;

DELETE FROM batch.trigger_misfire_pending pending
USING batch.workflow_definition workflow
WHERE pending.tenant_id = 'ta'
  AND pending.workflow_definition_id = workflow.id
  AND workflow.workflow_code IN
      ('WF-TA_TRIGGER_STAGE6C_SCHEDULED', 'WF-TA_TRIGGER_STAGE6C_MISFIRE');

DELETE FROM batch.trigger_request
WHERE tenant_id = 'ta'
  AND request_id LIKE :'batch_no' || '%';

-- 对应的单作业 Workflow 负责；Pipeline 阶段按 Job 类型解析。
