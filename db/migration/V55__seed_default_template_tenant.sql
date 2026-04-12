-- 创建 default 模板租户及基础配置，供新建租户通过 initConfigFrom 复制。
-- 所有配置均为保守默认值，新建租户可按业务需求调整。
-- 不创建操作账号：default 租户仅用于配置模板，不承载任何业务。

INSERT INTO batch.tenant (tenant_id, tenant_name, status, description, created_by)
VALUES ('default', 'Default Template', 'ACTIVE', 'Template tenant for new tenant config initialization', 'system')
ON CONFLICT (tenant_id) DO NOTHING;

-- ── RESOURCE_QUEUE ────────────────────────────────────────────────────────────
-- 保守额度：max_running_jobs / max_qps 约为测试环境的 50%
INSERT INTO batch.resource_queue (
    tenant_id, queue_code, queue_name, queue_type,
    max_running_jobs, max_running_partitions, max_qps,
    worker_group, resource_tag, priority_policy, fair_share_weight,
    enabled, description,
    fair_share_group, burst_limit, quota_reset_policy, group_shared_max_running_jobs
) VALUES
    ('default', 'import-queue',   'Default Import Queue',   'IMPORT',
     2, 4, 20, 'import',   'ingest',   'FAIR_SHARE', 5,
     TRUE, 'Import queue template — adjust max_running_jobs / max_qps before use',
     'core',     1, 'SLIDING_WINDOW', 3),
    ('default', 'export-queue',   'Default Export Queue',   'EXPORT',
     1, 2, 10, 'export',   'report',   'PRIORITY',   4,
     TRUE, 'Export queue template — adjust max_running_jobs / max_qps before use',
     'core',     1, 'CALENDAR_DAY',   2),
    ('default', 'dispatch-queue', 'Default Dispatch Queue', 'DISPATCH',
     2, 4, 15, 'dispatch', 'delivery', 'FIFO',        3,
     TRUE, 'Dispatch queue template — adjust max_running_jobs / max_qps before use',
     'delivery', 1, 'NONE',           2)
ON CONFLICT (tenant_id, queue_code) DO NOTHING;

-- ── TENANT_QUOTA_POLICY ───────────────────────────────────────────────────────
-- 保守额度：单租户并发上限较低，防止新租户上线初期冲高负载
INSERT INTO batch.tenant_quota_policy (
    tenant_id, policy_code,
    max_running_jobs_per_tenant, max_partitions_per_tenant, max_qps_per_tenant,
    fair_share_weight, enabled, description,
    fair_share_group, burst_limit, partition_burst_limit,
    quota_reset_policy, group_shared_max_running_jobs
) VALUES
    ('default', 'default-policy',
     5, 10, 30,
     1, TRUE, 'Conservative default quota policy — raise limits after capacity planning',
     'core', 1, 2, 'SLIDING_WINDOW', 3)
ON CONFLICT (tenant_id, policy_code) DO NOTHING;

-- ── BATCH_WINDOW ──────────────────────────────────────────────────────────────
-- 全天开放，最宽松窗口；租户可按批量时间窗收窄
INSERT INTO batch.batch_window (
    tenant_id, window_code, window_name, timezone,
    start_time, end_time, end_strategy, out_of_window_action,
    allow_cross_day, enabled, description
) VALUES
    ('default', 'always-open', 'Always Open', 'Asia/Shanghai',
     TIME '00:00:00', TIME '23:59:59', 'FINISH_RUNNING', 'WAIT',
     TRUE, TRUE, 'Full-day window template')
ON CONFLICT (tenant_id, window_code) DO NOTHING;

-- ── BUSINESS_CALENDAR ─────────────────────────────────────────────────────────
-- 无节假日占位，catchup 自动触发；租户可添加节假日列表并调整 holiday_roll_rule
INSERT INTO batch.business_calendar (
    tenant_id, calendar_code, calendar_name, timezone,
    holiday_roll_rule, catch_up_policy, catch_up_max_days, enabled
) VALUES
    ('default', 'default-calendar', 'Default Calendar', 'Asia/Shanghai',
     'NEXT_WORKDAY', 'AUTO', 3, TRUE)
ON CONFLICT (tenant_id, calendar_code) DO NOTHING;

-- ── FILE_TEMPLATE ─────────────────────────────────────────────────────────────
-- 通用占位模板：field_mappings / validation_rule_set 为空对象，
-- 拷贝到业务租户后需填写实际映射和 default_query_sql 再启用。
INSERT INTO batch.file_template_config (
    tenant_id, template_code, template_name, template_type, biz_type,
    file_format_type, charset, target_charset,
    with_bom, line_separator, delimiter, quote_char, escape_char,
    record_length, header_rows, footer_rows,
    header_template, trailer_template,
    checksum_type, compress_type, encrypt_type,
    naming_rule, field_mappings, validation_rule_set,
    default_query_code, default_query_sql, query_param_schema,
    streaming_enabled, page_size, fetch_size, chunk_size,
    enabled, version, description, created_by, updated_by,
    preview_masking_enabled, error_line_masking_enabled,
    log_masking_enabled, content_encryption_enabled,
    encryption_key_ref, download_requires_approval, masking_rule_set,
    load_target_ref, export_data_ref
) VALUES
    ('default', 'tpl-import-csv', 'Generic CSV Import Template', 'IMPORT', 'GENERIC',
     'DELIMITED', 'UTF-8', 'UTF-8',
     FALSE, E'\n', ',', '"', '\\',
     0, 1, 0, NULL, NULL,
     'SHA-256', 'NONE', 'NONE',
     '$${bizType}-import-$${bizDate}-$${version}', '{}', '{}',
     NULL, NULL, '{}',
     TRUE, 1000, 1000, 500,
     TRUE, 1,
     'Generic CSV import template — fill field_mappings / validation_rule_set / load_target_ref before use',
     'system', 'system',
     FALSE, FALSE, FALSE, FALSE, NULL, FALSE, NULL,
     'jdbc_mapped', NULL),
    ('default', 'tpl-export-csv', 'Generic CSV Export Template', 'EXPORT', 'GENERIC',
     'DELIMITED', 'UTF-8', 'UTF-8',
     FALSE, E'\n', ',', '"', '\\',
     0, 1, 0, NULL, NULL,
     'SHA-256', 'NONE', 'NONE',
     '$${bizType}-export-$${bizDate}-$${version}', '{}', '{}',
     NULL, NULL, '{}',
     TRUE, 1000, 1000, 500,
     TRUE, 1,
     'Generic CSV export template — fill field_mappings / default_query_sql / export_data_ref before use',
     'system', 'system',
     FALSE, FALSE, FALSE, FALSE, NULL, FALSE, NULL,
     NULL, 'sql_template_export')
ON CONFLICT (tenant_id, template_code) DO NOTHING;
