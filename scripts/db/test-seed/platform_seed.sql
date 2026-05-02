BEGIN;

TRUNCATE TABLE
    batch.alert_event,
    batch.approval_command,
    batch.batch_day_instance,
    batch.batch_window,
    batch.business_calendar,
    batch.calendar_holiday,
    batch.compensation_command,
    batch.config_change_log,
    batch.config_release,
    batch.dead_letter_task,
    batch.file_audit_log,
    batch.file_channel_config,
    batch.file_channel_health,
    batch.file_dispatch_record,
    batch.file_record,
    batch.file_template_config,
    batch.job_execution_log,
    batch.job_partition,
    batch.job_step_instance,
    batch.job_task,
    batch.job_instance,
    batch.outbox_event,
    batch.pipeline_instance,
    batch.pipeline_step_definition,
    batch.pipeline_step_run,
    batch.pipeline_definition,
    batch.quota_runtime_state,
    batch.resource_queue,
    batch.retry_schedule,
    batch.secret_version,
    batch.tenant_quota_policy,
    batch.tenant_scheduler_snapshot,
    batch.trigger_request,
    batch.worker_registry,
    batch.workflow_edge,
    batch.workflow_node,
    batch.workflow_node_run,
    batch.workflow_definition,
    batch.workflow_run
RESTART IDENTITY CASCADE;

INSERT INTO batch.resource_queue (
    id, tenant_id, queue_code, queue_name, queue_type, max_running_jobs, max_running_partitions, max_qps,
    worker_group, resource_tag, priority_policy, fair_share_weight, enabled, description,
    fair_share_group, burst_limit, quota_reset_policy, group_shared_max_running_jobs, created_at, updated_at
) VALUES
    (1001, 'default-tenant', 'import_queue', 'Default Import Queue', 'IMPORT', 4, 8, 50, 'import', 'ingest', 'FAIR_SHARE', 10, TRUE, 'Main import queue', 'core', 2, 'SLIDING_WINDOW', 6, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (1002, 'default-tenant', 'export_queue', 'Default Export Queue', 'EXPORT', 2, 4, 20, 'export', 'report', 'PRIORITY', 8, TRUE, 'Main export queue', 'core', 1, 'CALENDAR_DAY', 4, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (1003, 'default-tenant', 'dispatch_queue', 'Default Dispatch Queue', 'DISPATCH', 3, 6, 30, 'dispatch', 'delivery', 'FIFO', 6, TRUE, 'Dispatch queue', 'delivery', 1, 'NONE', 3, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (1004, 'tenant-finance', 'finance_export_queue', 'Finance Export Queue', 'EXPORT', 2, 4, 15, 'export', 'finance', 'PRIORITY', 12, TRUE, 'Finance tenant export queue', 'finance-core', 1, 'SLIDING_WINDOW', 3, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08');

INSERT INTO batch.tenant_quota_policy (
    id, tenant_id, policy_code, max_running_jobs_per_tenant, max_partitions_per_tenant, max_qps_per_tenant,
    fair_share_weight, enabled, description, fair_share_group, burst_limit, partition_burst_limit,
    quota_reset_policy, group_shared_max_running_jobs, created_at, updated_at
) VALUES
    (1101, 'default-tenant', 'default-policy', 8, 16, 80, 5, TRUE, 'Default tenant policy', 'core', 2, 4, 'SLIDING_WINDOW', 6, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (1102, 'tenant-finance', 'finance-policy', 6, 12, 50, 8, TRUE, 'Finance tenant policy', 'finance-core', 1, 3, 'CALENDAR_DAY', 4, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08');

INSERT INTO batch.batch_window (
    id, tenant_id, window_code, window_name, timezone, start_time, end_time, end_strategy, out_of_window_action,
    allow_cross_day, enabled, description, created_at, updated_at
) VALUES
    (1201, 'default-tenant', 'always_open', 'Always Open Window', 'Asia/Shanghai', TIME '00:00:00', TIME '23:59:59', 'FINISH_RUNNING', 'WAIT', TRUE, TRUE, 'System test open window', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (1202, 'tenant-finance', 'night_window', 'Night Processing Window', 'Asia/Shanghai', TIME '21:00:00', TIME '23:59:59', 'FINISH_RUNNING', 'WAIT', TRUE, TRUE, 'Finance tenant night window', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08');

INSERT INTO batch.business_calendar (
    id, tenant_id, calendar_code, calendar_name, timezone, holiday_roll_rule, catch_up_policy, catch_up_max_days,
    enabled, created_at, updated_at
) VALUES
    (1301, 'default-tenant', 'default-calendar', 'Default Calendar', 'Asia/Shanghai', 'NEXT_WORKDAY', 'AUTO', 3, TRUE, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (1302, 'tenant-finance', 'finance-calendar', 'Finance Calendar', 'Asia/Shanghai', 'SKIP', 'MANUAL_APPROVAL', 2, TRUE, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08');

INSERT INTO batch.calendar_holiday (
    id, calendar_id, biz_date, day_type, holiday_name, description, created_at, updated_at
) VALUES
    (1401, 1301, DATE '2026-05-01', 'HOLIDAY', 'Labor Day', 'System test holiday entry', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (1402, 1302, DATE '2026-05-02', 'WORKDAY_OVERRIDE', 'Weekend Make-up Day', 'Finance override', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08');

INSERT INTO batch.worker_registry (
    id, tenant_id, worker_code, worker_group, host_name, host_ip, process_id, capability_tags, resource_tag,
    status, heartbeat_at, last_start_at, version, current_load, drain_started_at, drain_deadline_at,
    created_at, updated_at
) VALUES
    (1501, 'default-tenant', 'import-node-1', 'import', 'import-host-1', '127.0.0.1', '10001', jsonb_build_object('formats', jsonb_build_array('JSON','DELIMITED','EXCEL','XML','FIXED_WIDTH'), 'role', 'import'), 'ingest', 'ONLINE', TIMESTAMPTZ '2026-03-22 08:15:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08', 'v1', 2, NULL, NULL, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (1502, 'default-tenant', 'export-node-1', 'export', 'export-host-1', '127.0.0.1', '10002', jsonb_build_object('formats', jsonb_build_array('JSON','DELIMITED','EXCEL','FIXED_WIDTH'), 'role', 'export'), 'report', 'ONLINE', TIMESTAMPTZ '2026-03-22 08:15:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08', 'v1', 1, NULL, NULL, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (1503, 'default-tenant', 'dispatch-node-1', 'dispatch', 'dispatch-host-1', '127.0.0.1', '10003', jsonb_build_object('channels', jsonb_build_array('API','API_PUSH','LOCAL','NAS','OSS','SFTP','EMAIL'), 'role', 'dispatch'), 'delivery', 'ONLINE', TIMESTAMPTZ '2026-03-22 08:15:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08', 'v1', 3, NULL, NULL, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (1504, 'default-tenant', 'dispatch-node-drain', 'dispatch', 'dispatch-host-2', '127.0.0.2', '10004', jsonb_build_object('channels', jsonb_build_array('NAS','OSS'), 'role', 'dispatch'), 'delivery', 'DRAINING', TIMESTAMPTZ '2026-03-22 07:00:00+08', TIMESTAMPTZ '2026-03-22 07:00:00+08', 'v1', 0, TIMESTAMPTZ '2026-03-22 07:00:00+08', TIMESTAMPTZ '2026-03-22 07:30:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (1505, 'tenant-finance', 'finance-export-node', 'export', 'finance-export-host', '127.0.0.3', '10005', jsonb_build_object('formats', jsonb_build_array('DELIMITED','EXCEL'), 'role', 'export'), 'finance', 'OFFLINE', TIMESTAMPTZ '2026-03-22 06:00:00+08', TIMESTAMPTZ '2026-03-22 06:00:00+08', 'v1', 0, NULL, NULL, TIMESTAMPTZ '2026-03-22 06:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08');

INSERT INTO batch.job_definition (
    id, tenant_id, job_code, job_name, job_type, biz_type, schedule_type, schedule_expr, timezone, priority,
    queue_code, worker_group, calendar_code, window_code, trigger_mode, dag_enabled, shard_strategy, retry_policy,
    retry_max_count, timeout_seconds, execution_handler, param_schema, default_params, version, enabled, description,
    created_by, updated_by, created_at, updated_at
) VALUES
    (2001, 'default-tenant', 'import_customer_job', 'Customer Import Job', 'IMPORT', 'CUSTOMER', 'MANUAL', NULL, 'Asia/Shanghai', 3, 'import_queue', 'import', 'default-calendar', 'always_open', 'MIXED', FALSE, 'DYNAMIC', 'EXPONENTIAL', 3, 3600, 'com.example.ImportCustomerJobHandler', jsonb_build_object('type', 'object'), jsonb_build_object('streamingEnabled', true), 1, TRUE, 'System test import job', 'system', 'system', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (2002, 'default-tenant', 'export_settlement_job', 'Settlement Export Job', 'EXPORT', 'SETTLEMENT', 'CRON', '0 0/30 * * * ?', 'Asia/Shanghai', 4, 'export_queue', 'export', 'default-calendar', 'always_open', 'SCHEDULED', TRUE, 'STATIC', 'FIXED', 2, 7200, 'com.example.ExportSettlementJobHandler', jsonb_build_object('type', 'object'), jsonb_build_object('pageSize', 1000), 1, TRUE, 'System test export job', 'system', 'system', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (2003, 'tenant-finance', 'finance_recon_workflow', 'Finance Reconciliation Workflow', 'WORKFLOW', 'SETTLEMENT', 'MANUAL', NULL, 'Asia/Shanghai', 2, 'finance_export_queue', 'export', 'finance-calendar', 'night_window', 'MIXED', TRUE, 'AUTO', 'EXPONENTIAL', 3, 5400, 'com.example.FinanceReconWorkflowHandler', jsonb_build_object('type', 'object'), jsonb_build_object('sourcePartitions', jsonb_build_array(1,2)), 1, TRUE, 'Finance workflow job', 'system', 'system', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08')
ON CONFLICT (id) DO NOTHING;

INSERT INTO batch.workflow_definition (
    id, tenant_id, workflow_code, workflow_name, workflow_type, version, enabled, description, created_by, updated_by, created_at, updated_at
) VALUES
    (2101, 'tenant-finance', 'finance_recon_flow', 'Finance Recon Flow', 'DAG', 1, TRUE, 'System test DAG flow', 'system', 'system', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08');

INSERT INTO batch.workflow_node (
    id, tenant_id, workflow_definition_id, node_code, node_name, node_type, related_job_code, related_pipeline_code, worker_group,
    window_code, node_order, retry_policy, retry_max_count, timeout_seconds, node_params, enabled, created_at, updated_at
) VALUES
    (2201, 'tenant-finance', 2101, 'START', 'Start', 'START', NULL, NULL, NULL, NULL, 0, 'NONE', 0, 0, jsonb_build_object('entry', true), TRUE, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (2202, 'tenant-finance', 2101, 'IMPORT_STEP', 'Import Step', 'FILE_STEP', 'import_customer_job', 'import_customer_pipeline', 'import', 'always_open', 1, 'FIXED', 1, 1800, jsonb_build_object('mode', 'file_import'), TRUE, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (2203, 'tenant-finance', 2101, 'EXPORT_STEP', 'Export Step', 'TASK', 'export_settlement_job', 'export_settlement_pipeline', 'export', 'always_open', 2, 'FIXED', 1, 1800, jsonb_build_object('mode', 'settlement_export'), TRUE, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (2204, 'tenant-finance', 2101, 'END', 'End', 'END', NULL, NULL, NULL, NULL, 3, 'NONE', 0, 0, jsonb_build_object('entry', false), TRUE, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08');

INSERT INTO batch.workflow_edge (
    id, tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, condition_expr, enabled, created_at, updated_at
) VALUES
    (2301, 'tenant-finance', 2101, 'START', 'IMPORT_STEP', 'ALWAYS', NULL, TRUE, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (2302, 'tenant-finance', 2101, 'IMPORT_STEP', 'EXPORT_STEP', 'SUCCESS', NULL, TRUE, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (2303, 'tenant-finance', 2101, 'EXPORT_STEP', 'END', 'ALWAYS', NULL, TRUE, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08');

INSERT INTO batch.config_release (
    id, tenant_id, config_type, config_key, config_name, config_status, version_no, gray_scope, config_payload,
    effective_from_at, effective_to_at, published_at, rolled_back_at, created_by, updated_by, created_at, updated_at
) VALUES
    (2401, 'default-tenant', 'FILE_CHANNEL', 'dispatch_api_push', 'Dispatch API Push Config', 'PUBLISHED', 1, jsonb_build_object('tenantIds', jsonb_build_array('default-tenant')), jsonb_build_object('channelCode', 'api_push_dispatch', 'enabled', true), TIMESTAMPTZ '2026-03-22 08:05:00+08', NULL, TIMESTAMPTZ '2026-03-22 08:05:00+08', NULL, 'system', 'system', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (2402, 'tenant-finance', 'JOB', 'finance_recon_job', 'Finance Recon Job Config', 'GRAY', 2, jsonb_build_object('workerGroups', jsonb_build_array('export')), jsonb_build_object('retryPolicy', 'EXPONENTIAL'), TIMESTAMPTZ '2026-03-22 08:10:00+08', NULL, NULL, NULL, 'system', 'system', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08');

INSERT INTO batch.secret_version (
    id, tenant_id, secret_ref, secret_name, version_no, secret_status, current_version,
    rotation_window_start_at, rotation_window_end_at, effective_from_at, effective_to_at,
    secret_payload, rotation_reason, created_by, updated_by, created_at, updated_at
) VALUES
    (2501, 'default-tenant', 'DEFAULT_TEST', 'Default Test KMS Secret', 1, 'PUBLISHED', TRUE, TIMESTAMPTZ '2026-03-22 00:00:00+08', TIMESTAMPTZ '2026-03-22 23:59:59+08', TIMESTAMPTZ '2026-03-22 00:00:00+08', NULL, jsonb_build_object('keyRef', 'DEFAULT_TEST'), 'bootstrap', 'system', 'system', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (2502, 'tenant-finance', 'FINANCE_TEST', 'Finance Test Secret', 1, 'GRAY', FALSE, TIMESTAMPTZ '2026-03-22 01:00:00+08', TIMESTAMPTZ '2026-03-22 02:00:00+08', TIMESTAMPTZ '2026-03-22 01:00:00+08', NULL, jsonb_build_object('keyRef', 'FINANCE_TEST'), 'rotation-preview', 'system', 'system', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08');

INSERT INTO batch.config_change_log (
    id, tenant_id, config_type, config_key, version_no, change_action, change_result, operator_type, operator_id, trace_id, change_summary, created_at
) VALUES
    (2601, 'default-tenant', 'FILE_CHANNEL', 'dispatch_api_push', 1, 'PUBLISH', 'SUCCESS', 'SYSTEM', 'system', 'trace-config-001', jsonb_build_object('reason', 'system-test-bootstrap'), TIMESTAMPTZ '2026-03-22 08:05:00+08'),
    (2602, 'tenant-finance', 'JOB', 'finance_recon_job', 2, 'GRAY', 'FAILED', 'USER', 'ops-user', 'trace-config-002', jsonb_build_object('reason', 'validation failed'), TIMESTAMPTZ '2026-03-22 08:10:00+08');

INSERT INTO batch.trigger_request (
    id, tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key, request_payload_hash,
    request_status, related_job_instance_id, trace_id, created_at, updated_at
) VALUES
    (3001, 'default-tenant', 'req-import-001', 'API', 'import_customer_job', DATE '2026-03-22', 'default-tenant:req-import-001', 'hash-import-001', 'LAUNCHED', 4001, 'trace-import-001', TIMESTAMPTZ '2026-03-22 08:00:10+08', TIMESTAMPTZ '2026-03-22 08:00:10+08'),
    (3002, 'default-tenant', 'req-export-001', 'SCHEDULED', 'export_settlement_job', DATE '2026-03-22', 'default-tenant:req-export-001', 'hash-export-001', 'LAUNCHED', 4002, 'trace-export-001', TIMESTAMPTZ '2026-03-22 08:00:20+08', TIMESTAMPTZ '2026-03-22 08:00:20+08'),
    (3003, 'tenant-finance', 'req-workflow-001', 'CATCH_UP', 'finance_recon_workflow', DATE '2026-03-22', 'tenant-finance:req-workflow-001', 'hash-workflow-001', 'LAUNCHED', 4003, 'trace-workflow-001', TIMESTAMPTZ '2026-03-22 08:00:30+08', TIMESTAMPTZ '2026-03-22 08:00:30+08'),
    (3004, 'default-tenant', 'req-manual-001', 'MANUAL', 'export_settlement_job', DATE '2026-03-22', 'default-tenant:req-manual-001', 'hash-manual-001', 'ACCEPTED', NULL, 'trace-manual-001', TIMESTAMPTZ '2026-03-22 08:00:40+08', TIMESTAMPTZ '2026-03-22 08:00:40+08');

INSERT INTO batch.job_instance (
    id, tenant_id, job_definition_id, trigger_request_id, job_code, instance_no, biz_date, trigger_type,
    instance_status, queue_code, worker_group, priority, dedup_key, version,
    expected_partition_count, success_partition_count, failed_partition_count, trace_id, params_snapshot,
    started_at, finished_at, batch_no, operator_id, rerun_flag, retry_flag, rerun_reason, related_file_id,
    parent_instance_id, result_summary, created_at, updated_at
) VALUES
    (4001, 'default-tenant', 2001, 3001, 'import_customer_job', 'IMP-20260322-001', DATE '2026-03-22', 'API', 'RUNNING', 'import_queue', 'import', 3, 'default-tenant:IMP-20260322-001', 1, 3, 1, 0, 'trace-import-001', jsonb_build_object('mode', 'customer_import', 'fileId', 5001), TIMESTAMPTZ '2026-03-22 08:00:12+08', NULL, 'BATCH-IMP-20260322-001', 'ops-user', FALSE, TRUE, NULL, 5001, NULL, NULL, TIMESTAMPTZ '2026-03-22 08:00:12+08', TIMESTAMPTZ '2026-03-22 08:00:12+08'),
    (4002, 'default-tenant', 2002, 3002, 'export_settlement_job', 'EXP-20260322-001', DATE '2026-03-22', 'SCHEDULED', 'SUCCESS', 'export_queue', 'export', 4, 'default-tenant:EXP-20260322-001', 2, 2, 2, 0, 'trace-export-001', jsonb_build_object('mode', 'settlement_export', 'fileId', 5003), TIMESTAMPTZ '2026-03-22 08:00:22+08', TIMESTAMPTZ '2026-03-22 08:20:22+08', 'BATCH-EXP-20260322-001', 'ops-user', TRUE, FALSE, 'retry validation', 5003, NULL, jsonb_build_object('result', 'success'), TIMESTAMPTZ '2026-03-22 08:00:22+08', TIMESTAMPTZ '2026-03-22 08:20:22+08'),
    (4003, 'tenant-finance', 2003, 3003, 'finance_recon_workflow', 'WF-20260322-001', DATE '2026-03-22', 'CATCH_UP', 'RUNNING', 'finance_export_queue', 'export', 2, 'tenant-finance:WF-20260322-001', 1, 2, 1, 0, 'trace-workflow-001', jsonb_build_object('mode', 'workflow', 'fileId', 5005), TIMESTAMPTZ '2026-03-22 08:00:32+08', NULL, 'BATCH-WF-20260322-001', 'ops-user', FALSE, FALSE, NULL, 5005, NULL, NULL, TIMESTAMPTZ '2026-03-22 08:00:32+08', TIMESTAMPTZ '2026-03-22 08:00:32+08'),
    (4004, 'default-tenant', 2002, NULL, 'export_settlement_job', 'EXP-20260322-002', DATE '2026-03-22', 'MANUAL', 'WAITING', 'export_queue', 'export', 5, 'default-tenant:EXP-20260322-002', 0, 0, 0, 0, 'trace-export-002', jsonb_build_object('mode', 'manual_export', 'fileId', 5006), NULL, NULL, 'BATCH-EXP-20260322-002', 'admin', FALSE, FALSE, NULL, 5006, 4002, NULL, TIMESTAMPTZ '2026-03-22 08:00:40+08', TIMESTAMPTZ '2026-03-22 08:00:40+08');

INSERT INTO batch.job_partition (
    id, tenant_id, job_instance_id, partition_no, partition_key, partition_status, worker_group, worker_code,
    lease_expire_at, retry_count, business_key, idempotency_key, input_snapshot, output_summary,
    started_at, finished_at, created_at, updated_at
) VALUES
    (4101, 'default-tenant', 4001, 1, 'customer', 'RUNNING', 'import', 'import-node-1', TIMESTAMPTZ '2026-03-22 08:30:00+08', 0, 'IMP-20260322-001:1', 'default-tenant:IMP-20260322-001:1', jsonb_build_object('fileId', 5001, 'chunk', 1), NULL, TIMESTAMPTZ '2026-03-22 08:00:13+08', NULL, TIMESTAMPTZ '2026-03-22 08:00:13+08', TIMESTAMPTZ '2026-03-22 08:00:13+08'),
    (4102, 'default-tenant', 4001, 2, 'customer', 'FAILED', 'import', 'import-node-1', TIMESTAMPTZ '2026-03-22 07:30:00+08', 1, 'IMP-20260322-001:2', 'default-tenant:IMP-20260322-001:2', jsonb_build_object('fileId', 5001, 'chunk', 2), jsonb_build_object('loaded', 500), TIMESTAMPTZ '2026-03-22 08:00:14+08', TIMESTAMPTZ '2026-03-22 08:05:00+08', TIMESTAMPTZ '2026-03-22 08:00:14+08', TIMESTAMPTZ '2026-03-22 08:05:00+08'),
    (4103, 'default-tenant', 4002, 1, 'settlement', 'SUCCESS', 'export', 'export-node-1', TIMESTAMPTZ '2026-03-22 09:00:00+08', 0, 'EXP-20260322-001:1', 'default-tenant:EXP-20260322-001:1', jsonb_build_object('batchId', 2001), jsonb_build_object('records', 3), TIMESTAMPTZ '2026-03-22 08:00:23+08', TIMESTAMPTZ '2026-03-22 08:20:21+08', TIMESTAMPTZ '2026-03-22 08:00:23+08', TIMESTAMPTZ '2026-03-22 08:20:21+08'),
    (4104, 'default-tenant', 4002, 2, 'settlement', 'SUCCESS', 'export', 'export-node-1', TIMESTAMPTZ '2026-03-22 09:00:00+08', 0, 'EXP-20260322-001:2', 'default-tenant:EXP-20260322-001:2', jsonb_build_object('batchId', 2001), jsonb_build_object('records', 1), TIMESTAMPTZ '2026-03-22 08:00:24+08', TIMESTAMPTZ '2026-03-22 08:20:21+08', TIMESTAMPTZ '2026-03-22 08:00:24+08', TIMESTAMPTZ '2026-03-22 08:20:21+08'),
    (4105, 'tenant-finance', 4003, 1, 'recon', 'READY', 'export', NULL, NULL, 0, 'WF-20260322-001:1', 'tenant-finance:WF-20260322-001:1', jsonb_build_object('workflow', true), NULL, NULL, NULL, TIMESTAMPTZ '2026-03-22 08:00:33+08', TIMESTAMPTZ '2026-03-22 08:00:33+08'),
    (4106, 'tenant-finance', 4003, 2, 'recon', 'WAITING', 'export', NULL, NULL, 0, 'WF-20260322-001:2', 'tenant-finance:WF-20260322-001:2', jsonb_build_object('workflow', true), NULL, NULL, NULL, TIMESTAMPTZ '2026-03-22 08:00:34+08', TIMESTAMPTZ '2026-03-22 08:00:34+08');

INSERT INTO batch.job_task (
    id, tenant_id, job_instance_id, job_partition_id, task_type, task_seq, task_status, assigned_worker_code,
    task_payload, result_summary, error_code, error_message, started_at, finished_at, created_at, updated_at
) VALUES
    (4201, 'default-tenant', 4001, 4101, 'EXECUTION', 1, 'RUNNING', 'import-node-1', jsonb_build_object('stage', 'parse'), NULL, NULL, NULL, TIMESTAMPTZ '2026-03-22 08:00:13+08', TIMESTAMPTZ '2026-03-22 08:00:13+08', TIMESTAMPTZ '2026-03-22 08:00:13+08', TIMESTAMPTZ '2026-03-22 08:00:13+08'),
    (4202, 'default-tenant', 4001, 4102, 'EXECUTION', 1, 'FAILED', 'import-node-1', jsonb_build_object('stage', 'validate'), jsonb_build_object('failedRows', 5), 'IMPORT_VALIDATE_REQUIRED', 'Required fields missing', TIMESTAMPTZ '2026-03-22 08:00:14+08', TIMESTAMPTZ '2026-03-22 08:05:00+08', TIMESTAMPTZ '2026-03-22 08:00:14+08', TIMESTAMPTZ '2026-03-22 08:05:00+08'),
    (4203, 'default-tenant', 4002, 4103, 'EXECUTION', 1, 'SUCCESS', 'export-node-1', jsonb_build_object('stage', 'generate'), jsonb_build_object('rows', 3), NULL, NULL, TIMESTAMPTZ '2026-03-22 08:00:23+08', TIMESTAMPTZ '2026-03-22 08:20:20+08', TIMESTAMPTZ '2026-03-22 08:00:23+08', TIMESTAMPTZ '2026-03-22 08:20:20+08'),
    (4204, 'default-tenant', 4002, 4104, 'COMPENSATION', 1, 'SUCCESS', 'export-node-1', jsonb_build_object('stage', 'register'), jsonb_build_object('rows', 1), NULL, NULL, TIMESTAMPTZ '2026-03-22 08:00:24+08', TIMESTAMPTZ '2026-03-22 08:20:20+08', TIMESTAMPTZ '2026-03-22 08:00:24+08', TIMESTAMPTZ '2026-03-22 08:20:20+08'),
    (4205, 'tenant-finance', 4003, 4105, 'EXECUTION', 1, 'READY', 'export-node-1', jsonb_build_object('stage', 'workflow'), NULL, NULL, NULL, NULL, NULL, TIMESTAMPTZ '2026-03-22 08:00:33+08', TIMESTAMPTZ '2026-03-22 08:00:33+08'),
    (4206, 'tenant-finance', 4003, 4106, 'REPLAY', 1, 'CREATED', NULL, jsonb_build_object('stage', 'replay'), NULL, NULL, NULL, NULL, NULL, TIMESTAMPTZ '2026-03-22 08:00:34+08', TIMESTAMPTZ '2026-03-22 08:00:34+08');

INSERT INTO batch.job_step_instance (
    id, tenant_id, job_instance_id, job_partition_id, job_task_id, step_code, step_type, step_status, retry_count,
    related_file_id, result_summary, error_code, error_message, version, started_at, finished_at, created_at, updated_at
) VALUES
    (4301, 'default-tenant', 4001, 4101, 4201, 'import_validate', 'VALIDATE', 'RUNNING', 0, 5001, jsonb_build_object('rows', 500), NULL, NULL, 0, TIMESTAMPTZ '2026-03-22 08:00:13+08', NULL, TIMESTAMPTZ '2026-03-22 08:00:13+08', TIMESTAMPTZ '2026-03-22 08:00:13+08'),
    (4302, 'default-tenant', 4001, 4102, 4202, 'import_validate', 'VALIDATE', 'FAILED', 1, 5001, jsonb_build_object('rows', 495), 'IMPORT_VALIDATE_REQUIRED', 'Required fields missing', 1, TIMESTAMPTZ '2026-03-22 08:00:14+08', TIMESTAMPTZ '2026-03-22 08:05:00+08', TIMESTAMPTZ '2026-03-22 08:00:14+08', TIMESTAMPTZ '2026-03-22 08:05:00+08'),
    (4303, 'default-tenant', 4002, 4103, 4203, 'export_generate', 'GENERATE', 'SUCCESS', 0, 5003, jsonb_build_object('rows', 3), NULL, NULL, 1, TIMESTAMPTZ '2026-03-22 08:00:23+08', TIMESTAMPTZ '2026-03-22 08:20:20+08', TIMESTAMPTZ '2026-03-22 08:00:23+08', TIMESTAMPTZ '2026-03-22 08:20:20+08'),
    (4304, 'tenant-finance', 4003, 4105, 4205, 'workflow_recon', 'TASK', 'READY', 0, 5005, NULL, NULL, NULL, 0, NULL, NULL, TIMESTAMPTZ '2026-03-22 08:00:33+08', TIMESTAMPTZ '2026-03-22 08:00:33+08');

INSERT INTO batch.workflow_run (
    id, tenant_id, workflow_definition_id, related_job_instance_id, biz_date, run_status, current_node_code,
    trace_id, started_at, finished_at, created_at, updated_at
) VALUES
    (4401, 'tenant-finance', 2101, 4003, DATE '2026-03-22', 'RUNNING', 'IMPORT_STEP', 'trace-workflow-001', TIMESTAMPTZ '2026-03-22 08:00:33+08', NULL, TIMESTAMPTZ '2026-03-22 08:00:33+08', TIMESTAMPTZ '2026-03-22 08:00:33+08');

INSERT INTO batch.workflow_node_run (
    id, workflow_run_id, node_code, node_type, run_seq, node_status, retry_count, error_code, error_message,
    started_at, finished_at, duration_ms
) VALUES
    (4501, 4401, 'START', 'START', 1, 'SUCCESS', 0, NULL, NULL, TIMESTAMPTZ '2026-03-22 08:00:33+08', TIMESTAMPTZ '2026-03-22 08:00:34+08', 1000),
    (4502, 4401, 'IMPORT_STEP', 'FILE_STEP', 1, 'RUNNING', 0, NULL, NULL, TIMESTAMPTZ '2026-03-22 08:00:34+08', NULL, 0),
    (4503, 4401, 'EXPORT_STEP', 'TASK', 1, 'READY', 0, NULL, NULL, NULL, NULL, 0);

INSERT INTO batch.pipeline_definition (
    id, tenant_id, job_code, pipeline_name, pipeline_type, biz_type, worker_group, version, enabled, description, created_at, updated_at
) VALUES
    (4601, 'default-tenant', 'import_customer_pipeline', 'Customer Import Pipeline', 'IMPORT', 'CUSTOMER', 'import', 1, TRUE, 'System test import pipeline', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (4602, 'default-tenant', 'export_settlement_pipeline', 'Settlement Export Pipeline', 'EXPORT', 'SETTLEMENT', 'export', 1, TRUE, 'System test export pipeline', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (4603, 'default-tenant', 'dispatch_settlement_pipeline', 'Settlement Dispatch Pipeline', 'DISPATCH', 'SETTLEMENT', 'dispatch', 1, TRUE, 'System test dispatch pipeline', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08');

INSERT INTO batch.pipeline_step_definition (
    id, pipeline_definition_id, step_code, step_name, stage_code, step_order, impl_code, step_params,
    timeout_seconds, retry_policy, retry_max_count, enabled, created_at, updated_at
) VALUES
    (4701, 4601, 'receive', 'Receive', 'RECEIVE', 1, 'fileReceive', jsonb_build_object('storageType', 'S3'), 300, 'NONE', 0, TRUE, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (4702, 4601, 'parse', 'Parse', 'PARSE', 2, 'csvParse', jsonb_build_object('delimiter', ','), 600, 'FIXED', 1, TRUE, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (4703, 4601, 'validate', 'Validate', 'VALIDATE', 3, 'rowValidate', jsonb_build_object('ruleSet', 'customer-default'), 600, 'FIXED', 1, TRUE, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (4704, 4601, 'load', 'Load', 'LOAD', 4, 'jdbcMappedLoad', jsonb_build_object('targetTable', 'biz.customer_account'), 1200, 'EXPONENTIAL', 2, TRUE, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (4711, 4602, 'prepare', 'Prepare', 'RECEIVE', 1, 'exportPrepare', jsonb_build_object('snapshotMode', 'BIZ_DATE'), 300, 'NONE', 0, TRUE, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (4712, 4602, 'generate', 'Generate', 'GENERATE', 2, 'csvGenerate', jsonb_build_object('delimiter', ','), 1200, 'FIXED', 1, TRUE, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (4713, 4602, 'store', 'Store', 'TRANSFER', 3, 'minioStore', jsonb_build_object('bucket', 'batch-dev'), 1200, 'FIXED', 1, TRUE, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (4714, 4602, 'register', 'Register', 'ACK', 4, 'fileRegister', jsonb_build_object('registerMode', 'atomic'), 300, 'NONE', 0, TRUE, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (4721, 4603, 'dispatch', 'Dispatch', 'DISPATCH', 1, 'dispatchChannel', jsonb_build_object('channels', jsonb_build_array('API','NAS','SFTP','EMAIL','OSS')), 1200, 'EXPONENTIAL', 2, TRUE, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08');

INSERT INTO batch.file_template_config (
    id, tenant_id, template_code, template_name, template_type, biz_type, file_format_type, charset, target_charset,
    with_bom, line_separator, delimiter, quote_char, escape_char, record_length, header_rows, footer_rows,
    header_template, trailer_template, checksum_type, compress_type, encrypt_type, naming_rule, field_mappings,
    validation_rule_set, default_query_code, default_query_sql, query_param_schema, streaming_enabled, page_size,
    fetch_size, chunk_size, enabled, version, description, created_by, updated_by, created_at, updated_at,
    preview_masking_enabled, error_line_masking_enabled, log_masking_enabled, content_encryption_enabled,
    encryption_key_ref, download_requires_approval, masking_rule_set
) VALUES
    (5001, 'default-tenant', 'import_customer_v1', 'Customer Import Template', 'IMPORT', 'CUSTOMER', 'DELIMITED', 'UTF-8', 'UTF-8', FALSE, E'\n', ',', '"', '\\', 0, 1, 0, jsonb_build_object('title', 'customer-import'), NULL, 'SHA-256', 'NONE', 'AES', 'customer-${bizDate}-${version}', jsonb_build_object('customer_no', 'customerNo', 'customer_name', 'customerName'), jsonb_build_object('required', jsonb_build_array('customerNo','customerName')), 'customer_query', 'select * from biz.customer_account where tenant_id = :tenantId', jsonb_build_object('tenantId', 'string'), TRUE, 1000, 1000, 500, TRUE, 1, 'Customer import template', 'system', 'system', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08', TRUE, TRUE, TRUE, TRUE, 'DEFAULT_TEST', FALSE, 'PCI_BASIC'),
    (5002, 'default-tenant', 'import_customer_json_v1', 'Customer Import JSON Template', 'IMPORT', 'CUSTOMER', 'JSON', 'UTF-8', 'UTF-8', FALSE, E'\n', NULL, NULL, NULL, 0, 0, 0, jsonb_build_object('title', 'customer-import-json'), NULL, 'SHA-256', 'NONE', 'AES', 'customer-json-${bizDate}-${version}', jsonb_build_object('customer_no', 'customerNo', 'customer_name', 'customerName'), jsonb_build_object('required', jsonb_build_array('customerNo','customerName')), 'customer_query_json', 'select * from biz.customer_account where tenant_id = :tenantId', jsonb_build_object('tenantId', 'string'), TRUE, 1000, 1000, 500, TRUE, 1, 'Customer JSON import template', 'system', 'system', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08', TRUE, TRUE, TRUE, FALSE, 'DEFAULT_TEST', FALSE, 'PCI_BASIC'),
    (5003, 'default-tenant', 'export_settlement_v1', 'Settlement Export Template', 'EXPORT', 'SETTLEMENT', 'DELIMITED', 'UTF-8', 'UTF-8', TRUE, E'\n', ',', '"', '\\', 0, 1, 0, jsonb_build_object('title', 'settlement-export'), jsonb_build_object('title', 'settlement-export-trailer'), 'SHA-256', 'NONE', 'AES', 'settlement-${bizDate}-${version}', jsonb_build_object('settlement_no', 'settlementNo', 'net_amount', 'netAmount'), jsonb_build_object('required', jsonb_build_array('settlementNo')), 'settlement_query', 'select * from biz.settlement_detail where tenant_id = :tenantId and batch_id = :batchId order by id asc', jsonb_build_object('tenantId', 'string', 'batchId', 'long'), TRUE, 1000, 1000, 500, TRUE, 1, 'Settlement export template', 'system', 'system', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08', TRUE, FALSE, TRUE, TRUE, 'DEFAULT_TEST', TRUE, 'PCI_BASIC'),
    (5004, 'tenant-finance', 'export_settlement_xlsx_v1', 'Settlement Export XLSX Template', 'EXPORT', 'SETTLEMENT', 'EXCEL', 'UTF-8', 'UTF-8', TRUE, E'\n', NULL, NULL, NULL, 0, 1, 0, jsonb_build_object('title', 'settlement-xlsx'), jsonb_build_object('title', 'settlement-xlsx-trailer'), 'SHA-256', 'NONE', 'NONE', 'settlement-xlsx-${bizDate}-${version}', jsonb_build_object('settlement_no', 'settlementNo'), jsonb_build_object('required', jsonb_build_array('settlementNo')), 'settlement_query_xlsx', 'select * from biz.settlement_detail where tenant_id = :tenantId and batch_id = :batchId order by id asc', jsonb_build_object('tenantId', 'string', 'batchId', 'long'), TRUE, 1000, 1000, 500, TRUE, 1, 'Settlement XLSX export template', 'system', 'system', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08', FALSE, FALSE, FALSE, FALSE, 'DEFAULT_TEST', FALSE, NULL);

INSERT INTO batch.file_channel_config (
    id, tenant_id, channel_code, channel_name, channel_type, target_endpoint, auth_type, config_json, receipt_policy,
    timeout_seconds, enabled, created_at, updated_at
) VALUES
    (5101, 'default-tenant', 'api_dispatch', 'API Dispatch', 'API', 'http://localhost:8090/api/dispatch', 'TOKEN', jsonb_build_object('target_endpoint', 'http://localhost:8090/api/dispatch', 'headers', jsonb_build_object('X-Tenant-Id', 'default-tenant')), 'SYNC', 30, TRUE, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (5102, 'default-tenant', 'api_push_dispatch', 'API Push Dispatch', 'API_PUSH', 'http://localhost:8090/api/push', 'TOKEN', jsonb_build_object('target_endpoint', 'http://localhost:8090/api/push', 'api_push_api_key', 'demo-push-key', 'authorization', 'Bearer demo-token', 'receipt_poll_url', 'http://localhost:8090/api/push/receipt'), 'ASYNC', 30, TRUE, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (5103, 'default-tenant', 'local_dispatch', 'Local Dispatch', 'LOCAL', '/tmp/batch/local-dispatch', 'NONE', jsonb_build_object('target_endpoint', '/tmp/batch/local-dispatch'), 'NONE', 10, TRUE, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (5104, 'default-tenant', 'nas_dispatch', 'NAS Dispatch', 'NAS', NULL, 'PASSWORD', jsonb_build_object('target_endpoint', '/mnt/nas/batch', 'nas_remote_directory', '/mnt/nas/batch', 'nas_remote_file_name', 'settlement.csv'), 'SYNC', 30, TRUE, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (5105, 'default-tenant', 'oss_dispatch', 'OSS Dispatch', 'OSS', NULL, 'TOKEN', jsonb_build_object('oss_bucket', 'batch-dev', 'oss_object_prefix', 'dispatch/', 'receipt_poll_url', 'http://localhost:19000/receipt'), 'POLLING', 30, TRUE, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (5106, 'default-tenant', 'sftp_dispatch', 'SFTP Dispatch', 'SFTP', 'sftp.example.com', 'PASSWORD', jsonb_build_object('sftp_host', 'sftp.example.com', 'sftp_port', 22, 'sftp_user', 'batch', 'sftp_password', 'batch-pass', 'sftp_remote_directory', '/inbox', 'sftp_remote_file_name', 'settlement.csv'), 'NONE', 45, TRUE, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (5107, 'default-tenant', 'email_dispatch', 'Email Dispatch', 'EMAIL', 'ops@example.com', 'PASSWORD', jsonb_build_object('smtp_host', 'smtp.example.com', 'smtp_port', 587, 'smtp_username', 'batch@example.com', 'smtp_password', 'batch-pass', 'smtp_starttls', TRUE, 'mail_from', 'batch@example.com', 'mail_to', 'ops@example.com', 'mail_subject', 'Batch Dispatch'), 'SYNC', 45, TRUE, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08');

INSERT INTO batch.file_record (
    id, tenant_id, file_code, biz_type, file_category, file_name, original_file_name, file_ext, file_format_type,
    charset, mime_type, file_size_bytes, checksum_type, checksum_value, storage_type, storage_path, storage_bucket,
    file_version, file_generation_no, is_latest, source_type, source_ref, file_status, biz_date, trace_id, metadata_json,
    created_at, updated_at
) VALUES
    (5201, 'default-tenant', 'FILE-IMP-001', 'CUSTOMER', 'INPUT', 'customer-account-20260322.csv', 'customer-account-20260322.csv', 'csv', 'DELIMITED', 'UTF-8', 'text/csv', 1890, 'SHA-256', 'sha256-import-001', 'S3', 'ingress/import/customer-account-20260322.csv', 'batch-dev', 'v1', 1, TRUE, 'API', 'req-import-001', 'RECEIVED', DATE '2026-03-22', 'trace-import-001', jsonb_build_object(
        'templateCode', 'import_customer_v1',
        'fileGroupCode', 'cust-import-20260322',
        'waitFileGroupMode', 'ALL_OF',
        'requiredFileSet', 'customer-account-20260322.csv',
        'arrivalTimeoutAction', 'MANUAL_CONFIRM',
        'arrivalState', 'WAITING_ARRIVAL',
        'expectedArrivalTime', '2026-03-22T07:30:00+08:00',
        'latestTolerableTime', '2026-03-22T09:00:00+08:00',
        'preview_masking_enabled', true,
        'log_masking_enabled', true
    ), TIMESTAMPTZ '2026-03-22 07:20:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (5202, 'default-tenant', 'FILE-IMP-002', 'CUSTOMER', 'INPUT', 'customer-account-20260322.json', 'customer-account-20260322.json', 'json', 'JSON', 'UTF-8', 'application/json', 1420, 'SHA-256', 'sha256-import-002', 'S3', 'ingress/import/customer-account-20260322.json', 'batch-dev', 'v1', 1, TRUE, 'SYSTEM', 'import-scan-001', 'VALIDATED', DATE '2026-03-22', 'trace-import-002', jsonb_build_object('templateCode', 'import_customer_json_v1', 'arrivalState', 'TRIGGERED', 'preview_masking_enabled', true, 'log_masking_enabled', true), TIMESTAMPTZ '2026-03-22 08:05:00+08', TIMESTAMPTZ '2026-03-22 08:05:00+08'),
    (5203, 'default-tenant', 'FILE-EXP-001', 'SETTLEMENT', 'OUTPUT', 'settlement-20260322.csv', 'settlement-20260322.csv', 'csv', 'DELIMITED', 'UTF-8', 'text/csv', 4096, 'SHA-256', 'sha256-export-001', 'S3', 'outbound/settlement/settlement-20260322.csv.part', 'batch-dev', 'v1', 1, TRUE, 'GENERATED', 'export-settlement-001', 'GENERATED', DATE '2026-03-22', 'trace-export-001', jsonb_build_object('templateCode', 'export_settlement_v1', 'content_encryption_enabled', true, 'encryption_key_ref', 'DEFAULT_TEST', 'download_requires_approval', true), TIMESTAMPTZ '2026-03-22 08:10:00+08', TIMESTAMPTZ '2026-03-22 08:20:20+08'),
    (5204, 'default-tenant', 'FILE-ARC-001', 'SETTLEMENT', 'ARCHIVE', 'settlement-20260315.csv', 'settlement-20260315.csv', 'csv', 'DELIMITED', 'UTF-8', 'text/csv', 3900, 'SHA-256', 'sha256-archive-001', 'S3', 'archive/settlement/settlement-20260315.csv', 'batch-dev', 'v1', 1, FALSE, 'SYSTEM', 'archive-job', 'ARCHIVED', DATE '2026-03-15', 'trace-archive-001', jsonb_build_object('cleanupReason', 'ARCHIVE_RETENTION_EXPIRED'), TIMESTAMPTZ '2026-03-22 06:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (5205, 'tenant-finance', 'FILE-WF-001', 'SETTLEMENT', 'OUTPUT', 'finance-recon-20260322.xlsx', 'finance-recon-20260322.xlsx', 'xlsx', 'EXCEL', 'UTF-8', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 8192, 'SHA-256', 'sha256-workflow-001', 'S3', 'outbound/finance/finance-recon-20260322.xlsx.part', 'batch-dev', 'v1', 1, TRUE, 'GENERATED', 'finance-recon', 'DISPATCHED', DATE '2026-03-22', 'trace-workflow-001', jsonb_build_object('templateCode', 'export_settlement_xlsx_v1', 'content_encryption_enabled', false, 'download_requires_approval', false), TIMESTAMPTZ '2026-03-22 08:12:00+08', TIMESTAMPTZ '2026-03-22 08:22:00+08'),
    (5206, 'default-tenant', 'FILE-DIS-001', 'OUTPUT', 'OUTPUT', 'dispatch-envelope.json', 'dispatch-envelope.json', 'json', 'JSON', 'UTF-8', 'application/json', 1024, 'SHA-256', 'sha256-dispatch-001', 'LOCAL', '/tmp/batch/dispatch-envelope.json', NULL, 'v1', 1, TRUE, 'SYSTEM', 'dispatch-job', 'DISPATCHED', DATE '2026-03-22', 'trace-dispatch-001', jsonb_build_object('channelCode', 'api_push_dispatch', 'receiptStatus', 'PENDING'), TIMESTAMPTZ '2026-03-22 08:15:00+08', TIMESTAMPTZ '2026-03-22 08:15:00+08');

INSERT INTO batch.pipeline_instance (
    id, tenant_id, pipeline_definition_id, job_code, pipeline_type, file_id, related_job_instance_id,
    current_stage, last_success_stage, run_status, trace_id, started_at, finished_at, created_at, updated_at
) VALUES
    (5301, 'default-tenant', 4601, 'import_customer_pipeline', 'IMPORT', 5201, 4001, 'VALIDATE', 'PARSE', 'RUNNING', 'trace-import-001', TIMESTAMPTZ '2026-03-22 08:00:13+08', NULL, TIMESTAMPTZ '2026-03-22 08:00:13+08', TIMESTAMPTZ '2026-03-22 08:00:13+08'),
    (5302, 'default-tenant', 4602, 'export_settlement_pipeline', 'EXPORT', 5203, 4002, 'REGISTER', 'GENERATE', 'SUCCESS', 'trace-export-001', TIMESTAMPTZ '2026-03-22 08:00:23+08', TIMESTAMPTZ '2026-03-22 08:20:20+08', TIMESTAMPTZ '2026-03-22 08:00:23+08', TIMESTAMPTZ '2026-03-22 08:20:20+08'),
    (5303, 'default-tenant', 4603, 'dispatch_settlement_pipeline', 'DISPATCH', 5206, 4004, 'DISPATCH', 'TRANSFER', 'FAILED', 'trace-dispatch-001', TIMESTAMPTZ '2026-03-22 08:15:00+08', NULL, TIMESTAMPTZ '2026-03-22 08:15:00+08', TIMESTAMPTZ '2026-03-22 08:15:00+08');

INSERT INTO batch.pipeline_step_run (
    id, pipeline_instance_id, step_code, stage_code, run_seq, step_status, input_summary, output_summary,
    error_code, error_message, retry_count, duration_ms, started_at, finished_at
) VALUES
    (5401, 5301, 'receive', 'RECEIVE', 1, 'SUCCESS', jsonb_build_object('fileId', 5201), jsonb_build_object('received', true), NULL, NULL, 0, 500, TIMESTAMPTZ '2026-03-22 08:00:13+08', TIMESTAMPTZ '2026-03-22 08:00:14+08'),
    (5402, 5301, 'parse', 'PARSE', 1, 'SUCCESS', jsonb_build_object('fileId', 5201), jsonb_build_object('rows', 1000), NULL, NULL, 0, 1200, TIMESTAMPTZ '2026-03-22 08:00:14+08', TIMESTAMPTZ '2026-03-22 08:00:16+08'),
    (5403, 5301, 'validate', 'VALIDATE', 1, 'RUNNING', jsonb_build_object('rows', 1000), NULL, NULL, NULL, 0, 0, TIMESTAMPTZ '2026-03-22 08:00:16+08', NULL),
    (5404, 5302, 'generate', 'GENERATE', 1, 'SUCCESS', jsonb_build_object('batchId', 2001), jsonb_build_object('rows', 4), NULL, NULL, 0, 1800, TIMESTAMPTZ '2026-03-22 08:00:23+08', TIMESTAMPTZ '2026-03-22 08:00:26+08'),
    (5405, 5302, 'store', 'TRANSFER', 1, 'SUCCESS', jsonb_build_object('path', 'outbound/settlement/settlement-20260322.csv.part'), jsonb_build_object('stored', true), NULL, NULL, 0, 900, TIMESTAMPTZ '2026-03-22 08:00:26+08', TIMESTAMPTZ '2026-03-22 08:00:27+08'),
    (5406, 5303, 'dispatch', 'DISPATCH', 1, 'FAILED', jsonb_build_object('channel', 'api_push_dispatch'), NULL, 'HTTP_500', 'Downstream dispatch API failed', 1, 200, TIMESTAMPTZ '2026-03-22 08:15:00+08', TIMESTAMPTZ '2026-03-22 08:15:00+08');

INSERT INTO batch.file_dispatch_record (
    id, tenant_id, file_id, pipeline_instance_id, channel_code, dispatch_target, dispatch_status,
    dispatch_attempt, receipt_code, receipt_status, external_request_id, error_code, error_message,
    dispatched_at, ack_at, created_at, updated_at
) VALUES
    (5501, 'default-tenant', 5203, 5302, 'api_dispatch', 'http://localhost:8090/api/dispatch', 'ACKED', 1, 'R-API-001', 'SUCCESS', 'EXT-API-001', NULL, NULL, TIMESTAMPTZ '2026-03-22 08:10:30+08', TIMESTAMPTZ '2026-03-22 08:10:31+08', TIMESTAMPTZ '2026-03-22 08:10:30+08', TIMESTAMPTZ '2026-03-22 08:10:31+08'),
    (5502, 'default-tenant', 5203, 5302, 'api_push_dispatch', 'http://localhost:8090/api/push', 'SENT', 1, 'R-PUSH-001', 'PENDING', 'EXT-PUSH-001', NULL, NULL, TIMESTAMPTZ '2026-03-22 08:10:32+08', NULL, TIMESTAMPTZ '2026-03-22 08:10:32+08', TIMESTAMPTZ '2026-03-22 08:10:32+08'),
    (5503, 'default-tenant', 5203, 5302, 'local_dispatch', '/tmp/batch/local-dispatch', 'ACKED', 1, 'R-LOCAL-001', 'SUCCESS', 'EXT-LOCAL-001', NULL, NULL, TIMESTAMPTZ '2026-03-22 08:10:33+08', TIMESTAMPTZ '2026-03-22 08:10:34+08', TIMESTAMPTZ '2026-03-22 08:10:33+08', TIMESTAMPTZ '2026-03-22 08:10:34+08'),
    (5504, 'default-tenant', 5203, 5302, 'nas_dispatch', '/mnt/nas/batch', 'FAILED', 2, 'R-NAS-001', 'FAILED', 'EXT-NAS-001', 'NAS_UNREACHABLE', 'NAS target unreachable', TIMESTAMPTZ '2026-03-22 08:10:35+08', NULL, TIMESTAMPTZ '2026-03-22 08:10:35+08', TIMESTAMPTZ '2026-03-22 08:10:35+08'),
    (5505, 'default-tenant', 5205, 5303, 'oss_dispatch', 'batch-dev/outbound/finance/', 'SENT', 1, 'R-OSS-001', 'PENDING', 'EXT-OSS-001', NULL, NULL, TIMESTAMPTZ '2026-03-22 08:15:00+08', NULL, TIMESTAMPTZ '2026-03-22 08:15:00+08', TIMESTAMPTZ '2026-03-22 08:15:00+08'),
    (5506, 'default-tenant', 5205, 5303, 'sftp_dispatch', 'sftp.example.com:/inbox', 'FAILED', 1, 'R-SFTP-001', 'FAILED', 'EXT-SFTP-001', 'SFTP_AUTH_FAILED', 'Authentication failed', TIMESTAMPTZ '2026-03-22 08:15:01+08', NULL, TIMESTAMPTZ '2026-03-22 08:15:01+08', TIMESTAMPTZ '2026-03-22 08:15:01+08'),
    (5507, 'default-tenant', 5205, 5303, 'email_dispatch', 'ops@example.com', 'SENT', 1, 'R-MAIL-001', 'PENDING', 'EXT-MAIL-001', NULL, NULL, TIMESTAMPTZ '2026-03-22 08:15:02+08', NULL, TIMESTAMPTZ '2026-03-22 08:15:02+08', TIMESTAMPTZ '2026-03-22 08:15:02+08');

INSERT INTO batch.file_audit_log (
    id, tenant_id, file_id, operation_type, operation_result, operator_type, operator_id, trace_id,
    evidence_ref, detail_summary, created_at
) VALUES
    (5601, 'default-tenant', 5201, 'RECEIVE', 'SUCCESS', 'SYSTEM', 'import-ingress-scanner', 'trace-import-001', 'ingress/import/customer-account-20260322.csv', jsonb_build_object('state', 'RECEIVED'), TIMESTAMPTZ '2026-03-22 08:00:11+08'),
    (5602, 'default-tenant', 5201, 'VALIDATE', 'FAILED', 'SYSTEM', 'import-worker', 'trace-import-001', 'validate-step', jsonb_build_object('errorCode', 'IMPORT_VALIDATE_REQUIRED'), TIMESTAMPTZ '2026-03-22 08:00:14+08'),
    (5603, 'default-tenant', 5203, 'GENERATE', 'SUCCESS', 'SYSTEM', 'export-worker', 'trace-export-001', 'generate-step', jsonb_build_object('rows', 4), TIMESTAMPTZ '2026-03-22 08:00:23+08'),
    (5604, 'default-tenant', 5203, 'DISPATCH', 'FAILED', 'SYSTEM', 'dispatch-worker', 'trace-dispatch-001', 'api_push_dispatch', jsonb_build_object('errorCode', 'HTTP_500'), TIMESTAMPTZ '2026-03-22 08:15:00+08'),
    (5605, 'tenant-finance', 5205, 'DISPATCH', 'SUCCESS', 'SYSTEM', 'dispatch-worker', 'trace-workflow-001', 'oss-dispatch', jsonb_build_object('channel', 'oss_dispatch'), TIMESTAMPTZ '2026-03-22 08:12:00+08'),
    (5606, 'default-tenant', 5204, 'CLEANUP', 'SUCCESS', 'SYSTEM', 'file-governance-scheduler', 'trace-archive-001', 'cleanup-5204', jsonb_build_object('cleanupReason', 'ARCHIVE_RETENTION_EXPIRED'), TIMESTAMPTZ '2026-03-22 08:30:00+08');

INSERT INTO batch.job_execution_log (
    id, tenant_id, job_instance_id, job_partition_id, log_level, log_type, trace_id, message, detail_ref,
    extra_json, created_at
) VALUES
    (5701, 'default-tenant', 4001, 4102, 'WARN', 'ALARM', 'trace-import-001', 'job SLA violated: DEADLINE_EXCEEDED', 'job-sla', jsonb_build_object('violationReason', 'DEADLINE_EXCEEDED'), TIMESTAMPTZ '2026-03-22 08:05:00+08'),
    (5702, 'default-tenant', 4001, 4102, 'ERROR', 'RETRY', 'trace-import-001', 'retry scheduled for failed partition', 'retry', jsonb_build_object('partitionId', 4102), TIMESTAMPTZ '2026-03-22 08:05:01+08'),
    (5703, 'default-tenant', 4002, 4103, 'INFO', 'AUDIT', 'trace-export-001', 'export completed', 'export', jsonb_build_object('rows', 4), TIMESTAMPTZ '2026-03-22 08:20:20+08'),
    (5704, 'tenant-finance', 4003, 4105, 'WARN', 'BUSINESS', 'trace-workflow-001', 'workflow waiting for next node', 'workflow', jsonb_build_object('node', 'IMPORT_STEP'), TIMESTAMPTZ '2026-03-22 08:10:00+08');

INSERT INTO batch.retry_schedule (
    id, tenant_id, related_type, related_id, retry_policy, retry_count, max_retry_count, next_retry_at,
    retry_status, dedup_key, last_error_code, last_error_message, created_at, updated_at
) VALUES
    (5801, 'default-tenant', 'JOB_PARTITION', 4102, 'EXPONENTIAL', 1, 3, TIMESTAMPTZ '2026-03-22 08:10:00+08', 'WAITING', 'default-tenant:4102:1', 'IMPORT_VALIDATE_REQUIRED', 'Required fields missing', TIMESTAMPTZ '2026-03-22 08:05:00+08', TIMESTAMPTZ '2026-03-22 08:05:00+08'),
    (5802, 'default-tenant', 'JOB_TASK', 4202, 'FIXED', 2, 3, TIMESTAMPTZ '2026-03-22 08:15:00+08', 'FAILED', 'default-tenant:4202:2', 'IMPORT_VALIDATE_REQUIRED', 'Required fields missing', TIMESTAMPTZ '2026-03-22 08:05:00+08', TIMESTAMPTZ '2026-03-22 08:05:00+08'),
    (5803, 'tenant-finance', 'PIPELINE_INSTANCE', 5303, 'EXPONENTIAL', 1, 2, TIMESTAMPTZ '2026-03-22 08:20:00+08', 'WAITING', 'tenant-finance:5303:1', 'HTTP_500', 'Dispatch failed', TIMESTAMPTZ '2026-03-22 08:15:01+08', TIMESTAMPTZ '2026-03-22 08:15:01+08'),
    (5804, 'default-tenant', 'FILE_DISPATCH', 5504, 'FIXED', 2, 3, TIMESTAMPTZ '2026-03-22 08:25:00+08', 'EXHAUSTED', 'default-tenant:5504:2', 'NAS_UNREACHABLE', 'NAS target unreachable', TIMESTAMPTZ '2026-03-22 08:10:35+08', TIMESTAMPTZ '2026-03-22 08:10:35+08');

INSERT INTO batch.dead_letter_task (
    id, tenant_id, source_type, source_id, dead_letter_reason, payload_ref, replay_status, replay_count,
    last_replay_at, last_replay_result, trace_id, created_at, updated_at
) VALUES
    (5901, 'default-tenant', 'JOB_PARTITION', 4102, 'Validation exceeded max retries', 'dlq/job-partition/4102.json', 'NEW', 0, NULL, NULL, 'trace-import-001', TIMESTAMPTZ '2026-03-22 08:05:00+08', TIMESTAMPTZ '2026-03-22 08:05:00+08'),
    (5902, 'default-tenant', 'FILE_DISPATCH', 5504, 'NAS dispatch failed repeatedly', 'dlq/file-dispatch/5504.json', 'FAILED', 1, TIMESTAMPTZ '2026-03-22 08:12:00+08', 'REPLAY_FAILED', 'trace-dispatch-001', TIMESTAMPTZ '2026-03-22 08:12:00+08', TIMESTAMPTZ '2026-03-22 08:12:00+08'),
    (5903, 'tenant-finance', 'PIPELINE_INSTANCE', 5303, 'Dispatch channel rejected payload', 'dlq/pipeline-instance/5303.json', 'GIVE_UP', 3, TIMESTAMPTZ '2026-03-22 08:18:00+08', 'REPLAY_GIVE_UP', 'trace-workflow-001', TIMESTAMPTZ '2026-03-22 08:18:00+08', TIMESTAMPTZ '2026-03-22 08:18:00+08');

INSERT INTO batch.outbox_event (
    id, tenant_id, aggregate_type, aggregate_id, event_type, event_key, payload_json, publish_status,
    publish_attempt, next_publish_at, trace_id, created_at, updated_at
) VALUES
    (6001, 'default-tenant', 'JOB_TASK', 4202, 'TASK_RETRY', 'default-tenant:task:4202', jsonb_build_object('jobTaskId', 4202, 'event', 'retry'), 'NEW', 0, TIMESTAMPTZ '2026-03-22 08:10:00+08', 'trace-import-001', TIMESTAMPTZ '2026-03-22 08:05:00+08', TIMESTAMPTZ '2026-03-22 08:05:00+08'),
    (6002, 'default-tenant', 'JOB_TASK', 4203, 'TASK_DISPATCH', 'default-tenant:task:4203', jsonb_build_object('jobTaskId', 4203, 'event', 'dispatch'), 'PUBLISHED', 1, NULL, 'trace-export-001', TIMESTAMPTZ '2026-03-22 08:00:23+08', TIMESTAMPTZ '2026-03-22 08:00:23+08'),
    (6003, 'tenant-finance', 'PIPELINE_INSTANCE', 5303, 'PIPELINE_RETRY', 'tenant-finance:pipeline:5303', jsonb_build_object('pipelineInstanceId', 5303, 'event', 'retry'), 'FAILED', 1, TIMESTAMPTZ '2026-03-22 08:20:00+08', 'trace-workflow-001', TIMESTAMPTZ '2026-03-22 08:15:01+08', TIMESTAMPTZ '2026-03-22 08:15:01+08'),
    (6004, 'default-tenant', 'FILE_DISPATCH', 5504, 'FILE_DISPATCH_RETRY', 'default-tenant:file-dispatch:5504', jsonb_build_object('dispatchRecordId', 5504, 'event', 'retry'), 'GIVE_UP', 3, NULL, 'trace-dispatch-001', TIMESTAMPTZ '2026-03-22 08:10:35+08', TIMESTAMPTZ '2026-03-22 08:10:35+08');

INSERT INTO batch.compensation_command (
    id, tenant_id, command_no, compensation_type, target_id, job_code, biz_date, batch_no, related_job_instance_id,
    related_file_id, approval_id, operator_id, reason, strategy, command_status, trace_id, result_summary, error_code,
    error_message, created_at, finished_at
) VALUES
    (6101, 'default-tenant', 'COMP-20260322-001', 'PARTITION', 4102, 'import_customer_job', DATE '2026-03-22', 'BATCH-IMP-20260322-001', 4001, 5201, 'APP-20260322-001', 'ops-user', 'Retry failed partition', 'STEP_RETRY', 'SUCCESS', 'trace-import-001', jsonb_build_object('replayed', true), NULL, NULL, TIMESTAMPTZ '2026-03-22 08:06:00+08', TIMESTAMPTZ '2026-03-22 08:06:05+08'),
    (6102, 'default-tenant', 'COMP-20260322-002', 'FILE', 5504, 'export_settlement_job', DATE '2026-03-22', 'BATCH-EXP-20260322-001', 4002, 5203, 'APP-20260322-002', 'admin', 'Recover NAS dispatch failure', 'FILE_RETRY', 'RUNNING', 'trace-dispatch-001', NULL, NULL, NULL, TIMESTAMPTZ '2026-03-22 08:12:00+08', NULL),
    (6103, 'tenant-finance', 'COMP-20260322-003', 'DLQ', 5903, 'finance_recon_workflow', DATE '2026-03-22', 'BATCH-WF-20260322-001', 4003, 5205, 'APP-20260322-003', 'ops-user', 'Replay dead letter', 'DLQ_REPLAY', 'FAILED', 'trace-workflow-001', NULL, 'DLQ_REPLAY_FAILED', 'Target unavailable', TIMESTAMPTZ '2026-03-22 08:18:30+08', TIMESTAMPTZ '2026-03-22 08:18:45+08'),
    (6104, 'default-tenant', 'COMP-20260322-004', 'JOB', 4004, 'export_settlement_job', DATE '2026-03-22', 'BATCH-EXP-20260322-002', 4004, 5206, 'APP-20260322-004', 'admin', 'Re-run manual export', 'JOB_RERUN', 'PENDING', 'trace-export-002', NULL, NULL, NULL, TIMESTAMPTZ '2026-03-22 08:00:41+08', NULL);

INSERT INTO batch.quota_runtime_state (
    id, tenant_id, quota_scope, owner_code, quota_reset_policy, window_started_at, window_expires_at,
    peak_borrowed_count, last_reset_at, created_at, updated_at
) VALUES
    (6201, 'default-tenant', 'TENANT', 'default-policy', 'SLIDING_WINDOW', TIMESTAMPTZ '2026-03-22 00:00:00+08', TIMESTAMPTZ '2026-03-23 00:00:00+08', 6, TIMESTAMPTZ '2026-03-22 00:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (6202, 'default-tenant', 'QUEUE', 'import_queue', 'CALENDAR_DAY', TIMESTAMPTZ '2026-03-22 00:00:00+08', TIMESTAMPTZ '2026-03-23 00:00:00+08', 4, TIMESTAMPTZ '2026-03-22 00:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (6203, 'tenant-finance', 'TENANT', 'finance-policy', 'CALENDAR_DAY', TIMESTAMPTZ '2026-03-22 00:00:00+08', TIMESTAMPTZ '2026-03-23 00:00:00+08', 3, TIMESTAMPTZ '2026-03-22 00:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08');

INSERT INTO batch.tenant_scheduler_snapshot (
    id, tenant_id, snapshot_at, fair_share_group, policy_code, active_jobs, active_partitions, max_jobs_base,
    burst_limit, effective_job_cap, group_active_jobs, group_max_jobs, quota_reset_policy, online_workers, detail_json
) VALUES
    (6301, 'default-tenant', TIMESTAMPTZ '2026-03-22 08:00:00+08', 'core', 'default-policy', 2, 4, 8, 2, 10, 2, 6, 'SLIDING_WINDOW', 3, jsonb_build_object('queues', jsonb_build_array('import_queue','export_queue'))),
    (6302, 'tenant-finance', TIMESTAMPTZ '2026-03-22 08:00:00+08', 'finance-core', 'finance-policy', 1, 2, 6, 1, 7, 1, 4, 'CALENDAR_DAY', 1, jsonb_build_object('queues', jsonb_build_array('finance_export_queue')));

INSERT INTO batch.file_channel_health (
    id, tenant_id, channel_code, channel_type, health_status, consecutive_failures, last_probe_at,
    last_success_at, last_failure_at, next_probe_at, probe_message, probe_evidence, created_at, updated_at
) VALUES
    (6401, 'default-tenant', 'nas_dispatch', 'NAS', 'HEALTHY', 0, TIMESTAMPTZ '2026-03-22 08:10:00+08', TIMESTAMPTZ '2026-03-22 08:10:00+08', NULL, TIMESTAMPTZ '2026-03-22 08:11:00+08', 'probe ok', 'nas://batch-dev/inbox', TIMESTAMPTZ '2026-03-22 08:10:00+08', TIMESTAMPTZ '2026-03-22 08:10:00+08'),
    (6402, 'default-tenant', 'oss_dispatch', 'OSS', 'DEGRADED', 2, TIMESTAMPTZ '2026-03-22 08:15:00+08', TIMESTAMPTZ '2026-03-22 08:05:00+08', TIMESTAMPTZ '2026-03-22 08:15:00+08', TIMESTAMPTZ '2026-03-22 08:20:00+08', 'intermittent failures', 'oss://batch-dev/outbound', TIMESTAMPTZ '2026-03-22 08:15:00+08', TIMESTAMPTZ '2026-03-22 08:15:00+08'),
    (6403, 'default-tenant', 'email_dispatch', 'EMAIL', 'UNHEALTHY', 5, TIMESTAMPTZ '2026-03-22 08:16:00+08', TIMESTAMPTZ '2026-03-22 08:04:00+08', TIMESTAMPTZ '2026-03-22 08:16:00+08', TIMESTAMPTZ '2026-03-22 08:30:00+08', 'smtp auth failed', 'mailto:ops@example.com', TIMESTAMPTZ '2026-03-22 08:16:00+08', TIMESTAMPTZ '2026-03-22 08:16:00+08');

INSERT INTO batch.approval_command (
    id, tenant_id, approval_no, approval_type, action_type, target_type, target_id, payload_json,
    approval_status, requester_id, approver_id, rejection_reason, approval_reason, source_trace_id,
    source_idempotency_key, approved_at, executed_at, created_at, updated_at
) VALUES
    (6501, 'default-tenant', 'APP-20260322-001', 'COMPENSATION', 'COMPENSATION', 'JOB_PARTITION', '4102', jsonb_build_object('jobPartitionId', 4102, 'reason', 'retry failed partition'), 'APPROVED', 'ops-user', 'sre-lead', NULL, 'Approved for retry', 'trace-import-001', 'default-tenant:APP-20260322-001', TIMESTAMPTZ '2026-03-22 08:06:00+08', NULL, TIMESTAMPTZ '2026-03-22 08:05:50+08', TIMESTAMPTZ '2026-03-22 08:06:00+08'),
    (6502, 'default-tenant', 'APP-20260322-002', 'DOWNLOAD', 'DOWNLOAD', 'FILE', '5203', jsonb_build_object('fileId', 5203, 'download', true), 'PENDING', 'admin', NULL, NULL, 'Awaiting approval', 'trace-export-001', 'default-tenant:APP-20260322-002', NULL, NULL, TIMESTAMPTZ '2026-03-22 08:10:00+08', TIMESTAMPTZ '2026-03-22 08:10:00+08'),
    (6503, 'tenant-finance', 'APP-20260322-003', 'CATCH_UP', 'CATCH_UP', 'JOB', '4003', jsonb_build_object('jobInstanceId', 4003, 'bizDate', '2026-03-22'), 'EXECUTED', 'ops-user', 'finance-lead', NULL, 'Catch-up completed', 'trace-workflow-001', 'tenant-finance:APP-20260322-003', TIMESTAMPTZ '2026-03-22 08:20:00+08', TIMESTAMPTZ '2026-03-22 08:20:10+08', TIMESTAMPTZ '2026-03-22 08:19:50+08', TIMESTAMPTZ '2026-03-22 08:20:10+08'),
    (6504, 'default-tenant', 'APP-20260322-004', 'DLQ_REPLAY', 'DLQ_REPLAY', 'DEAD_LETTER_TASK', '5902', jsonb_build_object('deadLetterTaskId', 5902, 'reason', 'replay dead letter'), 'REJECTED', 'ops-user', 'sre-lead', 'Source still unavailable', NULL, 'trace-dispatch-001', 'default-tenant:APP-20260322-004', NULL, NULL, TIMESTAMPTZ '2026-03-22 08:12:30+08', TIMESTAMPTZ '2026-03-22 08:12:30+08');

INSERT INTO batch.alert_event (
    id, tenant_id, service_name, alert_type, severity, title, detail_json, dedup_fingerprint,
    occurrence_count, first_seen_at, last_seen_at, trace_id, status, created_at, updated_at
) VALUES
    (6601, 'default-tenant', 'batch-orchestrator', 'JOB_SLA_VIOLATION', 'WARN', 'Import job SLA warning', jsonb_build_object('jobInstanceId', 4001, 'deadlineDelaySeconds', 3600), 'default-tenant:JOB_SLA_VIOLATION:4001', 3, TIMESTAMPTZ '2026-03-22 08:05:00+08', TIMESTAMPTZ '2026-03-22 08:08:00+08', 'trace-import-001', 'OPEN', TIMESTAMPTZ '2026-03-22 08:05:00+08', TIMESTAMPTZ '2026-03-22 08:08:00+08'),
    (6602, 'default-tenant', 'batch-worker-dispatch', 'DISPATCH_FAILURE', 'ERROR', 'NAS dispatch failure', jsonb_build_object('channelCode', 'nas_dispatch', 'errorCode', 'NAS_UNREACHABLE'), 'default-tenant:DISPATCH_FAILURE:nas_dispatch', 2, TIMESTAMPTZ '2026-03-22 08:10:35+08', TIMESTAMPTZ '2026-03-22 08:12:35+08', 'trace-dispatch-001', 'OPEN', TIMESTAMPTZ '2026-03-22 08:10:35+08', TIMESTAMPTZ '2026-03-22 08:12:35+08'),
    (6603, 'tenant-finance', 'batch-worker-dispatch', 'CHANNEL_UNHEALTHY', 'CRITICAL', 'Email channel unhealthy', jsonb_build_object('channelCode', 'email_dispatch', 'healthStatus', 'UNHEALTHY'), 'tenant-finance:CHANNEL_UNHEALTHY:email_dispatch', 1, TIMESTAMPTZ '2026-03-22 08:16:00+08', TIMESTAMPTZ '2026-03-22 08:16:00+08', 'trace-workflow-001', 'OPEN', TIMESTAMPTZ '2026-03-22 08:16:00+08', TIMESTAMPTZ '2026-03-22 08:16:00+08'),
    (6604, 'default-tenant', 'batch-orchestrator', 'QUOTA_BURST', 'WARN', 'Burst quota consumed', jsonb_build_object('queueCode', 'import_queue', 'currentLoad', 4), 'default-tenant:QUOTA_BURST:import_queue', 1, TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08', 'trace-import-001', 'ACKED', TIMESTAMPTZ '2026-03-22 08:00:00+08', TIMESTAMPTZ '2026-03-22 08:00:00+08'),
    (6605, 'tenant-finance', 'batch-orchestrator', 'FILE_ARRIVAL_DELAY', 'WARN', 'Finance file arrival delay', jsonb_build_object('fileId', 5205, 'delaySeconds', 7200), 'tenant-finance:FILE_ARRIVAL_DELAY:5205', 1, TIMESTAMPTZ '2026-03-22 08:12:00+08', TIMESTAMPTZ '2026-03-22 08:12:00+08', 'trace-workflow-001', 'OPEN', TIMESTAMPTZ '2026-03-22 08:12:00+08', TIMESTAMPTZ '2026-03-22 08:12:00+08');

DO $$
DECLARE
    tbl text;
BEGIN
    FOREACH tbl IN ARRAY ARRAY[
        'resource_queue',
        'tenant_quota_policy',
        'batch_window',
        'business_calendar',
        'calendar_holiday',
        'worker_registry',
        'job_definition',
        'workflow_definition',
        'workflow_node',
        'workflow_edge',
        'config_release',
        'secret_version',
        'config_change_log',
        'trigger_request',
        'job_instance',
        'job_partition',
        'job_task',
        'job_step_instance',
        'workflow_run',
        'workflow_node_run',
        'pipeline_definition',
        'pipeline_step_definition',
        'file_template_config',
        'file_channel_config',
        'file_record',
        'pipeline_instance',
        'pipeline_step_run',
        'file_dispatch_record',
        'file_audit_log',
        'job_execution_log',
        'retry_schedule',
        'dead_letter_task',
        'outbox_event',
        'compensation_command',
        'quota_runtime_state',
        'tenant_scheduler_snapshot',
        'file_channel_health',
        'approval_command',
        'alert_event'
    ] LOOP
        EXECUTE format(
            'SELECT setval(pg_get_serial_sequence(%L, %L), COALESCE((SELECT MAX(id) FROM batch.%I), 1), true)',
            'batch.' || tbl,
            'id',
            tbl
        );
    END LOOP;
END $$;

-- V64__normalize_code_conventions.sql 之后所有 code 列（worker_group / job_type 等）以
-- upper-case 为准；selector / dispatcher 入口都做 toUpperOrNull 严格匹配。本 seed 历史
-- 有大量 INSERT 用了小写（'import' / 'export' / 'dispatch'），加载后必须统一大写化，
-- 否则 worker selection 匹配不到 → 任务永远 WAITING。
UPDATE batch.worker_registry SET worker_group = upper(worker_group) WHERE worker_group <> upper(worker_group);
UPDATE batch.resource_queue SET worker_group = upper(worker_group) WHERE worker_group IS NOT NULL AND worker_group <> upper(worker_group);
UPDATE batch.job_definition SET worker_group = upper(worker_group) WHERE worker_group IS NOT NULL AND worker_group <> upper(worker_group);
UPDATE batch.job_instance SET worker_group = upper(worker_group) WHERE worker_group IS NOT NULL AND worker_group <> upper(worker_group);
UPDATE batch.job_partition SET worker_group = upper(worker_group) WHERE worker_group IS NOT NULL AND worker_group <> upper(worker_group);
UPDATE batch.pipeline_definition SET worker_group = upper(worker_group) WHERE worker_group IS NOT NULL AND worker_group <> upper(worker_group);

COMMIT;
