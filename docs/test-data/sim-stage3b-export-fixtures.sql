-- Stage 3b Export fixture: STATIC 4-shard keyset-range export.
-- Prerequisite: docs/test-data/sim-e2e-bootstrap.sql has been applied.

WITH src AS (
  SELECT *
  FROM batch.file_template_config
  WHERE tenant_id = 'ta'
    AND template_code = 'TA_EXPORT_REPORT_JSON_TPL'
    AND version = 1
    AND is_deleted = false
), tpl AS (
  SELECT
    'TA_EXPORT_REPORT_JSON_KEYSET_TPL' AS template_code,
    '客户 JSON keyset-range 分片导出模板' AS template_name,
    'TA_EXPORT_REPORT_JSON_KEYSET' AS biz_type,
    'ta_export_customer_keyset_${bizDate}_${batchNo}.json' AS naming_rule,
    'SELECT id, tenant_id, customer_no, customer_name, customer_type, certificate_no, mobile_no, email, status FROM biz.customer_account WHERE tenant_id = :tenantId AND customer_no LIKE ''EXP3B-%'' AND (:batchNo IS NOT NULL)' AS default_query_sql,
    jsonb_set(src.query_param_schema, '{partition_keyset_range}', 'true'::jsonb, true)
      AS query_param_schema,
    'Stage 3b export keyset-range 4-shard scenario' AS description
  FROM src
)
INSERT INTO batch.file_template_config (
    tenant_id, template_code, template_name, template_type, biz_type,
    file_format_type, charset, target_charset, with_bom, line_separator,
    delimiter, quote_char, escape_char, record_length, header_rows, footer_rows,
    header_template, trailer_template, checksum_type, compress_type, encrypt_type,
    naming_rule, field_mappings, validation_rule_set, default_query_code,
    default_query_sql, query_param_schema, streaming_enabled, page_size, fetch_size,
    chunk_size, enabled, version, description, created_by, updated_by,
    preprocess_pipeline, preview_masking_enabled, error_line_masking_enabled,
    log_masking_enabled, content_encryption_enabled, encryption_key_ref,
    download_requires_approval, masking_rule_set, export_data_ref, load_target_ref, is_deleted
)
SELECT
    src.tenant_id, tpl.template_code, tpl.template_name, src.template_type, tpl.biz_type,
    src.file_format_type, src.charset, src.target_charset, src.with_bom, src.line_separator,
    src.delimiter, src.quote_char, src.escape_char, src.record_length, src.header_rows, src.footer_rows,
    src.header_template, src.trailer_template, src.checksum_type, src.compress_type, src.encrypt_type,
    tpl.naming_rule, src.field_mappings, src.validation_rule_set, src.default_query_code,
    tpl.default_query_sql, tpl.query_param_schema, true, 20, 20,
    20, true, 1, tpl.description, 'sim-e2e', 'sim-e2e',
    src.preprocess_pipeline, src.preview_masking_enabled, src.error_line_masking_enabled,
    src.log_masking_enabled, src.content_encryption_enabled, src.encryption_key_ref,
    src.download_requires_approval, src.masking_rule_set, src.export_data_ref, src.load_target_ref, false
FROM src
JOIN tpl ON true
ON CONFLICT (tenant_id, template_code, version) DO UPDATE
SET template_name = EXCLUDED.template_name,
    biz_type = EXCLUDED.biz_type,
    naming_rule = EXCLUDED.naming_rule,
    default_query_sql = EXCLUDED.default_query_sql,
    query_param_schema = EXCLUDED.query_param_schema,
    streaming_enabled = EXCLUDED.streaming_enabled,
    page_size = EXCLUDED.page_size,
    fetch_size = EXCLUDED.fetch_size,
    chunk_size = EXCLUDED.chunk_size,
    enabled = true,
    description = EXCLUDED.description,
    updated_by = EXCLUDED.updated_by,
    updated_at = EXCLUDED.updated_at,
    is_deleted = false;

WITH src AS (
  SELECT *
  FROM batch.job_definition
  WHERE tenant_id = 'ta'
    AND job_code = 'TA_EXPORT_REPORT'
)
INSERT INTO batch.job_definition (
    tenant_id, job_code, job_name, job_type, biz_type, schedule_type, schedule_expr,
    timezone, priority, queue_code, worker_group, calendar_code, window_code,
    trigger_mode, dag_enabled, shard_strategy, retry_policy, retry_max_count,
    timeout_seconds, execution_handler, param_schema, default_params, version,
    enabled, description, created_by, updated_by, execution_mode,
    previous_day_dependency_scope, retry_policy_by_class
)
SELECT src.tenant_id, 'TA_EXPORT_REPORT_STATIC', '客户导出 STATIC 分片', src.job_type,
       'TA_EXPORT_REPORT_JSON_KEYSET', 'MANUAL', null, src.timezone, src.priority,
       src.queue_code, src.worker_group, src.calendar_code, src.window_code,
       'API', false, 'STATIC', 'NONE', 0, src.timeout_seconds, src.execution_handler,
       src.param_schema, jsonb_build_object('templateCode', 'TA_EXPORT_REPORT_JSON_KEYSET_TPL'),
       1, true, 'Stage 3b export keyset-range 4-shard scenario', 'sim-e2e', 'sim-e2e',
       'FULL', coalesce(src.previous_day_dependency_scope, 'INHERIT'), src.retry_policy_by_class
FROM src
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
    updated_at = EXCLUDED.updated_at,
    execution_mode = 'FULL';

WITH src AS (
  SELECT *
  FROM batch.pipeline_definition
  WHERE tenant_id = 'ta'
    AND job_code = 'TA_EXPORT_REPORT'
    AND version = 1
)
INSERT INTO batch.pipeline_definition (
    tenant_id, job_code, pipeline_name, pipeline_type, biz_type, worker_group,
    version, enabled, description
)
SELECT src.tenant_id, 'TA_EXPORT_REPORT_STATIC', '客户导出 STATIC 分片流水线',
       src.pipeline_type, 'TA_EXPORT_REPORT_JSON_KEYSET', src.worker_group,
       1, true, 'Stage 3b export keyset-range 4-shard scenario'
FROM src
ON CONFLICT (tenant_id, job_code, version) DO UPDATE
SET pipeline_name = EXCLUDED.pipeline_name,
    biz_type = EXCLUDED.biz_type,
    worker_group = EXCLUDED.worker_group,
    enabled = true,
    description = EXCLUDED.description,
    updated_at = EXCLUDED.updated_at;

WITH source_steps AS (
  SELECT psd.*
  FROM batch.pipeline_definition src_pd
  JOIN batch.pipeline_step_definition psd ON psd.pipeline_definition_id = src_pd.id
  WHERE src_pd.tenant_id = 'ta'
    AND src_pd.job_code = 'TA_EXPORT_REPORT'
    AND src_pd.version = 1
), target_pipeline AS (
  SELECT pd.id AS pipeline_definition_id
  FROM batch.pipeline_definition pd
  WHERE pd.tenant_id = 'ta'
    AND pd.job_code = 'TA_EXPORT_REPORT_STATIC'
    AND pd.version = 1
)
INSERT INTO batch.pipeline_step_definition (
    pipeline_definition_id, step_code, step_name, stage_code, step_order,
    impl_code, step_params, timeout_seconds, retry_policy, retry_max_count, enabled
)
SELECT tp.pipeline_definition_id, ss.step_code, ss.step_name, ss.stage_code, ss.step_order,
       ss.impl_code, coalesce(ss.step_params, '{}'::jsonb), ss.timeout_seconds,
       ss.retry_policy, ss.retry_max_count, ss.enabled
FROM target_pipeline tp
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
