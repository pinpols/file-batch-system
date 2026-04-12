-- Test seed data: multi-tenant setup for ta, tb, tc
-- Covers: job definitions, quota policies, resource queues, batch windows, business calendars,
--         worker registrations, template configs per tenant
-- Tenant roles:
--   ta = 零售业务  (retail)
--   tb = 金融业务  (finance)
--   tc = 风控业务  (risk management)

-- ── tenant ta: 零售业务 ────────────────────────────────────────────────────────

INSERT INTO batch.job_definition
  (tenant_id, job_code, job_name, job_type, schedule_type, trigger_mode, timezone,
   retry_policy, retry_max_count, created_at, updated_at)
VALUES
  ('ta', 'TA_IMPORT_CUSTOMER', 'TA Customer Import',   'IMPORT',   'CRON',        'SCHEDULED', 'Asia/Shanghai',
   'EXPONENTIAL', 3, now(), now()),
  ('ta', 'TA_EXPORT_REPORT',   'TA Report Export',     'EXPORT',   'MANUAL',      'MANUAL',    'Asia/Shanghai',
   'FIXED',       2, now(), now()),
  ('ta', 'TA_DISPATCH_ORDER',  'TA Order Dispatch',    'DISPATCH', 'FIXED_RATE',  'SCHEDULED', 'Asia/Shanghai',
   'FIXED',       1, now(), now()),
  ('ta', 'TA_WF_SETTLEMENT',   'TA Settlement Workflow','WORKFLOW', 'CRON',       'SCHEDULED', 'Asia/Shanghai',
   'EXPONENTIAL', 3, now(), now()),

-- ── tenant tb: 金融业务 ────────────────────────────────────────────────────────

  ('tb', 'TB_IMPORT_TRANSACTION', 'TB Transaction Import',   'IMPORT',   'CRON',   'SCHEDULED', 'Asia/Shanghai',
   'EXPONENTIAL', 3, now(), now()),
  ('tb', 'TB_EXPORT_STATEMENT',   'TB Statement Export',     'EXPORT',   'CRON',   'SCHEDULED', 'Asia/Shanghai',
   'FIXED',       2, now(), now()),
  ('tb', 'TB_WF_RECONCILE',       'TB Reconcile Workflow',   'WORKFLOW', 'CRON',   'SCHEDULED', 'Asia/Shanghai',
   'EXPONENTIAL', 3, now(), now()),

-- ── tenant tc: 风控业务 ────────────────────────────────────────────────────────

  ('tc', 'TC_IMPORT_RISK_SCORE',   'TC Risk Score Import',     'IMPORT',   'EVENT',  'EVENT',   'Asia/Shanghai',
   'EXPONENTIAL', 5, now(), now()),
  ('tc', 'TC_EXPORT_RISK_ALERT',   'TC Risk Alert Export',     'EXPORT',   'CRON',   'SCHEDULED','Asia/Shanghai',
   'FIXED',       3, now(), now()),
  ('tc', 'TC_DISPATCH_REVIEW',     'TC Review Dispatch',       'DISPATCH', 'MANUAL', 'MANUAL',  'Asia/Shanghai',
   'NONE',        0, now(), now()),
  ('tc', 'TC_WF_RISK_PIPELINE',    'TC Risk Pipeline Workflow','WORKFLOW', 'EVENT',  'EVENT',   'Asia/Shanghai',
   'EXPONENTIAL', 5, now(), now())
ON CONFLICT DO NOTHING;

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
  ('ta', 'worker-ta-import-001', 'IMPORT',   '{"import":true}',            null,
   'ONLINE', now(),                        0, null, null),
  ('ta', 'worker-ta-export-001', 'EXPORT',   '{"export":true}',            null,
   'ONLINE', now(),                        0, null, null),
  ('tb', 'worker-tb-import-001', 'IMPORT',   '{"import":true}',            null,
   'ONLINE', now(),                        0, null, null),
  ('tb', 'worker-tb-export-001', 'EXPORT',   '{"export":true}',            null,
   'ONLINE', now(),                        0, null, null),
  ('tc', 'worker-tc-001',        'DEFAULT',  '{"import":true,"export":true}', null,
   'ONLINE', now(),                        0, null, null),
  ('tc', 'worker-tc-offline',    'DEFAULT',  '{}',                         null,
   'ONLINE', now() - interval '10 minutes', 0, null, null)
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
  -- ta 零售: 客户数据 CSV 导入
  ('ta', 'IMP-CUSTOMER-CSV', 'Customer Import CSV', 'IMPORT', 'CUSTOMER',
   'DELIMITED', 'UTF-8', 'UTF-8', false,
   ',', '"', '"',
   0, 1, 0,
   'SHA-256', 'NONE', 'NONE',
   '[
     {"name":"customerId","targetColumn":"customer_id","type":"STRING","required":true},
     {"name":"customerName","targetColumn":"customer_name","type":"STRING","required":true},
     {"name":"phoneNo","targetColumn":"phone_no","type":"STRING","required":true},
     {"name":"email","targetColumn":"email","type":"STRING","required":false},
     {"name":"registerDate","targetColumn":"register_date","type":"DATE","required":true,"format":"yyyy-MM-dd"}
   ]'::jsonb,
   true, 1000, 1000, 500,
   false, null, false, false,
   true, 1, 'test'),

  -- tb 金融: 交易流水 CSV 导入
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
