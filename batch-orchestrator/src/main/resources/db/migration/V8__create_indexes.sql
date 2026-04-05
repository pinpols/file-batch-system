-- =========================================================
-- V7 - Create secondary indexes
-- =========================================================

CREATE INDEX IF NOT EXISTS idx_resource_queue_type_enabled
    ON batch.resource_queue (queue_type, enabled);
CREATE INDEX IF NOT EXISTS idx_resource_queue_worker_group
    ON batch.resource_queue (worker_group);
CREATE INDEX IF NOT EXISTS idx_resource_queue_resource_tag
    ON batch.resource_queue (resource_tag);

CREATE INDEX IF NOT EXISTS idx_tenant_quota_enabled
    ON batch.tenant_quota_policy (tenant_id, enabled);

CREATE INDEX IF NOT EXISTS idx_batch_window_enabled
    ON batch.batch_window (tenant_id, enabled);

CREATE INDEX IF NOT EXISTS idx_business_calendar_enabled
    ON batch.business_calendar (tenant_id, enabled);

CREATE INDEX IF NOT EXISTS idx_calendar_holiday_biz_date
    ON batch.calendar_holiday (biz_date, day_type);

CREATE INDEX IF NOT EXISTS idx_worker_registry_group_status
    ON batch.worker_registry (worker_group, status);
CREATE INDEX IF NOT EXISTS idx_worker_registry_heartbeat_at
    ON batch.worker_registry (heartbeat_at);

CREATE INDEX IF NOT EXISTS idx_job_definition_enabled
    ON batch.job_definition (tenant_id, enabled);
CREATE INDEX IF NOT EXISTS idx_job_definition_queue
    ON batch.job_definition (tenant_id, queue_code, enabled);
CREATE INDEX IF NOT EXISTS idx_job_definition_worker_group
    ON batch.job_definition (worker_group, enabled);

CREATE INDEX IF NOT EXISTS idx_workflow_definition_enabled
    ON batch.workflow_definition (tenant_id, enabled);

CREATE INDEX IF NOT EXISTS idx_workflow_node_type_enabled
    ON batch.workflow_node (node_type, enabled);

CREATE INDEX IF NOT EXISTS idx_workflow_edge_from
    ON batch.workflow_edge (workflow_definition_id, from_node_code);
CREATE INDEX IF NOT EXISTS idx_workflow_edge_to
    ON batch.workflow_edge (workflow_definition_id, to_node_code);

CREATE INDEX IF NOT EXISTS idx_trigger_request_job_biz_date
    ON batch.trigger_request (tenant_id, job_code, biz_date);
CREATE INDEX IF NOT EXISTS idx_trigger_request_created_at
    ON batch.trigger_request (created_at);

CREATE INDEX IF NOT EXISTS idx_job_instance_job_status
    ON batch.job_instance (tenant_id, job_code, instance_status);
CREATE INDEX IF NOT EXISTS idx_job_instance_biz_date
    ON batch.job_instance (tenant_id, biz_date, instance_status);
CREATE INDEX IF NOT EXISTS idx_job_instance_created_at
    ON batch.job_instance (created_at);
CREATE INDEX IF NOT EXISTS idx_job_instance_trace_id
    ON batch.job_instance (trace_id);

CREATE INDEX IF NOT EXISTS idx_job_partition_status_worker_group
    ON batch.job_partition (partition_status, worker_group);
CREATE INDEX IF NOT EXISTS idx_job_partition_lease_expire_at
    ON batch.job_partition (lease_expire_at);
CREATE INDEX IF NOT EXISTS idx_job_partition_business_key
    ON batch.job_partition (business_key);

CREATE INDEX IF NOT EXISTS idx_job_task_status_worker
    ON batch.job_task (task_status, assigned_worker_code);
CREATE INDEX IF NOT EXISTS idx_job_task_instance
    ON batch.job_task (job_instance_id, created_at);

CREATE INDEX IF NOT EXISTS idx_workflow_run_status
    ON batch.workflow_run (tenant_id, run_status, started_at);
CREATE INDEX IF NOT EXISTS idx_workflow_run_job_instance
    ON batch.workflow_run (related_job_instance_id);

CREATE INDEX IF NOT EXISTS idx_workflow_node_run_status_started
    ON batch.workflow_node_run (node_status, started_at);

CREATE INDEX IF NOT EXISTS idx_file_record_biz_type_status
    ON batch.file_record (tenant_id, biz_type, file_status);
CREATE INDEX IF NOT EXISTS idx_file_record_biz_date
    ON batch.file_record (tenant_id, biz_date, created_at);
CREATE INDEX IF NOT EXISTS idx_file_record_latest
    ON batch.file_record (tenant_id, is_latest, file_name);
CREATE INDEX IF NOT EXISTS idx_file_record_trace_id
    ON batch.file_record (trace_id);

CREATE INDEX IF NOT EXISTS idx_pipeline_definition_type_enabled
    ON batch.pipeline_definition (tenant_id, pipeline_type, enabled);

CREATE INDEX IF NOT EXISTS idx_pipeline_step_definition_order
    ON batch.pipeline_step_definition (pipeline_definition_id, step_order);

CREATE INDEX IF NOT EXISTS idx_pipeline_instance_type_status
    ON batch.pipeline_instance (tenant_id, pipeline_type, run_status);
CREATE INDEX IF NOT EXISTS idx_pipeline_instance_file_id
    ON batch.pipeline_instance (file_id);
CREATE INDEX IF NOT EXISTS idx_pipeline_instance_trace_id
    ON batch.pipeline_instance (trace_id);

CREATE INDEX IF NOT EXISTS idx_pipeline_step_run_status_started
    ON batch.pipeline_step_run (step_status, started_at);

CREATE INDEX IF NOT EXISTS idx_file_channel_enabled
    ON batch.file_channel_config (tenant_id, channel_type, enabled);

CREATE INDEX IF NOT EXISTS idx_file_template_type_biz
    ON batch.file_template_config (tenant_id, template_type, biz_type);
CREATE INDEX IF NOT EXISTS idx_file_template_enabled_updated
    ON batch.file_template_config (enabled, updated_at);

CREATE INDEX IF NOT EXISTS idx_file_dispatch_status
    ON batch.file_dispatch_record (tenant_id, dispatch_status, dispatched_at);
CREATE INDEX IF NOT EXISTS idx_file_dispatch_external_request
    ON batch.file_dispatch_record (external_request_id);

CREATE INDEX IF NOT EXISTS idx_file_audit_operation
    ON batch.file_audit_log (tenant_id, operation_type, created_at);
CREATE INDEX IF NOT EXISTS idx_file_audit_trace_id
    ON batch.file_audit_log (trace_id);

CREATE INDEX IF NOT EXISTS idx_job_execution_log_instance_created
    ON batch.job_execution_log (job_instance_id, created_at);
CREATE INDEX IF NOT EXISTS idx_job_execution_log_partition_created
    ON batch.job_execution_log (job_partition_id, created_at);
CREATE INDEX IF NOT EXISTS idx_job_execution_log_trace_id
    ON batch.job_execution_log (trace_id);

CREATE INDEX IF NOT EXISTS idx_retry_schedule_next_retry
    ON batch.retry_schedule (retry_status, next_retry_at);

CREATE INDEX IF NOT EXISTS idx_dead_letter_replay_status
    ON batch.dead_letter_task (replay_status, created_at);
CREATE INDEX IF NOT EXISTS idx_dead_letter_trace_id
    ON batch.dead_letter_task (trace_id);

CREATE INDEX IF NOT EXISTS idx_outbox_publish_status
    ON batch.outbox_event (publish_status, next_publish_at);
CREATE INDEX IF NOT EXISTS idx_outbox_aggregate
    ON batch.outbox_event (aggregate_type, aggregate_id);
