-- Stage 2c Import load-mode matrix platform fixture.
-- Business schema setup is kept in sim-stage2c-import-matrix-business.sql.

BEGIN;

WITH src AS (
  SELECT *
  FROM batch.file_template_config
  WHERE tenant_id = 'ta'
    AND template_code = 'TA_IMPORT_CUSTOMER_XML_TPL'
    AND is_deleted = false
  ORDER BY version DESC
  LIMIT 1
), templates AS (
  SELECT
    'TA_IMPORT_STAGE2C_APPEND_TPL' AS template_code,
    'Stage2c APPEND import template' AS template_name,
    'IMPORT_STAGE2C_APPEND' AS biz_type,
    jsonb_set(
      jsonb_set(src.query_param_schema, '{jdbcMappedImport,table}', '"import_stage2c_customer"', true),
      '{jdbcMappedImport,conflictColumns}',
      '[]'::jsonb,
      true
    ) AS query_param_schema,
    'Stage 2c import APPEND/no-conflict matrix scenario' AS description
  FROM src
  UNION ALL
  SELECT
    'TA_IMPORT_STAGE2C_UPSERT_TPL',
    'Stage2c UPSERT import template',
    'IMPORT_STAGE2C_UPSERT',
    jsonb_set(src.query_param_schema, '{jdbcMappedImport,loadStrategy}', '"BATCH_UPSERT"', true),
    'Stage 2c import BATCH_UPSERT matrix scenario'
  FROM src
  UNION ALL
  SELECT
    'TA_IMPORT_STAGE2C_REPLACE_TPL',
    'Stage2c partition replace import template',
    'IMPORT_STAGE2C_REPLACE',
    jsonb_set(
      jsonb_set(
        jsonb_set(src.query_param_schema, '{jdbcMappedImport,table}', '"import_stage2c_customer"', true),
        '{jdbcMappedImport,loadStrategy}',
        '"PARTITION_REPLACE_COPY"',
        true
      ),
      '{jdbcMappedImport,replacePartitionColumns}',
      '["tenant_id","source_batch_no"]'::jsonb,
      true
    ),
    'Stage 2c import PARTITION_REPLACE_COPY matrix scenario'
  FROM src
)
INSERT INTO batch.file_template_config (
    tenant_id, template_code, template_name, template_type, biz_type,
    file_format_type, charset, target_charset, with_bom, line_separator,
    delimiter, quote_char, escape_char, record_length, header_rows, footer_rows,
    header_template, trailer_template, checksum_type, compress_type, encrypt_type,
    naming_rule, field_mappings, validation_rule_set, query_param_schema,
    streaming_enabled, page_size, fetch_size, chunk_size, enabled, version,
    description, created_by, updated_by, preprocess_pipeline,
    preview_masking_enabled, error_line_masking_enabled, log_masking_enabled,
    content_encryption_enabled, download_requires_approval, load_target_ref, is_deleted
)
SELECT
    src.tenant_id, templates.template_code, templates.template_name, src.template_type, templates.biz_type,
    src.file_format_type, src.charset, src.target_charset, src.with_bom, src.line_separator,
    src.delimiter, src.quote_char, src.escape_char, src.record_length, src.header_rows, src.footer_rows,
    src.header_template, src.trailer_template, src.checksum_type, src.compress_type, src.encrypt_type,
    replace(src.naming_rule, 'customer_xml', lower(templates.biz_type)), src.field_mappings,
    src.validation_rule_set, templates.query_param_schema,
    src.streaming_enabled, src.page_size, src.fetch_size, src.chunk_size, true, 1,
    templates.description, 'sim-e2e', 'sim-e2e', src.preprocess_pipeline,
    src.preview_masking_enabled, src.error_line_masking_enabled, src.log_masking_enabled,
    src.content_encryption_enabled, src.download_requires_approval, src.load_target_ref, false
FROM src
JOIN templates ON true
ON CONFLICT (tenant_id, template_code, version) DO UPDATE
SET template_name = EXCLUDED.template_name,
    biz_type = EXCLUDED.biz_type,
    naming_rule = EXCLUDED.naming_rule,
    query_param_schema = EXCLUDED.query_param_schema,
    enabled = true,
    description = EXCLUDED.description,
    updated_by = EXCLUDED.updated_by,
    updated_at = CURRENT_TIMESTAMP,
    is_deleted = false;

WITH src AS (
  SELECT *
  FROM batch.job_definition
  WHERE tenant_id = 'ta'
    AND job_code = 'TA_IMPORT_CUSTOMER_XML'
), jobs AS (
  SELECT 'TA_IMPORT_STAGE2C_APPEND' AS job_code,
         'Stage2c APPEND import' AS job_name,
         'IMPORT_STAGE2C_APPEND' AS biz_type,
         'TA_IMPORT_STAGE2C_APPEND_TPL' AS template_code,
         'NONE' AS shard_strategy,
         'Stage 2c import APPEND/no-conflict matrix scenario' AS description
  UNION ALL
  SELECT 'TA_IMPORT_STAGE2C_UPSERT',
         'Stage2c UPSERT import',
         'IMPORT_STAGE2C_UPSERT',
         'TA_IMPORT_STAGE2C_UPSERT_TPL',
         'NONE',
         'Stage 2c import BATCH_UPSERT matrix scenario'
  UNION ALL
  SELECT 'TA_IMPORT_STAGE2C_REPLACE',
         'Stage2c partition replace import',
         'IMPORT_STAGE2C_REPLACE',
         'TA_IMPORT_STAGE2C_REPLACE_TPL',
         'NONE',
         'Stage 2c import PARTITION_REPLACE_COPY matrix scenario'
)
INSERT INTO batch.job_definition (
    tenant_id, job_code, job_name, job_type, biz_type, schedule_type, schedule_expr,
    timezone, priority, queue_code, worker_group, calendar_code, window_code,
    trigger_mode, dag_enabled, shard_strategy, retry_policy, retry_max_count,
    timeout_seconds, execution_handler, param_schema, default_params, version,
    enabled, description, created_by, updated_by, execution_mode,
    previous_day_dependency_scope, retry_policy_by_class
)
SELECT src.tenant_id, jobs.job_code, jobs.job_name, src.job_type, jobs.biz_type,
       'MANUAL', null, src.timezone, src.priority, src.queue_code, src.worker_group,
       src.calendar_code, src.window_code, 'API', false, jobs.shard_strategy,
       'NONE', 0, src.timeout_seconds, src.execution_handler,
       src.param_schema, jsonb_build_object('templateCode', jobs.template_code),
       1, true, jobs.description, 'sim-e2e', 'sim-e2e', 'FULL',
       coalesce(src.previous_day_dependency_scope, 'INHERIT'), src.retry_policy_by_class
FROM src
JOIN jobs ON true
ON CONFLICT (tenant_id, job_code) DO UPDATE
SET job_name = EXCLUDED.job_name,
    biz_type = EXCLUDED.biz_type,
    schedule_type = EXCLUDED.schedule_type,
    trigger_mode = EXCLUDED.trigger_mode,
    shard_strategy = EXCLUDED.shard_strategy,
    retry_policy = EXCLUDED.retry_policy,
    retry_max_count = EXCLUDED.retry_max_count,
    default_params = EXCLUDED.default_params,
    enabled = true,
    description = EXCLUDED.description,
    updated_by = EXCLUDED.updated_by,
    updated_at = CURRENT_TIMESTAMP,
    execution_mode = 'FULL';

WITH src AS (
  SELECT *
  FROM batch.pipeline_definition
  WHERE tenant_id = 'ta'
    AND job_code = 'TA_IMPORT_CUSTOMER_XML'
    AND version = 1
), pipes AS (
  SELECT 'TA_IMPORT_STAGE2C_APPEND' AS job_code,
         'Stage2c APPEND import pipeline' AS pipeline_name,
         'IMPORT_STAGE2C_APPEND' AS biz_type,
         'Stage 2c import APPEND/no-conflict matrix scenario' AS description
  UNION ALL
  SELECT 'TA_IMPORT_STAGE2C_UPSERT',
         'Stage2c UPSERT import pipeline',
         'IMPORT_STAGE2C_UPSERT',
         'Stage 2c import BATCH_UPSERT matrix scenario'
  UNION ALL
  SELECT 'TA_IMPORT_STAGE2C_REPLACE',
         'Stage2c partition replace import pipeline',
         'IMPORT_STAGE2C_REPLACE',
         'Stage 2c import PARTITION_REPLACE_COPY matrix scenario'
)
INSERT INTO batch.pipeline_definition (
    tenant_id, job_code, pipeline_name, pipeline_type, biz_type, worker_group,
    version, enabled, description
)
SELECT src.tenant_id, pipes.job_code, pipes.pipeline_name, src.pipeline_type, pipes.biz_type,
       src.worker_group, 1, true, pipes.description
FROM src
JOIN pipes ON true
ON CONFLICT (tenant_id, job_code, version) DO UPDATE
SET pipeline_name = EXCLUDED.pipeline_name,
    biz_type = EXCLUDED.biz_type,
    worker_group = EXCLUDED.worker_group,
    enabled = true,
    description = EXCLUDED.description,
    updated_at = CURRENT_TIMESTAMP;

WITH source_steps AS (
  SELECT psd.*
  FROM batch.pipeline_definition src_pd
  JOIN batch.pipeline_step_definition psd ON psd.pipeline_definition_id = src_pd.id
  WHERE src_pd.tenant_id = 'ta'
    AND src_pd.job_code = 'TA_IMPORT_CUSTOMER_XML'
    AND src_pd.version = 1
), target_pipelines AS (
  SELECT pd.id AS pipeline_definition_id
  FROM batch.pipeline_definition pd
  WHERE pd.tenant_id = 'ta'
    AND pd.job_code IN (
      'TA_IMPORT_STAGE2C_APPEND',
      'TA_IMPORT_STAGE2C_UPSERT',
      'TA_IMPORT_STAGE2C_REPLACE'
    )
    AND pd.version = 1
)
INSERT INTO batch.pipeline_step_definition (
    pipeline_definition_id, step_code, step_name, stage_code, step_order,
    impl_code, step_params, timeout_seconds, retry_policy, retry_max_count, enabled
)
SELECT tp.pipeline_definition_id, ss.step_code, ss.step_name, ss.stage_code, ss.step_order,
       ss.impl_code, coalesce(ss.step_params, '{}'::jsonb), ss.timeout_seconds,
       ss.retry_policy, ss.retry_max_count, ss.enabled
FROM target_pipelines tp
CROSS JOIN source_steps ss
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

COMMIT;
