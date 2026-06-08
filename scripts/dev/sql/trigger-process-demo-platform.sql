BEGIN;

DELETE FROM batch.pipeline_step_definition WHERE pipeline_definition_id IN
  (SELECT id FROM batch.pipeline_definition WHERE job_code = :'job_code');
DELETE FROM batch.pipeline_definition WHERE job_code = :'job_code';
DELETE FROM batch.job_definition WHERE job_code = :'job_code';

INSERT INTO batch.job_definition (
  tenant_id, job_code, job_name, job_type, biz_type, schedule_type, schedule_expr, timezone,
  priority, queue_code, worker_group, calendar_code, window_code, trigger_mode, dag_enabled,
  shard_strategy, retry_policy, retry_max_count, timeout_seconds, execution_handler,
  param_schema, default_params, version, enabled, description, created_by, updated_by, created_at, updated_at
) VALUES (
  'default-tenant', :'job_code', 'Process Demo Aggregate', 'PROCESS', 'DEMO',
  'MANUAL', NULL, 'Asia/Shanghai', 5, 'process_queue', 'PROCESS',
  'default-calendar', 'always_open', 'MANUAL', FALSE, 'NONE', 'NONE', 0, 600,
  'com.example.ProcessDemoHandler',
  jsonb_build_object('type','object'), jsonb_build_object(),
  1, TRUE, 'PROCESS demo for metric verification', 'system', 'system', now(), now()
);

INSERT INTO batch.pipeline_definition (
  tenant_id, job_code, pipeline_name, pipeline_type, biz_type, worker_group,
  version, enabled, description, created_at, updated_at
) VALUES (
  'default-tenant', :'job_code', 'Process Demo Pipeline', 'PROCESS', 'DEMO', 'PROCESS',
  1, TRUE, 'PROCESS demo for metric verification', now(), now()
);

WITH pd AS (SELECT id FROM batch.pipeline_definition WHERE job_code = :'job_code')
INSERT INTO batch.pipeline_step_definition (
  pipeline_definition_id, step_code, step_name, stage_code, step_order, impl_code,
  step_params, timeout_seconds, retry_policy, retry_max_count, enabled, created_at, updated_at
)
SELECT pd.id, 'PROCESS_PREPARE',  'Prepare',  'PREPARE',  1, 'PROCESS_PREPARE',  '{}'::jsonb, 60,  'NONE', 0, TRUE, now(), now() FROM pd
UNION ALL
SELECT pd.id, 'PROCESS_COMPUTE',  'Compute',  'COMPUTE',  2, 'sqlTransformCompute',
  jsonb_build_object('sqlTransformCompute', jsonb_build_object(
    'sourceSql', 'SELECT account_no, sum(amount) as total_amount, count(*) as txn_count FROM biz.process_demo_source WHERE tenant_id = :tenantId AND biz_date = :bizDate::date GROUP BY account_no',
    'targetSchema', 'biz',
    'targetTable', 'process_demo_target',
    'writeMode', 'UPSERT',
    'columns', jsonb_build_array(
      jsonb_build_object('source','account_no','target','account_no'),
      jsonb_build_object('source','total_amount','target','total_amount'),
      jsonb_build_object('source','txn_count','target','txn_count')
    ),
    'conflictColumns', jsonb_build_array('account_no'),
    'emptyResultPolicy','FAIL'
  )),
  120, 'NONE', 0, TRUE, now(), now() FROM pd
UNION ALL
SELECT pd.id, 'PROCESS_VALIDATE', 'Validate', 'VALIDATE', 3, 'PROCESS_VALIDATE', '{}'::jsonb, 60,  'NONE', 0, TRUE, now(), now() FROM pd
UNION ALL
SELECT pd.id, 'PROCESS_COMMIT',   'Commit',   'COMMIT',   4, 'PROCESS_COMMIT',   '{}'::jsonb, 120, 'NONE', 0, TRUE, now(), now() FROM pd
UNION ALL
SELECT pd.id, 'PROCESS_FEEDBACK', 'Feedback', 'FEEDBACK', 5, 'PROCESS_FEEDBACK', '{}'::jsonb, 60,  'NONE', 0, TRUE, now(), now() FROM pd;

COMMIT;
