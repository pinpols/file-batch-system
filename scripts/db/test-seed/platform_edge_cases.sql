BEGIN;

TRUNCATE TABLE
    batch.event_delivery_log,
    batch.event_outbox_retry,
    batch.file_error_record
RESTART IDENTITY CASCADE;

INSERT INTO batch.batch_window (
    id, tenant_id, window_code, window_name, timezone, start_time, end_time, end_strategy, out_of_window_action,
    allow_cross_day, enabled, description, created_at, updated_at
) VALUES
    (1210, 'default-tenant', 'business_hours', 'Business Hours Window', 'Asia/Shanghai', TIME '09:00:00', TIME '18:00:00', 'STOP', 'FAIL', FALSE, TRUE, 'System test boundary window', TIMESTAMPTZ '2026-03-22 09:00:00+08', TIMESTAMPTZ '2026-03-22 09:00:00+08'),
    (1211, 'tenant-finance', 'night_continue', 'Night Continue Window', 'Asia/Shanghai', TIME '22:00:00', TIME '23:59:59', 'CONTINUE', 'WAIT', TRUE, TRUE, 'Continue across boundary', TIMESTAMPTZ '2026-03-22 09:00:00+08', TIMESTAMPTZ '2026-03-22 09:00:00+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.business_calendar (
    id, tenant_id, calendar_code, calendar_name, timezone, holiday_roll_rule, catch_up_policy, catch_up_max_days,
    enabled, created_at, updated_at
) VALUES
    (1310, 'default-tenant', 'prev-workday-calendar', 'Prev Workday Calendar', 'Asia/Shanghai', 'PREV_WORKDAY', 'NONE', 0, TRUE, TIMESTAMPTZ '2026-03-22 09:00:00+08', TIMESTAMPTZ '2026-03-22 09:00:00+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.worker_registry (
    id, tenant_id, worker_code, worker_group, host_name, host_ip, process_id, capability_tags, resource_tag,
    status, heartbeat_at, last_start_at, version, current_load, drain_started_at, drain_deadline_at,
    created_at, updated_at
) VALUES
    (1510, 'default-tenant', 'retired-node', 'dispatch', 'retired-host', '127.0.0.10', '11010', jsonb_build_object('role', 'dispatch', 'state', 'retired'), 'delivery', 'DECOMMISSIONED', TIMESTAMPTZ '2026-03-21 18:00:00+08', TIMESTAMPTZ '2026-03-21 18:00:00+08', 'v1', 0, NULL, NULL, TIMESTAMPTZ '2026-03-21 18:00:00+08', TIMESTAMPTZ '2026-03-21 18:00:00+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.job_definition (
    id, tenant_id, job_code, job_name, job_type, biz_type, schedule_type, schedule_expr, timezone, priority,
    queue_code, worker_group, calendar_code, window_code, trigger_mode, dag_enabled, shard_strategy, retry_policy,
    retry_max_count, timeout_seconds, execution_handler, param_schema, default_params, version, enabled, description,
    created_by, updated_by, created_at, updated_at
) VALUES
    (2004, 'default-tenant', 'general_fixed_rate_job', 'General Fixed Rate Job', 'GENERAL', 'HOUSEKEEPING', 'FIXED_RATE', '300', 'Asia/Shanghai', 2, 'dispatch_queue', 'dispatch', 'prev-workday-calendar', 'business_hours', 'SCHEDULED', FALSE, 'NONE', 'NONE', 0, 1800, 'com.example.GeneralFixedRateJobHandler', jsonb_build_object('type', 'object'), jsonb_build_object('kind', 'fixedRate'), 1, TRUE, 'Covers GENERAL + FIXED_RATE + NONE shard_strategy; runs every 300s', 'system', 'system', TIMESTAMPTZ '2026-03-22 09:00:00+08', TIMESTAMPTZ '2026-03-22 09:00:00+08'),
    (2005, 'default-tenant', 'dispatch_event_job', 'Dispatch Event Job', 'DISPATCH', 'DELIVERY', 'EVENT', 'dispatch.completed', 'Asia/Shanghai', 6, 'dispatch_queue', 'dispatch', 'prev-workday-calendar', 'night_continue', 'EVENT', TRUE, 'AUTO', 'FIXED', 1, 1200, 'com.example.DispatchEventJobHandler', jsonb_build_object('type', 'object'), jsonb_build_object('kind', 'event'), 1, TRUE, 'Covers DISPATCH + EVENT schedule', 'system', 'system', TIMESTAMPTZ '2026-03-22 09:00:00+08', TIMESTAMPTZ '2026-03-22 09:00:00+08'),
    (2006, 'default-tenant', 'manual_backfill_job', 'Manual Backfill Job', 'GENERAL', 'MAINTENANCE', 'MANUAL', NULL, 'Asia/Shanghai', 1, 'import_queue', 'import', 'prev-workday-calendar', 'business_hours', 'MANUAL', FALSE, 'NONE', 'NONE', 0, 900, 'com.example.ManualBackfillJobHandler', jsonb_build_object('type', 'object'), jsonb_build_object('kind', 'manual'), 1, TRUE, 'Covers MANUAL trigger mode', 'system', 'system', TIMESTAMPTZ '2026-03-22 09:00:00+08', TIMESTAMPTZ '2026-03-22 09:00:00+08'),
    (2007, 'default-tenant', 'event_backfill_job', 'Event Backfill Job', 'WORKFLOW', 'MAINTENANCE', 'ONE_TIME', NULL, 'Asia/Shanghai', 3, 'import_queue', 'import', 'prev-workday-calendar', 'business_hours', 'EVENT', TRUE, 'AUTO', 'EXPONENTIAL', 2, 2700, 'com.example.EventBackfillWorkflowHandler', jsonb_build_object('type', 'object'), jsonb_build_object('kind', 'oneTime'), 1, TRUE, 'Covers ONE_TIME schedule and EVENT trigger mode', 'system', 'system', TIMESTAMPTZ '2026-03-22 09:00:00+08', TIMESTAMPTZ '2026-03-22 09:00:00+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.workflow_definition (
    id, tenant_id, workflow_code, workflow_name, workflow_type, version, enabled, description, created_by, updated_by, created_at, updated_at
) VALUES
    (2102, 'default-tenant', 'import_pipeline_flow', 'Import Pipeline Flow', 'PIPELINE', 1, TRUE, 'Pipeline workflow coverage', 'system', 'system', TIMESTAMPTZ '2026-03-22 09:00:00+08', TIMESTAMPTZ '2026-03-22 09:00:00+08'),
    (2103, 'default-tenant', 'mixed_orchestration_flow', 'Mixed Orchestration Flow', 'MIXED', 1, TRUE, 'Mixed workflow coverage', 'system', 'system', TIMESTAMPTZ '2026-03-22 09:00:00+08', TIMESTAMPTZ '2026-03-22 09:00:00+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.workflow_node (
    id, tenant_id, workflow_definition_id, node_code, node_name, node_type, related_job_code, related_pipeline_code, worker_group,
    window_code, node_order, retry_policy, retry_max_count, timeout_seconds, node_params, enabled, created_at, updated_at
) VALUES
    (2205, 'default-tenant', 2103, 'GATEWAY_SPLIT', 'Gateway Split', 'GATEWAY', NULL, NULL, NULL, NULL, 1, 'NONE', 0, 0, jsonb_build_object('branch', 'split'), TRUE, TIMESTAMPTZ '2026-03-22 09:00:00+08', TIMESTAMPTZ '2026-03-22 09:00:00+08'),
    (2206, 'default-tenant', 2103, 'PIPELINE_TASK', 'Pipeline Task', 'TASK', 'dispatch_event_job', 'dispatch_settlement_pipeline', 'dispatch', 'night_continue', 2, 'FIXED', 1, 900, jsonb_build_object('branch', 'task'), TRUE, TIMESTAMPTZ '2026-03-22 09:00:00+08', TIMESTAMPTZ '2026-03-22 09:00:00+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.workflow_edge (
    id, tenant_id, workflow_definition_id, from_node_code, to_node_code, edge_type, condition_expr, enabled, created_at, updated_at
) VALUES
    (2304, 'default-tenant', 2103, 'GATEWAY_SPLIT', 'PIPELINE_TASK', 'CONDITION', '$.branch == "task"', TRUE, TIMESTAMPTZ '2026-03-22 09:00:00+08', TIMESTAMPTZ '2026-03-22 09:00:00+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.trigger_request (
    id, tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key, request_payload_hash,
    request_status, related_job_instance_id, trace_id, created_at, updated_at
) VALUES
    (3005, 'default-tenant', 'req-event-001', 'EVENT', 'dispatch_event_job', DATE '2026-03-22', 'default-tenant:req-event-001', 'hash-event-001', 'LAUNCHED', NULL, 'trace-event-001', TIMESTAMPTZ '2026-03-22 09:00:05+08', TIMESTAMPTZ '2026-03-22 09:00:05+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.file_record (
    id, tenant_id, file_code, biz_type, file_category, file_name, original_file_name, file_ext, file_format_type,
    charset, mime_type, file_size_bytes, checksum_type, checksum_value, storage_type, storage_path, storage_bucket,
    file_version, file_generation_no, is_latest, source_type, source_ref, file_status, biz_date, trace_id, metadata_json,
    created_at, updated_at
) VALUES
    (5207, 'default-tenant', 'FILE-IMP-003', 'CUSTOMER', 'INPUT', 'customer-account-20260322.fw', 'customer-account-20260322.fw', 'fw', 'FIXED_WIDTH', 'UTF-8', 'text/plain', 2048, 'SHA-256', 'sha256-import-003', 'NAS', 'ingress/import/customer-account-20260322.fw', 'batch-dev', 'v1', 1, TRUE, 'UPLOAD', 'upload-001', 'PARSING', DATE '2026-03-22', 'trace-import-003', jsonb_build_object('templateCode', 'import_customer_v1', 'fileGroupCode', 'cust-import-20260322', 'preview_masking_enabled', true), TIMESTAMPTZ '2026-03-22 09:00:00+08', TIMESTAMPTZ '2026-03-22 09:00:10+08'),
    (5208, 'default-tenant', 'FILE-IMP-004', 'CUSTOMER', 'INPUT', 'customer-account-20260322.xml', 'customer-account-20260322.xml', 'xml', 'XML', 'UTF-8', 'application/xml', 1536, 'SHA-256', 'sha256-import-004', 'OSS', 'ingress/import/customer-account-20260322.xml', 'batch-dev', 'v1', 1, TRUE, 'SFTP', 'sftp-001', 'PARSED', DATE '2026-03-22', 'trace-import-004', jsonb_build_object('templateCode', 'import_customer_json_v1', 'arrivalState', 'TRIGGERED'), TIMESTAMPTZ '2026-03-22 09:00:02+08', TIMESTAMPTZ '2026-03-22 09:00:12+08'),
    (5209, 'default-tenant', 'FILE-EXP-002', 'SETTLEMENT', 'OUTPUT', 'settlement-20260322.bin', 'settlement-20260322.bin', 'bin', 'BINARY', 'UTF-8', 'application/octet-stream', 8192, 'SHA-256', 'sha256-export-002', 'HDFS', 'outbound/settlement/settlement-20260322.bin', 'batch-dev', 'v1', 1, TRUE, 'GENERATED', 'export-settlement-002', 'LOADED', DATE '2026-03-22', 'trace-export-002', jsonb_build_object('templateCode', 'export_settlement_v1', 'content_encryption_enabled', true), TIMESTAMPTZ '2026-03-22 09:00:03+08', TIMESTAMPTZ '2026-03-22 09:00:13+08'),
    (5210, 'default-tenant', 'FILE-EXP-003', 'SETTLEMENT', 'OUTPUT', 'settlement-20260322.intermediate', 'settlement-20260322.intermediate', 'csv', 'DELIMITED', 'UTF-8', 'text/csv', 1024, 'SHA-256', 'sha256-export-003', 'DB_BLOB', 'outbound/settlement/settlement-20260322.intermediate', NULL, 'v1', 1, FALSE, 'SYSTEM', 'workflow-stage', 'DISPATCHING', DATE '2026-03-22', 'trace-export-003', jsonb_build_object('templateCode', 'export_settlement_v1', 'stage', 'dispatching'), TIMESTAMPTZ '2026-03-22 09:00:04+08', TIMESTAMPTZ '2026-03-22 09:00:14+08'),
    (5211, 'default-tenant', 'FILE-IMP-005', 'CUSTOMER', 'INPUT', 'customer-account-20260322.jsonl', 'customer-account-20260322.jsonl', 'jsonl', 'JSON', 'UTF-8', 'application/json', 512, 'SHA-256', 'sha256-import-005', 'LOCAL', '/tmp/batch/customer-account-20260322.jsonl', NULL, 'v1', 1, TRUE, 'SYSTEM', 'parse-step', 'FAILED', DATE '2026-03-22', 'trace-import-005', jsonb_build_object('templateCode', 'import_customer_json_v1', 'failureStage', 'VALIDATE'), TIMESTAMPTZ '2026-03-22 09:00:05+08', TIMESTAMPTZ '2026-03-22 09:00:15+08'),
    (5212, 'default-tenant', 'FILE-EXP-004', 'SETTLEMENT', 'ARCHIVE', 'settlement-20260318.xlsx', 'settlement-20260318.xlsx', 'xlsx', 'EXCEL', 'UTF-8', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 4096, 'SHA-256', 'sha256-export-004', 'S3', 'archive/settlement/settlement-20260318.xlsx', 'batch-dev', 'v1', 1, FALSE, 'GENERATED', 'archive-job', 'DELETED', DATE '2026-03-18', 'trace-archive-002', jsonb_build_object('cleanupReason', 'MANUAL_DELETE'), TIMESTAMPTZ '2026-03-22 09:00:06+08', TIMESTAMPTZ '2026-03-22 09:00:16+08'),
    (5213, 'default-tenant', 'FILE-INT-001', 'SETTLEMENT', 'INTERMEDIATE', 'settlement-20260322.stage', 'settlement-20260322.stage', 'csv', 'DELIMITED', 'UTF-8', 'text/csv', 256, 'SHA-256', 'sha256-intermediate-001', 'LOCAL', '/tmp/batch/settlement-20260322.stage', NULL, 'v1', 1, TRUE, 'SYSTEM', 'stage-job', 'PARSED', DATE '2026-03-22', 'trace-stage-001', jsonb_build_object('stage', 'intermediate'), TIMESTAMPTZ '2026-03-22 09:00:07+08', TIMESTAMPTZ '2026-03-22 09:00:17+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.job_instance (
    id, tenant_id, job_definition_id, trigger_request_id, job_code, instance_no, biz_date, trigger_type,
    instance_status, queue_code, worker_group, priority, dedup_key, version,
    expected_partition_count, success_partition_count, failed_partition_count, trace_id, params_snapshot,
    started_at, finished_at, batch_no, operator_id, rerun_flag, retry_flag, rerun_reason, related_file_id,
    parent_instance_id, result_summary, created_at, updated_at
) VALUES
    (4005, 'default-tenant', 2004, NULL, 'general_fixed_rate_job', 'GEN-20260322-001', DATE '2026-03-22', 'SCHEDULED', 'CREATED', 'dispatch_queue', 'dispatch', 2, 'default-tenant:GEN-20260322-001', 0, 0, 0, 0, 'trace-gen-001', jsonb_build_object('mode', 'created'), NULL, NULL, 'BATCH-GEN-20260322-001', 'system', FALSE, FALSE, NULL, NULL, NULL, NULL, TIMESTAMPTZ '2026-03-22 09:00:10+08', TIMESTAMPTZ '2026-03-22 09:00:10+08'),
    (4006, 'default-tenant', 2005, 3005, 'dispatch_event_job', 'DIS-20260322-001', DATE '2026-03-22', 'EVENT', 'PARTIAL_FAILED', 'dispatch_queue', 'dispatch', 6, 'default-tenant:DIS-20260322-001', 1, 2, 1, 0, 'trace-dis-001', jsonb_build_object('mode', 'partial_failed'), TIMESTAMPTZ '2026-03-22 09:00:11+08', NULL, 'BATCH-DIS-20260322-001', 'ops-user', FALSE, TRUE, 'event retry', NULL, NULL, NULL, TIMESTAMPTZ '2026-03-22 09:00:11+08', TIMESTAMPTZ '2026-03-22 09:00:11+08'),
    (4007, 'default-tenant', 2006, NULL, 'manual_backfill_job', 'GEN-20260322-002', DATE '2026-03-22', 'MANUAL', 'FAILED', 'import_queue', 'import', 1, 'default-tenant:GEN-20260322-002', 0, 0, 0, 0, 'trace-gen-002', jsonb_build_object('mode', 'failed'), TIMESTAMPTZ '2026-03-22 09:00:12+08', TIMESTAMPTZ '2026-03-22 09:02:12+08', 'BATCH-GEN-20260322-002', 'ops-user', FALSE, FALSE, NULL, NULL, NULL, jsonb_build_object('reason', 'validation'), TIMESTAMPTZ '2026-03-22 09:00:12+08', TIMESTAMPTZ '2026-03-22 09:02:12+08'),
    (4008, 'default-tenant', 2007, NULL, 'event_backfill_job', 'GEN-20260322-003', DATE '2026-03-22', 'EVENT', 'CANCELLED', 'import_queue', 'import', 3, 'default-tenant:GEN-20260322-003', 0, 0, 0, 0, 'trace-gen-003', jsonb_build_object('mode', 'cancelled'), TIMESTAMPTZ '2026-03-22 09:00:13+08', NULL, 'BATCH-GEN-20260322-003', 'ops-user', FALSE, FALSE, NULL, NULL, NULL, NULL, TIMESTAMPTZ '2026-03-22 09:00:13+08', TIMESTAMPTZ '2026-03-22 09:00:13+08'),
    (4009, 'default-tenant', 2004, NULL, 'general_fixed_rate_job', 'GEN-20260322-004', DATE '2026-03-22', 'SCHEDULED', 'TERMINATED', 'dispatch_queue', 'dispatch', 2, 'default-tenant:GEN-20260322-004', 0, 0, 0, 0, 'trace-gen-004', jsonb_build_object('mode', 'terminated'), NULL, TIMESTAMPTZ '2026-03-22 09:03:00+08', 'BATCH-GEN-20260322-004', 'system', FALSE, FALSE, NULL, NULL, NULL, jsonb_build_object('reason', 'manual stop'), TIMESTAMPTZ '2026-03-22 09:00:14+08', TIMESTAMPTZ '2026-03-22 09:03:00+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.job_partition (
    id, tenant_id, job_instance_id, partition_no, partition_key, partition_status, worker_group, worker_code,
    lease_expire_at, retry_count, business_key, idempotency_key, input_snapshot, output_summary,
    started_at, finished_at, created_at, updated_at
) VALUES
    (4111, 'default-tenant', 4005, 1, 'created', 'CREATED', 'dispatch', NULL, NULL, 0, 'GEN-20260322-001:1', 'default-tenant:GEN-20260322-001:1', jsonb_build_object('state', 'created'), NULL, NULL, NULL, TIMESTAMPTZ '2026-03-22 09:00:10+08', TIMESTAMPTZ '2026-03-22 09:00:10+08'),
    (4112, 'default-tenant', 4006, 1, 'retrying', 'RETRYING', 'dispatch', 'dispatch-node-1', TIMESTAMPTZ '2026-03-22 09:20:00+08', 2, 'DIS-20260322-001:1', 'default-tenant:DIS-20260322-001:1', jsonb_build_object('state', 'retrying'), NULL, TIMESTAMPTZ '2026-03-22 09:00:11+08', NULL, TIMESTAMPTZ '2026-03-22 09:00:11+08', TIMESTAMPTZ '2026-03-22 09:00:11+08'),
    (4113, 'default-tenant', 4007, 1, 'cancelled', 'CANCELLED', 'import', NULL, NULL, 0, 'GEN-20260322-002:1', 'default-tenant:GEN-20260322-002:1', jsonb_build_object('state', 'cancelled'), NULL, NULL, NULL, TIMESTAMPTZ '2026-03-22 09:00:12+08', TIMESTAMPTZ '2026-03-22 09:00:12+08'),
    (4114, 'default-tenant', 4009, 1, 'terminated', 'TERMINATED', 'dispatch', 'dispatch-node-1', TIMESTAMPTZ '2026-03-22 09:03:00+08', 1, 'GEN-20260322-004:1', 'default-tenant:GEN-20260322-004:1', jsonb_build_object('state', 'terminated'), NULL, TIMESTAMPTZ '2026-03-22 09:00:14+08', TIMESTAMPTZ '2026-03-22 09:03:00+08', TIMESTAMPTZ '2026-03-22 09:00:14+08', TIMESTAMPTZ '2026-03-22 09:03:00+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.job_task (
    id, tenant_id, job_instance_id, job_partition_id, task_type, task_seq, task_status, assigned_worker_code,
    task_payload, result_summary, error_code, error_message, started_at, finished_at, created_at, updated_at
) VALUES
    (4210, 'default-tenant', 4005, 4111, 'EXECUTION', 1, 'CREATED', NULL, jsonb_build_object('stage', 'created'), NULL, NULL, NULL, NULL, NULL, TIMESTAMPTZ '2026-03-22 09:00:10+08', TIMESTAMPTZ '2026-03-22 09:00:10+08'),
    (4211, 'default-tenant', 4006, 4112, 'EXECUTION', 1, 'CANCELLED', 'dispatch-node-1', jsonb_build_object('stage', 'cancelled'), NULL, NULL, 'Cancelled by operator', TIMESTAMPTZ '2026-03-22 09:00:11+08', TIMESTAMPTZ '2026-03-22 09:01:11+08', TIMESTAMPTZ '2026-03-22 09:00:11+08', TIMESTAMPTZ '2026-03-22 09:01:11+08'),
    (4212, 'default-tenant', 4009, 4114, 'REPLAY', 1, 'TERMINATED', 'dispatch-node-1', jsonb_build_object('stage', 'terminated'), jsonb_build_object('result', 'terminated'), 'JOB_TERMINATED', 'Job terminated before completion', TIMESTAMPTZ '2026-03-22 09:00:14+08', TIMESTAMPTZ '2026-03-22 09:03:00+08', TIMESTAMPTZ '2026-03-22 09:00:14+08', TIMESTAMPTZ '2026-03-22 09:03:00+08'),
    (4213, 'default-tenant', 4009, 4114, 'EXECUTION', 2, 'TERMINATED', 'dispatch-node-1', jsonb_build_object('stage', 'terminated'), jsonb_build_object('result', 'terminated'), 'JOB_TERMINATED', 'Job terminated before completion', TIMESTAMPTZ '2026-03-22 09:00:14+08', TIMESTAMPTZ '2026-03-22 09:03:00+08', TIMESTAMPTZ '2026-03-22 09:00:14+08', TIMESTAMPTZ '2026-03-22 09:03:00+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.job_step_instance (
    id, tenant_id, job_instance_id, job_partition_id, job_task_id, step_code, step_type, step_status, retry_count,
    related_file_id, result_summary, error_code, error_message, version, started_at, finished_at, created_at, updated_at
) VALUES
    (4310, 'default-tenant', 4005, 4111, 4210, 'general_prepare', 'PREPARE', 'WAITING', 0, NULL, NULL, NULL, NULL, 0, NULL, NULL, TIMESTAMPTZ '2026-03-22 09:00:10+08', TIMESTAMPTZ '2026-03-22 09:00:10+08'),
    (4311, 'default-tenant', 4006, 4112, 4211, 'dispatch_retry', 'RETRY', 'RETRYING', 1, NULL, jsonb_build_object('attempts', 1), 'HTTP_503', 'Downstream unavailable', 1, TIMESTAMPTZ '2026-03-22 09:00:11+08', NULL, TIMESTAMPTZ '2026-03-22 09:00:11+08', TIMESTAMPTZ '2026-03-22 09:00:11+08'),
    (4312, 'default-tenant', 4007, 4113, 4212, 'general_cancel', 'CANCEL', 'CANCELLED', 0, NULL, NULL, NULL, NULL, 0, TIMESTAMPTZ '2026-03-22 09:00:12+08', TIMESTAMPTZ '2026-03-22 09:01:12+08', TIMESTAMPTZ '2026-03-22 09:00:12+08', TIMESTAMPTZ '2026-03-22 09:01:12+08'),
    (4313, 'default-tenant', 4009, 4114, 4213, 'general_stop', 'STOP', 'TERMINATED', 0, NULL, NULL, 'JOB_TERMINATED', 'Stopped by operator', 1, TIMESTAMPTZ '2026-03-22 09:00:14+08', TIMESTAMPTZ '2026-03-22 09:03:00+08', TIMESTAMPTZ '2026-03-22 09:00:14+08', TIMESTAMPTZ '2026-03-22 09:03:00+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.workflow_run (
    id, tenant_id, workflow_definition_id, related_job_instance_id, biz_date, run_status, current_node_code,
    trace_id, started_at, finished_at, created_at, updated_at
) VALUES
    (4402, 'default-tenant', 2102, 4005, DATE '2026-03-22', 'CREATED', 'START', 'trace-gen-001', NULL, NULL, TIMESTAMPTZ '2026-03-22 09:00:10+08', TIMESTAMPTZ '2026-03-22 09:00:10+08'),
    (4403, 'default-tenant', 2103, 4006, DATE '2026-03-22', 'SUCCESS', 'PIPELINE_TASK', 'trace-dis-001', TIMESTAMPTZ '2026-03-22 09:00:11+08', TIMESTAMPTZ '2026-03-22 09:20:11+08', TIMESTAMPTZ '2026-03-22 09:00:11+08', TIMESTAMPTZ '2026-03-22 09:20:11+08'),
    (4404, 'default-tenant', 2103, 4007, DATE '2026-03-22', 'FAILED', 'PIPELINE_TASK', 'trace-gen-002', TIMESTAMPTZ '2026-03-22 09:00:12+08', TIMESTAMPTZ '2026-03-22 09:02:12+08', TIMESTAMPTZ '2026-03-22 09:00:12+08', TIMESTAMPTZ '2026-03-22 09:02:12+08'),
    (4405, 'default-tenant', 2103, 4008, DATE '2026-03-22', 'TERMINATED', 'GATEWAY_SPLIT', 'trace-gen-003', TIMESTAMPTZ '2026-03-22 09:00:13+08', TIMESTAMPTZ '2026-03-22 09:01:13+08', TIMESTAMPTZ '2026-03-22 09:00:13+08', TIMESTAMPTZ '2026-03-22 09:01:13+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.workflow_node_run (
    id, workflow_run_id, node_code, node_type, run_seq, node_status, retry_count, error_code, error_message,
    started_at, finished_at, duration_ms
) VALUES
    (4504, 4403, 'GATEWAY_SPLIT', 'GATEWAY', 1, 'FAILED', 1, 'BRANCH_MISMATCH', 'Branch mismatch', TIMESTAMPTZ '2026-03-22 09:00:11+08', TIMESTAMPTZ '2026-03-22 09:00:12+08', 1000),
    (4505, 4404, 'PIPELINE_TASK', 'TASK', 1, 'SKIPPED', 0, NULL, NULL, NULL, NULL, 0),
    (4506, 4405, 'GATEWAY_SPLIT', 'GATEWAY', 1, 'READY', 0, NULL, NULL, NULL, NULL, 0)
ON CONFLICT DO NOTHING;

INSERT INTO batch.pipeline_instance (
    id, tenant_id, pipeline_definition_id, job_code, pipeline_type, file_id, related_job_instance_id,
    current_stage, last_success_stage, run_status, trace_id, started_at, finished_at, created_at, updated_at
) VALUES
    (5304, 'default-tenant', 4601, 'import_customer_pipeline', 'IMPORT', 5207, 4005, 'RECEIVE', NULL, 'CREATED', 'trace-gen-001', NULL, NULL, TIMESTAMPTZ '2026-03-22 09:00:10+08', TIMESTAMPTZ '2026-03-22 09:00:10+08'),
    (5305, 'default-tenant', 4602, 'export_settlement_pipeline', 'EXPORT', 5209, 4006, 'STORE', 'GENERATE', 'COMPENSATING', 'trace-dis-001', TIMESTAMPTZ '2026-03-22 09:00:11+08', NULL, TIMESTAMPTZ '2026-03-22 09:00:11+08', TIMESTAMPTZ '2026-03-22 09:00:11+08'),
    (5306, 'default-tenant', 4603, 'dispatch_settlement_pipeline', 'DISPATCH', 5210, 4009, 'DISPATCH', 'DISPATCH', 'TERMINATED', 'trace-gen-004', TIMESTAMPTZ '2026-03-22 09:00:14+08', TIMESTAMPTZ '2026-03-22 09:03:00+08', TIMESTAMPTZ '2026-03-22 09:00:14+08', TIMESTAMPTZ '2026-03-22 09:03:00+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.pipeline_step_run (
    id, pipeline_instance_id, step_code, stage_code, run_seq, step_status, input_summary, output_summary,
    error_code, error_message, retry_count, duration_ms, started_at, finished_at
) VALUES
    (5410, 5304, 'receive', 'RECEIVE', 1, 'PENDING', jsonb_build_object('fileId', 5207), NULL, NULL, NULL, 0, 0, NULL, NULL),
    (5411, 5305, 'generate', 'GENERATE', 1, 'FAILED', jsonb_build_object('batchId', 2001), NULL, 'WRITE_FAILED', 'Writer failed', 1, 500, TIMESTAMPTZ '2026-03-22 09:00:11+08', TIMESTAMPTZ '2026-03-22 09:00:12+08'),
    (5412, 5305, 'store', 'TRANSFER', 2, 'RETRYING', jsonb_build_object('path', 'outbound/retry.csv.part'), NULL, 'STORE_RETRY', 'Retrying store', 2, 0, TIMESTAMPTZ '2026-03-22 09:00:12+08', NULL),
    (5413, 5306, 'dispatch', 'DISPATCH', 1, 'SKIPPED', jsonb_build_object('channel', 'api_push_dispatch'), jsonb_build_object('skipped', true), NULL, NULL, 0, 0, NULL, NULL)
ON CONFLICT DO NOTHING;

INSERT INTO batch.pipeline_step_run (
    id, pipeline_instance_id, step_code, stage_code, run_seq, step_status, input_summary, output_summary,
    error_code, error_message, retry_count, duration_ms, started_at, finished_at
) VALUES
    (5414, 5306, 'dispatch', 'DISPATCH', 2, 'SUCCESS', jsonb_build_object('channel', 'api_push_dispatch'), jsonb_build_object('ack', true), NULL, NULL, 0, 1200, TIMESTAMPTZ '2026-03-22 09:02:00+08', TIMESTAMPTZ '2026-03-22 09:02:02+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.file_dispatch_record (
    id, tenant_id, file_id, pipeline_instance_id, channel_code, dispatch_target, dispatch_status,
    dispatch_attempt, receipt_code, receipt_status, external_request_id, error_code, error_message,
    dispatched_at, ack_at, created_at, updated_at
) VALUES
    (5510, 'default-tenant', 5209, 5305, 'api_dispatch', 'http://localhost:8090/api/dispatch', 'CREATED', 1, NULL, 'NONE', NULL, NULL, NULL, NULL, NULL, TIMESTAMPTZ '2026-03-22 09:00:13+08', TIMESTAMPTZ '2026-03-22 09:00:13+08'),
    (5511, 'default-tenant', 5210, 5306, 'api_push_dispatch', 'http://localhost:8090/api/push', 'COMPENSATED', 2, 'R-PUSH-002', 'FAILED', 'EXT-PUSH-002', 'HTTP_409', 'Duplicate dispatch prevented', TIMESTAMPTZ '2026-03-22 09:03:00+08', NULL, TIMESTAMPTZ '2026-03-22 09:03:00+08', TIMESTAMPTZ '2026-03-22 09:03:00+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.file_audit_log (
    id, tenant_id, file_id, operation_type, operation_result, operator_type, operator_id, trace_id,
    evidence_ref, detail_summary, created_at
) VALUES
    (5607, 'default-tenant', 5207, 'PARSE', 'SUCCESS', 'USER', 'qa-user', 'trace-import-003', 'parse-step', jsonb_build_object('rows', 100), TIMESTAMPTZ '2026-03-22 09:00:10+08'),
    (5608, 'default-tenant', 5211, 'VALIDATE', 'FAILED', 'API', 'import-api', 'trace-import-005', 'validate-step', jsonb_build_object('errorCode', 'IMPORT_VALIDATE_REQUIRED'), TIMESTAMPTZ '2026-03-22 09:00:15+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.job_execution_log (
    id, tenant_id, job_instance_id, job_partition_id, log_level, log_type, trace_id, message, detail_ref,
    extra_json, created_at
) VALUES
    (5705, 'default-tenant', 4005, 4111, 'DEBUG', 'SYSTEM', 'trace-gen-001', 'general fixed rate job created', 'job-create', jsonb_build_object('jobCode', 'general_fixed_rate_job'), TIMESTAMPTZ '2026-03-22 09:00:10+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.retry_schedule (
    id, tenant_id, related_type, related_id, retry_policy, retry_count, max_retry_count, next_retry_at,
    retry_status, dedup_key, last_error_code, last_error_message, created_at, updated_at
) VALUES
    (5805, 'default-tenant', 'JOB_INSTANCE', 4005, 'FIXED', 0, 3, TIMESTAMPTZ '2026-03-22 09:05:00+08', 'RUNNING', 'default-tenant:4005:0', NULL, NULL, TIMESTAMPTZ '2026-03-22 09:00:10+08', TIMESTAMPTZ '2026-03-22 09:00:10+08'),
    (5806, 'default-tenant', 'PIPELINE_INSTANCE', 5304, 'FIXED', 1, 2, TIMESTAMPTZ '2026-03-22 09:10:00+08', 'SUCCESS', 'default-tenant:5304:1', NULL, NULL, TIMESTAMPTZ '2026-03-22 09:00:10+08', TIMESTAMPTZ '2026-03-22 09:00:10+08'),
    (5807, 'default-tenant', 'FILE_DISPATCH', 5510, 'EXPONENTIAL', 2, 3, TIMESTAMPTZ '2026-03-22 09:15:00+08', 'CANCELLED', 'default-tenant:5510:2', 'HTTP_409', 'Duplicate dispatch prevented', TIMESTAMPTZ '2026-03-22 09:00:13+08', TIMESTAMPTZ '2026-03-22 09:00:13+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.dead_letter_task (
    id, tenant_id, source_type, source_id, dead_letter_reason, payload_ref, replay_status, replay_count,
    last_replay_at, last_replay_result, trace_id, created_at, updated_at
) VALUES
    (5904, 'default-tenant', 'JOB_TASK', 4211, 'Retry in progress', 'dlq/job-task/4211.json', 'REPLAYING', 1, TIMESTAMPTZ '2026-03-22 09:00:11+08', NULL, 'trace-dis-001', TIMESTAMPTZ '2026-03-22 09:00:11+08', TIMESTAMPTZ '2026-03-22 09:00:11+08'),
    (5905, 'default-tenant', 'JOB_INSTANCE', 4005, 'Replayed successfully', 'dlq/job-instance/4005.json', 'SUCCESS', 1, TIMESTAMPTZ '2026-03-22 09:10:00+08', 'REPLAY_SUCCESS', 'trace-gen-001', TIMESTAMPTZ '2026-03-22 09:10:00+08', TIMESTAMPTZ '2026-03-22 09:10:00+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.outbox_event (
    id, tenant_id, aggregate_type, aggregate_id, event_type, event_key, payload_json, publish_status,
    publish_attempt, next_publish_at, trace_id, created_at, updated_at
) VALUES
    (6005, 'default-tenant', 'JOB_INSTANCE', 4005, 'JOB_CREATED', 'default-tenant:job:4005', jsonb_build_object('jobInstanceId', 4005, 'event', 'created'), 'PUBLISHING', 1, TIMESTAMPTZ '2026-03-22 09:05:00+08', 'trace-gen-001', TIMESTAMPTZ '2026-03-22 09:00:10+08', TIMESTAMPTZ '2026-03-22 09:00:10+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.event_outbox_retry (
    id, tenant_id, outbox_event_id, event_key, retry_attempt, retry_status, retry_reason, next_retry_at, trace_id,
    created_at, updated_at
) VALUES
    (6105, 'default-tenant', 6005, 'default-tenant:job:4005', 1, 'WAITING', 'broker unavailable', TIMESTAMPTZ '2026-03-22 09:05:00+08', 'trace-gen-001', TIMESTAMPTZ '2026-03-22 09:00:10+08', TIMESTAMPTZ '2026-03-22 09:00:10+08'),
    (6106, 'default-tenant', 6002, 'default-tenant:task:4203', 1, 'RUNNING', NULL, TIMESTAMPTZ '2026-03-22 09:10:00+08', 'trace-export-001', TIMESTAMPTZ '2026-03-22 09:00:23+08', TIMESTAMPTZ '2026-03-22 09:00:23+08'),
    (6107, 'default-tenant', 6003, 'tenant-finance:pipeline:5303', 2, 'SUCCESS', NULL, NULL, 'trace-workflow-001', TIMESTAMPTZ '2026-03-22 09:15:00+08', TIMESTAMPTZ '2026-03-22 09:15:00+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.event_delivery_log (
    id, tenant_id, outbox_event_id, event_type, event_key, target_topic, target_worker_id, delivery_status,
    delivery_attempt, delivery_summary, error_message, trace_id, created_at, updated_at
) VALUES
    (6205, 'default-tenant', 6005, 'JOB_CREATED', 'default-tenant:job:4005', 'batch.job.created', 'orchestrator-1', 'PUBLISHED', 1, jsonb_build_object('published', true), NULL, 'trace-gen-001', TIMESTAMPTZ '2026-03-22 09:00:10+08', TIMESTAMPTZ '2026-03-22 09:00:10+08'),
    (6206, 'default-tenant', 6003, 'PIPELINE_RETRY', 'tenant-finance:pipeline:5303', 'batch.pipeline.retry', 'orchestrator-1', 'FAILED', 2, jsonb_build_object('published', false), 'Downstream unavailable', 'trace-workflow-001', TIMESTAMPTZ '2026-03-22 09:15:00+08', TIMESTAMPTZ '2026-03-22 09:15:00+08'),
    (6207, 'default-tenant', 6004, 'FILE_DISPATCH_RETRY', 'default-tenant:file-dispatch:5504', 'batch.file.dispatch', 'dispatch-worker-1', 'GIVE_UP', 3, jsonb_build_object('published', false), 'Retry exhausted', 'trace-dispatch-001', TIMESTAMPTZ '2026-03-22 09:16:00+08', TIMESTAMPTZ '2026-03-22 09:16:00+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.file_error_record (
    id, tenant_id, file_id, pipeline_instance_id, pipeline_step_run_id, record_no, error_code, error_message,
    error_stage, is_skipped, skip_action, raw_record, created_at
) VALUES
    (6301, 'default-tenant', 5207, 5304, 5410, 1, 'IMPORT_VALIDATE_REQUIRED', 'Missing customerName', 'VALIDATE', FALSE, NULL, jsonb_build_object('customerNo', 'CUSTX001'), TIMESTAMPTZ '2026-03-22 09:00:12+08'),
    (6302, 'default-tenant', 5209, 5305, 5411, 1, 'WRITE_FAILED', 'Writer failed', 'GENERATE', FALSE, NULL, jsonb_build_object('settlementNo', 'STL-X'), TIMESTAMPTZ '2026-03-22 09:00:13+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.config_release (
    id, tenant_id, config_type, config_key, config_name, config_status, version_no, gray_scope, config_payload,
    effective_from_at, effective_to_at, published_at, rolled_back_at, created_by, updated_by, created_at, updated_at
) VALUES
    (2403, 'default-tenant', 'JOB', 'general_fixed_rate_job', 'General Fixed Rate Job Config', 'DRAFT', 1, NULL, jsonb_build_object('enabled', true), NULL, NULL, NULL, NULL, 'system', 'system', TIMESTAMPTZ '2026-03-22 09:00:00+08', TIMESTAMPTZ '2026-03-22 09:00:00+08'),
    (2404, 'tenant-finance', 'FILE_CHANNEL', 'finance_recon_dispatch', 'Finance Recon Dispatch Config', 'ROLLED_BACK', 3, jsonb_build_object('tenantIds', jsonb_build_array('tenant-finance')), jsonb_build_object('channelCode', 'oss_dispatch'), TIMESTAMPTZ '2026-03-22 09:10:00+08', TIMESTAMPTZ '2026-03-22 09:20:00+08', TIMESTAMPTZ '2026-03-22 09:10:00+08', TIMESTAMPTZ '2026-03-22 09:20:00+08', 'system', 'system', TIMESTAMPTZ '2026-03-22 09:00:00+08', TIMESTAMPTZ '2026-03-22 09:20:00+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.secret_version (
    id, tenant_id, secret_ref, secret_name, version_no, secret_status, current_version,
    rotation_window_start_at, rotation_window_end_at, effective_from_at, effective_to_at,
    secret_payload, rotation_reason, created_by, updated_by, created_at, updated_at
) VALUES
    (2503, 'default-tenant', 'DEFAULT_TEST', 'Default Test KMS Secret', 2, 'DRAFT', FALSE, TIMESTAMPTZ '2026-03-22 09:00:00+08', TIMESTAMPTZ '2026-03-22 09:30:00+08', NULL, NULL, jsonb_build_object('keyRef', 'DEFAULT_TEST_DRAFT'), 'rotation-preview', 'system', 'system', TIMESTAMPTZ '2026-03-22 09:00:00+08', TIMESTAMPTZ '2026-03-22 09:00:00+08'),
    (2504, 'tenant-finance', 'FINANCE_TEST', 'Finance Test Secret', 2, 'ROLLED_BACK', FALSE, TIMESTAMPTZ '2026-03-22 09:00:00+08', TIMESTAMPTZ '2026-03-22 09:30:00+08', TIMESTAMPTZ '2026-03-22 09:05:00+08', TIMESTAMPTZ '2026-03-22 09:20:00+08', jsonb_build_object('keyRef', 'FINANCE_TEST_ROLLBACK'), 'rollback after validation', 'system', 'system', TIMESTAMPTZ '2026-03-22 09:00:00+08', TIMESTAMPTZ '2026-03-22 09:20:00+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.config_change_log (
    id, tenant_id, config_type, config_key, version_no, change_action, change_result, operator_type, operator_id, trace_id, change_summary, created_at
) VALUES
    (2603, 'default-tenant', 'JOB', 'general_fixed_rate_job', 1, 'CREATE', 'SUCCESS', 'API', 'config-api', 'trace-config-003', jsonb_build_object('reason', 'bootstrap'), TIMESTAMPTZ '2026-03-22 09:00:00+08'),
    (2604, 'tenant-finance', 'FILE_CHANNEL', 'finance_recon_dispatch', 3, 'ROLLBACK', 'SUCCESS', 'SYSTEM', 'system', 'trace-config-004', jsonb_build_object('reason', 'rollback config'), TIMESTAMPTZ '2026-03-22 09:20:00+08'),
    (2605, 'tenant-finance', 'SECRET', 'FINANCE_TEST', 2, 'ROTATE', 'FAILED', 'SYSTEM', 'system', 'trace-config-005', jsonb_build_object('reason', 'rotation validation failed'), TIMESTAMPTZ '2026-03-22 09:20:30+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.quota_runtime_state (
    id, tenant_id, quota_scope, owner_code, quota_reset_policy, window_started_at, window_expires_at,
    peak_borrowed_count, last_reset_at, created_at, updated_at
) VALUES
    (6204, 'default-tenant', 'QUEUE', 'dispatch_queue', 'NONE', NULL, NULL, 0, NULL, TIMESTAMPTZ '2026-03-22 09:00:00+08', TIMESTAMPTZ '2026-03-22 09:00:00+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.file_channel_health (
    id, tenant_id, channel_code, channel_type, health_status, consecutive_failures, last_probe_at,
    last_success_at, last_failure_at, next_probe_at, probe_message, probe_evidence, created_at, updated_at
) VALUES
    (6404, 'default-tenant', 'api_dispatch', 'API', 'HEALTHY', 0, TIMESTAMPTZ '2026-03-22 09:00:00+08', TIMESTAMPTZ '2026-03-22 09:00:00+08', NULL, TIMESTAMPTZ '2026-03-22 09:01:00+08', 'probe ok', 'http://localhost:8090/api/dispatch', TIMESTAMPTZ '2026-03-22 09:00:00+08', TIMESTAMPTZ '2026-03-22 09:00:00+08')
ON CONFLICT DO NOTHING;

INSERT INTO batch.alert_event (
    id, tenant_id, service_name, alert_type, severity, title, detail_json, dedup_fingerprint,
    occurrence_count, first_seen_at, last_seen_at, trace_id, status, created_at, updated_at
) VALUES
    (6606, 'default-tenant', 'batch-worker-import', 'IMPORT_WARNING', 'INFO', 'Import info', jsonb_build_object('fileId', 5207, 'detail', 'informational alert'), 'default-tenant:IMPORT_WARNING:5207', 1, TIMESTAMPTZ '2026-03-22 09:00:10+08', TIMESTAMPTZ '2026-03-22 09:00:10+08', 'trace-import-003', 'SUPPRESSED', TIMESTAMPTZ '2026-03-22 09:00:10+08', TIMESTAMPTZ '2026-03-22 09:00:10+08'),
    (6607, 'default-tenant', 'batch-orchestrator', 'JOB_TERMINATED', 'WARN', 'Job terminated', jsonb_build_object('jobInstanceId', 4009, 'reason', 'manual stop'), 'default-tenant:JOB_TERMINATED:4009', 1, TIMESTAMPTZ '2026-03-22 09:03:00+08', TIMESTAMPTZ '2026-03-22 09:03:00+08', 'trace-gen-004', 'CLOSED', TIMESTAMPTZ '2026-03-22 09:03:00+08', TIMESTAMPTZ '2026-03-22 09:03:00+08')
ON CONFLICT DO NOTHING;

COMMIT;
