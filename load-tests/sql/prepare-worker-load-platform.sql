BEGIN;

INSERT INTO batch.job_definition (
  tenant_id, job_code, job_name, job_type, biz_type,
  schedule_type, timezone, priority, queue_code, worker_group,
  calendar_code, window_code, trigger_mode, dag_enabled, shard_strategy,
  retry_policy, retry_max_count, timeout_seconds, enabled, version,
  description, created_by, updated_by, created_at, updated_at
) VALUES
  ('default-tenant', 'lt_dispatch_local_job', 'Load Test Dispatch Local', 'DISPATCH', 'LOAD_TEST',
   'MANUAL', 'Asia/Shanghai', 5, 'dispatch_queue', 'DISPATCH',
   'default-calendar', 'always_open', 'API', false, 'NONE',
   'NONE', 0, 600, true, 1, 'local dispatch load test job', 'load-test', 'load-test', now(), now()),
  ('default-tenant', 'lt_process_sql_job', 'Load Test Process SQL Aggregate', 'PROCESS', 'LOAD_TEST',
   'MANUAL', 'Asia/Shanghai', 5, 'process_queue', 'PROCESS',
   'default-calendar', 'always_open', 'API', false, 'NONE',
   'NONE', 0, 900, true, 1, 'sql aggregate process load test job', 'load-test', 'load-test', now(), now()),
  ('default-tenant', 'lt_process_copy_job', 'Load Test Process Staging Copy', 'PROCESS', 'LOAD_TEST',
   'MANUAL', 'Asia/Shanghai', 5, 'process_queue', 'PROCESS',
   'default-calendar', 'always_open', 'API', false, 'NONE',
   'NONE', 0, 1800, true, 1, 'one source row to one staging row process load test job', 'load-test', 'load-test', now(), now())
ON CONFLICT (tenant_id, job_code) DO UPDATE SET
  enabled = true,
  window_code = EXCLUDED.window_code,
  queue_code = EXCLUDED.queue_code,
  worker_group = EXCLUDED.worker_group,
  updated_at = now();

DELETE FROM batch.pipeline_step_definition
WHERE pipeline_definition_id IN (
  SELECT id FROM batch.pipeline_definition
  WHERE tenant_id = 'default-tenant' AND job_code IN ('lt_process_sql_job', 'lt_process_copy_job')
);

INSERT INTO batch.pipeline_definition (
    tenant_id, job_code, pipeline_name, pipeline_type, biz_type, worker_group,
    version, enabled, description, created_at, updated_at
)
SELECT
    'default-tenant', 'lt_process_sql_job', 'Load Test Process SQL Pipeline',
    'PROCESS', 'LOAD_TEST', 'PROCESS', 1, true, 'load test sql transform pipeline', now(), now()
WHERE NOT EXISTS (
  SELECT 1 FROM batch.pipeline_definition
  WHERE tenant_id = 'default-tenant' AND job_code = 'lt_process_sql_job'
);

WITH pd AS (
  SELECT id FROM batch.pipeline_definition
  WHERE tenant_id = 'default-tenant' AND job_code = 'lt_process_sql_job'
  ORDER BY id DESC
  LIMIT 1
)
INSERT INTO batch.pipeline_step_definition (
  pipeline_definition_id, step_code, step_name, stage_code, step_order,
  impl_code, step_params, timeout_seconds, retry_policy, retry_max_count,
  enabled, created_at, updated_at
)
SELECT id, 'PROCESS_PREPARE', 'Prepare', 'PREPARE', 1,
  'PROCESS_PREPARE', '{}'::jsonb, 120, 'NONE', 0, true, now(), now() FROM pd
UNION ALL
SELECT id, 'PROCESS_COMPUTE', 'Compute', 'COMPUTE', 2,
  'sqlTransformCompute',
  jsonb_build_object('sqlTransformCompute', jsonb_build_object(
    'sourceSql',
    'select tenant_id, account_id, biz_date, sum(amount) as total_amount, max(event_id) as high_water_mark from biz.process_order_event where tenant_id = :tenantId and biz_date = :bizDate::date and account_id like '''
      || :'run_id' || '-ACCT-%'' group by tenant_id, account_id, biz_date',
    'targetSchema', 'biz',
    'targetTable', 'process_account_summary',
    'writeMode', 'UPSERT',
    'columns', jsonb_build_array(
      jsonb_build_object('source', 'tenant_id', 'target', 'tenant_id'),
      jsonb_build_object('source', 'account_id', 'target', 'account_id'),
      jsonb_build_object('source', 'biz_date', 'target', 'biz_date'),
      jsonb_build_object('source', 'total_amount', 'target', 'total_amount'),
      jsonb_build_object('source', 'high_water_mark', 'target', 'high_water_mark')
    ),
    'conflictColumns', jsonb_build_array('tenant_id', 'account_id', 'biz_date'),
    'validations', jsonb_build_array(
      jsonb_build_object(
        'name', 'staged_rows_present',
        'checkSql', 'select count(*) > 0 as pass, ''expected staged rows'' as message from batch.process_staging where batch_key = :batchKey'
      )
    ),
    'emptyResultPolicy', 'FAIL',
    'maxStagedRows', :process_agg_max_staged_rows::bigint
  )),
  600, 'NONE', 0, true, now(), now() FROM pd
UNION ALL
SELECT id, 'PROCESS_VALIDATE', 'Validate', 'VALIDATE', 3,
  'PROCESS_VALIDATE', '{}'::jsonb, 120, 'NONE', 0, true, now(), now() FROM pd
UNION ALL
SELECT id, 'PROCESS_COMMIT', 'Commit', 'COMMIT', 4,
  'PROCESS_COMMIT', '{}'::jsonb, 300, 'NONE', 0, true, now(), now() FROM pd
UNION ALL
SELECT id, 'PROCESS_FEEDBACK', 'Feedback', 'FEEDBACK', 5,
  'PROCESS_FEEDBACK', '{}'::jsonb, 120, 'NONE', 0, true, now(), now() FROM pd;

INSERT INTO batch.pipeline_definition (
    tenant_id, job_code, pipeline_name, pipeline_type, biz_type, worker_group,
    version, enabled, description, created_at, updated_at
)
SELECT
    'default-tenant', 'lt_process_copy_job', 'Load Test Process Staging Copy Pipeline',
    'PROCESS', 'LOAD_TEST', 'PROCESS', 1, true, 'load test one row to one staging row pipeline', now(), now()
WHERE NOT EXISTS (
  SELECT 1 FROM batch.pipeline_definition
  WHERE tenant_id = 'default-tenant' AND job_code = 'lt_process_copy_job'
);

WITH pd AS (
  SELECT id FROM batch.pipeline_definition
  WHERE tenant_id = 'default-tenant' AND job_code = 'lt_process_copy_job'
  ORDER BY id DESC
  LIMIT 1
)
INSERT INTO batch.pipeline_step_definition (
  pipeline_definition_id, step_code, step_name, stage_code, step_order,
  impl_code, step_params, timeout_seconds, retry_policy, retry_max_count,
  enabled, created_at, updated_at
)
SELECT id, 'PROCESS_PREPARE', 'Prepare', 'PREPARE', 1,
  'PROCESS_PREPARE', '{}'::jsonb, 120, 'NONE', 0, true, now(), now() FROM pd
UNION ALL
SELECT id, 'PROCESS_COMPUTE', 'Compute', 'COMPUTE', 2,
  'sqlTransformCompute',
  jsonb_build_object('sqlTransformCompute', jsonb_build_object(
    'sourceSql',
    'select tenant_id, event_id, account_id, biz_date, amount, event_id as high_water_mark from biz.process_order_event where tenant_id = :tenantId and biz_date = :bizDate::date and account_id like '''
      || :'run_id' || '-ACCT-%''',
    'targetSchema', 'biz',
    'targetTable', 'process_event_copy',
    'writeMode', 'UPSERT',
    'stagingMode', 'DIRECT',
    'columns', jsonb_build_array(
      jsonb_build_object('source', 'tenant_id', 'target', 'tenant_id'),
      jsonb_build_object('source', 'event_id', 'target', 'event_id'),
      jsonb_build_object('source', 'account_id', 'target', 'account_id'),
      jsonb_build_object('source', 'biz_date', 'target', 'biz_date'),
      jsonb_build_object('source', 'amount', 'target', 'amount'),
      jsonb_build_object('source', 'high_water_mark', 'target', 'high_water_mark')
    ),
    'conflictColumns', jsonb_build_array('tenant_id', 'event_id'),
    'maxStagedRows', :process_copy_max_staged_rows::bigint
  )),
  1200, 'NONE', 0, true, now(), now() FROM pd
UNION ALL
SELECT id, 'PROCESS_VALIDATE', 'Validate', 'VALIDATE', 3,
  'PROCESS_VALIDATE', '{}'::jsonb, 120, 'NONE', 0, true, now(), now() FROM pd
UNION ALL
SELECT id, 'PROCESS_COMMIT', 'Commit', 'COMMIT', 4,
  'PROCESS_COMMIT', '{}'::jsonb, 900, 'NONE', 0, true, now(), now() FROM pd
UNION ALL
SELECT id, 'PROCESS_FEEDBACK', 'Feedback', 'FEEDBACK', 5,
  'PROCESS_FEEDBACK', '{}'::jsonb, 120, 'NONE', 0, true, now(), now() FROM pd;

WITH existing AS (
  SELECT id FROM batch.file_record
  WHERE tenant_id = 'default-tenant' AND file_code = :'run_id' || '-DISPATCH-FILE'
)
INSERT INTO batch.file_record (
  tenant_id, file_code, biz_type, file_category, file_name, original_file_name,
  file_ext, file_format_type, charset, mime_type, file_size_bytes, checksum_type,
  checksum_value, storage_type, storage_path, storage_bucket, file_version,
  file_generation_no, is_latest, source_type, source_ref, file_status, biz_date,
  trace_id, metadata_json, created_at, updated_at
) SELECT
  'default-tenant', :'run_id' || '-DISPATCH-FILE', 'LOAD_TEST', 'OUTPUT',
  :'run_id' || '-dispatch.txt', :'run_id' || '-dispatch.txt', 'txt', 'DELIMITED',
  'UTF-8', 'text/plain', :dispatch_file_size::bigint, 'NONE', :'run_id' || '-dispatch-checksum',
  'LOCAL', :'dispatch_file', 'batch-dev', 'v1', 1, true, 'GENERATED',
  :'run_id', 'GENERATED', :'biz_date'::date, :'run_id',
  jsonb_build_object('runId', :'run_id', 'loadTest', true), now(), now()
WHERE NOT EXISTS (SELECT 1 FROM existing);

UPDATE batch.file_record
SET file_status = 'GENERATED',
    storage_path = :'dispatch_file',
    file_size_bytes = :dispatch_file_size::bigint,
    updated_at = now()
WHERE tenant_id = 'default-tenant' AND file_code = :'run_id' || '-DISPATCH-FILE';

UPDATE batch.worker_registry
SET status = 'ONLINE',
    heartbeat_at = now(),
    updated_at = now(),
    drain_started_at = NULL,
    drain_deadline_at = NULL
WHERE tenant_id = 'default-tenant'
  AND worker_group = 'PROCESS'
  AND worker_code = 'process-node-1'
  AND status = 'DECOMMISSIONED';

COMMIT;
