INSERT INTO batch.job_definition (
  tenant_id, job_code, job_name, job_type, biz_type, schedule_type, schedule_expr, timezone,
  priority, queue_code, worker_group, calendar_code, window_code, trigger_mode, dag_enabled,
  shard_strategy, retry_policy, retry_max_count, timeout_seconds, execution_handler,
  param_schema, default_params, version, enabled, description, created_by, updated_by, created_at, updated_at
) VALUES (
  :'tenant_id', :'job_code', 'Process Demo Aggregate', 'PROCESS', 'DEMO',
  'MANUAL', NULL, 'Asia/Shanghai', 5, 'process_queue', 'PROCESS',
  'default_calendar', 'always_open', 'MANUAL', FALSE, 'NONE', 'NONE', 0, 600,
  'com.example.ProcessDemoHandler',
  jsonb_build_object('type','object'),
  jsonb_build_object(
    'processImplCode', 'sqlTransformCompute',
    'systemPipelineStepParams', jsonb_build_object(
      'COMPUTE', jsonb_build_object(
        'sqlTransformCompute', jsonb_build_object(
          'sourceSql', 'SELECT account_no, sum(amount) AS total_amount, count(*) AS txn_count FROM biz.process_demo_source WHERE tenant_id = :tenantId AND biz_date = :bizDate::date GROUP BY account_no LIMIT 1000',
          'targetSchema', 'biz',
          'targetTable', 'process_demo_target',
          'writeMode', 'UPSERT',
          'columns', jsonb_build_array(
            jsonb_build_object('source','account_no','target','account_no'),
            jsonb_build_object('source','total_amount','target','total_amount'),
            jsonb_build_object('source','txn_count','target','txn_count')
          ),
          'conflictColumns', jsonb_build_array('account_no'),
          'emptyResultPolicy', 'FAIL'
        )
      )
    )
  ),
  1, TRUE, 'PROCESS demo for metric verification', 'system', 'system', now(), now()
)
ON CONFLICT (tenant_id, job_code) DO UPDATE SET
  job_name = EXCLUDED.job_name,
  job_type = EXCLUDED.job_type,
  biz_type = EXCLUDED.biz_type,
  default_params = EXCLUDED.default_params,
  enabled = EXCLUDED.enabled,
  updated_by = EXCLUDED.updated_by,
  updated_at = now();
