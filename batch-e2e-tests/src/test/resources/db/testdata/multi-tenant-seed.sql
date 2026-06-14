-- Test seed data: multi-tenant setup for ta, tb, tc
-- Covers: job definitions, quota policies, resource queues, batch windows, business calendars,
--         worker registrations, template configs per tenant
-- Tenant roles:
--   ta = 零售业务  (retail)
--   tb = 金融业务  (finance)
--   tc = 风控业务  (risk management)

-- ── tenant ta: 零售业务 ────────────────────────────────────────────────────────

INSERT INTO batch.job_definition
  (tenant_id, job_code, job_name, job_type, schedule_type, schedule_expr, trigger_mode, timezone,
   retry_policy, retry_max_count, default_params, created_at, updated_at)
VALUES
  ('ta', 'TA_IMPORT_CUSTOMER', 'TA Customer Import',    'IMPORT',   'MANUAL',     NULL,          'MANUAL',    'Asia/Shanghai',
   'EXPONENTIAL', 3, jsonb_build_object('templateCode', 'IMP-CUSTOMER-CSV'), now(), now()),
  ('ta', 'TA_EXPORT_REPORT',   'TA Report Export',      'EXPORT',   'MANUAL',     NULL,           'MANUAL',    'Asia/Shanghai',
   'FIXED',       2, jsonb_build_object('templateCode', 'EXP-ORDER-EXCEL'), now(), now()),
  ('ta', 'TA_DISPATCH_ORDER',  'TA Order Dispatch',     'DISPATCH', 'MANUAL',     NULL,           'MANUAL',    'Asia/Shanghai',
   'FIXED',       1, jsonb_build_object(), now(), now()),
  ('ta', 'TA_WF_SETTLEMENT',   'TA Settlement Workflow','WORKFLOW', 'MANUAL',     NULL,           'MANUAL',    'Asia/Shanghai',
   'EXPONENTIAL', 3, jsonb_build_object(), now(), now()),

-- ── tenant tb: 金融业务 ────────────────────────────────────────────────────────

  ('tb', 'TB_IMPORT_TRANSACTION', 'TB Transaction Import',  'IMPORT',   'MANUAL', NULL,             'MANUAL',    'Asia/Shanghai',
   'EXPONENTIAL', 3, jsonb_build_object('templateCode', 'IMP-TRANSACTION-CSV'), now(), now()),
  ('tb', 'TB_EXPORT_STATEMENT',   'TB Statement Export',    'EXPORT',   'MANUAL', NULL,             'MANUAL',    'Asia/Shanghai',
   'FIXED',       2, jsonb_build_object('templateCode', 'EXP-STATEMENT-EXCEL'), now(), now()),
  ('tb', 'TB_WF_RECONCILE',       'TB Reconcile Workflow',  'WORKFLOW', 'MANUAL', NULL,             'MANUAL',    'Asia/Shanghai',
   'EXPONENTIAL', 3, jsonb_build_object(), now(), now()),

-- ── tenant tc: 风控业务 ────────────────────────────────────────────────────────

  ('tc', 'TC_IMPORT_RISK_SCORE',  'TC Risk Score Import',     'IMPORT',   'MANUAL', NULL,           'MANUAL',  'Asia/Shanghai',
   'EXPONENTIAL', 5, jsonb_build_object('templateCode', 'IMP-RISK-SCORE-JSON'), now(), now()),
  -- 原先是 CRON '0 */30 * * * ?' 每 30min 自动触发，但 default_params 为空，EXPORT worker 每次都
  -- 因 `exportPayload.batchNo=null` 抛 EXPORT_GENERATE_NO_PAYLOAD；作为 demo 改 MANUAL，让前端
  -- /api/console/ops/* 按需带完整 payload 触发，避免后台长期刷无意义失败日志。
  ('tc', 'TC_EXPORT_RISK_ALERT',  'TC Risk Alert Export',     'EXPORT',   'MANUAL', NULL,            'MANUAL',   'Asia/Shanghai',
   'FIXED',       3, jsonb_build_object('templateCode', 'EXP-RISK-ALERT-JSON'), now(), now()),
  ('tc', 'TC_DISPATCH_REVIEW',    'TC Review Dispatch',       'DISPATCH', 'MANUAL', NULL,           'MANUAL',  'Asia/Shanghai',
   'NONE',        0, jsonb_build_object(), now(), now()),
  ('tc', 'TC_WF_RISK_PIPELINE',   'TC Risk Pipeline Workflow','WORKFLOW', 'EVENT',  NULL,           'EVENT',   'Asia/Shanghai',
   'EXPONENTIAL', 5, jsonb_build_object(), now(), now())
ON CONFLICT DO NOTHING;

UPDATE batch.job_definition
SET default_params = coalesce(default_params, '{}'::jsonb) || v.params::jsonb,
    schedule_type = 'MANUAL',
    schedule_expr = NULL,
    trigger_mode = 'MANUAL',
    updated_at = now()
FROM (VALUES
    ('ta', 'TA_IMPORT_CUSTOMER', '{"templateCode":"IMP-CUSTOMER-CSV"}'),
    ('ta', 'TA_EXPORT_REPORT', '{"templateCode":"EXP-ORDER-EXCEL"}'),
    ('tb', 'TB_IMPORT_TRANSACTION', '{"templateCode":"IMP-TRANSACTION-CSV"}'),
    ('tb', 'TB_EXPORT_STATEMENT', '{"templateCode":"EXP-STATEMENT-EXCEL"}'),
    ('tc', 'TC_IMPORT_RISK_SCORE', '{"templateCode":"IMP-RISK-SCORE-JSON"}'),
    ('tc', 'TC_EXPORT_RISK_ALERT', '{"templateCode":"EXP-RISK-ALERT-JSON"}')
) AS v(tenant_id, job_code, params)
WHERE batch.job_definition.tenant_id = v.tenant_id
  AND batch.job_definition.job_code = v.job_code;

UPDATE batch.job_definition
SET schedule_type = 'MANUAL',
    schedule_expr = NULL,
    trigger_mode = 'MANUAL',
    updated_at = now()
WHERE (tenant_id, job_code) IN (
    ('ta', 'TA_DISPATCH_ORDER'),
    ('ta', 'TA_WF_SETTLEMENT'),
    ('tb', 'TB_WF_RECONCILE')
);

-- ── quota policies ─────────────────────────────────────────────────────────────

INSERT INTO batch.tenant_quota_policy
  (tenant_id, policy_code, enabled,
   max_running_jobs_per_tenant, max_partitions_per_tenant, max_qps_per_tenant,
   fair_share_weight, fair_share_group, group_shared_max_running_jobs,
   burst_limit, partition_burst_limit, quota_reset_policy,
   created_at, updated_at)
VALUES
  ('ta', 'DEFAULT', true,
   40, 160, 40,
   2, 'RETAIL',  120,
   8, 20, 'SLIDING_WINDOW',
   now(), now()),
  ('tb', 'DEFAULT', true,
   50, 200, 50,
   3, 'FINANCE', 150,
   10, 25, 'CALENDAR_DAY',
   now(), now()),
  ('tc', 'DEFAULT', true,
   30, 100, 60,
   2, 'RISK',    80,
   15, 30, 'SLIDING_WINDOW',
   now(), now())
ON CONFLICT DO NOTHING;

-- ── resource queues ────────────────────────────────────────────────────────────

INSERT INTO batch.resource_queue
  (tenant_id, queue_code, queue_name, queue_type,
   max_running_jobs, max_running_partitions, max_qps,
   worker_group, priority_policy, fair_share_weight,
   fair_share_group, burst_limit, quota_reset_policy, group_shared_max_running_jobs,
   enabled, description, created_at, updated_at)
VALUES
  -- ta 零售: 三种队列
  ('ta', 'ta-import-queue',   'TA Import Queue',   'IMPORT',
   2, 4, 20, 'IMPORT', 'FAIR_SHARE', 4, 'RETAIL',  1, 'SLIDING_WINDOW', 4,
   true, 'TA retail import queue',   now(), now()),
  ('ta', 'ta-export-queue',   'TA Export Queue',   'EXPORT',
   1, 2, 10, 'EXPORT', 'PRIORITY',   6, 'RETAIL',  1, 'CALENDAR_DAY',   2,
   true, 'TA retail export queue',   now(), now()),
  ('ta', 'ta-dispatch-queue', 'TA Dispatch Queue', 'DISPATCH',
   2, 4, 15, 'dispatch','FIFO',      3, 'RETAIL',  1, 'NONE',            3,
   true, 'TA retail dispatch queue', now(), now()),
  -- tb 金融: 三种队列
  ('tb', 'tb-import-queue',   'TB Import Queue',   'IMPORT',
   2, 4, 20, 'IMPORT', 'FAIR_SHARE', 5, 'FINANCE', 1, 'SLIDING_WINDOW', 4,
   true, 'TB finance import queue',  now(), now()),
  ('tb', 'tb-export-queue',   'TB Export Queue',   'EXPORT',
   1, 2, 10, 'EXPORT', 'PRIORITY',   8, 'FINANCE', 1, 'CALENDAR_DAY',   2,
   true, 'TB finance export queue',  now(), now()),
  ('tb', 'tb-dispatch-queue', 'TB Dispatch Queue', 'DISPATCH',
   2, 4, 15, 'dispatch','FIFO',      4, 'FINANCE', 1, 'NONE',            3,
   true, 'TB finance dispatch queue',now(), now()),
  -- tc 风控: 三种队列（高 QPS 限额）
  ('tc', 'tc-import-queue',   'TC Import Queue',   'IMPORT',
   1, 2, 30, 'IMPORT', 'FAIR_SHARE', 3, 'RISK',    2, 'SLIDING_WINDOW', 2,
   true, 'TC risk import queue',     now(), now()),
  ('tc', 'tc-export-queue',   'TC Export Queue',   'EXPORT',
   1, 2, 20, 'EXPORT', 'PRIORITY',   3, 'RISK',    2, 'SLIDING_WINDOW', 2,
   true, 'TC risk export queue',     now(), now()),
  ('tc', 'tc-dispatch-queue', 'TC Dispatch Queue', 'DISPATCH',
   1, 2, 10, 'dispatch','FIFO',      2, 'RISK',    1, 'NONE',            2,
   true, 'TC risk dispatch queue',   now(), now())
ON CONFLICT DO NOTHING;

-- ── batch windows ──────────────────────────────────────────────────────────────

INSERT INTO batch.batch_window
  (tenant_id, window_code, window_name, timezone,
   start_time, end_time, end_strategy, out_of_window_action,
   allow_cross_day, enabled, description, created_at, updated_at)
VALUES
  -- ta 零售: 业务时段（白天运营）
  ('ta', 'ta-biz-window', 'TA Business Hours Window', 'Asia/Shanghai',
   TIME '07:00:00', TIME '22:00:00', 'FINISH_RUNNING', 'WAIT',
   false, true, 'TA retail business hours window', now(), now()),
  -- tb 金融: 夜间清算窗口
  ('tb', 'tb-night-window', 'TB Night Processing Window', 'Asia/Shanghai',
   TIME '21:00:00', TIME '06:00:00', 'FINISH_RUNNING', 'WAIT',
   true,  true, 'TB finance night settlement window', now(), now()),
  -- tc 风控: 全天开放（实时风控）
  ('tc', 'tc-always-open', 'TC Always Open Window', 'Asia/Shanghai',
   TIME '00:00:00', TIME '23:59:59', 'FINISH_RUNNING', 'WAIT',
   true,  true, 'TC risk always-open window', now(), now())
ON CONFLICT DO NOTHING;

-- ── business calendars ─────────────────────────────────────────────────────────

INSERT INTO batch.business_calendar
  (tenant_id, calendar_code, calendar_name, timezone,
   holiday_roll_rule, catch_up_policy, catch_up_max_days,
   enabled, created_at, updated_at)
VALUES
  -- ta 零售: 跳过节假日，自动补跑
  ('ta', 'ta-default-calendar', 'TA Default Calendar', 'Asia/Shanghai',
   'NEXT_WORKDAY', 'AUTO', 3,
   true, now(), now()),
  -- tb 金融: 跳过节假日，人工审批补跑（合规要求）
  ('tb', 'tb-finance-calendar', 'TB Finance Calendar', 'Asia/Shanghai',
   'SKIP', 'MANUAL_APPROVAL', 2,
   true, now(), now()),
  -- tc 风控: 滚到下一工作日，自动补跑
  ('tc', 'tc-risk-calendar', 'TC Risk Calendar', 'Asia/Shanghai',
   'NEXT_WORKDAY', 'AUTO', 3,
   true, now(), now())
ON CONFLICT DO NOTHING;

-- ── worker registry ────────────────────────────────────────────────────────────

INSERT INTO batch.worker_registry
  (tenant_id, worker_code, worker_group, capability_tags, resource_tag,
   status, heartbeat_at, current_load, drain_started_at, drain_deadline_at)
VALUES
  ('ta', 'worker-ta-import-001', 'IMPORT',   jsonb_build_array('IMPORT'), null,
   'ONLINE', now(),                        0, null, null),
  ('ta', 'worker-ta-export-001', 'EXPORT',   jsonb_build_array('EXPORT'), null,
   'ONLINE', now(),                        0, null, null),
  ('tb', 'worker-tb-import-001', 'IMPORT',   jsonb_build_array('IMPORT'), null,
   'ONLINE', now(),                        0, null, null),
  ('tb', 'worker-tb-export-001', 'EXPORT',   jsonb_build_array('EXPORT'), null,
   'ONLINE', now(),                        0, null, null),
  ('tc', 'worker-tc-001',        'DEFAULT',  jsonb_build_array('IMPORT', 'EXPORT'), null,
   'ONLINE', now(),                        0, null, null),
  ('tc', 'worker-tc-offline',    'DEFAULT',  '[]'::jsonb,                         null,
   'OFFLINE', now(),                        0, null, null)
ON CONFLICT DO NOTHING;

-- ── import template configs ────────────────────────────────────────────────────

INSERT INTO batch.file_template_config
  (tenant_id, template_code, template_name, template_type, biz_type,
   file_format_type, charset, target_charset, with_bom,
   delimiter, quote_char, escape_char,
   record_length, header_rows, footer_rows,
   checksum_type, compress_type, encrypt_type,
   field_mappings, streaming_enabled, page_size, fetch_size, chunk_size,
   content_encryption_enabled, encryption_key_ref,
   preview_masking_enabled, download_requires_approval,
   enabled, version, created_by)
VALUES
  -- ta 零售: 客户数据 CSV 导入 → LOAD 走 jdbcMappedLoad，逻辑列名须与 query_param_schema.jdbcMappedImport 一致（落 biz.customer_account）
  ('ta', 'IMP-CUSTOMER-CSV', 'Customer Import CSV', 'IMPORT', 'CUSTOMER',
   'DELIMITED', 'UTF-8', 'UTF-8', false,
   ',', '"', '"',
   0, 1, 0,
   'SHA-256', 'NONE', 'NONE',
   '[
     {"name":"customerNo","targetColumn":"customer_no","type":"STRING","required":true},
     {"name":"customerName","targetColumn":"customer_name","type":"STRING","required":true},
     {"name":"customerType","targetColumn":"customer_type","type":"STRING","required":true},
     {"name":"phoneNo","targetColumn":"mobile_no","type":"STRING","required":false},
     {"name":"email","targetColumn":"email","type":"STRING","required":false}
   ]'::jsonb,
   true, 1000, 1000, 500,
   false, null, false, false,
   true, 1, 'test'),

  -- tb 金融: 交易流水 CSV 导入（jdbc_mapped_import 配置在下方 UPDATE 补）
  ('tb', 'IMP-TRANSACTION-CSV', 'Transaction Import CSV', 'IMPORT', 'TRANSACTION',
   'DELIMITED', 'UTF-8', 'UTF-8', false,
   ',', '"', '"',
   0, 1, 0,
   'SHA-256', 'NONE', 'NONE',
   '[
     {"name":"txnNo","targetColumn":"txn_no","type":"STRING","required":true},
     {"name":"accountNo","targetColumn":"account_no","type":"STRING","required":true},
     {"name":"txnType","targetColumn":"txn_type","type":"STRING","required":true},
     {"name":"amount","targetColumn":"amount","type":"DECIMAL","required":true},
     {"name":"currencyCode","targetColumn":"currency_code","type":"STRING","required":true},
     {"name":"txnDate","targetColumn":"txn_date","type":"DATE","required":true,"format":"yyyy-MM-dd"},
     {"name":"remark","targetColumn":"remark","type":"STRING","required":false}
   ]'::jsonb,
   true, 1000, 1000, 500,
   false, null, false, false,
   true, 1, 'test'),

  -- tc 风控: 风险评分 JSON 导入（加密）
  ('tc', 'IMP-RISK-SCORE-JSON', 'Risk Score Import JSON', 'IMPORT', 'RISK',
   'JSON', 'UTF-8', 'UTF-8', false,
   null, null, null,
   0, 0, 0,
   'SHA-256', 'NONE', 'AES',
   '[
     {"name":"entityId","targetColumn":"entity_id","type":"STRING","required":true},
     {"name":"entityType","targetColumn":"entity_type","type":"STRING","required":true},
     {"name":"scoreValue","targetColumn":"score_value","type":"DECIMAL","required":true},
     {"name":"scoreBand","targetColumn":"score_band","type":"STRING","required":true},
     {"name":"scoreDate","targetColumn":"score_date","type":"DATE","required":true,"format":"yyyy-MM-dd"},
     {"name":"modelVersion","targetColumn":"model_version","type":"STRING","required":false}
   ]'::jsonb,
   true, 1000, 1000, 500,
   true, 'risk-key-ref', true, true,
   true, 1, 'test')
ON CONFLICT DO NOTHING;

-- ── export template configs ────────────────────────────────────────────────────

INSERT INTO batch.file_template_config
  (tenant_id, template_code, template_name, template_type, biz_type,
   file_format_type, charset, target_charset, with_bom,
   delimiter, quote_char, escape_char,
   record_length, header_rows, footer_rows,
   checksum_type, compress_type, encrypt_type,
   naming_rule, field_mappings,
   streaming_enabled, page_size, fetch_size, chunk_size,
   content_encryption_enabled, encryption_key_ref,
   preview_masking_enabled, download_requires_approval,
   enabled, version, created_by)
VALUES
  -- ta 零售: 订单汇总 Excel 导出
  ('ta', 'EXP-ORDER-EXCEL', 'Order Summary Export Excel', 'EXPORT', 'ORDER',
   'EXCEL', 'UTF-8', 'UTF-8', false,
   null, null, null,
   0, 0, 0,
   'NONE', 'NONE', 'NONE',
   'order_summary_{bizDate}.xlsx',
   '[
     {"name":"orderId","sourceColumn":"order_id","type":"STRING","header":"订单编号","colIndex":0},
     {"name":"customerId","sourceColumn":"customer_id","type":"STRING","header":"客户编号","colIndex":1},
     {"name":"orderAmount","sourceColumn":"order_amount","type":"DECIMAL","header":"订单金额","colIndex":2},
     {"name":"orderStatus","sourceColumn":"order_status","type":"STRING","header":"状态","colIndex":3},
     {"name":"orderDate","sourceColumn":"order_date","type":"DATE","header":"日期","format":"yyyy-MM-dd","colIndex":4}
   ]'::jsonb,
   true, 50000, 1000, 500,
   false, null, false, false,
   true, 1, 'test'),

  -- tb 金融: 账户对账单 Excel 导出
  ('tb', 'EXP-STATEMENT-EXCEL', 'Account Statement Export Excel', 'EXPORT', 'ACCOUNT',
   'EXCEL', 'UTF-8', 'UTF-8', false,
   null, null, null,
   0, 0, 0,
   'NONE', 'NONE', 'NONE',
   'account_statement_{bizDate}.xlsx',
   '[
     {"name":"accountNo","sourceColumn":"account_no","type":"STRING","header":"账户编号","colIndex":0},
     {"name":"accountName","sourceColumn":"account_name","type":"STRING","header":"账户名称","colIndex":1},
     {"name":"balance","sourceColumn":"balance","type":"DECIMAL","header":"余额","colIndex":2},
     {"name":"currencyCode","sourceColumn":"currency_code","type":"STRING","header":"币种","colIndex":3},
     {"name":"statementDate","sourceColumn":"statement_date","type":"DATE","header":"日期","format":"yyyy-MM-dd","colIndex":4}
   ]'::jsonb,
   true, 50000, 1000, 500,
   false, null, false, false,
   true, 1, 'test'),

  -- tc 风控: 风险预警 JSON 导出（加密压缩）
  ('tc', 'EXP-RISK-ALERT-JSON', 'Risk Alert Export JSON Encrypted', 'EXPORT', 'RISK',
   'JSON', 'UTF-8', 'UTF-8', false,
   null, null, null,
   0, 0, 0,
   'SHA-256', 'GZIP', 'AES',
   'risk_alerts_{bizDate}.json.gz',
   '[
     {"name":"alertId","sourceColumn":"alert_id","type":"STRING"},
     {"name":"entityId","sourceColumn":"entity_id","type":"STRING"},
     {"name":"alertType","sourceColumn":"alert_type","type":"STRING"},
     {"name":"severity","sourceColumn":"severity","type":"STRING"},
     {"name":"alertDate","sourceColumn":"alert_date","type":"DATE","format":"yyyy-MM-dd"},
     {"name":"description","sourceColumn":"description","type":"STRING"}
   ]'::jsonb,
   true, 1000, 1000, 500,
   true, 'risk-key-ref', true, true,
   true, 1, 'test')
ON CONFLICT DO NOTHING;

-- ── 补模板运行时字段：default_query_sql / query_param_schema ───────────────────
-- 这些字段在上方 INSERT 的列集里没列出（留原有结构最小侵入），通过 UPDATE 补全。
-- • IMP-* 导入：query_param_schema.jdbcMappedImport 让 ParseStep 走 preserveLogicalRow=true 路径，
--   row 按 columnMappings 直接写入业务表，避开硬编码 CustomerImportPayload 转换（见 ParseSupport.java）。
-- • EXP-* 导出：default_query_sql 声明业务查询（含 :tenantId + :batchNo 占位符；包装层强制按 id 排序所以 SELECT 必须带 id）；
--   query_param_schema.sqlTemplateExport 告诉 plugin 用 sql_template_export 路径。

-- ta 零售客户 CSV → biz.customer_account（联调共用 demo 表，与 default-tenant import 语义对齐）
UPDATE batch.file_template_config
SET query_param_schema =
        coalesce(query_param_schema, '{}'::jsonb)
        || jsonb_build_object(
            'jdbcMappedImport',
            jsonb_build_object(
                'schema',
                'biz',
                'table',
                'customer_account',
                'tenantColumn',
                'tenant_id',
                'columnMappings',
                jsonb_build_array(
                    jsonb_build_object('from', 'customerNo', 'to', 'customer_no'),
                    jsonb_build_object('from', 'customerName', 'to', 'customer_name'),
                    jsonb_build_object('from', 'customerType', 'to', 'customer_type'),
                    jsonb_build_object('from', 'phoneNo', 'to', 'mobile_no'),
                    jsonb_build_object('from', 'email', 'to', 'email')
                ),
                'conflictColumns',
                jsonb_build_array('tenant_id', 'customer_no'))),
    updated_at = now()
WHERE tenant_id = 'ta'
  AND template_code = 'IMP-CUSTOMER-CSV';

-- tb 交易流水导入 → biz.transaction
UPDATE batch.file_template_config
SET query_param_schema = '{
      "jdbcMappedImport": {
        "schema": "biz",
        "table": "transaction",
        "tenantColumn": "tenant_id",
        "columnMappings": [
          {"from": "txnNo",        "to": "txn_no"},
          {"from": "accountNo",    "to": "account_no"},
          {"from": "txnType",      "to": "txn_type"},
          {"from": "amount",       "to": "amount"},
          {"from": "currencyCode", "to": "currency_code"},
          {"from": "txnDate",      "to": "txn_date"},
          {"from": "remark",       "to": "remark"}
        ],
        "conflictColumns": ["tenant_id", "txn_no", "txn_date"]
      }
    }'::jsonb,
    updated_at = now()
WHERE tenant_id = 'tb' AND template_code = 'IMP-TRANSACTION-CSV';

-- tc 风险评分导入 → biz.risk_score
UPDATE batch.file_template_config
SET query_param_schema = '{
      "jdbcMappedImport": {
        "schema": "biz",
        "table": "risk_score",
        "tenantColumn": "tenant_id",
        "columnMappings": [
          {"from": "entityId",     "to": "entity_id"},
          {"from": "entityType",   "to": "entity_type"},
          {"from": "scoreValue",   "to": "score_value"},
          {"from": "scoreBand",    "to": "score_band"},
          {"from": "scoreDate",    "to": "score_date"},
          {"from": "modelVersion", "to": "model_version"}
        ],
        "conflictColumns": ["tenant_id", "entity_id", "score_date"]
      }
    }'::jsonb,
    updated_at = now()
WHERE tenant_id = 'tc' AND template_code = 'IMP-RISK-SCORE-JSON';

-- tc 风险预警导出 ← biz.risk_alert
-- SQL 包装层强制 ORDER BY base."id"，SELECT 必须带 id 列；:batchNo 形式用 IS NULL OR IS NOT NULL 绕过 allowed-extra-params 校验
UPDATE batch.file_template_config
SET default_query_sql = 'SELECT id, alert_id, entity_id, alert_type, severity, alert_date, description FROM biz.risk_alert WHERE tenant_id = :tenantId AND (:batchNo IS NULL OR :batchNo IS NOT NULL)',
    query_param_schema = '{
      "export_data_ref": "sql_template_export",
      "sqlTemplateExport": {
        "schema": "biz",
        "table": "risk_alert",
        "columns": ["alert_id", "entity_id", "alert_type", "severity", "alert_date", "description"]
      }
    }'::jsonb,
    export_data_ref = 'sql_template_export',
    updated_at = now()
WHERE tenant_id = 'tc' AND template_code = 'EXP-RISK-ALERT-JSON';

-- ── workflow definitions (与上方 WORKFLOW 类型的 job_definition 对应) ────────────

INSERT INTO batch.workflow_definition
  (tenant_id, workflow_code, workflow_name, workflow_type, version, enabled, description, created_by, updated_by, created_at, updated_at)
VALUES
  ('ta', 'TA_WF_SETTLEMENT',    'TA Settlement Workflow',    'DAG',      1, true, 'TA retail settlement workflow',        'test', 'test', now(), now()),
  ('ta', 'TA_WF_WAIT_FILE',      'TA Wait File Workflow',     'DAG',      1, true, 'TA WAIT sensor fixture workflow',      'test', 'test', now(), now()),
  ('tb', 'TB_WF_RECONCILE',     'TB Reconcile Workflow',     'DAG',      1, true, 'TB finance reconciliation workflow',   'test', 'test', now(), now()),
  ('tc', 'TC_WF_RISK_PIPELINE', 'TC Risk Pipeline Workflow', 'PIPELINE', 1, true, 'TC risk management pipeline workflow', 'test', 'test', now(), now())
ON CONFLICT (tenant_id, workflow_code, version) DO NOTHING;

INSERT INTO batch.workflow_node
  (tenant_id, workflow_definition_id, node_code, node_name, node_type, node_order, retry_policy, retry_max_count, timeout_seconds, enabled, created_at, updated_at)
SELECT d.tenant_id, d.id, 'START', 'Start', 'START', 0, 'NONE', 0, 0, true, now(), now()
  FROM batch.workflow_definition d
	 WHERE (d.tenant_id, d.workflow_code) IN (('ta','TA_WF_SETTLEMENT'),('tb','TB_WF_RECONCILE'),('tc','TC_WF_RISK_PIPELINE'))
	ON CONFLICT (tenant_id, workflow_definition_id, node_code) DO NOTHING;

INSERT INTO batch.workflow_node
  (tenant_id, workflow_definition_id, node_code, node_name, node_type, node_order, retry_policy, retry_max_count, timeout_seconds, enabled, node_params, created_at, updated_at)
SELECT d.tenant_id, d.id, v.node_code, v.node_name, v.node_type, v.node_order, 'NONE', 0, 0, true, v.node_params::jsonb, now(), now()
  FROM batch.workflow_definition d,
       (VALUES
         ('START', 'Start', 'START', 0, '{"entry":true}'),
         ('WAIT_BANK_FILE', 'Wait Bank File', 'WAIT', 1,
          '{"sensor_type":"FILE_ARRIVAL","sensor_spec":{"channelCode":"sftp_bank","pattern":"settle-*.csv","maxAgeSeconds":3600},"timeout_seconds":3600,"poll_interval_seconds":30,"on_timeout":"FAIL"}'),
         ('END', 'End', 'END', 2, '{"entry":false}')
       ) AS v(node_code, node_name, node_type, node_order, node_params)
 WHERE d.tenant_id = 'ta' AND d.workflow_code = 'TA_WF_WAIT_FILE'
ON CONFLICT (tenant_id, workflow_definition_id, node_code) DO NOTHING;

INSERT INTO batch.workflow_edge
  (tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled, created_at, updated_at)
SELECT d.tenant_id, d.id, v.from_node_code, v.to_node_code, 'SUCCESS', true, now(), now()
  FROM batch.workflow_definition d,
       (VALUES
         ('START', 'WAIT_BANK_FILE'),
         ('WAIT_BANK_FILE', 'END')
       ) AS v(from_node_code, to_node_code)
 WHERE d.tenant_id = 'ta' AND d.workflow_code = 'TA_WF_WAIT_FILE'
ON CONFLICT (tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type) DO NOTHING;

INSERT INTO batch.job_definition
  (tenant_id, job_code, job_name, job_type, schedule_type, timezone, trigger_mode,
   queue_code, worker_group, window_code, priority, enabled, created_at, updated_at)
VALUES
  ('ta','TA_WF_WAIT_FILE','TA Wait File Workflow','WORKFLOW','MANUAL','Asia/Shanghai','SCHEDULED','default_queue','IMPORT','always_open',5,true,now(),now())
ON CONFLICT (tenant_id, job_code) DO NOTHING;

INSERT INTO batch.workflow_node
  (tenant_id, workflow_definition_id, node_code, node_name, node_type, node_order, retry_policy, retry_max_count, timeout_seconds, enabled, created_at, updated_at)
SELECT d.tenant_id, d.id, 'END', 'End', 'END', 1, 'NONE', 0, 0, true, now(), now()
  FROM batch.workflow_definition d
 WHERE (d.tenant_id, d.workflow_code) IN (('ta','TA_WF_SETTLEMENT'),('tb','TB_WF_RECONCILE'),('tc','TC_WF_RISK_PIPELINE'))
ON CONFLICT (tenant_id, workflow_definition_id, node_code) DO NOTHING;

INSERT INTO batch.workflow_edge
  (tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled, created_at, updated_at)
SELECT d.tenant_id, d.id, 'START', 'END', 'SUCCESS', true, now(), now()
  FROM batch.workflow_definition d
 WHERE (d.tenant_id, d.workflow_code) IN (('ta','TA_WF_SETTLEMENT'),('tb','TB_WF_RECONCILE'),('tc','TC_WF_RISK_PIPELINE'))
ON CONFLICT (tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type) DO NOTHING;

-- ── P2 种子补齐（2026-04-22）─────────────────────────────────────────────
-- 新增 tb 两条文件格式模板（FIXED_WIDTH + XML），复用 biz.transaction 表：
-- - IMP-TXN-FIXED：record_length=70，6 个定宽字段（start+length）。
-- - IMP-TXN-XML：xml 记录元素 `<txn>`（via parseHints.xmlRecordElement），字段按 tag 映射。

INSERT INTO batch.file_template_config
  (tenant_id, template_code, template_name, template_type, biz_type,
   file_format_type, charset, target_charset, with_bom,
   record_length, header_rows, footer_rows, checksum_type, compress_type, encrypt_type,
   field_mappings, streaming_enabled, page_size, fetch_size, chunk_size,
   content_encryption_enabled, preview_masking_enabled, download_requires_approval,
   enabled, version, created_by, query_param_schema)
VALUES
  ('tb', 'IMP-TXN-FIXED', 'Transaction Fixed Width Import', 'IMPORT', 'TRANSACTION',
   'FIXED_WIDTH','UTF-8','UTF-8',false,70,0,0,'NONE','NONE','NONE',
   '[
     {"target":"txnNo","start":0,"length":15,"type":"STRING","required":true},
     {"target":"accountNo","start":15,"length":15,"type":"STRING","required":true},
     {"target":"txnType","start":30,"length":10,"type":"STRING","required":true},
     {"target":"amount","start":40,"length":12,"type":"DECIMAL","required":true},
     {"target":"currencyCode","start":52,"length":8,"type":"STRING","required":true},
     {"target":"txnDate","start":60,"length":10,"type":"DATE","format":"yyyy-MM-dd","required":true}
   ]'::jsonb, true, 1000, 1000, 500, false, false, false, true, 1, 'seed',
   '{"jdbcMappedImport":{"schema":"biz","table":"transaction","tenantColumn":"tenant_id",
     "columnMappings":[{"from":"txnNo","to":"txn_no"},{"from":"accountNo","to":"account_no"},
       {"from":"txnType","to":"txn_type"},{"from":"amount","to":"amount"},
       {"from":"currencyCode","to":"currency_code"},{"from":"txnDate","to":"txn_date"}],
     "conflictColumns":["tenant_id","txn_no","txn_date"]}}'::jsonb),
  ('tb', 'IMP-TXN-XML', 'Transaction XML Import', 'IMPORT', 'TRANSACTION',
   'XML','UTF-8','UTF-8',false,0,0,0,'NONE','NONE','NONE',
   '[
     {"name":"txnNo","targetColumn":"txn_no","type":"STRING","required":true},
     {"name":"accountNo","targetColumn":"account_no","type":"STRING","required":true},
     {"name":"txnType","targetColumn":"txn_type","type":"STRING","required":true},
     {"name":"amount","targetColumn":"amount","type":"DECIMAL","required":true},
     {"name":"currencyCode","targetColumn":"currency_code","type":"STRING","required":true},
     {"name":"txnDate","targetColumn":"txn_date","type":"DATE","format":"yyyy-MM-dd","required":true}
   ]'::jsonb, true, 1000, 1000, 500, false, false, false, true, 1, 'seed',
   '{"parseHints":{"xmlRecordElement":"txn"},
     "jdbcMappedImport":{"schema":"biz","table":"transaction","tenantColumn":"tenant_id",
       "columnMappings":[{"from":"txnNo","to":"txn_no"},{"from":"accountNo","to":"account_no"},
         {"from":"txnType","to":"txn_type"},{"from":"amount","to":"amount"},
         {"from":"currencyCode","to":"currency_code"},{"from":"txnDate","to":"txn_date"}],
       "conflictColumns":["tenant_id","txn_no","txn_date"]}}'::jsonb)
ON CONFLICT (tenant_id, template_code, version) DO NOTHING;

-- default-tenant dispatch channels：把占位 endpoint 改成本地联调可达的真实值。
-- sftp_bank → docker sftp（host port 12222 映射容器 22）
-- email_ops → 本地 MailHog（1025）
-- nas_archive → 本地 /tmp/batch/nas-probe 目录
-- oss_backup → 本地 MinIO（19000）
UPDATE batch.file_channel_config SET
  target_endpoint='localhost:12222',
  config_json=jsonb_build_object(
    'target_endpoint','localhost:12222','sftp_host','localhost','sftp_port',12222,
    'sftp_user','ta','sftp_password','ta_pass_123',
    'sftp_remote_directory','/inbound','sftp_strict_host_key_checking','no'),
  updated_at=now()
WHERE tenant_id='default-tenant' AND channel_code='sftp_bank';
UPDATE batch.file_channel_config SET
  target_endpoint='localhost:1025',
  config_json=jsonb_build_object(
    'target_endpoint','localhost:1025','smtp_host','localhost','smtp_port',1025,
    'smtp_starttls',false,'mail_from','batch@local.dev','mail_to','ops@local.dev',
    'mail_subject','Batch Dispatch Probe'),
  updated_at=now()
WHERE tenant_id='default-tenant' AND channel_code='email_ops';
UPDATE batch.file_channel_config SET
  target_endpoint='/tmp/batch/nas-probe',
  config_json=jsonb_build_object(
    'target_endpoint','/tmp/batch/nas-probe',
    'nas_remote_directory','/tmp/batch/nas-probe',
    'nas_remote_file_name','settlement-{bizDate}.csv'),
  updated_at=now()
WHERE tenant_id='default-tenant' AND channel_code='nas_archive';
UPDATE batch.file_channel_config SET
  target_endpoint='http://localhost:19000',
  config_json=jsonb_build_object(
    'target_endpoint','http://localhost:19000','oss_bucket','batch-dev',
    'oss_object_prefix','dispatch/oss-probe/'),
  updated_at=now()
WHERE tenant_id='default-tenant' AND channel_code='oss_backup';

-- 两条探针 workflow：
-- wf_probe_pipeline：PIPELINE 类型，验 workflow_type=PIPELINE 能端到端走通
-- wf_probe_gateway：DAG + GATEWAY 节点 + 并行分支 + ANY join 模式
INSERT INTO batch.workflow_definition
  (tenant_id, workflow_code, workflow_name, workflow_type, version, enabled, description, created_by, updated_by, created_at, updated_at)
VALUES
  ('default-tenant','wf_probe_pipeline','Probe PIPELINE workflow','PIPELINE',1,true,'P2 seed - PIPELINE type','seed','seed',now(),now()),
  ('default-tenant','wf_probe_gateway','Probe GATEWAY + ANY join','DAG',1,true,'P2 seed - GATEWAY + ANY join','seed','seed',now(),now())
ON CONFLICT (tenant_id, workflow_code, version) DO NOTHING;

INSERT INTO batch.workflow_node
  (tenant_id, workflow_definition_id, node_code, node_name, node_type, related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled, node_params, created_at, updated_at)
SELECT wd.tenant_id, wd.id, v.nc, v.nn, v.nt, v.rjc, v.no_, 'NONE', 0, 0, true, v.np::jsonb, now(), now()
FROM batch.workflow_definition wd, (VALUES
  ('wf_probe_pipeline','START','Start','START',NULL::text,0,'{"entry":true}'),
  ('wf_probe_pipeline','PROBE_TASK','Probe Task','TASK','export_settlement_job',1,'{"step":"probe"}'),
  ('wf_probe_pipeline','END','End','END',NULL::text,2,'{"entry":false}'),
  ('wf_probe_gateway','START','Start','START',NULL::text,0,'{"entry":true}'),
  ('wf_probe_gateway','FORK','Fork Gateway','GATEWAY',NULL::text,1,'{}'),
  ('wf_probe_gateway','BRANCH_A','Branch A','TASK','export_settlement_job',2,'{"step":"branchA"}'),
  ('wf_probe_gateway','BRANCH_B','Branch B','TASK','export_settlement_job',3,'{"step":"branchB"}'),
  ('wf_probe_gateway','MERGE','Merge Gateway','GATEWAY',NULL::text,4,'{"joinMode":"ANY"}'),
  ('wf_probe_gateway','END','End','END',NULL::text,5,'{"entry":false}')
) AS v(wc,nc,nn,nt,rjc,no_,np)
WHERE wd.tenant_id='default-tenant' AND wd.workflow_code=v.wc AND wd.version=1
ON CONFLICT DO NOTHING;

INSERT INTO batch.workflow_edge
  (tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled, created_at, updated_at)
SELECT wd.tenant_id, wd.id, v.f, v.t, v.et, true, now(), now()
FROM batch.workflow_definition wd, (VALUES
  ('wf_probe_pipeline','START','PROBE_TASK','ALWAYS'),
  ('wf_probe_pipeline','PROBE_TASK','END','ALWAYS'),
  ('wf_probe_gateway','START','FORK','ALWAYS'),
  ('wf_probe_gateway','FORK','BRANCH_A','ALWAYS'),
  ('wf_probe_gateway','FORK','BRANCH_B','ALWAYS'),
  ('wf_probe_gateway','BRANCH_A','MERGE','SUCCESS'),
  ('wf_probe_gateway','BRANCH_B','MERGE','SUCCESS'),
  ('wf_probe_gateway','MERGE','END','ALWAYS')
) AS v(wc,f,t,et)
WHERE wd.tenant_id='default-tenant' AND wd.workflow_code=v.wc AND wd.version=1
ON CONFLICT DO NOTHING;

INSERT INTO batch.job_definition
  (tenant_id, job_code, job_name, job_type, schedule_type, timezone, trigger_mode,
   queue_code, worker_group, window_code, priority, enabled, created_at, updated_at)
VALUES
  ('default-tenant','wf_probe_pipeline','Probe PIPELINE','WORKFLOW','MANUAL','Asia/Shanghai','SCHEDULED','export_queue','EXPORT','always_open',5,true,now(),now()),
  ('default-tenant','wf_probe_gateway','Probe GATEWAY','WORKFLOW','MANUAL','Asia/Shanghai','SCHEDULED','export_queue','EXPORT','always_open',5,true,now(),now())
ON CONFLICT (tenant_id, job_code) DO NOTHING;

-- MIXED workflow_type + 含 FILE_STEP 节点：完整覆盖 workflow_definition.workflow_type 枚举
INSERT INTO batch.workflow_definition
  (tenant_id, workflow_code, workflow_name, workflow_type, version, enabled, description, created_by, updated_by, created_at, updated_at)
VALUES
  ('default-tenant','wf_probe_mixed','Probe MIXED workflow','MIXED',1,true,'P2 seed - MIXED type (TASK + FILE_STEP mixed)','seed','seed',now(),now())
ON CONFLICT (tenant_id, workflow_code, version) DO NOTHING;

INSERT INTO batch.workflow_node
  (tenant_id, workflow_definition_id, node_code, node_name, node_type, related_job_code, node_order, retry_policy, retry_max_count, timeout_seconds, enabled, node_params, created_at, updated_at)
SELECT wd.tenant_id, wd.id, v.nc, v.nn, v.nt, v.rjc, v.no_, 'NONE', 0, 0, true, v.np::jsonb, now(), now()
FROM batch.workflow_definition wd, (VALUES
  ('wf_probe_mixed','START','Start','START',NULL::text,0,'{"entry":true}'),
  ('wf_probe_mixed','PROCESS','Process Task','TASK','export_settlement_job',1,'{"step":"process"}'),
  -- ADR-009: REPORT 节点演示用 $.nodes.<X>.output.<key> DSL 引用上游 PROCESS 节点产出
  ('wf_probe_mixed','REPORT','Generate Report','FILE_STEP','export_settlement_job',2,
   '{"step":"report","upstreamProcessedCount":"$.nodes.PROCESS.output.processedCount","bizDate":"$.workflowRun.bizDate"}'),
  ('wf_probe_mixed','END','End','END',NULL::text,3,'{"entry":false}')
) AS v(wc,nc,nn,nt,rjc,no_,np)
WHERE wd.tenant_id='default-tenant' AND wd.workflow_code=v.wc AND wd.version=1
ON CONFLICT DO NOTHING;

INSERT INTO batch.workflow_edge
  (tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, enabled, created_at, updated_at)
SELECT wd.tenant_id, wd.id, v.f, v.t, v.et, true, now(), now()
FROM batch.workflow_definition wd, (VALUES
  ('wf_probe_mixed','START','PROCESS','ALWAYS'),
  ('wf_probe_mixed','PROCESS','REPORT','SUCCESS'),
  ('wf_probe_mixed','REPORT','END','ALWAYS')
) AS v(wc,f,t,et)
WHERE wd.tenant_id='default-tenant' AND wd.workflow_code=v.wc AND wd.version=1
ON CONFLICT DO NOTHING;

INSERT INTO batch.job_definition
  (tenant_id, job_code, job_name, job_type, schedule_type, timezone, trigger_mode,
   queue_code, worker_group, window_code, priority, enabled, created_at, updated_at)
VALUES
  ('default-tenant','wf_probe_mixed','Probe MIXED','WORKFLOW','MANUAL','Asia/Shanghai','SCHEDULED','export_queue','EXPORT','always_open',5,true,now(),now())
ON CONFLICT (tenant_id, job_code) DO NOTHING;

-- API / API_PUSH dispatch channel：本地开发用 MockServer / mockoon / 或 python3 -m http.server 的自建 mock
-- 启动本地 mock 的快速命令（Python 3.9+）：
--   python3 scripts/local/mock-partner.py 1080
-- 之后 channel config 的 target_endpoint 指向 http://localhost:1080/api/receive 即可真发。
-- 出于避免 seed 默认指向脆弱 endpoint 的考虑，channel_code 保持原 endpoint 占位；
-- 联调时按需通过运维工具临时覆盖 config_json.target_endpoint 即可。
