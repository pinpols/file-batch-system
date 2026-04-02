-- ============================================================
-- Console Demo Seed — default-tenant 全量演示数据
-- 覆盖：resource_queue / tenant_quota_policy / batch_window /
--       business_calendar / calendar_holiday / worker_registry /
--       job_definition / workflow_definition / workflow_node /
--       workflow_edge / pipeline_definition / pipeline_step_definition /
--       file_template_config / file_channel_config / config_release /
--       secret_version / trigger_request / job_instance /
--       job_partition / job_task / job_step_instance / file_record /
--       pipeline_instance / pipeline_step_run / file_dispatch_record /
--       workflow_run / workflow_node_run / outbox_event /
--       event_outbox_retry / event_delivery_log / file_audit_log /
--       file_error_record / job_execution_log / retry_schedule /
--       dead_letter_task / compensation_command / approval_command /
--       alert_event / batch_day_instance / quota_runtime_state /
--       tenant_scheduler_snapshot / file_channel_health /
--       console_ai_audit_log / config_change_log
-- 幂等执行：所有静态数据 ON CONFLICT(id) DO UPDATE；
--           批量运行态数据 ON CONFLICT(id) DO NOTHING；
--           可安全重复执行，不删已有生产数据。
-- 用法：psql -f console-demo-seed.sql
-- ============================================================
BEGIN;

-- ── 1. resource_queue ────────────────────────────────────────
INSERT INTO batch.resource_queue (
    id, tenant_id, queue_code, queue_name, queue_type,
    max_running_jobs, max_running_partitions, max_qps,
    worker_group, resource_tag, priority_policy, fair_share_weight,
    enabled, description, fair_share_group, burst_limit,
    quota_reset_policy, group_shared_max_running_jobs
) VALUES
(10001,'default-tenant','import_queue',  'Import Queue',   'IMPORT', 4,8,50, 'import',  'ingest',   'FAIR_SHARE',10,TRUE,'Primary import queue',  'core',    2,'SLIDING_WINDOW',6),
(10002,'default-tenant','export_queue',  'Export Queue',   'EXPORT', 3,6,30, 'export',  'report',   'PRIORITY',  8,TRUE,'Primary export queue',  'core',    1,'CALENDAR_DAY',  4),
(10003,'default-tenant','dispatch_queue','Dispatch Queue', 'DISPATCH',3,6,30,'dispatch','delivery', 'FIFO',      6,TRUE,'Primary dispatch queue','delivery',1,'NONE',          3),
(10004,'default-tenant','general_queue', 'General Queue',  'IMPORT', 2,4,20, 'general', 'misc',     'FAIR_SHARE',5,TRUE,'General purpose queue', 'core',    1,'NONE',          2),
(10005,'default-tenant','workflow_queue','Workflow Queue', 'EXPORT', 2,4,20, 'export',  'workflow', 'PRIORITY',  7,TRUE,'Workflow orchestration queue','core',1,'SLIDING_WINDOW',3)
ON CONFLICT (id) DO UPDATE SET
    queue_name=EXCLUDED.queue_name, max_running_jobs=EXCLUDED.max_running_jobs,
    max_running_partitions=EXCLUDED.max_running_partitions, max_qps=EXCLUDED.max_qps,
    priority_policy=EXCLUDED.priority_policy, fair_share_weight=EXCLUDED.fair_share_weight,
    burst_limit=EXCLUDED.burst_limit, quota_reset_policy=EXCLUDED.quota_reset_policy,
    group_shared_max_running_jobs=EXCLUDED.group_shared_max_running_jobs,
    enabled=EXCLUDED.enabled, description=EXCLUDED.description,
    updated_at=current_timestamp;

-- ── 2. tenant_quota_policy ───────────────────────────────────
INSERT INTO batch.tenant_quota_policy (
    id, tenant_id, policy_code,
    max_running_jobs_per_tenant, max_partitions_per_tenant, max_qps_per_tenant,
    fair_share_weight, enabled, description, fair_share_group,
    burst_limit, partition_burst_limit, quota_reset_policy, group_shared_max_running_jobs
) VALUES
(10101,'default-tenant','default-policy',10,20,100,5,TRUE,'Default tenant quota','core',3,6,'SLIDING_WINDOW',8)
ON CONFLICT (id) DO UPDATE SET
    max_running_jobs_per_tenant=EXCLUDED.max_running_jobs_per_tenant,
    max_partitions_per_tenant=EXCLUDED.max_partitions_per_tenant,
    max_qps_per_tenant=EXCLUDED.max_qps_per_tenant,
    fair_share_weight=EXCLUDED.fair_share_weight,
    burst_limit=EXCLUDED.burst_limit, partition_burst_limit=EXCLUDED.partition_burst_limit,
    quota_reset_policy=EXCLUDED.quota_reset_policy,
    group_shared_max_running_jobs=EXCLUDED.group_shared_max_running_jobs,
    enabled=EXCLUDED.enabled, updated_at=current_timestamp;

-- ── 3. batch_window ──────────────────────────────────────────
INSERT INTO batch.batch_window (
    id, tenant_id, window_code, window_name, timezone, start_time, end_time,
    end_strategy, out_of_window_action, allow_cross_day, enabled, description
) VALUES
(10201,'default-tenant','always_open',    'Always Open',    'Asia/Shanghai',TIME '00:00:00',TIME '23:59:59','FINISH_RUNNING','WAIT',TRUE, TRUE,'24-hour open window'),
(10202,'default-tenant','business_hours', 'Business Hours', 'Asia/Shanghai',TIME '09:00:00',TIME '18:00:00','STOP',          'FAIL',FALSE,TRUE,'Business hours only'),
(10203,'default-tenant','night_batch',    'Night Batch',    'Asia/Shanghai',TIME '22:00:00',TIME '06:00:00','CONTINUE',      'WAIT',TRUE, TRUE,'Night batch window')
ON CONFLICT (id) DO UPDATE SET
    window_name=EXCLUDED.window_name, start_time=EXCLUDED.start_time,
    end_time=EXCLUDED.end_time, end_strategy=EXCLUDED.end_strategy,
    out_of_window_action=EXCLUDED.out_of_window_action,
    allow_cross_day=EXCLUDED.allow_cross_day, enabled=EXCLUDED.enabled,
    updated_at=current_timestamp;

-- ── 4. business_calendar ─────────────────────────────────────
INSERT INTO batch.business_calendar (
    id, tenant_id, calendar_code, calendar_name, timezone,
    holiday_roll_rule, catch_up_policy, catch_up_max_days, enabled,
    cutoff_time, late_arrival_tolerance_min, sla_offset_min
) VALUES
(10301,'default-tenant','default-calendar','Default Calendar','Asia/Shanghai','NEXT_WORKDAY','AUTO',          3,TRUE,TIME '06:00:00',60, 480),
(10302,'default-tenant','strict-calendar', 'Strict Calendar', 'Asia/Shanghai','SKIP',        'MANUAL_APPROVAL',2,TRUE,TIME '18:00:00',30, 240)
ON CONFLICT (id) DO UPDATE SET
    calendar_name=EXCLUDED.calendar_name, holiday_roll_rule=EXCLUDED.holiday_roll_rule,
    catch_up_policy=EXCLUDED.catch_up_policy, catch_up_max_days=EXCLUDED.catch_up_max_days,
    cutoff_time=EXCLUDED.cutoff_time,
    late_arrival_tolerance_min=EXCLUDED.late_arrival_tolerance_min,
    sla_offset_min=EXCLUDED.sla_offset_min, enabled=EXCLUDED.enabled,
    updated_at=current_timestamp;

-- ── 5. calendar_holiday ──────────────────────────────────────
INSERT INTO batch.calendar_holiday (id, calendar_id, biz_date, day_type, holiday_name, description) VALUES
(10401,10301,DATE '2026-04-05','HOLIDAY',         'Qingming',    'Qingming Festival'),
(10402,10301,DATE '2026-05-01','HOLIDAY',         'Labor Day',   'International Labor Day'),
(10403,10301,DATE '2026-05-02','HOLIDAY',         'Labor Day 2', 'Labor holiday'),
(10404,10302,DATE '2026-05-01','HOLIDAY',         'Labor Day',   'International Labor Day'),
(10405,10302,DATE '2026-04-26','WORKDAY_OVERRIDE','Make-up Day', 'Weekend make-up')
ON CONFLICT (id) DO UPDATE SET
    day_type=EXCLUDED.day_type, holiday_name=EXCLUDED.holiday_name,
    description=EXCLUDED.description;

-- ── 6. worker_registry (12) ──────────────────────────────────
INSERT INTO batch.worker_registry (
    id, tenant_id, worker_code, worker_group, host_name, host_ip,
    process_id, capability_tags, resource_tag, status, heartbeat_at,
    last_start_at, version, current_load, drain_started_at, drain_deadline_at
) VALUES
(10501,'default-tenant','import-node-1',    'import',  'import-host-1',    '10.0.1.1', '20001','{"role":"import","formats":["CSV","JSON","EXCEL"]}'::jsonb, 'ingest',  'ONLINE',       now()-interval '30s',  now()-interval '2h', 'v2.1',3,NULL,                       NULL),
(10502,'default-tenant','import-node-2',    'import',  'import-host-2',    '10.0.1.2', '20002','{"role":"import","formats":["CSV","JSON"]}'::jsonb,         'ingest',  'ONLINE',       now()-interval '25s',  now()-interval '2h', 'v2.1',1,NULL,                       NULL),
(10503,'default-tenant','import-node-3',    'import',  'import-host-3',    '10.0.1.3', '20003','{"role":"import","formats":["XML","FIXED_WIDTH"]}'::jsonb,  'ingest',  'DRAINING',     now()-interval '5m',   now()-interval '3h', 'v2.0',0,now()-interval '5m',         now()+interval '25m'),
(10504,'default-tenant','export-node-1',    'export',  'export-host-1',    '10.0.2.1', '20004','{"role":"export","formats":["CSV","EXCEL"]}'::jsonb,        'report',  'ONLINE',       now()-interval '20s',  now()-interval '1h', 'v2.1',2,NULL,                       NULL),
(10505,'default-tenant','export-node-2',    'export',  'export-host-2',    '10.0.2.2', '20005','{"role":"export","formats":["CSV","JSON"]}'::jsonb,         'report',  'ONLINE',       now()-interval '15s',  now()-interval '1h', 'v2.1',1,NULL,                       NULL),
(10506,'default-tenant','dispatch-node-1',  'dispatch','dispatch-host-1',  '10.0.3.1', '20006','{"role":"dispatch","channels":["API","SFTP","NAS"]}'::jsonb,'delivery','ONLINE',       now()-interval '10s',  now()-interval '30m','v2.1',4,NULL,                       NULL),
(10507,'default-tenant','dispatch-node-2',  'dispatch','dispatch-host-2',  '10.0.3.2', '20007','{"role":"dispatch","channels":["EMAIL","OSS"]}'::jsonb,     'delivery','ONLINE',       now()-interval '35s',  now()-interval '30m','v2.1',2,NULL,                       NULL),
(10508,'default-tenant','general-node-1',   'general', 'general-host-1',   '10.0.4.1', '20008','{"role":"general"}'::jsonb,                                 'misc',    'ONLINE',       now()-interval '45s',  now()-interval '4h', 'v2.1',0,NULL,                       NULL),
(10509,'default-tenant','import-node-old',  'import',  'import-host-old',  '10.0.1.99','20009','{"role":"import"}'::jsonb,                                  'ingest',  'OFFLINE',      now()-interval '2h',   now()-interval '1d', 'v1.9',0,NULL,                       NULL),
(10510,'default-tenant','dispatch-node-drain','dispatch','dispatch-host-3','10.0.3.3', '20010','{"role":"dispatch","channels":["API"]}'::jsonb,             'delivery','DRAINING',     now()-interval '10m',  now()-interval '2h', 'v2.0',1,now()-interval '10m',        now()+interval '20m'),
(10511,'default-tenant','export-node-old',  'export',  'export-host-old',  '10.0.2.99','20011','{"role":"export"}'::jsonb,                                  'report',  'DECOMMISSIONED',now()-interval '3d', now()-interval '7d', 'v1.8',0,NULL,                       NULL),
(10512,'default-tenant','general-node-2',   'general', 'general-host-2',   '10.0.4.2', '20012','{"role":"general"}'::jsonb,                                 'misc',    'ONLINE',       now()-interval '50s',  now()-interval '5h', 'v2.1',1,NULL,                       NULL)
ON CONFLICT (id) DO UPDATE SET
    status=EXCLUDED.status, heartbeat_at=EXCLUDED.heartbeat_at,
    current_load=EXCLUDED.current_load, drain_started_at=EXCLUDED.drain_started_at,
    drain_deadline_at=EXCLUDED.drain_deadline_at, version=EXCLUDED.version,
    updated_at=current_timestamp;

-- ── 7. job_definition (25) ───────────────────────────────────
INSERT INTO batch.job_definition (
    id, tenant_id, job_code, job_name, job_type, biz_type,
    schedule_type, schedule_expr, timezone, priority,
    queue_code, worker_group, calendar_code, window_code,
    trigger_mode, dag_enabled, shard_strategy, retry_policy,
    retry_max_count, timeout_seconds, execution_handler,
    param_schema, default_params, version, enabled, description,
    created_by, updated_by
) VALUES
-- IMPORT (5)
(20001,'default-tenant','imp_customer_csv',   'Customer CSV Import',     'IMPORT',  'CUSTOMER',  'CRON',       '0 0 8 * * ?',     'Asia/Shanghai',3,'import_queue',  'import',  'default-calendar','always_open',   'SCHEDULED',FALSE,'DYNAMIC',  'EXPONENTIAL',3,3600, 'com.example.ImportCustomerCsvHandler', '{"type":"object","properties":{"bizDate":{"type":"string"}}}'::jsonb, '{"streaming":true,"pageSize":1000}'::jsonb,         1,TRUE,'Daily customer CSV import','system','system'),
(20002,'default-tenant','imp_customer_json',  'Customer JSON Import',    'IMPORT',  'CUSTOMER',  'MANUAL',     NULL,              'Asia/Shanghai',3,'import_queue',  'import',  'default-calendar','always_open',   'API',      FALSE,'NONE',     'FIXED',      2,1800, 'com.example.ImportCustomerJsonHandler','{"type":"object","properties":{"bizDate":{"type":"string"}}}'::jsonb, '{"format":"json","pageSize":1000}'::jsonb,          1,TRUE,'On-demand customer JSON import','system','system'),
(20003,'default-tenant','imp_loan_batch',     'Loan Batch Import',       'IMPORT',  'LOAN',      'CRON',       '0 30 7 * * ?',    'Asia/Shanghai',5,'import_queue',  'import',  'default-calendar','business_hours','SCHEDULED',FALSE,'STATIC',   'EXPONENTIAL',3,7200, 'com.example.ImportLoanBatchHandler',   '{"type":"object","properties":{"bizDate":{"type":"string"}}}'::jsonb, '{"chunkSize":500,"pageSize":1000}'::jsonb,          1,TRUE,'Daily loan data batch import','system','system'),
(20004,'default-tenant','imp_kyc_doc',        'KYC Document Import',     'IMPORT',  'KYC',       'EVENT',      'kyc.document.arrived','Asia/Shanghai',4,'import_queue','import',  'default-calendar','always_open',   'EVENT',    FALSE,'NONE',     'FIXED',      2,1200, 'com.example.ImportKycDocHandler',      '{"type":"object","properties":{"docId":{"type":"string"}}}'::jsonb,  '{"validateStrict":true}'::jsonb,                    1,TRUE,'Event-driven KYC document import','system','system'),
(20005,'default-tenant','imp_blacklist',      'Blacklist Sync Import',   'IMPORT',  'RISK',      'CRON',       '0 0 6 * * ?',     'Asia/Shanghai',5,'import_queue',  'import',  'strict-calendar', 'always_open',   'SCHEDULED',FALSE,'NONE',     'EXPONENTIAL',5,3600, 'com.example.ImportBlacklistHandler',   '{"type":"object","properties":{"bizDate":{"type":"string"}}}'::jsonb, '{"source":"central","pageSize":2000}'::jsonb,       1,TRUE,'Daily blacklist sync from central','system','system'),
-- EXPORT (5)
(20006,'default-tenant','exp_settlement_daily','Daily Settlement Export', 'EXPORT',  'SETTLEMENT','CRON',       '0 0 9 * * ?',     'Asia/Shanghai',4,'export_queue',  'export',  'default-calendar','business_hours','SCHEDULED',TRUE, 'STATIC',   'FIXED',      2,7200, 'com.example.ExportSettlementHandler',  '{"type":"object","properties":{"bizDate":{"type":"string"}}}'::jsonb, '{"pageSize":1000,"queryCode":"settlement_daily"}'::jsonb,1,TRUE,'Daily settlement file generation','system','system'),
(20007,'default-tenant','exp_report_monthly', 'Monthly Report Export',   'EXPORT',  'REPORT',    'CRON',       '0 0 2 1 * ?',     'Asia/Shanghai',2,'export_queue',  'export',  'default-calendar','night_batch',   'SCHEDULED',FALSE,'NONE',     'FIXED',      1,14400,'com.example.ExportMonthlyReportHandler','{"type":"object","properties":{"period":{"type":"string"}}}'::jsonb,  '{"format":"xlsx","queryCode":"monthly_summary"}'::jsonb,1,TRUE,'Monthly summary report export','system','system'),
(20008,'default-tenant','exp_customer_extract','Customer Extract',        'EXPORT',  'CUSTOMER',  'MANUAL',     NULL,              'Asia/Shanghai',3,'export_queue',  'export',  'default-calendar','always_open',   'API',      FALSE,'DYNAMIC',  'NONE',       0,3600, 'com.example.ExportCustomerExtractHandler','{"type":"object","properties":{"bizDate":{"type":"string"}}}'::jsonb,'{"includeInactive":false}'::jsonb,                1,TRUE,'On-demand customer data extract','system','system'),
(20009,'default-tenant','exp_audit_report',   'Audit Trail Export',      'EXPORT',  'AUDIT',     'CRON',       '0 0 3 * * ?',     'Asia/Shanghai',2,'export_queue',  'export',  'default-calendar','night_batch',   'SCHEDULED',FALSE,'NONE',     'FIXED',      1,7200, 'com.example.ExportAuditReportHandler', '{"type":"object","properties":{"days":{"type":"integer"}}}'::jsonb,  '{"days":7}'::jsonb,                                 1,TRUE,'Daily audit trail export','system','system'),
(20010,'default-tenant','exp_balance_snapshot','Balance Snapshot',        'EXPORT',  'FINANCE',   'CRON',       '0 0 0 * * ?',     'Asia/Shanghai',5,'export_queue',  'export',  'strict-calendar', 'night_batch',   'SCHEDULED',FALSE,'STATIC',   'EXPONENTIAL',3,10800,'com.example.ExportBalanceSnapshotHandler','{"type":"object","properties":{"bizDate":{"type":"string"}}}'::jsonb,'{"snapshot":"midnight"}'::jsonb,                  1,TRUE,'Midnight balance snapshot','system','system'),
-- DISPATCH (5)
(20011,'default-tenant','disp_sftp_bank',     'Bank SFTP Dispatch',      'DISPATCH','SETTLEMENT','MANUAL',     NULL,              'Asia/Shanghai',4,'dispatch_queue','dispatch','default-calendar','business_hours','API',      FALSE,'NONE',     'EXPONENTIAL',3,1200, 'com.example.DispatchSftpBankHandler',  '{"type":"object","properties":{"fileId":{"type":"integer"}}}'::jsonb,'{"channel":"sftp"}'::jsonb,                         1,TRUE,'Dispatch settlement to bank via SFTP','system','system'),
(20012,'default-tenant','disp_api_partner',   'Partner API Dispatch',    'DISPATCH','DELIVERY',  'EVENT',      'file.generated',  'Asia/Shanghai',4,'dispatch_queue','dispatch','default-calendar','always_open',   'EVENT',    FALSE,'NONE',     'FIXED',      2,600,  'com.example.DispatchApiPartnerHandler','{"type":"object","properties":{"fileId":{"type":"integer"}}}'::jsonb,'{"channel":"api"}'::jsonb,                          1,TRUE,'Push files to partner via API','system','system'),
(20013,'default-tenant','disp_email_report',  'Email Report Dispatch',   'DISPATCH','REPORT',    'MANUAL',     NULL,              'Asia/Shanghai',2,'dispatch_queue','dispatch','default-calendar','always_open',   'MANUAL',   FALSE,'NONE',     'FIXED',      1,300,  'com.example.DispatchEmailReportHandler','{"type":"object","properties":{"fileId":{"type":"integer"}}}'::jsonb,'{"channel":"email"}'::jsonb,                        1,TRUE,'Email report dispatch','system','system'),
(20014,'default-tenant','disp_nas_archive',   'NAS Archive Dispatch',    'DISPATCH','ARCHIVE',   'CRON',       '0 0 4 * * ?',     'Asia/Shanghai',1,'dispatch_queue','dispatch','default-calendar','night_batch',   'SCHEDULED',FALSE,'NONE',     'NONE',       0,1800, 'com.example.DispatchNasArchiveHandler','{"type":"object","properties":{"bizDate":{"type":"string"}}}'::jsonb,'{"channel":"nas"}'::jsonb,                          1,TRUE,'Archive files to NAS nightly','system','system'),
(20015,'default-tenant','disp_oss_backup',    'OSS Backup Dispatch',     'DISPATCH','BACKUP',    'CRON',       '0 0 5 * * ?',     'Asia/Shanghai',1,'dispatch_queue','dispatch','default-calendar','night_batch',   'SCHEDULED',FALSE,'NONE',     'FIXED',      1,1800, 'com.example.DispatchOssBackupHandler', '{"type":"object","properties":{"bizDate":{"type":"string"}}}'::jsonb,'{"channel":"oss"}'::jsonb,                          1,TRUE,'Backup files to OSS nightly','system','system'),
-- GENERAL (5)
(20016,'default-tenant','gen_data_cleanup',   'Data Cleanup',            'GENERAL', 'HOUSEKEEPING','CRON',     '0 0 3 * * ?',     'Asia/Shanghai',1,'general_queue', 'general', 'default-calendar','night_batch',   'SCHEDULED',FALSE,'NONE',     'NONE',       0,3600, 'com.example.DataCleanupHandler',       '{"type":"object","properties":{"retentionDays":{"type":"integer"}}}'::jsonb,'{"retentionDays":90}'::jsonb,              1,TRUE,'Nightly data cleanup','system','system'),
(20017,'default-tenant','gen_reconcile',      'Reconciliation',          'GENERAL', 'FINANCE',   'CRON',       '0 30 9 * * ?',    'Asia/Shanghai',5,'general_queue', 'general', 'strict-calendar', 'business_hours','SCHEDULED',FALSE,'NONE',     'FIXED',      2,7200, 'com.example.ReconcileHandler',         '{"type":"object","properties":{"bizDate":{"type":"string"}}}'::jsonb, '{"mode":"full"}'::jsonb,                            1,TRUE,'Daily reconciliation','system','system'),
(20018,'default-tenant','gen_index_rebuild',  'Index Rebuild',           'GENERAL', 'MAINTENANCE','MANUAL',    NULL,              'Asia/Shanghai',1,'general_queue', 'general', 'default-calendar','night_batch',   'MANUAL',   FALSE,'NONE',     'NONE',       0,1800, 'com.example.IndexRebuildHandler',      '{"type":"object","properties":{"tables":{"type":"array"}}}'::jsonb,  '{"tables":["all"]}'::jsonb,                         1,TRUE,'On-demand index rebuild','system','system'),
(20019,'default-tenant','gen_stats_collect',  'Stats Collector',         'GENERAL', 'MONITORING','FIXED_RATE', NULL,              'Asia/Shanghai',2,'general_queue', 'general', 'default-calendar','always_open',   'SCHEDULED',FALSE,'NONE',     'NONE',       0,300,  'com.example.StatsCollectorHandler',    '{"type":"object"}'::jsonb,                                            '{"interval":"5m"}'::jsonb,                          1,TRUE,'Periodic stats collection','system','system'),
(20020,'default-tenant','gen_archive_purge',  'Archive Purge',           'GENERAL', 'HOUSEKEEPING','CRON',     '0 0 2 1 * ?',     'Asia/Shanghai',1,'general_queue', 'general', 'default-calendar','night_batch',   'SCHEDULED',FALSE,'NONE',     'NONE',       0,7200, 'com.example.ArchivePurgeHandler',      '{"type":"object","properties":{"olderThanDays":{"type":"integer"}}}'::jsonb,'{"olderThanDays":365}'::jsonb,             1,TRUE,'Monthly archive purge','system','system'),
-- WORKFLOW (5)
(20021,'default-tenant','wf_eod_process',     'EOD Processing',          'WORKFLOW','FINANCE',   'CRON',       '0 0 18 * * ?',    'Asia/Shanghai',5,'workflow_queue','export',  'strict-calendar', 'business_hours','SCHEDULED',TRUE, 'AUTO',     'EXPONENTIAL',3,14400,'com.example.EodProcessHandler',        '{"type":"object","properties":{"bizDate":{"type":"string"}}}'::jsonb, '{"steps":["settle","export","dispatch"]}'::jsonb,   1,TRUE,'End-of-day processing workflow','system','system'),
(20022,'default-tenant','wf_month_close',     'Month-End Close',         'WORKFLOW','FINANCE',   'CRON',       '0 0 20 L * ?',    'Asia/Shanghai',5,'workflow_queue','export',  'strict-calendar', 'night_batch',   'SCHEDULED',TRUE, 'AUTO',     'EXPONENTIAL',3,21600,'com.example.MonthCloseHandler',        '{"type":"object","properties":{"period":{"type":"string"}}}'::jsonb,  '{"steps":["reconcile","report","archive"]}'::jsonb, 1,TRUE,'Month-end close workflow','system','system'),
(20023,'default-tenant','wf_data_migration',  'Data Migration',          'WORKFLOW','MAINTENANCE','MANUAL',    NULL,              'Asia/Shanghai',3,'workflow_queue','export',  'default-calendar','always_open',   'MANUAL',   TRUE, 'NONE',     'FIXED',      2,28800,'com.example.DataMigrationHandler',     '{"type":"object","properties":{"source":{"type":"string"}}}'::jsonb,  '{"source":"legacy"}'::jsonb,                        1,TRUE,'One-time data migration workflow','system','system'),
(20024,'default-tenant','wf_compliance_check','Compliance Check',        'WORKFLOW','RISK',      'CRON',       '0 0 10 * * ?',    'Asia/Shanghai',4,'workflow_queue','export',  'strict-calendar', 'business_hours','SCHEDULED',TRUE, 'NONE',     'FIXED',      1,3600, 'com.example.ComplianceCheckHandler',   '{"type":"object","properties":{"scope":{"type":"string"}}}'::jsonb,  '{"scope":"all"}'::jsonb,                            1,TRUE,'Daily compliance check workflow','system','system'),
(20025,'default-tenant','wf_onboarding',      'Onboarding Flow',         'WORKFLOW','CUSTOMER',  'EVENT',      'customer.created','Asia/Shanghai',3,'workflow_queue','export',  'default-calendar','always_open',   'EVENT',    TRUE, 'NONE',     'EXPONENTIAL',2,1800, 'com.example.OnboardingHandler',        '{"type":"object","properties":{"customerId":{"type":"string"}}}'::jsonb,'{"notify":true}'::jsonb,                        1,TRUE,'Customer onboarding workflow','system','system')
ON CONFLICT (id) DO UPDATE SET
    job_name=EXCLUDED.job_name, schedule_type=EXCLUDED.schedule_type,
    schedule_expr=EXCLUDED.schedule_expr, retry_policy=EXCLUDED.retry_policy,
    retry_max_count=EXCLUDED.retry_max_count, timeout_seconds=EXCLUDED.timeout_seconds,
    param_schema=EXCLUDED.param_schema, default_params=EXCLUDED.default_params,
    enabled=EXCLUDED.enabled, updated_by=EXCLUDED.updated_by,
    updated_at=current_timestamp;

-- ── 8. workflow_definition (10) ──────────────────────────────
INSERT INTO batch.workflow_definition (
    id, tenant_id, workflow_code, workflow_name, workflow_type,
    version, enabled, description, created_by, updated_by
) VALUES
(21001,'default-tenant','wf_eod_process',    'EOD Processing Flow',    'DAG',     1,TRUE,'End-of-day multi-step workflow',     'system','system'),
(21002,'default-tenant','wf_month_close',    'Month-End Close Flow',   'DAG',     1,TRUE,'Month-end reconcile-report-archive', 'system','system'),
(21003,'default-tenant','wf_data_migration', 'Data Migration Flow',    'DAG',     1,TRUE,'Legacy data migration',              'system','system'),
(21004,'default-tenant','wf_compliance_check','Compliance Check Flow', 'DAG',     1,TRUE,'Daily compliance scan workflow',     'system','system'),
(21005,'default-tenant','wf_onboarding',     'Onboarding Flow',        'PIPELINE',1,TRUE,'Customer onboarding pipeline',       'system','system'),
(21006,'default-tenant','wf_import_export',  'Import-Export Chain',    'DAG',     1,TRUE,'Import then export chained workflow','system','system'),
(21007,'default-tenant','wf_settle_dispatch','Settle & Dispatch',      'DAG',     1,TRUE,'Settlement then dispatch workflow',  'system','system'),
(21008,'default-tenant','wf_full_pipeline',  'Full Pipeline',          'MIXED',   1,TRUE,'Full import-export-dispatch flow',   'system','system'),
(21009,'default-tenant','wf_risk_scan',      'Risk Scan Flow',         'DAG',     1,TRUE,'Risk scanning workflow',             'system','system'),
(21010,'default-tenant','wf_archive_flow',   'Archive Flow',           'PIPELINE',1,TRUE,'Archive workflow',                   'system','system')
ON CONFLICT (id) DO UPDATE SET
    workflow_name=EXCLUDED.workflow_name, description=EXCLUDED.description,
    enabled=EXCLUDED.enabled, updated_by=EXCLUDED.updated_by,
    updated_at=current_timestamp;

-- ── 9. workflow_node (32) ────────────────────────────────────
-- node_type 枚举：START / END / TASK / FILE_STEP / GATEWAY / JOB (V27)
INSERT INTO batch.workflow_node (
    id, workflow_definition_id, node_code, node_name, node_type,
    related_job_code, related_pipeline_code, worker_group, window_code,
    node_order, retry_policy, retry_max_count, timeout_seconds,
    node_params, enabled
) VALUES
-- wf_eod_process (21001): START → SETTLE → DISPATCH → END
(22001,21001,'START',   'Start',             'START',    NULL,                    NULL,                    NULL,      NULL,             0,'NONE',       0,0,    '{"entry":true}'::jsonb,          TRUE),
(22002,21001,'SETTLE',  'Settlement Export', 'TASK',     'exp_settlement_daily',  'export_settlement_pipeline','export','business_hours',1,'FIXED',       2,3600, '{"step":"settle"}'::jsonb,       TRUE),
(22003,21001,'DISPATCH','Bank Dispatch',     'TASK',     'disp_sftp_bank',        NULL,                    'dispatch','business_hours',2,'EXPONENTIAL',  3,1200, '{"step":"dispatch"}'::jsonb,     TRUE),
(22004,21001,'END',     'End',               'END',      NULL,                    NULL,                    NULL,      NULL,             3,'NONE',       0,0,    '{"entry":false}'::jsonb,         TRUE),
-- wf_month_close (21002): START → RECONCILE → REPORT → ARCHIVE → END
(22005,21002,'START',    'Start',            'START',    NULL,                    NULL,                    NULL,      NULL,             0,'NONE',       0,0,    '{"entry":true}'::jsonb,          TRUE),
(22006,21002,'RECONCILE','Reconcile',        'TASK',     'gen_reconcile',         NULL,                    'general', 'business_hours', 1,'FIXED',       2,7200, '{"step":"reconcile"}'::jsonb,    TRUE),
(22007,21002,'REPORT',   'Export Report',    'FILE_STEP','exp_report_monthly',    'export_report_pipeline','export',  'night_batch',    2,'FIXED',       1,14400,'{"step":"report"}'::jsonb,       TRUE),
(22008,21002,'ARCHIVE',  'NAS Archive',      'TASK',     'disp_nas_archive',      NULL,                    'dispatch','night_batch',    3,'NONE',       0,1800, '{"step":"archive"}'::jsonb,      TRUE),
(22009,21002,'END',      'End',              'END',      NULL,                    NULL,                    NULL,      NULL,             4,'NONE',       0,0,    '{"entry":false}'::jsonb,         TRUE),
-- wf_import_export (21006): START → IMPORT(FILE_STEP) → EXPORT(TASK) → END
(22010,21006,'START',   'Start',             'START',    NULL,                    NULL,                    NULL,      NULL,             0,'NONE',       0,0,    '{"entry":true}'::jsonb,          TRUE),
(22011,21006,'IMPORT',  'Customer Import',   'FILE_STEP','imp_customer_csv',      'import_customer_pipeline','import','always_open',   1,'FIXED',       2,3600, '{"step":"import"}'::jsonb,       TRUE),
(22012,21006,'EXPORT',  'Customer Extract',  'TASK',     'exp_customer_extract',  NULL,                    'export',  'always_open',    2,'FIXED',       1,3600, '{"step":"export"}'::jsonb,       TRUE),
(22013,21006,'END',     'End',               'END',      NULL,                    NULL,                    NULL,      NULL,             3,'NONE',       0,0,    '{"entry":false}'::jsonb,         TRUE),
-- wf_risk_scan (21009): START → BLACKLIST_IMPORT(JOB, V27) → COMPLIANCE(TASK) → END
(22014,21009,'START',        'Start',              'START',  NULL,             NULL,    NULL,     NULL,           0,'NONE',      0,0,    '{"entry":true}'::jsonb,                     TRUE),
(22015,21009,'BLACKLIST_SYNC','Blacklist Sync Job', 'JOB',   'imp_blacklist',  NULL,    'import', 'always_open',  1,'EXPONENTIAL',2,3600, '{"childJob":"imp_blacklist"}'::jsonb,       TRUE),
(22016,21009,'COMPLIANCE',   'Compliance Check',   'TASK',  'wf_compliance_check',NULL,'export', 'business_hours',2,'FIXED',      1,3600, '{"step":"compliance"}'::jsonb,              TRUE),
(22017,21009,'END',          'End',                'END',   NULL,             NULL,    NULL,     NULL,           3,'NONE',      0,0,    '{"entry":false}'::jsonb,                    TRUE)
ON CONFLICT (id) DO UPDATE SET
    node_name=EXCLUDED.node_name, node_type=EXCLUDED.node_type,
    related_job_code=EXCLUDED.related_job_code,
    related_pipeline_code=EXCLUDED.related_pipeline_code,
    retry_policy=EXCLUDED.retry_policy, retry_max_count=EXCLUDED.retry_max_count,
    node_params=EXCLUDED.node_params, enabled=EXCLUDED.enabled;

-- ── 10. workflow_edge ────────────────────────────────────────
INSERT INTO batch.workflow_edge (
    id, workflow_definition_id, from_node_code, to_node_code, edge_type, condition_expr, enabled
) VALUES
(23001,21001,'START',   'SETTLE',       'ALWAYS',  NULL,TRUE),
(23002,21001,'SETTLE',  'DISPATCH',     'SUCCESS', NULL,TRUE),
(23003,21001,'DISPATCH','END',          'ALWAYS',  NULL,TRUE),
(23004,21002,'START',   'RECONCILE',    'ALWAYS',  NULL,TRUE),
(23005,21002,'RECONCILE','REPORT',      'SUCCESS', NULL,TRUE),
(23006,21002,'REPORT',  'ARCHIVE',      'SUCCESS', NULL,TRUE),
(23007,21002,'ARCHIVE', 'END',          'ALWAYS',  NULL,TRUE),
(23008,21006,'START',   'IMPORT',       'ALWAYS',  NULL,TRUE),
(23009,21006,'IMPORT',  'EXPORT',       'SUCCESS', NULL,TRUE),
(23010,21006,'EXPORT',  'END',          'ALWAYS',  NULL,TRUE),
(23011,21009,'START',        'BLACKLIST_SYNC','ALWAYS', NULL,TRUE),
(23012,21009,'BLACKLIST_SYNC','COMPLIANCE',  'SUCCESS',NULL,TRUE),
(23013,21009,'COMPLIANCE',   'END',         'ALWAYS', NULL,TRUE)
ON CONFLICT (id) DO UPDATE SET
    edge_type=EXCLUDED.edge_type, condition_expr=EXCLUDED.condition_expr,
    enabled=EXCLUDED.enabled;

-- ── 11. pipeline_definition (8) ──────────────────────────────
INSERT INTO batch.pipeline_definition (
    id, tenant_id, job_code, pipeline_name, pipeline_type, biz_type,
    worker_group, version, enabled, description
) VALUES
(46001,'default-tenant','import_customer_pipeline',  'Customer Import Pipeline',  'IMPORT', 'CUSTOMER',  'import', 1,TRUE,'CSV/JSON customer import pipeline'),
(46002,'default-tenant','import_loan_pipeline',      'Loan Import Pipeline',      'IMPORT', 'LOAN',      'import', 1,TRUE,'Loan batch import pipeline'),
(46003,'default-tenant','export_settlement_pipeline','Settlement Export Pipeline','EXPORT', 'SETTLEMENT','export', 1,TRUE,'Daily settlement export pipeline'),
(46004,'default-tenant','export_report_pipeline',    'Report Export Pipeline',    'EXPORT', 'REPORT',    'export', 1,TRUE,'Monthly report export pipeline'),
(46005,'default-tenant','export_customer_pipeline',  'Customer Extract Pipeline', 'EXPORT', 'CUSTOMER',  'export', 1,TRUE,'Customer extract export pipeline'),
(46006,'default-tenant','dispatch_pipeline',         'General Dispatch Pipeline', 'DISPATCH','DELIVERY', 'dispatch',1,TRUE,'Multi-channel dispatch pipeline'),
(46007,'default-tenant','dispatch_archive_pipeline', 'Archive Dispatch Pipeline', 'DISPATCH','ARCHIVE',  'dispatch',1,TRUE,'NAS/OSS archive dispatch pipeline'),
(46008,'default-tenant','import_kyc_pipeline',       'KYC Import Pipeline',       'IMPORT', 'KYC',       'import', 1,TRUE,'KYC document import pipeline')
ON CONFLICT (id) DO UPDATE SET
    pipeline_name=EXCLUDED.pipeline_name, enabled=EXCLUDED.enabled,
    description=EXCLUDED.description, updated_at=current_timestamp;

-- ── 12. pipeline_step_definition (24) ────────────────────────
INSERT INTO batch.pipeline_step_definition (
    id, pipeline_definition_id, step_code, step_name, stage_code,
    step_order, impl_code, step_params, timeout_seconds,
    retry_policy, retry_max_count, enabled
) VALUES
(47001,46001,'receive',  'Receive',  'RECEIVE',  1,'fileReceive',    '{"storageType":"S3"}'::jsonb,                      300, 'NONE',       0,TRUE),
(47002,46001,'parse',    'Parse',    'PARSE',    2,'csvParse',       '{"delimiter":",","quoteChar":"\"","encoding":"UTF-8"}'::jsonb,600,'FIXED',1,TRUE),
(47003,46001,'validate', 'Validate', 'VALIDATE', 3,'rowValidate',    '{"ruleSet":"customer","failFast":false}'::jsonb,   600, 'FIXED',      1,TRUE),
(47004,46001,'load',     'Load',     'LOAD',     4,'jdbcMappedLoad', '{"target":"biz.customer_account","chunkSize":500}'::jsonb,1200,'EXPONENTIAL',2,TRUE),
(47005,46002,'receive',  'Receive',  'RECEIVE',  1,'fileReceive',    '{"storageType":"S3"}'::jsonb,                      300, 'NONE',       0,TRUE),
(47006,46002,'parse',    'Parse',    'PARSE',    2,'csvParse',       '{"delimiter":",","encoding":"UTF-8"}'::jsonb,       600, 'FIXED',      1,TRUE),
(47007,46002,'validate', 'Validate', 'VALIDATE', 3,'rowValidate',    '{"ruleSet":"loan","failFast":false}'::jsonb,       600, 'FIXED',      1,TRUE),
(47008,46002,'load',     'Load',     'LOAD',     4,'jdbcMappedLoad', '{"target":"biz.loan_account","chunkSize":500}'::jsonb,1200,'EXPONENTIAL',2,TRUE),
(47009,46003,'prepare',  'Prepare',  'RECEIVE',  1,'exportPrepare',  '{"snapshot":"BIZ_DATE"}'::jsonb,                   300, 'NONE',       0,TRUE),
(47010,46003,'generate', 'Generate', 'GENERATE', 2,'csvGenerate',    '{"delimiter":",","withBom":true}'::jsonb,          1200,'FIXED',      1,TRUE),
(47011,46003,'store',    'Store',    'TRANSFER', 3,'minioStore',     '{"bucket":"batch-dev","prefix":"settlement/"}'::jsonb,1200,'FIXED',   1,TRUE),
(47012,46003,'register', 'Register', 'ACK',      4,'fileRegister',   '{"mode":"atomic"}'::jsonb,                         300, 'NONE',       0,TRUE),
(47013,46006,'dispatch', 'Dispatch', 'DISPATCH', 1,'dispatchChannel','{"channels":["API","SFTP","NAS"]}'::jsonb,         1200,'EXPONENTIAL',2,TRUE),
(47014,46004,'prepare',  'Prepare',  'RECEIVE',  1,'exportPrepare',  '{"snapshot":"PERIOD"}'::jsonb,                     300, 'NONE',       0,TRUE),
(47015,46004,'generate', 'Generate', 'GENERATE', 2,'xlsxGenerate',   '{"template":"report","sheetName":"Summary"}'::jsonb,3600,'FIXED',    1,TRUE),
(47016,46004,'store',    'Store',    'TRANSFER', 3,'minioStore',     '{"bucket":"batch-dev","prefix":"reports/"}'::jsonb,1200,'FIXED',      1,TRUE),
(47017,46005,'prepare',  'Prepare',  'RECEIVE',  1,'exportPrepare',  '{"snapshot":"FULL"}'::jsonb,                       300, 'NONE',       0,TRUE),
(47018,46005,'generate', 'Generate', 'GENERATE', 2,'csvGenerate',    '{"delimiter":",","encoding":"UTF-8"}'::jsonb,      1200,'FIXED',      1,TRUE),
(47019,46005,'store',    'Store',    'TRANSFER', 3,'minioStore',     '{"bucket":"batch-dev","prefix":"customer/"}'::jsonb,600,'FIXED',      1,TRUE),
(47020,46007,'dispatch', 'Dispatch', 'DISPATCH', 1,'archiveDispatch','{"channels":["NAS","OSS"]}'::jsonb,                1800,'FIXED',      1,TRUE),
(47021,46008,'receive',  'Receive',  'RECEIVE',  1,'fileReceive',    '{"storageType":"S3"}'::jsonb,                      300, 'NONE',       0,TRUE),
(47022,46008,'parse',    'Parse',    'PARSE',    2,'jsonParse',      '{"arrayMode":true,"rootPath":"$.documents"}'::jsonb,600,'FIXED',     1,TRUE),
(47023,46008,'validate', 'Validate', 'VALIDATE', 3,'rowValidate',    '{"ruleSet":"kyc","failFast":true}'::jsonb,         600, 'FIXED',      1,TRUE),
(47024,46008,'load',     'Load',     'LOAD',     4,'jdbcMappedLoad', '{"target":"biz.kyc_document","chunkSize":200}'::jsonb,1200,'EXPONENTIAL',2,TRUE)
ON CONFLICT (id) DO UPDATE SET
    step_name=EXCLUDED.step_name, impl_code=EXCLUDED.impl_code,
    step_params=EXCLUDED.step_params, timeout_seconds=EXCLUDED.timeout_seconds,
    retry_policy=EXCLUDED.retry_policy, retry_max_count=EXCLUDED.retry_max_count,
    enabled=EXCLUDED.enabled;

-- ── 13. file_template_config (10) ────────────────────────────
-- 包含 V17 安全字段 + V28 插件引用，所有关键字段不留空
INSERT INTO batch.file_template_config (
    id, tenant_id, template_code, template_name, template_type, biz_type,
    file_format_type, charset, target_charset, with_bom, delimiter,
    quote_char, escape_char, record_length, header_rows, footer_rows,
    checksum_type, compress_type, encrypt_type, naming_rule,
    field_mappings, validation_rule_set,
    default_query_code, default_query_sql, query_param_schema,
    streaming_enabled, page_size, fetch_size, chunk_size,
    enabled, version, description, created_by, updated_by,
    -- V17 security flags
    preview_masking_enabled, error_line_masking_enabled, log_masking_enabled,
    content_encryption_enabled, encryption_key_ref,
    download_requires_approval, masking_rule_set,
    -- V28 plugin refs
    export_data_ref, load_target_ref
) VALUES
-- 导入模板 (5)
(50001,'default-tenant','imp_customer_csv_v1',  'Customer CSV Import',    'IMPORT','CUSTOMER',  'DELIMITED','UTF-8','UTF-8',FALSE,',','"','\\',0,1,0,'SHA-256','NONE', 'NONE','customer-${bizDate}',
 '{"customerNo":"customerNo","customerName":"customerName","idNo":"idNo","mobile":"mobile"}'::jsonb,
 '{"required":["customerNo","idNo"],"maxErrors":100}'::jsonb,
 NULL,NULL,'{"jdbcMappedImport":{"table":"biz.customer","pkColumns":["customerNo"]}}'::jsonb,
 TRUE,1000,1000,500,TRUE,1,'Customer CSV template','system','system',
 TRUE,TRUE,FALSE,FALSE,NULL,FALSE,NULL,
 NULL,'jdbc_mapped'),
(50002,'default-tenant','imp_customer_json_v1', 'Customer JSON Import',   'IMPORT','CUSTOMER',  'JSON',     'UTF-8','UTF-8',FALSE,NULL,NULL,NULL,0,0,0,'SHA-256','NONE','NONE','customer-json-${bizDate}',
 '{"customerNo":"customerNo","customerName":"customerName"}'::jsonb,
 '{"required":["customerNo"],"maxErrors":50}'::jsonb,
 NULL,NULL,'{"jdbcMappedImport":{"table":"biz.customer","pkColumns":["customerNo"]}}'::jsonb,
 TRUE,1000,1000,500,TRUE,1,'Customer JSON template','system','system',
 TRUE,FALSE,FALSE,FALSE,NULL,FALSE,NULL,
 NULL,'jdbc_mapped'),
(50003,'default-tenant','imp_loan_csv_v1',       'Loan CSV Import',        'IMPORT','LOAN',       'DELIMITED','UTF-8','UTF-8',FALSE,',','"','\\',0,1,0,'SHA-256','GZIP','NONE','loan-${bizDate}',
 '{"loanNo":"loanNo","amount":"amount","currency":"currency","customerId":"customerId"}'::jsonb,
 '{"required":["loanNo","amount"],"maxErrors":200}'::jsonb,
 NULL,NULL,'{"jdbcMappedImport":{"table":"biz.loan_account","pkColumns":["loanNo"]}}'::jsonb,
 TRUE,1000,1000,500,TRUE,1,'Loan CSV template','system','system',
 FALSE,FALSE,FALSE,FALSE,NULL,FALSE,NULL,
 NULL,'jdbc_mapped'),
(50004,'default-tenant','imp_kyc_json_v1',       'KYC JSON Import',        'IMPORT','KYC',        'JSON',     'UTF-8','UTF-8',FALSE,NULL,NULL,NULL,0,0,0,'SHA-256','NONE','AES','kyc-${bizDate}',
 '{"docId":"docId","docType":"docType","customerId":"customerId","issueDate":"issueDate"}'::jsonb,
 '{"required":["docId","docType","customerId"],"maxErrors":0}'::jsonb,
 NULL,NULL,'{"jdbcMappedImport":{"table":"biz.kyc_document","pkColumns":["docId"]}}'::jsonb,
 TRUE,500,500,200,TRUE,1,'KYC JSON template — AES encrypted','system','system',
 TRUE,TRUE,TRUE,TRUE,'DEFAULT_TEST',TRUE,'PCI_BASIC',
 NULL,'jdbc_mapped'),
(50005,'default-tenant','imp_blacklist_csv_v1',  'Blacklist CSV Import',   'IMPORT','RISK',       'DELIMITED','UTF-8','UTF-8',FALSE,',','"','\\',0,1,0,'SHA-256','NONE','NONE','blacklist-${bizDate}',
 '{"idNo":"idNo","name":"name","reason":"reason","effectiveDate":"effectiveDate"}'::jsonb,
 '{"required":["idNo"],"maxErrors":0}'::jsonb,
 NULL,NULL,'{"jdbcMappedImport":{"table":"biz.blacklist","pkColumns":["idNo"]}}'::jsonb,
 TRUE,2000,2000,1000,TRUE,1,'Blacklist CSV template','system','system',
 TRUE,FALSE,TRUE,FALSE,NULL,FALSE,'RISK_BASIC',
 NULL,'jdbc_mapped'),
-- 导出模板 (5)
(50006,'default-tenant','exp_settlement_csv_v1', 'Settlement CSV Export',  'EXPORT','SETTLEMENT', 'DELIMITED','UTF-8','UTF-8',TRUE, ',','"','\\',0,1,0,'SHA-256','NONE','AES','settlement-${bizDate}',
 '{"settlementNo":"settlementNo","netAmount":"netAmount","currency":"currency","tradeDate":"tradeDate"}'::jsonb,
 '{"required":["settlementNo","netAmount"]}'::jsonb,
 'settlement_daily','SELECT s.* FROM biz.settlement s WHERE s.trade_date = #{bizDate}',
 '{"jdbcMappedExport":{"resultSetMapping":"settlementMapping"}}'::jsonb,
 TRUE,1000,1000,500,TRUE,1,'Settlement export CSV — AES encrypted + approval','system','system',
 TRUE,TRUE,TRUE,TRUE,'DEFAULT_TEST',TRUE,'PCI_FULL',
 'sql_template_export',NULL),
(50007,'default-tenant','exp_report_xlsx_v1',    'Monthly Report XLSX',    'EXPORT','REPORT',     'EXCEL',    'UTF-8','UTF-8',TRUE, NULL,NULL,NULL,0,1,0,'SHA-256','NONE','NONE','report-${period}',
 '{"metric":"metric","value":"value","unit":"unit","period":"period"}'::jsonb,
 NULL,
 'monthly_summary','SELECT r.* FROM biz.monthly_report r WHERE r.period = #{period}',
 NULL,
 TRUE,1000,1000,500,TRUE,1,'Monthly report XLSX','system','system',
 FALSE,FALSE,FALSE,FALSE,NULL,FALSE,NULL,
 'sql_template_export',NULL),
(50008,'default-tenant','exp_customer_csv_v1',   'Customer Extract CSV',   'EXPORT','CUSTOMER',   'DELIMITED','UTF-8','UTF-8',FALSE,',','"','\\',0,1,0,'SHA-256','GZIP','NONE','customer-extract-${bizDate}',
 '{"customerNo":"customerNo","customerName":"customerName","mobile":"mobile","status":"status"}'::jsonb,
 NULL,
 NULL,'SELECT c.* FROM biz.customer c WHERE c.updated_date >= #{bizDate}',
 NULL,
 TRUE,2000,2000,1000,TRUE,1,'Customer extract CSV — GZIP + approval required','system','system',
 TRUE,FALSE,FALSE,FALSE,NULL,TRUE,NULL,
 'sql_template_export',NULL),
(50009,'default-tenant','exp_audit_csv_v1',      'Audit Trail CSV',        'EXPORT','AUDIT',      'DELIMITED','UTF-8','UTF-8',FALSE,',','"','\\',0,1,0,'SHA-256','GZIP','NONE','audit-${bizDate}',
 '{"eventId":"eventId","eventType":"eventType","operatorId":"operatorId","createdAt":"createdAt"}'::jsonb,
 NULL,
 NULL,'SELECT a.* FROM biz.audit_log a WHERE a.created_date = #{bizDate}',
 NULL,
 TRUE,5000,5000,2000,TRUE,1,'Audit trail export CSV','system','system',
 FALSE,FALSE,FALSE,FALSE,NULL,FALSE,NULL,
 'sql_template_export',NULL),
(50010,'default-tenant','exp_balance_csv_v1',    'Balance Snapshot CSV',   'EXPORT','FINANCE',    'DELIMITED','UTF-8','UTF-8',TRUE, ',','"','\\',0,1,0,'SHA-256','NONE','AES','balance-${bizDate}',
 '{"accountNo":"accountNo","balance":"balance","currency":"currency","asOfDate":"asOfDate"}'::jsonb,
 '{"required":["accountNo","balance"]}'::jsonb,
 'balance_snapshot','SELECT b.* FROM biz.account_balance b WHERE b.as_of_date = #{bizDate}',
 '{"jdbcMappedExport":{"resultSetMapping":"balanceMapping"}}'::jsonb,
 TRUE,1000,1000,500,TRUE,1,'Balance snapshot CSV — AES encrypted + approval','system','system',
 TRUE,TRUE,TRUE,TRUE,'DEFAULT_TEST',TRUE,'PCI_FULL',
 'sql_template_export',NULL)
ON CONFLICT (id) DO UPDATE SET
    template_name=EXCLUDED.template_name, field_mappings=EXCLUDED.field_mappings,
    validation_rule_set=EXCLUDED.validation_rule_set,
    default_query_code=EXCLUDED.default_query_code,
    default_query_sql=EXCLUDED.default_query_sql,
    query_param_schema=EXCLUDED.query_param_schema,
    preview_masking_enabled=EXCLUDED.preview_masking_enabled,
    error_line_masking_enabled=EXCLUDED.error_line_masking_enabled,
    log_masking_enabled=EXCLUDED.log_masking_enabled,
    content_encryption_enabled=EXCLUDED.content_encryption_enabled,
    encryption_key_ref=EXCLUDED.encryption_key_ref,
    download_requires_approval=EXCLUDED.download_requires_approval,
    masking_rule_set=EXCLUDED.masking_rule_set,
    export_data_ref=EXCLUDED.export_data_ref,
    load_target_ref=EXCLUDED.load_target_ref,
    enabled=EXCLUDED.enabled, updated_by=EXCLUDED.updated_by,
    updated_at=current_timestamp;

-- ── 14. file_channel_config (7) ──────────────────────────────
INSERT INTO batch.file_channel_config (
    id, tenant_id, channel_code, channel_name, channel_type,
    target_endpoint, auth_type, config_json, receipt_policy,
    timeout_seconds, enabled
) VALUES
(51001,'default-tenant','api_dispatch',     'API Dispatch',    'API',     'http://partner.example.com/api/receive','TOKEN',    '{"headers":{"X-Tenant-Id":"default-tenant"},"maxRetry":3}'::jsonb,                                    'SYNC',   30,TRUE),
(51002,'default-tenant','api_push_dispatch','API Push',        'API_PUSH','http://partner.example.com/api/push',   'TOKEN',    '{"apiKey":"demo-key","receiptPollUrl":"http://partner.example.com/api/receipt","pollIntervalSec":10}'::jsonb,'ASYNC',  30,TRUE),
(51003,'default-tenant','sftp_bank',        'Bank SFTP',       'SFTP',    'sftp.bank.example.com',                 'PASSWORD', '{"port":22,"user":"batch","remoteDir":"/inbox","knownHostsRef":"bank_known_hosts"}'::jsonb,              'NONE',   45,TRUE),
(51004,'default-tenant','nas_archive',      'NAS Archive',     'NAS',     '/mnt/nas/batch/archive',                'NONE',     '{"remoteDir":"/mnt/nas/batch/archive","mkdirs":true}'::jsonb,                                         'SYNC',   30,TRUE),
(51005,'default-tenant','oss_backup',       'OSS Backup',      'OSS',     'https://oss.example.com',               'TOKEN',    '{"bucket":"batch-backup","prefix":"dispatch/","region":"cn-shanghai"}'::jsonb,                         'POLLING',30,TRUE),
(51006,'default-tenant','email_ops',        'Email Ops',       'EMAIL',   'ops@example.com',                       'PASSWORD', '{"smtpHost":"smtp.example.com","smtpPort":587,"from":"batch@example.com","tls":true}'::jsonb,            'SYNC',   45,TRUE),
(51007,'default-tenant','local_test',       'Local Test',      'LOCAL',   '/tmp/batch/dispatch',                   'NONE',     '{"targetDir":"/tmp/batch/dispatch","mkdirs":true}'::jsonb,                                            'NONE',   10,TRUE)
ON CONFLICT (id) DO UPDATE SET
    channel_name=EXCLUDED.channel_name, target_endpoint=EXCLUDED.target_endpoint,
    config_json=EXCLUDED.config_json, receipt_policy=EXCLUDED.receipt_policy,
    timeout_seconds=EXCLUDED.timeout_seconds, enabled=EXCLUDED.enabled,
    updated_at=current_timestamp;

-- ── 15. config_release (8) ───────────────────────────────────
INSERT INTO batch.config_release (
    id, tenant_id, config_type, config_key, config_name, config_status,
    version_no, gray_scope, config_payload, effective_from_at,
    effective_to_at, published_at, rolled_back_at, created_by, updated_by
) VALUES
(24001,'default-tenant','FILE_CHANNEL',  'api_dispatch',         'API Dispatch Config',          'PUBLISHED',  1,'{"tenantIds":["default-tenant"]}'::jsonb,       '{"channelCode":"api_dispatch","enabled":true}'::jsonb,                      now()-interval '7d', NULL,               now()-interval '7d',NULL,                  'system','system'),
(24002,'default-tenant','FILE_CHANNEL',  'sftp_bank',            'Bank SFTP Config',             'PUBLISHED',  2,'{"tenantIds":["default-tenant"]}'::jsonb,       '{"channelCode":"sftp_bank","timeout":45,"enabled":true}'::jsonb,            now()-interval '5d', NULL,               now()-interval '5d',NULL,                  'system','system'),
(24003,'default-tenant','JOB',           'imp_customer_csv',     'Import Customer Config',       'GRAY',       1,'{"workerGroups":["import"]}'::jsonb,            '{"retryPolicy":"EXPONENTIAL","maxRetry":3,"timeout":3600}'::jsonb,          now()-interval '2d', NULL,               NULL,               NULL,                  'admin', 'admin'),
(24004,'default-tenant','JOB',           'exp_settlement_daily', 'Settlement Export Config',     'PUBLISHED',  1,'{"tenantIds":["default-tenant"]}'::jsonb,       '{"pageSize":1000,"queryCode":"settlement_daily"}'::jsonb,                   now()-interval '10d',NULL,               now()-interval '10d',NULL,                 'system','system'),
(24005,'default-tenant','FILE_TEMPLATE', 'imp_customer_csv_v1',  'Customer CSV Template Config', 'PUBLISHED',  1,'{"tenantIds":["default-tenant"]}'::jsonb,       '{"templateCode":"imp_customer_csv_v1","maskingEnabled":true}'::jsonb,       now()-interval '14d',NULL,               now()-interval '14d',NULL,                 'system','system'),
(24006,'default-tenant','JOB',           'wf_eod_process',       'EOD Workflow Config',          'ROLLED_BACK',1,'{"tenantIds":["default-tenant"]}'::jsonb,       '{"timeout":14400,"retryPolicy":"EXPONENTIAL"}'::jsonb,                      now()-interval '3d', now()-interval '1d',now()-interval '3d', now()-interval '1d','admin', 'sre-lead'),
(24007,'default-tenant','FILE_CHANNEL',  'email_ops',            'Email Ops Config',             'DRAFT',      1,NULL,                                            '{"smtpHost":"smtp.example.com","smtpPort":587}'::jsonb,                     NULL,                NULL,               NULL,               NULL,                  'admin', 'admin'),
(24008,'default-tenant','JOB',           'gen_reconcile',        'Reconcile Config',             'PUBLISHED',  1,'{"tenantIds":["default-tenant"]}'::jsonb,       '{"mode":"full","timeout":7200}'::jsonb,                                    now()-interval '20d',NULL,               now()-interval '20d',NULL,                 'system','system')
ON CONFLICT (id) DO UPDATE SET
    config_name=EXCLUDED.config_name, config_status=EXCLUDED.config_status,
    config_payload=EXCLUDED.config_payload, gray_scope=EXCLUDED.gray_scope,
    effective_from_at=EXCLUDED.effective_from_at, published_at=EXCLUDED.published_at,
    rolled_back_at=EXCLUDED.rolled_back_at, updated_by=EXCLUDED.updated_by,
    updated_at=current_timestamp;

-- ── 16. secret_version (5) ───────────────────────────────────
INSERT INTO batch.secret_version (
    id, tenant_id, secret_ref, secret_name, version_no, secret_status,
    current_version, rotation_window_start_at, rotation_window_end_at,
    effective_from_at, effective_to_at, secret_payload, rotation_reason,
    created_by, updated_by
) VALUES
(25001,'default-tenant','DEFAULT_TEST','Default Test Key', 1,'PUBLISHED',TRUE, now()-interval '30d', now()+interval '335d',now()-interval '30d',NULL,  '{"keyRef":"DEFAULT_TEST","algo":"AES-256-GCM"}'::jsonb,  'bootstrap',     'system','system'),
(25002,'default-tenant','EXPORT_AES', 'Export AES Key',   1,'PUBLISHED',TRUE, now()-interval '60d', now()+interval '305d',now()-interval '60d',NULL,  '{"keyRef":"EXPORT_AES","algo":"AES-256-GCM"}'::jsonb,    'initial',       'system','system'),
(25003,'default-tenant','EXPORT_AES', 'Export AES Key v2',2,'GRAY',     FALSE,now()-interval '1d',  now()+interval '6d',  now()-interval '1d', NULL,  '{"keyRef":"EXPORT_AES_V2","algo":"AES-256-GCM"}'::jsonb, 'rotation-preview','admin','admin'),
(25004,'default-tenant','KYC_AES',    'KYC Encryption Key',1,'PUBLISHED',TRUE,now()-interval '90d', now()+interval '275d',now()-interval '90d',NULL,  '{"keyRef":"KYC_AES","algo":"AES-256-GCM"}'::jsonb,       'initial',       'system','system'),
(25005,'default-tenant','ARCHIVE_KEY','Archive Key',       1,'ROLLED_BACK',FALSE,now()-interval '180d',now()-interval '90d',now()-interval '180d',now()-interval '90d','{"keyRef":"ARCHIVE_KEY","algo":"AES-256-CBC"}'::jsonb,'expired','system','system')
ON CONFLICT (id) DO UPDATE SET
    secret_name=EXCLUDED.secret_name, secret_status=EXCLUDED.secret_status,
    current_version=EXCLUDED.current_version,
    rotation_window_start_at=EXCLUDED.rotation_window_start_at,
    rotation_window_end_at=EXCLUDED.rotation_window_end_at,
    secret_payload=EXCLUDED.secret_payload,
    updated_by=EXCLUDED.updated_by, updated_at=current_timestamp;

-- ── 17-20. 批量运行态数据（幂等：ON CONFLICT DO NOTHING）────────
DO $$
DECLARE
    ts_base TIMESTAMPTZ := TIMESTAMPTZ '2026-03-01 08:00:00+08';
    job_codes TEXT[] := ARRAY[
        'imp_customer_csv','imp_customer_json','imp_loan_batch','imp_kyc_doc','imp_blacklist',
        'exp_settlement_daily','exp_report_monthly','exp_customer_extract','exp_audit_report','exp_balance_snapshot',
        'disp_sftp_bank','disp_api_partner','disp_email_report','disp_nas_archive','disp_oss_backup',
        'gen_data_cleanup','gen_reconcile','gen_index_rebuild','gen_stats_collect','gen_archive_purge',
        'wf_eod_process','wf_month_close','wf_data_migration','wf_compliance_check','wf_onboarding'
    ];
    inst_statuses  TEXT[] := ARRAY['CREATED','WAITING','READY','RUNNING','RUNNING','RUNNING','SUCCESS','SUCCESS','SUCCESS','SUCCESS','FAILED','FAILED','CANCELLED','TERMINATED','PARTIAL_FAILED'];
    trigger_types  TEXT[] := ARRAY['API','SCHEDULED','MANUAL','EVENT','CATCH_UP'];
    task_statuses  TEXT[] := ARRAY['CREATED','READY','RUNNING','RUNNING','SUCCESS','SUCCESS','SUCCESS','FAILED','CANCELLED','TERMINATED'];
    file_statuses  TEXT[] := ARRAY['RECEIVED','PARSING','PARSED','VALIDATED','LOADED','GENERATED','DISPATCHING','DISPATCHED','DISPATCHED','ARCHIVED','FAILED','DELETED'];
    file_cats      TEXT[] := ARRAY['INPUT','INPUT','INPUT','INPUT','INPUT','OUTPUT','OUTPUT','OUTPUT','OUTPUT','ARCHIVE','INPUT','OUTPUT'];
    file_fmts      TEXT[] := ARRAY['DELIMITED','JSON','DELIMITED','JSON','DELIMITED','DELIMITED','EXCEL','DELIMITED','DELIMITED','DELIMITED','JSON','DELIMITED'];
    pipe_statuses  TEXT[] := ARRAY['CREATED','RUNNING','SUCCESS','SUCCESS','SUCCESS','FAILED'];
    step_run_statuses TEXT[] := ARRAY['PENDING','RUNNING','RUNNING','SUCCESS','SUCCESS','SUCCESS','FAILED','RETRYING','SKIPPED','PENDING'];
    wf_run_statuses TEXT[] := ARRAY['CREATED','RUNNING','RUNNING','SUCCESS','SUCCESS','FAILED','TERMINATED'];
    wf_node_statuses TEXT[] := ARRAY['READY','RUNNING','RUNNING','SUCCESS','SUCCESS','SUCCESS','FAILED','SKIPPED','READY','RUNNING'];
    approval_statuses TEXT[] := ARRAY['PENDING','PENDING','PENDING','APPROVED','APPROVED','APPROVED','REJECTED','REJECTED','EXECUTED','EXECUTED'];
    approval_types TEXT[] := ARRAY['COMPENSATION','DOWNLOAD','CATCH_UP','DLQ_REPLAY','COMPENSATION','DOWNLOAD','CATCH_UP','DLQ_REPLAY','COMPENSATION','DOWNLOAD'];
    alert_sevs     TEXT[] := ARRAY['INFO','WARN','WARN','ERROR','ERROR','CRITICAL'];
    alert_stats    TEXT[] := ARRAY['OPEN','OPEN','ACKED','SUPPRESSED','CLOSED','OPEN'];
    alert_types    TEXT[] := ARRAY['JOB_SLA_VIOLATION','DISPATCH_FAILURE','CHANNEL_UNHEALTHY','QUOTA_BURST','FILE_ARRIVAL_DELAY','WORKER_HEARTBEAT_LOST','PARTITION_RETRY_EXHAUSTED','DEAD_LETTER_CREATED','PIPELINE_TIMEOUT','OUTBOX_GIVE_UP'];
    outbox_statuses TEXT[] := ARRAY['NEW','NEW','PUBLISHING','PUBLISHED','PUBLISHED','PUBLISHED','FAILED','GIVE_UP'];
    outbox_retry_statuses TEXT[] := ARRAY['WAITING','WAITING','FAILED','EXHAUSTED','CANCELLED'];
    delivery_statuses TEXT[] := ARRAY['PUBLISHED','PUBLISHED','PUBLISHED','FAILED','PUBLISHED'];
    dlq_statuses   TEXT[] := ARRAY['NEW','NEW','NEW','FAILED','SUCCESS','GIVE_UP'];
    retry_statuses TEXT[] := ARRAY['WAITING','WAITING','WAITING','FAILED','EXHAUSTED'];
BEGIN

-- trigger_request (58: 50 LAUNCHED + 5 ACCEPTED + 3 REJECTED)
INSERT INTO batch.trigger_request (
    id, tenant_id, request_id, trigger_type, job_code, biz_date,
    dedup_key, request_payload_hash, request_status,
    related_job_instance_id, trace_id, created_at, updated_at
)
SELECT
    30000 + n, 'default-tenant',
    'req-demo-' || lpad(n::text,3,'0'),
    trigger_types[((n-1) % 5) + 1],
    job_codes[((n-1) % 25) + 1],
    DATE '2026-03-01' + ((n-1) % 29),
    'default-tenant:req-demo-' || lpad(n::text,3,'0'),
    md5('req-demo-' || n),
    CASE WHEN n<=50 THEN 'LAUNCHED' WHEN n<=55 THEN 'ACCEPTED' ELSE 'REJECTED' END,
    CASE WHEN n<=50 THEN 40000+n ELSE NULL END,
    'trace-demo-' || lpad(n::text,3,'0'),
    ts_base + (n * interval '8 hour'),
    ts_base + (n * interval '8 hour')
FROM generate_series(1,58) AS n
ON CONFLICT (id) DO NOTHING;

-- job_instance (50)
INSERT INTO batch.job_instance (
    id, tenant_id, job_definition_id, trigger_request_id, job_code,
    instance_no, biz_date, trigger_type, instance_status,
    queue_code, worker_group, priority, dedup_key, version,
    expected_partition_count, success_partition_count, failed_partition_count,
    trace_id, params_snapshot, deadline_at, expected_duration_seconds, sla_alerted_at,
    started_at, finished_at,
    batch_no, operator_id, rerun_flag, retry_flag, rerun_reason,
    related_file_id, parent_instance_id, result_summary,
    created_at, updated_at
)
SELECT
    40000+n, 'default-tenant',
    20000 + ((n-1) % 25) + 1,
    30000+n,
    job_codes[((n-1) % 25) + 1],
    'INST-' || to_char(DATE '2026-03-01'+((n-1)%29),'YYYYMMDD') || '-' || lpad(n::text,3,'0'),
    DATE '2026-03-01' + ((n-1) % 29),
    trigger_types[((n-1) % 5) + 1],
    inst_statuses[((n-1) % 15) + 1],
    CASE WHEN ((n-1)%25)<5  THEN 'import_queue'
         WHEN ((n-1)%25)<10 THEN 'export_queue'
         WHEN ((n-1)%25)<15 THEN 'dispatch_queue'
         WHEN ((n-1)%25)<20 THEN 'general_queue'
         ELSE 'workflow_queue' END,
    CASE WHEN ((n-1)%25)<5  THEN 'import'
         WHEN ((n-1)%25)<10 THEN 'export'
         WHEN ((n-1)%25)<15 THEN 'dispatch'
         WHEN ((n-1)%25)<20 THEN 'general'
         ELSE 'export' END,
    ((n-1)%5)+1,
    'default-tenant:INST-' || lpad(n::text,3,'0'),
    n%3,
    2,
    CASE WHEN inst_statuses[((n-1)%15)+1]='SUCCESS' THEN 2 WHEN inst_statuses[((n-1)%15)+1]='PARTIAL_FAILED' THEN 1 ELSE 0 END,
    CASE WHEN inst_statuses[((n-1)%15)+1] IN ('FAILED','PARTIAL_FAILED') THEN 1 ELSE 0 END,
    'trace-demo-' || lpad(n::text,3,'0'),
    jsonb_build_object('jobCode',job_codes[((n-1)%25)+1],'bizDate',(DATE '2026-03-01'+((n-1)%29))::text,'seq',n),
    ts_base + (n * interval '8 hour') + interval '8 hour',
    3600,
    CASE WHEN inst_statuses[((n-1)%15)+1]='SUCCESS' AND n%7=0
         THEN ts_base+(n*interval '8 hour')+interval '9 hour' ELSE NULL END,
    ts_base+(n*interval '8 hour'),
    CASE WHEN inst_statuses[((n-1)%15)+1] IN ('SUCCESS','FAILED','CANCELLED','TERMINATED')
         THEN ts_base+(n*interval '8 hour')+interval '20 min' ELSE NULL END,
    'BATCH-' || to_char(DATE '2026-03-01'+((n-1)%29),'YYYYMMDD') || '-' || lpad(n::text,3,'0'),
    CASE WHEN n%3=0 THEN 'admin' ELSE 'ops-user' END,
    n%7=0, n%5=0,
    CASE WHEN n%7=0 THEN 'rerun requested by admin' ELSE NULL END,
    CASE WHEN ((n-1)%25)<15 THEN 52000+n ELSE NULL END,
    NULL,
    CASE WHEN inst_statuses[((n-1)%15)+1] IN ('SUCCESS','FAILED','PARTIAL_FAILED')
         THEN jsonb_build_object('result',inst_statuses[((n-1)%15)+1],'partitions',2,'successCount',
              CASE WHEN inst_statuses[((n-1)%15)+1]='SUCCESS' THEN 2 WHEN inst_statuses[((n-1)%15)+1]='PARTIAL_FAILED' THEN 1 ELSE 0 END)
         ELSE NULL END,
    ts_base+(n*interval '8 hour'),
    ts_base+(n*interval '8 hour')+interval '5 min'
FROM generate_series(1,50) AS n
ON CONFLICT (id) DO NOTHING;

-- job_partition (60) — 含 version (V32)
INSERT INTO batch.job_partition (
    id, tenant_id, job_instance_id, partition_no, partition_key,
    partition_status, worker_group, worker_code, lease_expire_at,
    retry_count, business_key, idempotency_key,
    input_snapshot, output_summary, started_at, finished_at,
    version, created_at, updated_at
)
SELECT
    41000+n, 'default-tenant',
    40000+((n-1)/2)+1,
    ((n-1)%2)+1,
    'part-' || ((n-1)%2+1),
    task_statuses[((n-1)%10)+1],
    CASE WHEN ((n-1)/2+1)%5<2 THEN 'import' WHEN ((n-1)/2+1)%5<4 THEN 'export' ELSE 'dispatch' END,
    CASE WHEN task_statuses[((n-1)%10)+1] IN ('RUNNING','SUCCESS','FAILED')
         THEN 'import-node-' || (n%2+1) ELSE NULL END,
    CASE WHEN task_statuses[((n-1)%10)+1]='RUNNING'
         THEN now()+interval '30 min' ELSE NULL END,
    CASE WHEN task_statuses[((n-1)%10)+1]='FAILED' THEN 1 ELSE 0 END,
    'BIZ-' || lpad(n::text,4,'0'),
    'default-tenant:PART-' || lpad(n::text,4,'0'),
    jsonb_build_object('partNo',((n-1)%2)+1,'bizDate',(DATE '2026-03-01'+((n-1)%29))::text),
    CASE WHEN task_statuses[((n-1)%10)+1]='SUCCESS'
         THEN jsonb_build_object('rows',100+n,'durationMs',(200+n*50)) ELSE NULL END,
    ts_base+(n*interval '4 hour'),
    CASE WHEN task_statuses[((n-1)%10)+1] IN ('SUCCESS','FAILED')
         THEN ts_base+(n*interval '4 hour')+interval '10 min' ELSE NULL END,
    n%3,
    ts_base+(n*interval '4 hour'),
    ts_base+(n*interval '4 hour')+interval '1 min'
FROM generate_series(1,60) AS n
ON CONFLICT (id) DO NOTHING;

-- job_task (60) — 含 version (V32)
INSERT INTO batch.job_task (
    id, tenant_id, job_instance_id, job_partition_id, task_type,
    task_seq, task_status, assigned_worker_code,
    task_payload, result_summary, error_code, error_message,
    started_at, finished_at, version, created_at, updated_at
)
SELECT
    42000+n, 'default-tenant',
    40000+((n-1)/2)+1,
    41000+n,
    CASE WHEN n%5=0 THEN 'COMPENSATION' WHEN n%7=0 THEN 'REPLAY' ELSE 'EXECUTION' END,
    1,
    task_statuses[((n-1)%10)+1],
    CASE WHEN task_statuses[((n-1)%10)+1] IN ('RUNNING','SUCCESS','FAILED')
         THEN 'import-node-' || (n%2+1) ELSE NULL END,
    jsonb_build_object('taskSeq',n,'type',CASE WHEN n%5=0 THEN 'comp' WHEN n%7=0 THEN 'replay' ELSE 'exec' END,
                       'partitionKey','part-'||((n-1)%2+1)),
    CASE WHEN task_statuses[((n-1)%10)+1]='SUCCESS'
         THEN jsonb_build_object('processed',100+n,'durationMs',(300+n*60)) ELSE NULL END,
    CASE WHEN task_statuses[((n-1)%10)+1]='FAILED' THEN 'TASK_EXEC_ERROR' ELSE NULL END,
    CASE WHEN task_statuses[((n-1)%10)+1]='FAILED' THEN 'Execution failed at step '||n ELSE NULL END,
    ts_base+(n*interval '4 hour'),
    CASE WHEN task_statuses[((n-1)%10)+1] IN ('SUCCESS','FAILED')
         THEN ts_base+(n*interval '4 hour')+interval '8 min' ELSE NULL END,
    n%3,
    ts_base+(n*interval '4 hour'),
    ts_base+(n*interval '4 hour')+interval '1 min'
FROM generate_series(1,60) AS n
ON CONFLICT (id) DO NOTHING;

-- job_step_instance (50)
INSERT INTO batch.job_step_instance (
    id, tenant_id, job_instance_id, job_partition_id, job_task_id,
    step_code, step_type, step_status, retry_count,
    related_file_id, result_summary, error_code, error_message,
    version, started_at, finished_at, created_at, updated_at
)
SELECT
    43000+n, 'default-tenant',
    40000+((n-1)/2)+1,
    41000+n,
    42000+n,
    (ARRAY['receive','parse','validate','load'])[(n-1)%4+1],
    (ARRAY['RECEIVE','PARSE','VALIDATE','LOAD'])[(n-1)%4+1],
    task_statuses[((n-1)%10)+1],
    CASE WHEN task_statuses[((n-1)%10)+1]='FAILED' THEN 1 ELSE 0 END,
    52000+n,
    CASE WHEN task_statuses[((n-1)%10)+1]='SUCCESS'
         THEN jsonb_build_object('rows',50+n,'durationMs',(150+n*30)) ELSE NULL END,
    CASE WHEN task_statuses[((n-1)%10)+1]='FAILED' THEN 'STEP_FAILED' ELSE NULL END,
    CASE WHEN task_statuses[((n-1)%10)+1]='FAILED' THEN 'Step failed at record '||(n*10) ELSE NULL END,
    0,
    ts_base+(n*interval '4 hour'),
    CASE WHEN task_statuses[((n-1)%10)+1] IN ('SUCCESS','FAILED')
         THEN ts_base+(n*interval '4 hour')+interval '5 min' ELSE NULL END,
    ts_base+(n*interval '4 hour'),
    ts_base+(n*interval '4 hour')+interval '1 min'
FROM generate_series(1,50) AS n
ON CONFLICT (id) DO NOTHING;

-- file_record (45) — 所有列完整填充
INSERT INTO batch.file_record (
    id, tenant_id, file_code, biz_type, file_category, file_name,
    original_file_name, file_ext, file_format_type, charset, mime_type,
    file_size_bytes, checksum_type, checksum_value, storage_type,
    storage_path, storage_bucket, file_version, file_generation_no,
    is_latest, source_type, source_ref, file_status, biz_date,
    trace_id, metadata_json, created_at, updated_at
)
SELECT
    52000+n, 'default-tenant',
    'FILE-DEMO-' || lpad(n::text,3,'0'),
    (ARRAY['CUSTOMER','SETTLEMENT','LOAN','KYC','RISK','REPORT','AUDIT','FINANCE'])[(n-1)%8+1],
    file_cats[((n-1)%12)+1],
    'demo-file-' || lpad(n::text,3,'0') || '.' || (ARRAY['csv','json','xlsx','csv','csv','csv','xlsx','csv','csv','csv','json','csv'])[(n-1)%12+1],
    'demo-file-' || lpad(n::text,3,'0') || '.' || (ARRAY['csv','json','xlsx','csv','csv','csv','xlsx','csv','csv','csv','json','csv'])[(n-1)%12+1],
    (ARRAY['csv','json','xlsx','csv','csv','csv','xlsx','csv','csv','csv','json','csv'])[(n-1)%12+1],
    file_fmts[((n-1)%12)+1],
    'UTF-8',
    CASE WHEN (n-1)%12 IN (2,6) THEN 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
         WHEN (n-1)%12 IN (1,10) THEN 'application/json'
         ELSE 'text/csv' END,
    1024*(n%50+1),
    'SHA-256', md5('file-'||n),
    CASE WHEN n%3=0 THEN 'LOCAL' ELSE 'S3' END,
    CASE WHEN n%3=0 THEN '/tmp/batch/demo-file-'||n ELSE 'ingress/demo/demo-file-'||lpad(n::text,3,'0') END,
    CASE WHEN n%3!=0 THEN 'batch-dev' ELSE NULL END,
    'v1', 1, n%5!=0,
    CASE WHEN n%4=0 THEN 'SYSTEM' WHEN n%4=1 THEN 'API' WHEN n%4=2 THEN 'GENERATED' ELSE 'UPLOAD' END,
    'source-ref-'||n,
    file_statuses[((n-1)%12)+1],
    DATE '2026-03-01'+((n-1)%29),
    'trace-demo-'||lpad(n::text,3,'0'),
    jsonb_build_object(
        'templateCode',(ARRAY['imp_customer_csv_v1','imp_customer_json_v1','imp_loan_csv_v1','exp_settlement_csv_v1','exp_report_xlsx_v1'])[(n-1)%5+1],
        'fileGroupCode','group-'||((n-1)/5+1),
        'waitFileGroupMode','ALL_OF',
        'requiredFileSet','demo-file-'||lpad(n::text,3,'0')||'.csv',
        'seq',n
    ),
    ts_base+(n*interval '6 hour'),
    ts_base+(n*interval '6 hour')+interval '3 min'
FROM generate_series(1,45) AS n
ON CONFLICT (id) DO NOTHING;

-- pipeline_instance (35)
INSERT INTO batch.pipeline_instance (
    id, tenant_id, pipeline_definition_id, job_code, pipeline_type,
    file_id, related_job_instance_id, current_stage, last_success_stage,
    run_status, trace_id, started_at, finished_at, created_at, updated_at
)
SELECT
    53000+n, 'default-tenant',
    46000+((n-1)%8)+1,
    job_codes[((n-1)%25)+1],
    (ARRAY['IMPORT','IMPORT','EXPORT','EXPORT','DISPATCH','IMPORT','EXPORT','DISPATCH'])[(n-1)%8+1],
    52000+n,
    40000+n,
    (ARRAY['RECEIVE','PARSE','VALIDATE','LOAD','GENERATE','TRANSFER','DISPATCH','ACK'])[(n-1)%8+1],
    CASE WHEN n>3 THEN (ARRAY['RECEIVE','PARSE','VALIDATE','LOAD','GENERATE','TRANSFER','DISPATCH','ACK'])[GREATEST(((n-1)%8),1)] ELSE NULL END,
    pipe_statuses[((n-1)%6)+1],
    'trace-demo-'||lpad(n::text,3,'0'),
    ts_base+(n*interval '6 hour'),
    CASE WHEN pipe_statuses[((n-1)%6)+1] IN ('SUCCESS','FAILED')
         THEN ts_base+(n*interval '6 hour')+interval '15 min' ELSE NULL END,
    ts_base+(n*interval '6 hour'),
    ts_base+(n*interval '6 hour')+interval '2 min'
FROM generate_series(1,35) AS n
ON CONFLICT (id) DO NOTHING;

-- pipeline_step_run (70)
INSERT INTO batch.pipeline_step_run (
    id, pipeline_instance_id, step_code, stage_code, run_seq,
    step_status, input_summary, output_summary, error_code,
    error_message, retry_count, duration_ms, started_at, finished_at
)
SELECT
    54000+n,
    53000+((n-1)/2)+1,
    (ARRAY['receive','parse','validate','load','generate','store','dispatch','register'])[(n-1)%8+1],
    (ARRAY['RECEIVE','PARSE','VALIDATE','LOAD','GENERATE','TRANSFER','DISPATCH','ACK'])[(n-1)%8+1],
    1,
    step_run_statuses[((n-1)%10)+1],
    jsonb_build_object('stepNo',n,'inputRows',100+n),
    CASE WHEN step_run_statuses[((n-1)%10)+1]='SUCCESS'
         THEN jsonb_build_object('rows',100+n,'sizeBytes',1024*(n%50+1)) ELSE NULL END,
    CASE WHEN step_run_statuses[((n-1)%10)+1]='FAILED' THEN 'STEP_ERROR' ELSE NULL END,
    CASE WHEN step_run_statuses[((n-1)%10)+1]='FAILED' THEN 'Pipeline step failed at record '||(n*10) ELSE NULL END,
    CASE WHEN step_run_statuses[((n-1)%10)+1]='RETRYING' THEN 1 ELSE 0 END,
    200+n*50,
    ts_base+(n*interval '3 hour'),
    CASE WHEN step_run_statuses[((n-1)%10)+1] IN ('SUCCESS','FAILED')
         THEN ts_base+(n*interval '3 hour')+interval '3 min' ELSE NULL END
FROM generate_series(1,70) AS n
ON CONFLICT (id) DO NOTHING;

-- file_dispatch_record (35)
INSERT INTO batch.file_dispatch_record (
    id, tenant_id, file_id, pipeline_instance_id, channel_code,
    dispatch_target, dispatch_status, dispatch_attempt,
    receipt_code, receipt_status, external_request_id,
    error_code, error_message, dispatched_at, ack_at,
    created_at, updated_at
)
SELECT
    55000+n, 'default-tenant',
    52000+n,
    53000+((n-1)/2)+1,
    (ARRAY['api_dispatch','api_push_dispatch','sftp_bank','nas_archive','oss_backup','email_ops','local_test'])[(n-1)%7+1],
    CASE (n-1)%7
        WHEN 0 THEN 'http://partner.example.com/api/receive'
        WHEN 1 THEN 'http://partner.example.com/api/push'
        WHEN 2 THEN 'sftp.bank.example.com:/inbox/demo-file-'||n||'.csv'
        WHEN 3 THEN '/mnt/nas/batch/archive/demo-file-'||n||'.csv'
        WHEN 4 THEN 'oss://batch-backup/dispatch/demo-file-'||n||'.csv'
        WHEN 5 THEN 'ops@example.com'
        ELSE '/tmp/batch/dispatch/demo-file-'||n||'.csv' END,
    (ARRAY['SENT','ACKED','ACKED','FAILED','SENT','ACKED','FAILED'])[(n-1)%7+1],
    CASE WHEN (n-1)%7 IN (3,6) THEN 2 ELSE 1 END,
    'R-'||lpad(n::text,4,'0'),
    CASE WHEN (n-1)%7 IN (1,2,5) THEN 'SUCCESS' WHEN (n-1)%7 IN (3,6) THEN 'FAILED' ELSE 'PENDING' END,
    'EXT-'||lpad(n::text,4,'0'),
    CASE WHEN (n-1)%7 IN (3,6) THEN 'DISPATCH_ERROR' ELSE NULL END,
    CASE WHEN (n-1)%7 IN (3,6) THEN 'Dispatch failed: connection refused / timeout' ELSE NULL END,
    ts_base+(n*interval '5 hour'),
    CASE WHEN (n-1)%7 IN (1,2,5) THEN ts_base+(n*interval '5 hour')+interval '2 min' ELSE NULL END,
    ts_base+(n*interval '5 hour'),
    ts_base+(n*interval '5 hour')+interval '1 min'
FROM generate_series(1,35) AS n
ON CONFLICT (id) DO NOTHING;

-- workflow_run (25)
INSERT INTO batch.workflow_run (
    id, tenant_id, workflow_definition_id, related_job_instance_id,
    biz_date, run_status, current_node_code, trace_id,
    started_at, finished_at, created_at, updated_at
)
SELECT
    44000+n, 'default-tenant',
    21000+((n-1)%10)+1,
    40000+n,
    DATE '2026-03-01'+((n-1)%29),
    wf_run_statuses[((n-1)%7)+1],
    (ARRAY['SETTLE','RECONCILE','END','END','END','REPORT','START'])[(n-1)%7+1],
    'trace-demo-'||lpad(n::text,3,'0'),
    ts_base+(n*interval '10 hour'),
    CASE WHEN wf_run_statuses[((n-1)%7)+1] IN ('SUCCESS','FAILED','TERMINATED')
         THEN ts_base+(n*interval '10 hour')+interval '30 min' ELSE NULL END,
    ts_base+(n*interval '10 hour'),
    ts_base+(n*interval '10 hour')+interval '5 min'
FROM generate_series(1,25) AS n
ON CONFLICT (id) DO NOTHING;

-- workflow_node_run (50)
INSERT INTO batch.workflow_node_run (
    id, workflow_run_id, node_code, node_type, run_seq,
    node_status, retry_count, error_code, error_message,
    started_at, finished_at, duration_ms
)
SELECT
    45000+n,
    44000+((n-1)/2)+1,
    (ARRAY['START','SETTLE','DISPATCH','END','RECONCILE','REPORT','ARCHIVE'])[(n-1)%7+1],
    (ARRAY['START','TASK','TASK','END','TASK','FILE_STEP','TASK'])[(n-1)%7+1],
    1,
    wf_node_statuses[((n-1)%10)+1],
    CASE WHEN wf_node_statuses[((n-1)%10)+1]='FAILED' THEN 1 ELSE 0 END,
    CASE WHEN wf_node_statuses[((n-1)%10)+1]='FAILED' THEN 'NODE_ERROR' ELSE NULL END,
    CASE WHEN wf_node_statuses[((n-1)%10)+1]='FAILED' THEN 'Node execution failed: downstream timeout' ELSE NULL END,
    ts_base+(n*interval '5 hour'),
    CASE WHEN wf_node_statuses[((n-1)%10)+1] IN ('SUCCESS','FAILED')
         THEN ts_base+(n*interval '5 hour')+interval '10 min' ELSE NULL END,
    CASE WHEN wf_node_statuses[((n-1)%10)+1] IN ('SUCCESS','FAILED') THEN 600000+n*1000 ELSE 0 END
FROM generate_series(1,50) AS n
ON CONFLICT (id) DO NOTHING;

-- outbox_event (30)
INSERT INTO batch.outbox_event (
    id, tenant_id, aggregate_type, aggregate_id, event_type,
    event_key, payload_json, publish_status, publish_attempt,
    next_publish_at, trace_id, created_at, updated_at
)
SELECT
    60000+n, 'default-tenant',
    (ARRAY['JOB_TASK','JOB_TASK','PIPELINE_INSTANCE','FILE_DISPATCH'])[(n-1)%4+1],
    42000+n,
    (ARRAY['TASK_DISPATCH','TASK_RETRY','PIPELINE_RETRY','FILE_DISPATCH_RETRY'])[(n-1)%4+1],
    'default-tenant:outbox-demo-'||lpad(n::text,3,'0'),
    jsonb_build_object('seq',n,'event','demo','aggregateId',42000+n),
    outbox_statuses[((n-1)%8)+1],
    CASE WHEN outbox_statuses[((n-1)%8)+1]='PUBLISHED' THEN 1
         WHEN outbox_statuses[((n-1)%8)+1]='GIVE_UP' THEN 3
         WHEN outbox_statuses[((n-1)%8)+1]='FAILED' THEN 1
         ELSE 0 END,
    CASE WHEN outbox_statuses[((n-1)%8)+1] IN ('NEW','FAILED')
         THEN now()+(n*interval '5 min') ELSE NULL END,
    'trace-demo-'||lpad(n::text,3,'0'),
    ts_base+(n*interval '7 hour'),
    ts_base+(n*interval '7 hour')+interval '1 min'
FROM generate_series(1,30) AS n
ON CONFLICT (id) DO NOTHING;

-- event_outbox_retry (25)
INSERT INTO batch.event_outbox_retry (
    id, tenant_id, outbox_event_id, event_key, retry_attempt,
    retry_status, retry_reason, next_retry_at, trace_id,
    created_at, updated_at
)
SELECT
    60500+n, 'default-tenant',
    60000+n,
    'default-tenant:outbox-demo-'||lpad(n::text,3,'0'),
    ((n-1)%3)+1,
    outbox_retry_statuses[((n-1)%5)+1],
    'Publish attempt failed: '||(ARRAY['broker timeout','connection refused','serialization error','partition leader unavailable','unknown topic'])[(n-1)%5+1],
    CASE WHEN (n-1)%5<2 THEN now()+(n*interval '3 min') ELSE NULL END,
    'trace-demo-'||lpad(n::text,3,'0'),
    ts_base+(n*interval '7 hour'),
    ts_base+(n*interval '7 hour')+interval '30s'
FROM generate_series(1,25) AS n
ON CONFLICT (id) DO NOTHING;

-- event_delivery_log (35)
INSERT INTO batch.event_delivery_log (
    id, tenant_id, outbox_event_id, event_type, event_key,
    target_topic, target_worker_id, delivery_status, delivery_attempt,
    delivery_summary, error_message, trace_id, created_at, updated_at
)
SELECT
    60800+n, 'default-tenant',
    60000+((n-1)%30)+1,
    (ARRAY['TASK_DISPATCH','TASK_RETRY','PIPELINE_RETRY','FILE_DISPATCH_RETRY'])[(n-1)%4+1],
    'default-tenant:outbox-demo-'||lpad(((n-1)%30+1)::text,3,'0'),
    'batch.task.dispatch.'||(ARRAY['import','export','dispatch','import'])[(n-1)%4+1],
    CASE WHEN n%2=0 THEN 'import-node-1' ELSE NULL END,
    delivery_statuses[((n-1)%5)+1],
    1,
    jsonb_build_object('deliverySeq',n,'topic','batch.task.dispatch.'||(ARRAY['import','export','dispatch','import'])[(n-1)%4+1]),
    CASE WHEN (n-1)%5=3 THEN 'Kafka send timeout after 30s' ELSE NULL END,
    'trace-demo-'||lpad(n::text,3,'0'),
    ts_base+(n*interval '5 hour'),
    ts_base+(n*interval '5 hour')+interval '1s'
FROM generate_series(1,35) AS n
ON CONFLICT (id) DO NOTHING;

-- file_audit_log (50)
INSERT INTO batch.file_audit_log (
    id, tenant_id, file_id, operation_type, operation_result,
    operator_type, operator_id, trace_id, evidence_ref,
    detail_summary, created_at
)
SELECT
    56000+n, 'default-tenant',
    52000+((n-1)%45)+1,
    (ARRAY['RECEIVE','VALIDATE','GENERATE','DISPATCH','CLEANUP','DOWNLOAD','ARCHIVE','REDISPATCH'])[(n-1)%8+1],
    (ARRAY['SUCCESS','SUCCESS','SUCCESS','FAILED','SUCCESS','SUCCESS','SUCCESS','FAILED'])[(n-1)%8+1],
    CASE WHEN n%3=0 THEN 'USER' ELSE 'SYSTEM' END,
    CASE WHEN n%3=0 THEN 'admin' ELSE 'system-worker' END,
    'trace-demo-'||lpad(n::text,3,'0'),
    'evidence-'||n,
    jsonb_build_object('auditSeq',n,'detail','File audit record '||n,
        'operation',(ARRAY['RECEIVE','VALIDATE','GENERATE','DISPATCH','CLEANUP','DOWNLOAD','ARCHIVE','REDISPATCH'])[(n-1)%8+1]),
    ts_base+(n*interval '4 hour')
FROM generate_series(1,50) AS n
ON CONFLICT (id) DO NOTHING;

-- file_error_record (30)
INSERT INTO batch.file_error_record (
    id, tenant_id, file_id, pipeline_instance_id, pipeline_step_run_id,
    record_no, error_code, error_message, error_stage,
    is_skipped, skip_action, raw_record, created_at
)
SELECT
    68000+n, 'default-tenant',
    52000+((n-1)%45)+1,
    53000+((n-1)%35)+1,
    54000+((n-1)%70)+1,
    n*10,
    (ARRAY['FIELD_REQUIRED','FORMAT_INVALID','DUPLICATE_KEY','CONSTRAINT_VIOLATION','DATA_TRUNCATED'])[(n-1)%5+1],
    (ARRAY['Required field customerNo is null','Date format invalid: expected yyyy-MM-dd','Duplicate primary key: customerNo=C'||n,'FK constraint violated: customerId not found','Data too long for column name (max 256)'])[(n-1)%5+1],
    (ARRAY['PARSE','VALIDATE','VALIDATE','LOAD','LOAD'])[(n-1)%5+1],
    n%3=0,
    CASE WHEN n%3=0 THEN 'SKIP_AND_LOG' ELSE NULL END,
    jsonb_build_object('line',n*10,'raw','col1,col2,col3,demo-data-'||n,'errorCol',(ARRAY['customerNo','bizDate','customerNo','customerId','name'])[(n-1)%5+1]),
    ts_base+(n*interval '6 hour')
FROM generate_series(1,30) AS n
ON CONFLICT (id) DO NOTHING;

-- job_execution_log (50)
INSERT INTO batch.job_execution_log (
    id, tenant_id, job_instance_id, job_partition_id, log_level,
    log_type, trace_id, message, detail_ref, extra_json, created_at
)
SELECT
    57000+n, 'default-tenant',
    40000+((n-1)%50)+1,
    41000+((n-1)%60)+1,
    (ARRAY['INFO','INFO','WARN','ERROR','INFO','INFO','WARN','ERROR'])[(n-1)%8+1],
    (ARRAY['AUDIT','BUSINESS','ALARM','RETRY','AUDIT','BUSINESS','ALARM','RETRY'])[(n-1)%8+1],
    'trace-demo-'||lpad(((n-1)%50+1)::text,3,'0'),
    (ARRAY[
        'Job started successfully',
        'Processing partition complete: '||n||' rows',
        'SLA deadline approaching in 30 min',
        'Retry scheduled for failed task',
        'Export file generated: size='||(1024*(n%50+1))||'B',
        'Dispatch completed to channel api_dispatch',
        'Worker heartbeat delayed >60s on import-node-'||(n%2+1),
        'Partition execution failed, scheduling retry #'||(n%3+1)
    ])[(n-1)%8+1],
    'log-ref-'||n,
    jsonb_build_object('logSeq',n,'partitionId',41000+((n-1)%60)+1),
    ts_base+(n*interval '3 hour')
FROM generate_series(1,50) AS n
ON CONFLICT (id) DO NOTHING;

-- retry_schedule (25)
INSERT INTO batch.retry_schedule (
    id, tenant_id, related_type, related_id, retry_policy,
    retry_count, max_retry_count, next_retry_at, retry_status,
    dedup_key, last_error_code, last_error_message,
    created_at, updated_at
)
SELECT
    58000+n, 'default-tenant',
    (ARRAY['JOB_PARTITION','JOB_TASK','PIPELINE_INSTANCE','FILE_DISPATCH'])[(n-1)%4+1],
    41000+n,
    (ARRAY['FIXED','EXPONENTIAL','FIXED','EXPONENTIAL'])[(n-1)%4+1],
    ((n-1)%3)+1,
    3,
    now()+(n*interval '10 min'),
    retry_statuses[((n-1)%5)+1],
    'default-tenant:retry-'||lpad(n::text,3,'0'),
    (ARRAY['TASK_TIMEOUT','IMPORT_ERROR','EXPORT_FAILED','DISPATCH_ERROR'])[(n-1)%4+1],
    'Last attempt failed: '||(ARRAY['timeout after 60s','parse error at line '||(n*10),'generate failed: disk full','channel down: connection refused'])[(n-1)%4+1],
    ts_base+(n*interval '8 hour'),
    ts_base+(n*interval '8 hour')+interval '2 min'
FROM generate_series(1,25) AS n
ON CONFLICT (id) DO NOTHING;

-- dead_letter_task (25)
INSERT INTO batch.dead_letter_task (
    id, tenant_id, source_type, source_id, dead_letter_reason,
    payload_ref, replay_status, replay_count, last_replay_at,
    last_replay_result, trace_id, created_at, updated_at
)
SELECT
    59000+n, 'default-tenant',
    (ARRAY['JOB_PARTITION','JOB_TASK','PIPELINE_INSTANCE','FILE_DISPATCH'])[(n-1)%4+1],
    41000+n,
    (ARRAY['Max retries exceeded after 3 attempts','Validation permanently failed: no valid records','Channel rejected payload: 422 Unprocessable Entity','Worker crashed during execution'])[(n-1)%4+1],
    'dlq/'||(ARRAY['partition','task','pipeline','dispatch'])[(n-1)%4+1]||'/'||(41000+n)||'.json',
    dlq_statuses[((n-1)%6)+1],
    CASE WHEN dlq_statuses[((n-1)%6)+1] IN ('FAILED','SUCCESS','GIVE_UP') THEN ((n-1)%3)+1 ELSE 0 END,
    CASE WHEN dlq_statuses[((n-1)%6)+1] IN ('FAILED','SUCCESS','GIVE_UP')
         THEN ts_base+(n*interval '8 hour')+interval '30 min' ELSE NULL END,
    CASE WHEN dlq_statuses[((n-1)%6)+1]='SUCCESS' THEN 'REPLAY_SUCCESS'
         WHEN dlq_statuses[((n-1)%6)+1]='FAILED' THEN 'REPLAY_FAILED'
         WHEN dlq_statuses[((n-1)%6)+1]='GIVE_UP' THEN 'REPLAY_GIVE_UP'
         ELSE NULL END,
    'trace-demo-'||lpad(n::text,3,'0'),
    ts_base+(n*interval '8 hour'),
    ts_base+(n*interval '8 hour')+interval '5 min'
FROM generate_series(1,25) AS n
ON CONFLICT (id) DO NOTHING;

-- compensation_command (20)
INSERT INTO batch.compensation_command (
    id, tenant_id, command_no, compensation_type, target_id,
    job_code, biz_date, batch_no, related_job_instance_id,
    related_file_id, approval_id, operator_id, reason, strategy,
    command_status, trace_id, result_summary, error_code, error_message,
    created_at, finished_at
)
SELECT
    61000+n, 'default-tenant',
    'COMP-DEMO-'||lpad(n::text,3,'0'),
    (ARRAY['PARTITION','FILE','DLQ','JOB'])[(n-1)%4+1],
    41000+n,
    job_codes[((n-1)%25)+1],
    DATE '2026-03-01'+((n-1)%29),
    'BATCH-DEMO-'||lpad(n::text,3,'0'),
    40000+n, 52000+n,
    'APP-DEMO-'||lpad(n::text,3,'0'),
    CASE WHEN n%2=0 THEN 'admin' ELSE 'ops-user' END,
    'Compensation for '||(ARRAY['partition failure','file dispatch error','dead letter replay','job rerun'])[(n-1)%4+1]||' — item '||n,
    (ARRAY['STEP_RETRY','FILE_RETRY','DLQ_REPLAY','JOB_RERUN'])[(n-1)%4+1],
    (ARRAY['SUCCESS','RUNNING','FAILED','PENDING','SUCCESS'])[(n-1)%5+1],
    'trace-demo-'||lpad(n::text,3,'0'),
    CASE WHEN (n-1)%5=0 THEN jsonb_build_object('compensated',true,'duration',300+n*10) ELSE NULL END,
    CASE WHEN (n-1)%5=2 THEN 'COMP_FAILED' ELSE NULL END,
    CASE WHEN (n-1)%5=2 THEN 'Target unavailable: downstream service timeout' ELSE NULL END,
    ts_base+(n*interval '10 hour'),
    CASE WHEN (n-1)%5 IN (0,2) THEN ts_base+(n*interval '10 hour')+interval '5 min' ELSE NULL END
FROM generate_series(1,20) AS n
ON CONFLICT (id) DO NOTHING;

-- approval_command (25)
INSERT INTO batch.approval_command (
    id, tenant_id, approval_no, approval_type, action_type,
    target_type, target_id, payload_json, approval_status,
    requester_id, approver_id, rejection_reason, approval_reason,
    source_trace_id, source_idempotency_key, approved_at,
    executed_at, created_at, updated_at
)
SELECT
    65000+n, 'default-tenant',
    'APP-DEMO-'||lpad(n::text,3,'0'),
    approval_types[((n-1)%10)+1],
    approval_types[((n-1)%10)+1],
    (ARRAY['JOB_PARTITION','FILE','JOB','DEAD_LETTER_TASK','JOB_PARTITION','FILE','JOB','DEAD_LETTER_TASK','JOB_PARTITION','FILE'])[(n-1)%10+1],
    (41000+n)::text,
    CASE approval_types[((n-1)%10)+1]
        WHEN 'COMPENSATION' THEN jsonb_build_object(
            'tenantId','default-tenant','compensationType','PARTITION','targetId',41000+n,
            'jobCode',job_codes[((n-1)%25)+1],
            'bizDate','2026-03-'||lpad(((n-1)%28+1)::text,2,'0'),
            'reason','Compensation for partition '||n,'strategy','STEP_RETRY')
        WHEN 'DOWNLOAD' THEN jsonb_build_object(
            'tenantId','default-tenant','fileId',52000+n,
            'reason','Download file for regulatory audit','requestId','req-demo-'||lpad(n::text,3,'0'))
        WHEN 'CATCH_UP' THEN jsonb_build_object(
            'tenantId','default-tenant','requestId','req-catchup-'||n,
            'jobCode',job_codes[((n-1)%25)+1],
            'bizDate','2026-03-'||lpad(((n-1)%28+1)::text,2,'0'),
            'reason','Catch-up for missed schedule on '||(DATE '2026-03-01'+((n-1)%28))::text)
        WHEN 'DLQ_REPLAY' THEN jsonb_build_object(
            'tenantId','default-tenant','deadLetterId',59000+((n-1)%25)+1,
            'reason','Replay dead letter task after root cause fix','strategy','STEP_RETRY')
        ELSE jsonb_build_object('type',approval_types[((n-1)%10)+1],'targetId',41000+n)
    END,
    approval_statuses[((n-1)%10)+1],
    CASE WHEN n%2=0 THEN 'admin' ELSE 'ops-user' END,
    CASE WHEN approval_statuses[((n-1)%10)+1] IN ('APPROVED','REJECTED','EXECUTED') THEN 'sre-lead' ELSE NULL END,
    CASE WHEN approval_statuses[((n-1)%10)+1]='REJECTED' THEN 'Risk assessment: operation not compliant with policy' ELSE NULL END,
    CASE WHEN approval_statuses[((n-1)%10)+1] IN ('APPROVED','EXECUTED') THEN 'Approved per SOP-2026-03 section 4.2' ELSE NULL END,
    'trace-demo-'||lpad(n::text,3,'0'),
    'default-tenant:APP-DEMO-'||lpad(n::text,3,'0'),
    CASE WHEN approval_statuses[((n-1)%10)+1] IN ('APPROVED','EXECUTED')
         THEN ts_base+(n*interval '8 hour')+interval '1 hour' ELSE NULL END,
    CASE WHEN approval_statuses[((n-1)%10)+1]='EXECUTED'
         THEN ts_base+(n*interval '8 hour')+interval '1 hour 5 min' ELSE NULL END,
    ts_base+(n*interval '8 hour'),
    ts_base+(n*interval '8 hour')+interval '10 min'
FROM generate_series(1,25) AS n
ON CONFLICT (id) DO NOTHING;

-- alert_event (30)
INSERT INTO batch.alert_event (
    id, tenant_id, service_name, alert_type, severity, title,
    detail_json, dedup_fingerprint, occurrence_count,
    first_seen_at, last_seen_at, trace_id, status,
    created_at, updated_at
)
SELECT
    66000+n, 'default-tenant',
    (ARRAY['batch-orchestrator','batch-worker-import','batch-worker-export','batch-worker-dispatch','batch-trigger','batch-console-api'])[(n-1)%6+1],
    alert_types[((n-1)%10)+1],
    alert_sevs[((n-1)%6)+1],
    alert_types[((n-1)%10)+1]||' — instance '||(40000+n),
    jsonb_build_object(
        'instanceId',40000+n,
        'jobCode',job_codes[((n-1)%25)+1],
        'detail','Alert demo '||n,
        'occurrenceCount',((n-1)%5)+1,
        'threshold',CASE alert_sevs[((n-1)%6)+1] WHEN 'CRITICAL' THEN 1 WHEN 'ERROR' THEN 3 ELSE 10 END
    ),
    'default-tenant:'||alert_types[((n-1)%10)+1]||':demo-'||n,
    ((n-1)%5)+1,
    ts_base+(n*interval '6 hour'),
    ts_base+(n*interval '6 hour')+((n%3)*interval '30 min'),
    'trace-demo-'||lpad(n::text,3,'0'),
    alert_stats[((n-1)%6)+1],
    ts_base+(n*interval '6 hour'),
    ts_base+(n*interval '6 hour')+interval '5 min'
FROM generate_series(1,30) AS n
ON CONFLICT (id) DO NOTHING;

-- batch_day_instance (30)
INSERT INTO batch.batch_day_instance (
    id, tenant_id, calendar_code, biz_date, day_status,
    open_at, cutoff_at, settled_at, sla_deadline_at,
    late_count, catchup_count, created_at, updated_at
)
SELECT
    64000+n, 'default-tenant',
    CASE WHEN n%2=0 THEN 'default-calendar' ELSE 'strict-calendar' END,
    DATE '2026-03-01'+(n-1),
    (ARRAY['OPEN','CUTOFF','IN_FLIGHT','SETTLED','SETTLED','SETTLED','FAILED'])[(n-1)%7+1],
    ts_base+((n-1)*interval '24 hour'),
    CASE WHEN (n-1)%7>=1 THEN ts_base+((n-1)*interval '24 hour')+interval '10 hour' ELSE NULL END,
    CASE WHEN (n-1)%7 IN (3,4,5) THEN ts_base+((n-1)*interval '24 hour')+interval '14 hour' ELSE NULL END,
    ts_base+((n-1)*interval '24 hour')+interval '18 hour',
    CASE WHEN (n-1)%7=6 THEN 2 ELSE n%3 END,
    CASE WHEN (n-1)%7 IN (3,4,5) THEN 1 ELSE 0 END,
    ts_base+((n-1)*interval '24 hour'),
    ts_base+((n-1)*interval '24 hour')+interval '12 hour'
FROM generate_series(1,30) AS n
ON CONFLICT (id) DO NOTHING;

-- config_change_log (15)
INSERT INTO batch.config_change_log (
    id, tenant_id, config_type, config_key, version_no,
    change_action, change_result, operator_type, operator_id,
    trace_id, change_summary, created_at
)
SELECT
    26000+n, 'default-tenant',
    (ARRAY['FILE_CHANNEL','JOB','FILE_TEMPLATE','JOB','FILE_CHANNEL'])[(n-1)%5+1],
    (ARRAY['api_dispatch','imp_customer_csv','imp_customer_csv_v1','exp_settlement_daily','sftp_bank'])[(n-1)%5+1],
    ((n-1)%3)+1,
    (ARRAY['PUBLISH','GRAY','PUBLISH','ROLLBACK','PUBLISH'])[(n-1)%5+1],
    (ARRAY['SUCCESS','SUCCESS','SUCCESS','FAILED','SUCCESS'])[(n-1)%5+1],
    CASE WHEN n%3=0 THEN 'USER' ELSE 'SYSTEM' END,
    CASE WHEN n%3=0 THEN 'admin' ELSE 'system' END,
    'trace-cfg-'||lpad(n::text,3,'0'),
    jsonb_build_object('action',(ARRAY['PUBLISH','GRAY','PUBLISH','ROLLBACK','PUBLISH'])[(n-1)%5+1],
                       'configKey',(ARRAY['api_dispatch','imp_customer_csv','imp_customer_csv_v1','exp_settlement_daily','sftp_bank'])[(n-1)%5+1],
                       'version',((n-1)%3)+1),
    ts_base+(n*interval '12 hour')
FROM generate_series(1,15) AS n
ON CONFLICT (id) DO NOTHING;

END $$;

-- ── 21. 静态基础设施数据（幂等） ──────────────────────────────

-- quota_runtime_state (4)
INSERT INTO batch.quota_runtime_state (
    id, tenant_id, quota_scope, owner_code, quota_reset_policy,
    window_started_at, window_expires_at, peak_borrowed_count,
    last_reset_at, created_at, updated_at
) VALUES
(62001,'default-tenant','TENANT','default-policy','SLIDING_WINDOW',now()-interval '12h',now()+interval '12h',7,now()-interval '12h',now()-interval '12h',now()),
(62002,'default-tenant','QUEUE', 'import_queue',  'CALENDAR_DAY',  now()-interval '8h', now()+interval '16h',4,now()-interval '8h', now()-interval '8h', now()),
(62003,'default-tenant','QUEUE', 'export_queue',  'CALENDAR_DAY',  now()-interval '8h', now()+interval '16h',2,now()-interval '8h', now()-interval '8h', now()),
(62004,'default-tenant','QUEUE', 'dispatch_queue','NONE',          NULL,                NULL,                3,NULL,                now()-interval '1d',  now())
ON CONFLICT (id) DO UPDATE SET
    peak_borrowed_count=EXCLUDED.peak_borrowed_count,
    window_expires_at=EXCLUDED.window_expires_at,
    updated_at=current_timestamp;

-- tenant_scheduler_snapshot (5)
INSERT INTO batch.tenant_scheduler_snapshot (
    id, tenant_id, snapshot_at, fair_share_group, policy_code,
    active_jobs, active_partitions, max_jobs_base, burst_limit,
    effective_job_cap, group_active_jobs, group_max_jobs,
    quota_reset_policy, online_workers, detail_json
)
SELECT
    63000+n, 'default-tenant',
    now()-((5-n)*interval '1 hour'),
    'core','default-policy',
    2+(n%4), 4+(n%6), 10, 3, 13, 3+(n%3), 8, 'SLIDING_WINDOW', 4+(n%3),
    jsonb_build_object('snapshot',n,'activeQueues',ARRAY['import_queue','export_queue','dispatch_queue'],
                       'fairShareScores',jsonb_build_object('import_queue',n*10,'export_queue',n*8))
FROM generate_series(1,5) AS n
ON CONFLICT (id) DO UPDATE SET
    snapshot_at=EXCLUDED.snapshot_at,
    active_jobs=EXCLUDED.active_jobs, active_partitions=EXCLUDED.active_partitions,
    online_workers=EXCLUDED.online_workers, detail_json=EXCLUDED.detail_json;

-- file_channel_health (7)
INSERT INTO batch.file_channel_health (
    id, tenant_id, channel_code, channel_type, health_status,
    consecutive_failures, last_probe_at, last_success_at,
    last_failure_at, next_probe_at, probe_message, probe_evidence,
    created_at, updated_at
) VALUES
(63501,'default-tenant','api_dispatch',     'API',    'HEALTHY',   0,now()-interval '1m', now()-interval '1m', NULL,               now()+interval '1m', 'HTTP 200 OK',               'http://partner.example.com/health',  now()-interval '1h',now()),
(63502,'default-tenant','api_push_dispatch','API_PUSH','HEALTHY',  0,now()-interval '2m', now()-interval '2m', NULL,               now()+interval '1m', 'HTTP 200 OK (push health)', 'http://partner.example.com/push/health',now()-interval '1h',now()),
(63503,'default-tenant','sftp_bank',        'SFTP',   'DEGRADED',  2,now()-interval '5m', now()-interval '30m',now()-interval '5m',now()+interval '5m', 'Connection timeout 30s',    'sftp.bank.example.com:22',           now()-interval '1h',now()),
(63504,'default-tenant','nas_archive',      'NAS',    'HEALTHY',   0,now()-interval '3m', now()-interval '3m', NULL,               now()+interval '2m', 'Path accessible',           '/mnt/nas/batch/archive',             now()-interval '1h',now()),
(63505,'default-tenant','oss_backup',       'OSS',    'HEALTHY',   0,now()-interval '4m', now()-interval '4m', NULL,               now()+interval '2m', 'Bucket reachable',          'oss://batch-backup',                 now()-interval '1h',now()),
(63506,'default-tenant','email_ops',        'EMAIL',  'UNHEALTHY', 5,now()-interval '10m',now()-interval '2h', now()-interval '10m',now()+interval '15m','SMTP auth failed: 535',    'smtp.example.com:587',               now()-interval '1h',now()),
(63507,'default-tenant','local_test',       'LOCAL',  'HEALTHY',   0,now()-interval '1m', now()-interval '1m', NULL,               now()+interval '1m', 'Directory writable',        '/tmp/batch/dispatch',                now()-interval '1h',now())
ON CONFLICT (id) DO UPDATE SET
    health_status=EXCLUDED.health_status,
    consecutive_failures=EXCLUDED.consecutive_failures,
    last_probe_at=EXCLUDED.last_probe_at,
    last_success_at=EXCLUDED.last_success_at,
    last_failure_at=EXCLUDED.last_failure_at,
    next_probe_at=EXCLUDED.next_probe_at,
    probe_message=EXCLUDED.probe_message,
    updated_at=current_timestamp;

-- console_ai_audit_log (25)
INSERT INTO batch.console_ai_audit_log (
    id, tenant_id, request_id, trace_id, session_id,
    operator_id, prompt_category, prompt_decision, model_name,
    prompt_hash, prompt_preview, response_hash, response_preview,
    refusal_reason, created_at
)
SELECT
    67000+n, 'default-tenant',
    'ai-req-demo-'||lpad(n::text,3,'0'),
    'trace-ai-'||lpad(n::text,3,'0'),
    'session-'||((n-1)/3+1),
    CASE WHEN n%2=0 THEN 'admin' ELSE 'ops-user' END,
    (ARRAY['JOB_ANALYSIS','ERROR_DIAGNOSIS','CONFIG_REVIEW','PERFORMANCE','GENERAL'])[(n-1)%5+1],
    (ARRAY['ALLOW','ALLOW','ALLOW','REFUSE','ALLOW'])[(n-1)%5+1],
    'gpt-4o-mini',
    md5('prompt-'||n),
    (ARRAY[
        'Why did job imp_customer_csv fail on 2026-03-15?',
        'Analyze the error pattern for FIELD_REQUIRED errors in partition 41'||(n%9+1),
        'Review the retry policy configuration for export jobs',
        'What is the average processing time for import partitions this week?',
        'Summarize today''s batch processing status for default-tenant'
    ])[(n-1)%5+1],
    CASE WHEN (n-1)%5!=3 THEN md5('response-'||n) ELSE NULL END,
    CASE WHEN (n-1)%5!=3 THEN 'Based on the logs, the root cause is '||(ARRAY['missing field in source file','format mismatch','retry policy misconfigured','slow query > 30s','all tasks completed normally'])[(n-1)%5+1] ELSE NULL END,
    CASE WHEN (n-1)%5=3 THEN 'Prompt contains PII patterns (ID card number detected)' ELSE NULL END,
    TIMESTAMPTZ '2026-03-01 08:00:00+08' + (n * interval '6 hour')
FROM generate_series(1,25) AS n
ON CONFLICT (id) DO NOTHING;

-- ── 22. Reset sequences ──────────────────────────────────────
DO $$
DECLARE tbl text;
BEGIN
    FOREACH tbl IN ARRAY ARRAY[
        'resource_queue','tenant_quota_policy','batch_window',
        'business_calendar','calendar_holiday','worker_registry',
        'job_definition','workflow_definition','workflow_node',
        'workflow_edge','config_release','secret_version',
        'config_change_log','trigger_request','job_instance',
        'job_partition','job_task','job_step_instance',
        'workflow_run','workflow_node_run','pipeline_definition',
        'pipeline_step_definition','file_template_config',
        'file_channel_config','file_record','pipeline_instance',
        'pipeline_step_run','file_dispatch_record','file_audit_log',
        'file_error_record','job_execution_log','retry_schedule',
        'dead_letter_task','outbox_event','event_outbox_retry',
        'event_delivery_log','compensation_command','quota_runtime_state',
        'tenant_scheduler_snapshot','file_channel_health',
        'approval_command','alert_event','console_ai_audit_log',
        'batch_day_instance'
    ] LOOP
        EXECUTE format(
            'SELECT setval(pg_get_serial_sequence(%L,%L), COALESCE((SELECT MAX(id) FROM batch.%I),1), true)',
            'batch.'||tbl,'id',tbl
        );
    END LOOP;
END $$;

COMMIT;
