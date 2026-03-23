-- Test seed data: multi-tenant setup for t2 and t3
-- Covers: job definitions, quota policies, worker registrations, template configs per tenant
-- Complements the existing t1-only seeds for cross-tenant isolation tests.

-- ── tenant t2: finance business unit ──────────────────────────────────────────

INSERT INTO batch.job_definition
  (tenant_id, job_code, job_name, job_type, schedule_type, trigger_mode, timezone,
   retry_policy, retry_max_count, created_at, updated_at)
VALUES
  ('t2', 'T2_IMPORT_JOB', 'T2 Finance Import', 'IMPORT', 'MANUAL', 'MANUAL', 'UTC',
   'FIXED', 3, now(), now()),
  ('t2', 'T2_EXPORT_JOB', 'T2 Finance Export', 'EXPORT', 'CRON', 'SCHEDULED', 'UTC',
   'EXPONENTIAL', 2, now(), now()),
  ('t2', 'T2_DISPATCH_JOB', 'T2 Finance Dispatch', 'DISPATCH', 'MANUAL', 'MANUAL', 'UTC',
   'NONE', 0, now(), now()),

-- ── tenant t3: risk management unit ───────────────────────────────────────────

  ('t3', 'T3_IMPORT_JOB', 'T3 Risk Import', 'IMPORT', 'EVENT', 'EVENT', 'UTC',
   'EXPONENTIAL', 5, now(), now()),
  ('t3', 'T3_EXPORT_JOB', 'T3 Risk Export', 'EXPORT', 'CRON', 'SCHEDULED', 'UTC',
   'FIXED', 3, now(), now()),
  ('t3', 'T3_DISPATCH_JOB', 'T3 Risk Dispatch', 'DISPATCH', 'MANUAL', 'MANUAL', 'UTC',
   'NONE', 0, now(), now())
ON CONFLICT DO NOTHING;

-- ── quota policies ─────────────────────────────────────────────────────────────

INSERT INTO batch.tenant_quota_policy
  (tenant_id, policy_code, enabled,
   max_running_jobs_per_tenant, max_partitions_per_tenant, max_qps_per_tenant,
   fair_share_weight, fair_share_group, group_shared_max_running_jobs,
   burst_limit, partition_burst_limit, quota_reset_policy,
   created_at, updated_at)
VALUES
  ('t2', 'DEFAULT', true,
   50, 200, 50,
   2, 'FINANCE', 150,
   10, 25, 'NONE',
   now(), now()),
  ('t3', 'DEFAULT', true,
   30, 100, 30,
   1, 'RISK', 80,
   5, 10, 'SLIDING_WINDOW',
   now(), now())
ON CONFLICT DO NOTHING;

-- ── worker registry ────────────────────────────────────────────────────────────

INSERT INTO batch.worker_registry
  (tenant_id, worker_code, worker_group, capability_tags, resource_tag,
   status, heartbeat_at, current_load, drain_started_at, drain_deadline_at)
VALUES
  ('t2', 'worker-t2-import-001', 'IMPORT', '{"import":true}', null,
   'ONLINE', now(), 0, null, null),
  ('t2', 'worker-t2-export-001', 'EXPORT', '{"export":true}', null,
   'ONLINE', now(), 0, null, null),
  ('t3', 'worker-t3-001', 'DEFAULT', '{"import":true,"export":true}', null,
   'ONLINE', now(), 0, null, null),
  ('t3', 'worker-t3-offline', 'DEFAULT', '{}', null,
   'ONLINE', now() - interval '10 minutes', 0, null, null)
ON CONFLICT DO NOTHING;

-- ── import template configs (t2 & t3) ─────────────────────────────────────────

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
  -- t2 finance: CSV import of transactions
  ('t2', 'IMP-TRANSACTION-CSV', 'Transaction Import CSV', 'IMPORT', 'TRANSACTION',
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

  -- t3 risk: encrypted JSON envelope import
  ('t3', 'IMP-RISK-SCORE-JSON', 'Risk Score Import JSON', 'IMPORT', 'RISK',
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

-- ── export template configs (t2 & t3) ─────────────────────────────────────────

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
  -- t2 finance: Excel export of account summary
  ('t2', 'EXP-ACCOUNT-EXCEL', 'Account Summary Export Excel', 'EXPORT', 'ACCOUNT',
   'EXCEL', 'UTF-8', 'UTF-8', false,
   null, null, null,
   0, 0, 0,
   'NONE', 'NONE', 'NONE',
   'account_summary_{bizDate}.xlsx',
   '[
     {"name":"accountNo","sourceColumn":"account_no","type":"STRING","header":"账户编号","colIndex":0},
     {"name":"accountName","sourceColumn":"account_name","type":"STRING","header":"账户名称","colIndex":1},
     {"name":"balance","sourceColumn":"balance","type":"DECIMAL","header":"余额","colIndex":2},
     {"name":"currencyCode","sourceColumn":"currency_code","type":"STRING","header":"币种","colIndex":3}
   ]'::jsonb,
   true, 50000, 1000, 500,
   false, null, false, false,
   true, 1, 'test'),

  -- t3 risk: JSON export of risk alerts (sensitive, encrypted)
  ('t3', 'EXP-RISK-ALERT-JSON', 'Risk Alert Export JSON Encrypted', 'EXPORT', 'RISK',
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
