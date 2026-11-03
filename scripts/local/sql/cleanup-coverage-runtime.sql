-- 用途：仅清理 ta/tb/tc 的 *_COV_* 运行产物，保留租户、定义、模板和其他业务数据。
-- 用法：由 coverage-harness 传入 business_db 后在本地测试库执行，禁止用于生产或非覆盖场景。
-- DANGER：删除运行数据前固定覆盖 ID 并在事务中按外键顺序处理；失败回滚，验收 JSON 保留，重跑 harness 可重建场景。
BEGIN;

CREATE TEMP TABLE coverage_job_instances ON COMMIT DROP AS
SELECT id FROM batch.job_instance
WHERE tenant_id IN ('ta', 'tb', 'tc') AND job_code LIKE '%_COV_%';

CREATE TEMP TABLE coverage_workflow_runs ON COMMIT DROP AS
SELECT id FROM batch.workflow_run
WHERE tenant_id IN ('ta', 'tb', 'tc')
  AND workflow_definition_id IN (
      SELECT id FROM batch.workflow_definition
      WHERE tenant_id IN ('ta', 'tb', 'tc') AND workflow_code LIKE '%_COV_%');

CREATE TEMP TABLE coverage_trigger_requests ON COMMIT DROP AS
SELECT id, request_id FROM batch.trigger_request
WHERE tenant_id IN ('ta', 'tb', 'tc')
  AND (workflow_definition_id IN (
           SELECT id FROM batch.workflow_definition
           WHERE tenant_id IN ('ta', 'tb', 'tc') AND workflow_code LIKE '%_COV_%')
       OR job_code LIKE '%_COV_%'
       OR related_job_instance_id IN (SELECT id FROM coverage_job_instances));

CREATE TEMP TABLE coverage_files ON COMMIT DROP AS
SELECT id FROM batch.file_record
WHERE tenant_id IN ('ta', 'tb', 'tc')
  AND (biz_type LIKE '%_COV_%' OR file_name ILIKE '%coverage%');

CREATE TEMP TABLE coverage_pipeline_instances ON COMMIT DROP AS
SELECT id FROM batch.pipeline_instance
WHERE tenant_id IN ('ta', 'tb', 'tc') AND job_code LIKE '%_COV_%';

CREATE TEMP TABLE coverage_job_tasks ON COMMIT DROP AS
SELECT id, job_partition_id FROM batch.job_task
WHERE tenant_id IN ('ta', 'tb', 'tc')
  AND job_instance_id IN (SELECT id FROM coverage_job_instances);

CREATE TEMP TABLE coverage_job_partitions ON COMMIT DROP AS
SELECT id FROM batch.job_partition
WHERE tenant_id IN ('ta', 'tb', 'tc')
  AND job_instance_id IN (SELECT id FROM coverage_job_instances);

CREATE TEMP TABLE coverage_outbox_events ON COMMIT DROP AS
SELECT id FROM batch.outbox_event
WHERE tenant_id IN ('ta', 'tb', 'tc')
  AND (event_key ILIKE '%_COV_%' OR payload_json::text ILIKE '%_COV_%'
       OR (aggregate_type = 'JOB_INSTANCE'
           AND aggregate_id IN (SELECT id FROM coverage_job_instances)))
FOR UPDATE;

CREATE TEMP TABLE coverage_worker_reports ON COMMIT DROP AS
SELECT id FROM batch.worker_report_outbox
WHERE tenant_id IN ('ta', 'tb', 'tc')
  AND (task_id IN (SELECT id FROM coverage_job_tasks) OR payload_json::text ILIKE '%_COV_%')
FOR UPDATE;

DELETE FROM batch.worker_report_outbox
WHERE tenant_id IN ('ta', 'tb', 'tc') AND id IN (SELECT id FROM coverage_worker_reports);
DELETE FROM batch.event_delivery_log
WHERE tenant_id IN ('ta', 'tb', 'tc')
  AND outbox_event_id IN (SELECT id FROM coverage_outbox_events);
DELETE FROM batch.event_outbox_retry
WHERE tenant_id IN ('ta', 'tb', 'tc')
  AND outbox_event_id IN (SELECT id FROM coverage_outbox_events);
DELETE FROM batch.outbox_event
WHERE tenant_id IN ('ta', 'tb', 'tc') AND id IN (SELECT id FROM coverage_outbox_events);

DELETE FROM batch.workflow_node_run
WHERE workflow_run_id IN (SELECT id FROM coverage_workflow_runs)
   OR job_instance_id IN (SELECT id FROM coverage_job_instances);
DELETE FROM batch.workflow_run
WHERE tenant_id IN ('ta', 'tb', 'tc') AND id IN (SELECT id FROM coverage_workflow_runs);

DELETE FROM batch.asset_partition
WHERE tenant_id IN ('ta', 'tb', 'tc')
  AND (asset_code ILIKE '%_COV_%' OR job_instance_id IN (SELECT id FROM coverage_job_instances)
       OR result_version_id IN (
           SELECT id FROM batch.result_version
           WHERE tenant_id IN ('ta', 'tb', 'tc')
             AND (job_instance_id IN (SELECT id FROM coverage_job_instances)
                  OR business_key ILIKE '%_COV_%' OR payload_json::text ILIKE '%_COV_%')));
DELETE FROM batch.result_version
WHERE tenant_id IN ('ta', 'tb', 'tc')
  AND (job_instance_id IN (SELECT id FROM coverage_job_instances)
       OR business_key ILIKE '%_COV_%' OR payload_json::text ILIKE '%_COV_%');
DELETE FROM batch.job_execution_log
WHERE tenant_id IN ('ta', 'tb', 'tc')
  AND (job_instance_id IN (SELECT id FROM coverage_job_instances)
       OR job_partition_id IN (SELECT id FROM coverage_job_partitions)
       OR extra_json::text ILIKE '%_COV_%' OR message ILIKE '%_COV_%');
DELETE FROM batch.job_step_instance
WHERE tenant_id IN ('ta', 'tb', 'tc')
  AND (job_instance_id IN (SELECT id FROM coverage_job_instances)
       OR job_partition_id IN (SELECT id FROM coverage_job_partitions)
       OR job_task_id IN (SELECT id FROM coverage_job_tasks));
DELETE FROM batch.job_monitoring_alert_claim
WHERE tenant_id IN ('ta', 'tb', 'tc')
  AND job_instance_id IN (SELECT id FROM coverage_job_instances);
DELETE FROM batch.dead_letter_task
WHERE tenant_id IN ('ta', 'tb', 'tc')
  AND (payload_ref ILIKE '%_COV_%'
       OR (source_type = 'JOB_TASK' AND source_id IN (SELECT id FROM coverage_job_tasks))
       OR (source_type = 'JOB_PARTITION' AND source_id IN (SELECT id FROM coverage_job_partitions)));
DELETE FROM batch.compensation_command
WHERE tenant_id IN ('ta', 'tb', 'tc')
  AND (job_code LIKE '%_COV_%'
       OR related_job_instance_id IN (SELECT id FROM coverage_job_instances));
DELETE FROM batch.approval_command
WHERE tenant_id IN ('ta', 'tb', 'tc') AND payload_json::text ILIKE '%_COV_%';

DELETE FROM batch.job_task
WHERE tenant_id IN ('ta', 'tb', 'tc')
  AND (job_instance_id IN (SELECT id FROM coverage_job_instances)
       OR external_outcome_resolution_trigger_request_id IN (SELECT id FROM coverage_trigger_requests));
DELETE FROM batch.job_partition
WHERE tenant_id IN ('ta', 'tb', 'tc')
  AND job_instance_id IN (SELECT id FROM coverage_job_instances);

DELETE FROM batch.scheduled_occurrence_catch_up_audit
WHERE tenant_id IN ('ta', 'tb', 'tc') AND request_id IN (SELECT request_id FROM coverage_trigger_requests);
DELETE FROM batch.trigger_misfire_pending
WHERE tenant_id IN ('ta', 'tb', 'tc') AND catch_up_request_id IN (SELECT id FROM coverage_trigger_requests);
DELETE FROM batch.trigger_outbox_event
WHERE tenant_id IN ('ta', 'tb', 'tc') AND request_id IN (SELECT request_id FROM coverage_trigger_requests);
DELETE FROM batch.batch_day_waiting_launch
WHERE tenant_id IN ('ta', 'tb', 'tc') AND request_id IN (SELECT request_id FROM coverage_trigger_requests);
DELETE FROM batch.job_dependency_waiting_launch
WHERE tenant_id IN ('ta', 'tb', 'tc') AND request_id IN (SELECT request_id FROM coverage_trigger_requests);
DELETE FROM batch.expected_work_occurrence
WHERE tenant_id IN ('ta', 'tb', 'tc')
  AND (trigger_request_id IN (SELECT request_id FROM coverage_trigger_requests)
       OR workflow_definition_id IN (
           SELECT id FROM batch.workflow_definition
           WHERE tenant_id IN ('ta', 'tb', 'tc') AND workflow_code LIKE '%_COV_%'));
DELETE FROM batch.scheduled_occurrence
WHERE tenant_id IN ('ta', 'tb', 'tc')
  AND (request_id IN (SELECT request_id FROM coverage_trigger_requests)
       OR workflow_definition_id IN (
           SELECT id FROM batch.workflow_definition
           WHERE tenant_id IN ('ta', 'tb', 'tc') AND workflow_code LIKE '%_COV_%'));
DELETE FROM batch.workflow_launch_wait
WHERE tenant_id IN ('ta', 'tb', 'tc')
  AND (request_id IN (SELECT request_id FROM coverage_trigger_requests)
       OR workflow_definition_id IN (
           SELECT id FROM batch.workflow_definition
           WHERE tenant_id IN ('ta', 'tb', 'tc') AND workflow_code LIKE '%_COV_%'));
DELETE FROM batch.scheduled_occurrence_coverage
WHERE tenant_id IN ('ta', 'tb', 'tc')
  AND workflow_definition_id IN (
      SELECT id FROM batch.workflow_definition
      WHERE tenant_id IN ('ta', 'tb', 'tc') AND workflow_code LIKE '%_COV_%');
DELETE FROM batch.batch_day_operation_audit
WHERE tenant_id IN ('ta', 'tb', 'tc') AND calendar_code ILIKE '%_cov_calendar';
DELETE FROM batch.batch_day_instance
WHERE tenant_id IN ('ta', 'tb', 'tc') AND calendar_code ILIKE '%_cov_calendar';

DELETE FROM batch.file_dispatch_record
WHERE tenant_id IN ('ta', 'tb', 'tc')
  AND (pipeline_instance_id IN (SELECT id FROM coverage_pipeline_instances)
       OR file_id IN (SELECT id FROM coverage_files));
DELETE FROM batch.pipeline_step_run
WHERE pipeline_instance_id IN (SELECT id FROM coverage_pipeline_instances);
DELETE FROM batch.pipeline_progress
WHERE tenant_id IN ('ta', 'tb', 'tc')
  AND pipeline_instance_id IN (SELECT id FROM coverage_pipeline_instances);
DELETE FROM batch.pipeline_instance
WHERE tenant_id IN ('ta', 'tb', 'tc') AND id IN (SELECT id FROM coverage_pipeline_instances);

DELETE FROM batch.job_instance
WHERE tenant_id IN ('ta', 'tb', 'tc') AND id IN (SELECT id FROM coverage_job_instances);
DELETE FROM batch.trigger_request
WHERE tenant_id IN ('ta', 'tb', 'tc') AND id IN (SELECT id FROM coverage_trigger_requests);

DELETE FROM batch.file_audit_log
WHERE file_id IN (SELECT id FROM coverage_files);
DELETE FROM batch.file_record
WHERE tenant_id IN ('ta', 'tb', 'tc') AND id IN (SELECT id FROM coverage_files);
DELETE FROM batch.console_operation_audit
WHERE tenant_id IN ('ta', 'tb', 'tc')
  AND (aggregate_id ILIKE '%cov%' OR request_id LIKE 'coverage-%'
       OR params::text ILIKE '%_COV_%' OR params::text ILIKE '%coverage%');

COMMIT;

\connect :business_db
BEGIN;
DELETE FROM biz.customer_account
WHERE tenant_id IN ('ta', 'tb', 'tc') AND customer_no LIKE 'COV-%';
COMMIT;
