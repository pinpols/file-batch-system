BEGIN;

-- Pipeline definitions and templates are shared fixtures. Serialize their setup;
-- each benchmark starts independently after this transaction commits.
SELECT pg_advisory_xact_lock(
  hashtext('batch-load-tests:prepare-worker-load-platform')::bigint
);

INSERT INTO batch.job_definition (
  tenant_id, job_code, job_name, job_type, biz_type,
  schedule_type, timezone, priority, queue_code, worker_group,
  calendar_code, window_code, trigger_mode, dag_enabled, shard_strategy,
  retry_policy, retry_max_count, timeout_seconds, enabled, version,
  description, created_by, updated_by, created_at, updated_at
) VALUES
  ('default-tenant', 'lt_dispatch_local_job', 'Load Test Dispatch Local', 'DISPATCH', 'LOAD_TEST',
   'MANUAL', 'Asia/Shanghai', 5, 'dispatch_queue', 'DISPATCH',
   'default_calendar', 'always_open', 'API', false, 'NONE',
   'NONE', 0, 600, true, 1, 'local dispatch load test job', 'load-test', 'load-test', now(), now()),
  ('default-tenant', 'import_customer_job', 'Load Test Customer Import', 'IMPORT', 'LOAD_TEST',
   'MANUAL', 'Asia/Shanghai', 5, 'import_queue', 'import',
   'default_calendar', 'always_open', 'API', false, 'NONE',
   'NONE', 0, 900, true, 1, 'customer import load test job', 'load-test', 'load-test', now(), now()),
  ('default-tenant', 'export_settlement_job', 'Load Test Settlement Export', 'EXPORT', 'LOAD_TEST',
   'MANUAL', 'Asia/Shanghai', 5, 'export_queue', 'export',
   'default_calendar', 'always_open', 'API', false, 'NONE',
   'NONE', 0, 1200, true, 1, 'settlement export load test job', 'load-test', 'load-test', now(), now()),
  ('default-tenant', 'lt_process_sql_job', 'Load Test Process SQL Aggregate', 'PROCESS', 'LOAD_TEST',
   'MANUAL', 'Asia/Shanghai', 5, 'process_queue', 'PROCESS',
   'default_calendar', 'always_open', 'API', false, 'NONE',
   'NONE', 0, 900, true, 1, 'sql aggregate process load test job', 'load-test', 'load-test', now(), now()),
  ('default-tenant', 'lt_process_copy_job', 'Load Test Process Staging Copy', 'PROCESS', 'LOAD_TEST',
   'MANUAL', 'Asia/Shanghai', 5, 'process_queue', 'PROCESS',
   'default_calendar', 'always_open', 'API', false, 'NONE',
   'NONE', 0, 1800, true, 1, 'one source row to one staging row process load test job', 'load-test', 'load-test', now(), now())
ON CONFLICT (tenant_id, job_code) DO UPDATE SET
  enabled = true,
  window_code = EXCLUDED.window_code,
  queue_code = EXCLUDED.queue_code,
  worker_group = EXCLUDED.worker_group,
  updated_at = now();

INSERT INTO batch.job_definition (
  tenant_id, job_code, job_name, job_type, biz_type,
  schedule_type, schedule_expr, timezone, priority, queue_code, worker_group,
  calendar_code, window_code, trigger_mode, dag_enabled, shard_strategy,
  retry_policy, retry_max_count, timeout_seconds, execution_handler, param_schema,
  default_params, version, enabled, description, created_by, updated_by,
  created_at, updated_at
) VALUES (
  'default-tenant', 'atomic_sql_demo', 'Load Test Atomic SQL', 'ATOMIC', 'LOAD_TEST',
  'MANUAL', NULL, 'Asia/Shanghai', 5, 'atomic_queue', 'atomic',
  'default_calendar', 'always_open', 'API', false, 'STATIC',
  'EXPONENTIAL', 1, 300, NULL, jsonb_build_object('type', 'object'),
  jsonb_build_object('taskType', 'sql', 'sql', 'SELECT 1'), 1, true,
  'load test read-only atomic SQL job', 'load-test', 'load-test', now(), now()
)
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
  version = EXCLUDED.version,
  enabled = true,
  description = EXCLUDED.description,
  updated_by = EXCLUDED.updated_by,
  updated_at = now();

INSERT INTO batch.file_template_config (
  tenant_id, template_code, template_name, template_type, biz_type,
  file_format_type, charset, target_charset, delimiter, quote_char, escape_char,
  header_rows, checksum_type, compress_type, encrypt_type, field_mappings,
  default_query_code, default_query_sql, query_param_schema, streaming_enabled,
  page_size, fetch_size, chunk_size, enabled, version, created_by, updated_by,
  load_target_ref, export_data_ref, is_deleted
) VALUES
  (
    'default-tenant', 'import_customer_v1', 'Load Test Customer Import', 'IMPORT',
    'LOAD_TEST', 'DELIMITED', 'UTF-8', 'UTF-8', ',', '"', '"', 1,
    'NONE', 'NONE', 'NONE',
    '[
      {"name":"customerNo","targetColumn":"customer_no","type":"STRING","required":true,"maxLength":64},
      {"name":"customerName","targetColumn":"customer_name","type":"STRING","required":true,"maxLength":256},
      {"name":"customerType","targetColumn":"customer_type","type":"STRING","required":true}
    ]'::jsonb,
    NULL, NULL,
    '{"jdbcMappedImport":{
      "schema":"biz","table":"customer_account","tenantColumn":"tenant_id",
      "columnMappings":[
        {"from":"customerNo","to":"customer_no"},
        {"from":"customerName","to":"customer_name"},
        {"from":"customerType","to":"customer_type"}
      ],
      "conflictColumns":["tenant_id","customer_no"]
    }}'::jsonb,
    true, 1000, 1000, 500, true, 1, 'load-test', 'load-test',
    'jdbc_mapped', NULL, false
  ),
  (
    'default-tenant', 'export_settlement_v1', 'Load Test Settlement Export', 'EXPORT',
    'LOAD_TEST', 'DELIMITED', 'UTF-8', 'UTF-8', ',', '"', '"', 0,
    'NONE', 'NONE', 'NONE',
    '[
      {"name":"batchNo","sourceColumn":"batch_no","type":"STRING","header":"batchNo"},
      {"name":"bizDate","sourceColumn":"biz_date","type":"DATE","header":"bizDate","format":"yyyy-MM-dd"},
      {"name":"settlementNo","sourceColumn":"settlement_no","type":"STRING","header":"settlementNo"},
      {"name":"customerNo","sourceColumn":"customer_no","type":"STRING","header":"customerNo"},
      {"name":"grossAmount","sourceColumn":"gross_amount","type":"DECIMAL","header":"grossAmount"},
      {"name":"feeAmount","sourceColumn":"fee_amount","type":"DECIMAL","header":"feeAmount"},
      {"name":"netAmount","sourceColumn":"net_amount","type":"DECIMAL","header":"netAmount"},
      {"name":"currency","sourceColumn":"currency","type":"STRING","header":"currency"},
      {"name":"status","sourceColumn":"settlement_status","type":"STRING","header":"status"}
    ]'::jsonb,
    'LOAD_TEST_SETTLEMENT_DETAIL',
    'SELECT sb.batch_no, sb.biz_date, sd.settlement_no, sd.customer_no,
            sd.gross_amount, sd.fee_amount, sd.net_amount, sd.currency,
            sd.settlement_status, sd.id
       FROM biz.settlement_detail sd
       JOIN biz.settlement_batch sb
         ON sb.tenant_id = sd.tenant_id AND sb.id = sd.batch_id
      WHERE sb.tenant_id = :tenantId AND sb.batch_no = :batchNo',
    '{"sqlTemplateExport":{"cursorColumn":"id"}}'::jsonb,
    true, 1000, 1000, 500, true, 1, 'load-test', 'load-test',
    NULL, 'sql_template_export', false
  )
ON CONFLICT (tenant_id, template_code, version) DO UPDATE SET
  template_name = EXCLUDED.template_name,
  template_type = EXCLUDED.template_type,
  biz_type = EXCLUDED.biz_type,
  file_format_type = EXCLUDED.file_format_type,
  charset = EXCLUDED.charset,
  target_charset = EXCLUDED.target_charset,
  delimiter = EXCLUDED.delimiter,
  quote_char = EXCLUDED.quote_char,
  escape_char = EXCLUDED.escape_char,
  header_rows = EXCLUDED.header_rows,
  field_mappings = EXCLUDED.field_mappings,
  default_query_code = EXCLUDED.default_query_code,
  default_query_sql = EXCLUDED.default_query_sql,
  query_param_schema = EXCLUDED.query_param_schema,
  streaming_enabled = EXCLUDED.streaming_enabled,
  page_size = EXCLUDED.page_size,
  fetch_size = EXCLUDED.fetch_size,
  chunk_size = EXCLUDED.chunk_size,
  enabled = true,
  updated_by = EXCLUDED.updated_by,
  load_target_ref = EXCLUDED.load_target_ref,
  export_data_ref = EXCLUDED.export_data_ref,
  is_deleted = false,
  updated_at = now();

INSERT INTO batch.file_channel_config (
  tenant_id, channel_code, channel_name, channel_type, target_endpoint,
  auth_type, config_json, receipt_policy, timeout_seconds, enabled, is_deleted
) VALUES (
  'default-tenant', 'local_dispatch', 'Load Test Local Dispatch', 'LOCAL',
  '/tmp/batch/local-dispatch', 'NONE',
  '{"target_endpoint":"/tmp/batch/local-dispatch","receipt_policy":"NONE","channel_type":"LOCAL","channel_code":"local_dispatch"}'::jsonb,
  'NONE', 30, true, false
)
ON CONFLICT (tenant_id, channel_code) DO UPDATE SET
  channel_name = EXCLUDED.channel_name,
  channel_type = EXCLUDED.channel_type,
  target_endpoint = EXCLUDED.target_endpoint,
  auth_type = EXCLUDED.auth_type,
  config_json = EXCLUDED.config_json,
  receipt_policy = EXCLUDED.receipt_policy,
  timeout_seconds = EXCLUDED.timeout_seconds,
  enabled = true,
  is_deleted = false,
  updated_at = now();

DELETE FROM batch.pipeline_step_definition
WHERE pipeline_definition_id IN (
  SELECT id FROM batch.pipeline_definition
  WHERE tenant_id = 'default-tenant'
    AND job_code IN ('import_customer_job', 'export_settlement_job', 'lt_dispatch_local_job',
                     'lt_process_sql_job', 'lt_process_copy_job')
);

INSERT INTO batch.pipeline_definition (
    tenant_id, job_code, pipeline_name, pipeline_type, biz_type, worker_group,
    version, enabled, description, created_at, updated_at
)
SELECT
    'default-tenant', 'import_customer_job', 'Load Test Customer Import Pipeline',
    'IMPORT', 'LOAD_TEST', 'import', 1, true,
    'load test customer import pipeline', now(), now()
WHERE NOT EXISTS (
  SELECT 1 FROM batch.pipeline_definition
  WHERE tenant_id = 'default-tenant' AND job_code = 'import_customer_job' AND version = 1
);

WITH pd AS (
  SELECT id FROM batch.pipeline_definition
  WHERE tenant_id = 'default-tenant' AND job_code = 'import_customer_job' AND version = 1
  ORDER BY id DESC
  LIMIT 1
), steps(stage_code, step_order, step_code, step_name, impl_code, step_params, timeout_seconds,
         retry_policy, retry_max_count) AS (
  VALUES
    ('RECEIVE',    1, 'IMPORT_RECEIVE',    'Receive',    'IMPORT_RECEIVE',    '{}'::jsonb, 300, 'NONE', 0),
    ('PREPROCESS', 2, 'IMPORT_PREPROCESS', 'Preprocess', 'IMPORT_PREPROCESS', '{}'::jsonb, 600, 'NONE', 0),
    ('PARSE',      3, 'IMPORT_PARSE',      'Parse',      'IMPORT_PARSE',      '{}'::jsonb, 900, 'FIXED', 1),
    ('VALIDATE',   4, 'IMPORT_VALIDATE',   'Validate',   'IMPORT_VALIDATE',   '{}'::jsonb, 900, 'FIXED', 1),
    ('LOAD',       5, 'IMPORT_LOAD',       'Load',       'IMPORT_LOAD',       '{}'::jsonb, 1200, 'NONE', 0)
)
INSERT INTO batch.pipeline_step_definition (
    pipeline_definition_id, step_code, step_name, stage_code, step_order,
    impl_code, step_params, timeout_seconds, retry_policy, retry_max_count,
    enabled, created_at, updated_at
)
SELECT pd.id, steps.step_code, steps.step_name, steps.stage_code, steps.step_order,
       steps.impl_code, steps.step_params, steps.timeout_seconds, steps.retry_policy,
       steps.retry_max_count, true, now(), now()
FROM pd CROSS JOIN steps
ON CONFLICT (pipeline_definition_id, step_code) DO UPDATE SET
  step_name = EXCLUDED.step_name,
  stage_code = EXCLUDED.stage_code,
  step_order = EXCLUDED.step_order,
  impl_code = EXCLUDED.impl_code,
  step_params = EXCLUDED.step_params,
  timeout_seconds = EXCLUDED.timeout_seconds,
  retry_policy = EXCLUDED.retry_policy,
  retry_max_count = EXCLUDED.retry_max_count,
  enabled = true,
  updated_at = now();

INSERT INTO batch.pipeline_definition (
    tenant_id, job_code, pipeline_name, pipeline_type, biz_type, worker_group,
    version, enabled, description, created_at, updated_at
)
SELECT
    'default-tenant', 'export_settlement_job', 'Load Test Settlement Export Pipeline',
    'EXPORT', 'LOAD_TEST', 'export', 1, true,
    'load test settlement export pipeline', now(), now()
WHERE NOT EXISTS (
  SELECT 1 FROM batch.pipeline_definition
  WHERE tenant_id = 'default-tenant' AND job_code = 'export_settlement_job' AND version = 1
);

WITH pd AS (
  SELECT id FROM batch.pipeline_definition
  WHERE tenant_id = 'default-tenant' AND job_code = 'export_settlement_job' AND version = 1
  ORDER BY id DESC
  LIMIT 1
), steps(stage_code, step_order, step_code, step_name, impl_code, step_params, timeout_seconds,
         retry_policy, retry_max_count) AS (
  VALUES
    ('PREPARE',   1, 'EXPORT_PREPARE',  'Prepare',  'EXPORT_PREPARE',  '{"snapshotMode":"BIZ_DATE"}'::jsonb, 300, 'NONE', 0),
    ('GENERATE',  2, 'EXPORT_GENERATE', 'Generate', 'EXPORT_GENERATE', '{"delimiter":","}'::jsonb, 1200, 'FIXED', 1),
    ('STORE',     3, 'EXPORT_STORE',    'Store',    'EXPORT_STORE',    '{"bucket":"batch-dev"}'::jsonb, 1200, 'FIXED', 1),
    ('REGISTER',  4, 'EXPORT_REGISTER', 'Register', 'EXPORT_REGISTER', '{"registerMode":"atomic"}'::jsonb, 300, 'NONE', 0),
    ('COMPLETE',  5, 'EXPORT_COMPLETE', 'Complete', 'EXPORT_COMPLETE', '{"terminalOnSuccess":true}'::jsonb, 300, 'NONE', 0)
)
INSERT INTO batch.pipeline_step_definition (
    pipeline_definition_id, step_code, step_name, stage_code, step_order,
    impl_code, step_params, timeout_seconds, retry_policy, retry_max_count,
    enabled, created_at, updated_at
)
SELECT pd.id, steps.step_code, steps.step_name, steps.stage_code, steps.step_order,
       steps.impl_code, steps.step_params, steps.timeout_seconds, steps.retry_policy,
       steps.retry_max_count, true, now(), now()
FROM pd CROSS JOIN steps
ON CONFLICT (pipeline_definition_id, step_code) DO UPDATE SET
  step_name = EXCLUDED.step_name,
  stage_code = EXCLUDED.stage_code,
  step_order = EXCLUDED.step_order,
  impl_code = EXCLUDED.impl_code,
  step_params = EXCLUDED.step_params,
  timeout_seconds = EXCLUDED.timeout_seconds,
  retry_policy = EXCLUDED.retry_policy,
  retry_max_count = EXCLUDED.retry_max_count,
  enabled = true,
  updated_at = now();

INSERT INTO batch.pipeline_definition (
    tenant_id, job_code, pipeline_name, pipeline_type, biz_type, worker_group,
    version, enabled, description, created_at, updated_at
)
SELECT
    'default-tenant', 'lt_dispatch_local_job', 'Load Test Dispatch Local Pipeline',
    'DISPATCH', 'LOAD_TEST', 'DISPATCH', 1, true,
    'load test local dispatch pipeline', now(), now()
WHERE NOT EXISTS (
  SELECT 1 FROM batch.pipeline_definition
  WHERE tenant_id = 'default-tenant' AND job_code = 'lt_dispatch_local_job' AND version = 1
);

WITH pd AS (
  SELECT id FROM batch.pipeline_definition
  WHERE tenant_id = 'default-tenant' AND job_code = 'lt_dispatch_local_job' AND version = 1
  ORDER BY id DESC
  LIMIT 1
), steps(stage_code, step_order, step_code, step_name, impl_code, step_params) AS (
  VALUES
    ('PREPARE',    1, 'DISPATCH_PREPARE',    'Prepare',    'DISPATCH_PREPARE',    '{}'::jsonb),
    ('DISPATCH',   2, 'DISPATCH_DISPATCH',   'Dispatch',   'DISPATCH_DISPATCH',   '{}'::jsonb),
    ('ACK',        3, 'DISPATCH_ACK',        'Ack',        'DISPATCH_ACK',        '{"onSuccessNextStageCode":"COMPLETE"}'::jsonb),
    ('RETRY',      4, 'DISPATCH_RETRY',      'Retry',      'DISPATCH_RETRY',      '{"onFailureNextStageCode":"COMPENSATE"}'::jsonb),
    ('COMPENSATE', 5, 'DISPATCH_COMPENSATE', 'Compensate', 'DISPATCH_COMPENSATE', '{"terminalOnSuccess":true}'::jsonb),
    ('COMPLETE',   6, 'DISPATCH_COMPLETE',   'Complete',   'DISPATCH_COMPLETE',   '{"terminalOnSuccess":true}'::jsonb)
)
INSERT INTO batch.pipeline_step_definition (
    pipeline_definition_id, step_code, step_name, stage_code, step_order,
    impl_code, step_params, timeout_seconds, retry_policy, retry_max_count,
    enabled, created_at, updated_at
)
SELECT pd.id, steps.step_code, steps.step_name, steps.stage_code, steps.step_order,
       steps.impl_code, steps.step_params, 300, 'NONE', 0, true, now(), now()
FROM pd CROSS JOIN steps;

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
),
p AS (
  SELECT left(regexp_replace(:'run_id', '[^A-Za-z0-9]', '', 'g'), 16) || '-ACCT-' AS account_prefix
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
      || p.account_prefix || '%'' group by tenant_id, account_id, biz_date',
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
  600, 'NONE', 0, true, now(), now() FROM pd CROSS JOIN p
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
),
p AS (
  SELECT left(regexp_replace(:'run_id', '[^A-Za-z0-9]', '', 'g'), 16) || '-ACCT-' AS account_prefix
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
      || p.account_prefix || '%''',
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
  1200, 'NONE', 0, true, now(), now() FROM pd CROSS JOIN p
UNION ALL
SELECT id, 'PROCESS_VALIDATE', 'Validate', 'VALIDATE', 3,
  'PROCESS_VALIDATE', '{}'::jsonb, 120, 'NONE', 0, true, now(), now() FROM pd
UNION ALL
SELECT id, 'PROCESS_COMMIT', 'Commit', 'COMMIT', 4,
  'PROCESS_COMMIT', '{}'::jsonb, 900, 'NONE', 0, true, now(), now() FROM pd
UNION ALL
SELECT id, 'PROCESS_FEEDBACK', 'Feedback', 'FEEDBACK', 5,
  'PROCESS_FEEDBACK', '{}'::jsonb, 120, 'NONE', 0, true, now(), now() FROM pd;

INSERT INTO batch.file_record (
  tenant_id, file_code, biz_type, file_category, file_name, original_file_name,
  file_ext, file_format_type, charset, mime_type, file_size_bytes, checksum_type,
  checksum_value, storage_type, storage_path, storage_bucket, file_version,
  file_generation_no, is_latest, source_type, source_ref, file_status, biz_date,
  trace_id, metadata_json, created_at, updated_at
) SELECT
  'default-tenant',
  :'run_id' || '-DISPATCH-FILE-' || lpad(fixture_no::text, 6, '0'),
  'LOAD_TEST', 'OUTPUT',
  :'run_id' || '-dispatch-' || lpad(fixture_no::text, 6, '0') || '.txt',
  :'run_id' || '-dispatch-' || lpad(fixture_no::text, 6, '0') || '.txt',
  'txt', 'DELIMITED', 'UTF-8', 'text/plain', :dispatch_file_size::bigint,
  'NONE', :'run_id' || '-dispatch-checksum-' || lpad(fixture_no::text, 6, '0'),
  'LOCAL',
  :'dispatch_dir' || '/' || :'run_id' || '-dispatch-' || lpad(fixture_no::text, 6, '0') || '.txt',
  'batch-dev', 'v1', 1, true, 'GENERATED', :'run_id', 'GENERATED',
  :'biz_date'::date, :'run_id',
  jsonb_build_object('runId', :'run_id', 'loadTest', true, 'fixtureNo', fixture_no),
  now(), now()
FROM generate_series(1, :dispatch_fixture_count::integer) fixture_no
ON CONFLICT (tenant_id, checksum_value, storage_path)
WHERE checksum_value IS NOT NULL
DO UPDATE SET
  file_code = EXCLUDED.file_code,
  file_name = EXCLUDED.file_name,
  original_file_name = EXCLUDED.original_file_name,
  file_size_bytes = EXCLUDED.file_size_bytes,
  file_status = 'GENERATED',
  biz_date = EXCLUDED.biz_date,
  trace_id = EXCLUDED.trace_id,
  metadata_json = EXCLUDED.metadata_json,
  updated_at = now();

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
