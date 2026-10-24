-- DANGER: 执行前会清理/重建压测平台夹具。仅限专用本地压测库；禁止连接生产或共享验收库。
BEGIN;

-- 流水线定义和模板是共享测试数据，因此串行执行初始化；本事务提交后，各压测可独立运行。
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

-- 审批请求和待处理义务由第 22/24 阶段的真实 Quartz 回调生成。
WITH account_prefix AS (
  SELECT left(encode(sha256(convert_to(:'run_id', 'UTF8')), 'hex'), 16) || '-ACCT-' AS value
)
UPDATE batch.job_definition job
SET default_params = coalesce(job.default_params, '{}'::jsonb) || jsonb_build_object(
      'processImplCode', 'sqlTransformCompute',
      'systemPipelineStepParams', jsonb_build_object(
        'COMPUTE', jsonb_build_object(
          'sqlTransformCompute', jsonb_build_object(
            'sourceSql',
              'SELECT tenant_id, account_id, biz_date, sum(amount) AS total_amount, max(event_id) AS high_water_mark FROM biz.process_order_event WHERE tenant_id = :tenantId AND biz_date = :bizDate::date AND account_id LIKE '
              || quote_literal(account_prefix.value || '%')
              || ' GROUP BY tenant_id, account_id, biz_date LIMIT 100000',
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
                'checkSql', 'SELECT count(*) > 0 AS pass, ''expected staged rows'' AS message FROM batch.process_staging WHERE batch_key = :batchKey'
              )
            ),
            'emptyResultPolicy', 'FAIL',
            'maxStagedRows', :process_agg_max_staged_rows::bigint
          )
        )
      )
    ),
    updated_at = now()
FROM account_prefix
WHERE job.tenant_id = 'default-tenant'
  AND job.job_code = 'lt_process_sql_job';

WITH account_prefix AS (
  SELECT left(encode(sha256(convert_to(:'run_id', 'UTF8')), 'hex'), 16) || '-ACCT-' AS value
)
UPDATE batch.job_definition job
SET default_params = coalesce(job.default_params, '{}'::jsonb) || jsonb_build_object(
      'processImplCode', 'sqlTransformCompute',
      'systemPipelineStepParams', jsonb_build_object(
        'COMPUTE', jsonb_build_object(
          'sqlTransformCompute', jsonb_build_object(
            'sourceSql',
              'SELECT tenant_id, event_id, account_id, biz_date, amount, event_id AS high_water_mark FROM biz.process_order_event WHERE tenant_id = :tenantId AND biz_date = :bizDate::date AND account_id LIKE '
              || quote_literal(account_prefix.value || '%')
              || ' LIMIT ' || :process_copy_max_staged_rows::text,
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
          )
        )
      )
    ),
    updated_at = now()
FROM account_prefix
WHERE job.tenant_id = 'default-tenant'
  AND job.job_code = 'lt_process_copy_job';

-- 业务 PROCESS 配置属于 Job 执行参数；系统 Pipeline 阶段定义是权威来源。
-- 种子夹具可在同步序列后插入显式 ID。
SELECT setval(
  pg_get_serial_sequence('batch.file_record', 'id'),
  COALESCE(MAX(id), 1),
  MAX(id) IS NOT NULL
)
FROM batch.file_record;

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
-- DANGER: 执行前会清理并重建压测平台夹具。仅限专用本地压测库；禁止连接生产或共享验收库。
