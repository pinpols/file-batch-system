CREATE SCHEMA IF NOT EXISTS batch;
CREATE SCHEMA IF NOT EXISTS quartz;

-- ShedLock table used by orchestrator @SchedulerLock integration tests.
CREATE TABLE IF NOT EXISTS batch.shedlock (
    name        VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until  TIMESTAMPTZ  NOT NULL,
    locked_at   TIMESTAMPTZ  NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS batch.resource_queue (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    queue_code VARCHAR(128) NOT NULL,
    queue_name VARCHAR(256) NOT NULL,
    queue_type VARCHAR(32) NOT NULL,
    max_running_jobs INTEGER NOT NULL DEFAULT 0,
    max_running_partitions INTEGER NOT NULL DEFAULT 0,
    max_qps INTEGER NOT NULL DEFAULT 0,
    worker_group VARCHAR(128),
    resource_tag VARCHAR(64),
    priority_policy VARCHAR(32) NOT NULL DEFAULT 'FIFO',
    fair_share_weight INTEGER NOT NULL DEFAULT 1,
    fair_share_group VARCHAR(128),
    burst_limit INTEGER NOT NULL DEFAULT 0,
    quota_reset_policy VARCHAR(32) NOT NULL DEFAULT 'NONE',
    group_shared_max_running_jobs INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.tenant_quota_policy (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    policy_code VARCHAR(128) NOT NULL,
    max_running_jobs_per_tenant INTEGER NOT NULL DEFAULT 0,
    max_partitions_per_tenant INTEGER NOT NULL DEFAULT 0,
    max_qps_per_tenant INTEGER NOT NULL DEFAULT 0,
    fair_share_weight INTEGER NOT NULL DEFAULT 1,
    fair_share_group VARCHAR(128),
    burst_limit INTEGER NOT NULL DEFAULT 0,
    partition_burst_limit INTEGER NOT NULL DEFAULT 0,
    quota_reset_policy VARCHAR(32) NOT NULL DEFAULT 'NONE',
    group_shared_max_running_jobs INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.batch_window (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    window_code VARCHAR(128) NOT NULL,
    window_name VARCHAR(256) NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    end_strategy VARCHAR(32) NOT NULL DEFAULT 'FINISH_RUNNING',
    out_of_window_action VARCHAR(32) NOT NULL DEFAULT 'WAIT',
    allow_cross_day BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.business_calendar (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    calendar_code VARCHAR(128) NOT NULL,
    calendar_name VARCHAR(256) NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    holiday_roll_rule VARCHAR(32) NOT NULL DEFAULT 'SKIP',
    catch_up_policy VARCHAR(32) NOT NULL DEFAULT 'NONE',
    catch_up_max_days INTEGER NOT NULL DEFAULT 0,
    cutoff_time TIME NOT NULL DEFAULT '06:00:00',
    late_arrival_tolerance_min INTEGER NOT NULL DEFAULT 60,
    sla_offset_min INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_business_calendar_late_arrival_tolerance_min CHECK (late_arrival_tolerance_min >= 0),
    CONSTRAINT ck_business_calendar_sla_offset_min CHECK (sla_offset_min >= 0)
);

CREATE TABLE IF NOT EXISTS batch.calendar_holiday (
    id BIGSERIAL PRIMARY KEY,
    calendar_id BIGINT NOT NULL,
    biz_date DATE NOT NULL,
    day_type VARCHAR(32) NOT NULL,
    holiday_name VARCHAR(128),
    description VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.batch_day_instance (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    calendar_code VARCHAR(128) NOT NULL,
    biz_date DATE NOT NULL,
    day_status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    open_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cutoff_at TIMESTAMPTZ,
    settled_at TIMESTAMPTZ,
    sla_deadline_at TIMESTAMPTZ,
    late_count INTEGER NOT NULL DEFAULT 0,
    catchup_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_batch_day_instance UNIQUE (tenant_id, calendar_code, biz_date),
    CONSTRAINT ck_batch_day_instance_status CHECK (day_status IN ('OPEN', 'CUTOFF', 'IN_FLIGHT', 'SETTLED', 'FAILED')),
    CONSTRAINT ck_batch_day_instance_late_count CHECK (late_count >= 0),
    CONSTRAINT ck_batch_day_instance_catchup_count CHECK (catchup_count >= 0)
);

CREATE TABLE IF NOT EXISTS batch.worker_registry (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    worker_code VARCHAR(128) NOT NULL,
    worker_group VARCHAR(128) NOT NULL,
    host_name VARCHAR(256),
    host_ip VARCHAR(64),
    process_id VARCHAR(64),
    capability_tags JSONB,
    resource_tag VARCHAR(64),
    status VARCHAR(32) NOT NULL DEFAULT 'ONLINE',
    heartbeat_at TIMESTAMPTZ NOT NULL,
    current_load INTEGER NOT NULL DEFAULT 0,
    drain_started_at TIMESTAMPTZ,
    drain_deadline_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.job_definition (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    job_code VARCHAR(128) NOT NULL,
    job_name VARCHAR(256) NOT NULL,
    job_type VARCHAR(32) NOT NULL,
    biz_type VARCHAR(64),
    schedule_type VARCHAR(32) NOT NULL,
    schedule_expr VARCHAR(256),
    timezone VARCHAR(64) NOT NULL,
    priority INTEGER NOT NULL DEFAULT 5,
    queue_code VARCHAR(128),
    worker_group VARCHAR(128),
    calendar_code VARCHAR(128),
    window_code VARCHAR(128),
    trigger_mode VARCHAR(32) NOT NULL DEFAULT 'SCHEDULED',
    dag_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    shard_strategy VARCHAR(32) NOT NULL DEFAULT 'NONE',
    retry_policy VARCHAR(32) NOT NULL DEFAULT 'NONE',
    retry_max_count INTEGER NOT NULL DEFAULT 0,
    timeout_seconds INTEGER NOT NULL DEFAULT 0,
    execution_handler VARCHAR(256),
    param_schema JSONB,
    default_params JSONB,
    version INTEGER NOT NULL DEFAULT 1,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(1024),
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.workflow_definition (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    workflow_code VARCHAR(128) NOT NULL,
    workflow_name VARCHAR(256) NOT NULL,
    workflow_type VARCHAR(32) NOT NULL DEFAULT 'DAG',
    version INTEGER NOT NULL DEFAULT 1,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(1024),
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.workflow_node (
    id BIGSERIAL PRIMARY KEY,
    workflow_definition_id BIGINT NOT NULL,
    node_code VARCHAR(128) NOT NULL,
    node_name VARCHAR(256) NOT NULL,
    node_type VARCHAR(32) NOT NULL,
    related_job_code VARCHAR(128),
    related_pipeline_code VARCHAR(128),
    worker_group VARCHAR(128),
    window_code VARCHAR(128),
    node_order INTEGER NOT NULL DEFAULT 0,
    retry_policy VARCHAR(32) NOT NULL DEFAULT 'NONE',
    retry_max_count INTEGER NOT NULL DEFAULT 0,
    timeout_seconds INTEGER NOT NULL DEFAULT 0,
    node_params JSONB,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.workflow_edge (
    id BIGSERIAL PRIMARY KEY,
    workflow_definition_id BIGINT NOT NULL,
    from_node_code VARCHAR(128) NOT NULL,
    to_node_code VARCHAR(128) NOT NULL,
    edge_type VARCHAR(32) NOT NULL DEFAULT 'SUCCESS',
    condition_expr VARCHAR(1024),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.trigger_request (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    trigger_type VARCHAR(32) NOT NULL,
    job_code VARCHAR(128) NOT NULL,
    biz_date DATE,
    dedup_key VARCHAR(256) NOT NULL,
    request_payload_hash VARCHAR(128),
    request_status VARCHAR(32) NOT NULL,
    related_job_instance_id BIGINT,
    trace_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.job_instance (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    job_definition_id BIGINT NOT NULL,
    trigger_request_id BIGINT,
    job_code VARCHAR(128) NOT NULL,
    instance_no VARCHAR(128) NOT NULL,
    biz_date DATE,
    trigger_type VARCHAR(32) NOT NULL,
    instance_status VARCHAR(32) NOT NULL,
    batch_no VARCHAR(128),
    operator_id VARCHAR(64),
    rerun_flag BOOLEAN NOT NULL DEFAULT FALSE,
    retry_flag BOOLEAN NOT NULL DEFAULT FALSE,
    rerun_reason VARCHAR(512),
    related_file_id BIGINT,
    parent_instance_id BIGINT,
    queue_code VARCHAR(128),
    worker_group VARCHAR(128),
    priority INTEGER NOT NULL DEFAULT 5,
    dedup_key VARCHAR(256) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    expected_partition_count INTEGER NOT NULL DEFAULT 0,
    success_partition_count INTEGER NOT NULL DEFAULT 0,
    failed_partition_count INTEGER NOT NULL DEFAULT 0,
    trace_id VARCHAR(128),
    params_snapshot JSONB,
    result_summary JSONB,
    deadline_at TIMESTAMPTZ,
    expected_duration_seconds INTEGER NOT NULL DEFAULT 0,
    sla_alerted_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_job_instance_tenant_instance_no UNIQUE (tenant_id, instance_no),
    CONSTRAINT uk_job_instance_tenant_dedup UNIQUE (tenant_id, dedup_key),
    CONSTRAINT ck_job_instance_expected_duration_seconds CHECK (expected_duration_seconds >= 0)
);

CREATE TABLE IF NOT EXISTS batch.job_partition (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    job_instance_id BIGINT NOT NULL,
    partition_no INTEGER NOT NULL,
    partition_key VARCHAR(256),
    partition_status VARCHAR(32) NOT NULL,
    worker_group VARCHAR(128),
    worker_code VARCHAR(128),
    lease_expire_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    retry_count INTEGER NOT NULL DEFAULT 0,
    business_key VARCHAR(256),
    idempotency_key VARCHAR(512),
    input_snapshot JSONB,
    output_summary JSONB,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.job_task (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    job_instance_id BIGINT NOT NULL,
    job_partition_id BIGINT,
    task_type VARCHAR(32) NOT NULL DEFAULT 'EXECUTION',
    task_seq INTEGER NOT NULL DEFAULT 1,
    task_status VARCHAR(32) NOT NULL,
    assigned_worker_code VARCHAR(128),
    version BIGINT NOT NULL DEFAULT 0,
    task_payload JSONB,
    result_summary JSONB,
    error_code VARCHAR(64),
    error_message VARCHAR(2048),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.job_step_instance (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    job_instance_id BIGINT NOT NULL,
    job_partition_id BIGINT,
    job_task_id BIGINT NOT NULL,
    step_code VARCHAR(128) NOT NULL,
    step_type VARCHAR(64) NOT NULL,
    step_status VARCHAR(32) NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    related_file_id BIGINT,
    result_summary JSONB,
    error_code VARCHAR(64),
    error_message VARCHAR(2048),
    version BIGINT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.compensation_command (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    command_no VARCHAR(128) NOT NULL,
    compensation_type VARCHAR(32) NOT NULL,
    target_id BIGINT,
    job_code VARCHAR(128),
    biz_date DATE,
    batch_no VARCHAR(128),
    related_job_instance_id BIGINT,
    related_file_id BIGINT,
    approval_id VARCHAR(128),
    operator_id VARCHAR(64),
    reason VARCHAR(1024),
    strategy VARCHAR(64),
    command_status VARCHAR(32) NOT NULL,
    trace_id VARCHAR(128),
    result_summary JSONB,
    error_code VARCHAR(64),
    error_message VARCHAR(2048),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS batch.workflow_run (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    workflow_definition_id BIGINT NOT NULL,
    related_job_instance_id BIGINT,
    biz_date DATE,
    run_status VARCHAR(32) NOT NULL,
    current_node_code VARCHAR(128),
    trace_id VARCHAR(128),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.workflow_node_run (
    id BIGSERIAL PRIMARY KEY,
    workflow_run_id BIGINT NOT NULL,
    node_code VARCHAR(128) NOT NULL,
    node_type VARCHAR(32) NOT NULL,
    run_seq INTEGER NOT NULL DEFAULT 1,
    node_status VARCHAR(32) NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(64),
    error_message VARCHAR(1024),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    duration_ms BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS batch.file_record (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    file_code VARCHAR(128),
    biz_type VARCHAR(64),
    file_category VARCHAR(32) NOT NULL,
    file_name VARCHAR(512) NOT NULL,
    original_file_name VARCHAR(512),
    file_ext VARCHAR(32),
    file_format_type VARCHAR(32) NOT NULL,
    charset VARCHAR(32),
    mime_type VARCHAR(128),
    file_size_bytes BIGINT NOT NULL DEFAULT 0,
    checksum_type VARCHAR(32) NOT NULL DEFAULT 'NONE',
    checksum_value VARCHAR(256),
    storage_type VARCHAR(32) NOT NULL,
    storage_path VARCHAR(1024) NOT NULL,
    storage_bucket VARCHAR(256),
    file_version VARCHAR(64),
    file_generation_no INTEGER NOT NULL DEFAULT 1,
    is_latest BOOLEAN NOT NULL DEFAULT TRUE,
    source_type VARCHAR(32) NOT NULL,
    source_ref VARCHAR(256),
    file_status VARCHAR(32) NOT NULL,
    biz_date DATE,
    trace_id VARCHAR(128),
    metadata_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.pipeline_definition (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    job_code VARCHAR(128) NOT NULL,
    pipeline_name VARCHAR(256) NOT NULL,
    pipeline_type VARCHAR(32) NOT NULL,
    biz_type VARCHAR(64),
    worker_group VARCHAR(128),
    version INTEGER NOT NULL DEFAULT 1,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.pipeline_step_definition (
    id BIGSERIAL PRIMARY KEY,
    pipeline_definition_id BIGINT NOT NULL,
    step_code VARCHAR(128) NOT NULL,
    step_name VARCHAR(256) NOT NULL,
    stage_code VARCHAR(64) NOT NULL,
    step_order INTEGER NOT NULL,
    impl_code VARCHAR(128) NOT NULL,
    step_params JSONB,
    timeout_seconds INTEGER NOT NULL DEFAULT 0,
    retry_policy VARCHAR(32) NOT NULL DEFAULT 'NONE',
    retry_max_count INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.pipeline_instance (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    pipeline_definition_id BIGINT NOT NULL,
    job_code VARCHAR(128) NOT NULL,
    pipeline_type VARCHAR(32) NOT NULL,
    file_id BIGINT,
    related_job_instance_id BIGINT,
    current_stage VARCHAR(64),
    last_success_stage VARCHAR(64),
    run_status VARCHAR(32) NOT NULL,
    trace_id VARCHAR(128),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.pipeline_step_run (
    id BIGSERIAL PRIMARY KEY,
    pipeline_instance_id BIGINT NOT NULL,
    step_code VARCHAR(128) NOT NULL,
    stage_code VARCHAR(64) NOT NULL,
    run_seq INTEGER NOT NULL DEFAULT 1,
    step_status VARCHAR(32) NOT NULL,
    input_summary JSONB,
    output_summary JSONB,
    error_code VARCHAR(64),
    error_message VARCHAR(1024),
    retry_count INTEGER NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS batch.file_channel_config (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    channel_code VARCHAR(128) NOT NULL,
    channel_name VARCHAR(256) NOT NULL,
    channel_type VARCHAR(32) NOT NULL,
    target_endpoint VARCHAR(1024),
    auth_type VARCHAR(32) NOT NULL DEFAULT 'NONE',
    config_json JSONB NOT NULL,
    receipt_policy VARCHAR(32) NOT NULL DEFAULT 'NONE',
    timeout_seconds INTEGER NOT NULL DEFAULT 0,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.file_template_config (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    template_code VARCHAR(128) NOT NULL,
    template_name VARCHAR(256) NOT NULL,
    template_type VARCHAR(32) NOT NULL,
    biz_type VARCHAR(64),
    file_format_type VARCHAR(32) NOT NULL,
    charset VARCHAR(32),
    target_charset VARCHAR(32),
    with_bom BOOLEAN NOT NULL DEFAULT FALSE,
    line_separator VARCHAR(16),
    delimiter VARCHAR(8),
    quote_char VARCHAR(8),
    escape_char VARCHAR(8),
    record_length INTEGER NOT NULL DEFAULT 0,
    header_rows INTEGER NOT NULL DEFAULT 0,
    footer_rows INTEGER NOT NULL DEFAULT 0,
    header_template JSONB,
    trailer_template JSONB,
    checksum_type VARCHAR(32) NOT NULL DEFAULT 'NONE',
    compress_type VARCHAR(32) NOT NULL DEFAULT 'NONE',
    encrypt_type VARCHAR(32) NOT NULL DEFAULT 'NONE',
    naming_rule VARCHAR(512),
    field_mappings JSONB,
    validation_rule_set JSONB,
    default_query_code VARCHAR(128),
    default_query_sql TEXT,
    query_param_schema JSONB,
    streaming_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    page_size INTEGER NOT NULL DEFAULT 1000,
    fetch_size INTEGER NOT NULL DEFAULT 1000,
    chunk_size INTEGER NOT NULL DEFAULT 500,
    preprocess_pipeline JSONB,
    preview_masking_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    error_line_masking_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    log_masking_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    content_encryption_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    encryption_key_ref VARCHAR(256),
    download_requires_approval BOOLEAN NOT NULL DEFAULT FALSE,
    masking_rule_set VARCHAR(128),
    export_data_ref VARCHAR(128),
    load_target_ref VARCHAR(128),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version INTEGER NOT NULL DEFAULT 1,
    description VARCHAR(1024),
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.file_dispatch_record (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    file_id BIGINT NOT NULL,
    pipeline_instance_id BIGINT,
    channel_code VARCHAR(128) NOT NULL,
    dispatch_target VARCHAR(256),
    dispatch_status VARCHAR(32) NOT NULL,
    dispatch_attempt INTEGER NOT NULL DEFAULT 1,
    receipt_code VARCHAR(128),
    receipt_status VARCHAR(32) NOT NULL DEFAULT 'NONE',
    external_request_id VARCHAR(128),
    error_code VARCHAR(64),
    error_message VARCHAR(1024),
    dispatched_at TIMESTAMPTZ,
    ack_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.file_audit_log (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    file_id BIGINT NOT NULL,
    operation_type VARCHAR(64) NOT NULL,
    operation_result VARCHAR(32) NOT NULL,
    operator_type VARCHAR(32) NOT NULL,
    operator_id VARCHAR(64),
    trace_id VARCHAR(128),
    evidence_ref VARCHAR(512),
    detail_summary JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.job_execution_log (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    job_instance_id BIGINT,
    job_partition_id BIGINT,
    log_level VARCHAR(16) NOT NULL,
    log_type VARCHAR(32) NOT NULL,
    trace_id VARCHAR(128),
    message VARCHAR(2048) NOT NULL,
    detail_ref VARCHAR(512),
    extra_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.retry_schedule (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    related_type VARCHAR(32) NOT NULL,
    related_id BIGINT NOT NULL,
    retry_policy VARCHAR(32) NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    max_retry_count INTEGER NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ NOT NULL,
    retry_status VARCHAR(32) NOT NULL,
    dedup_key VARCHAR(256) NOT NULL,
    last_error_code VARCHAR(64),
    last_error_message VARCHAR(1024),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.console_ai_audit_log (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    trace_id VARCHAR(128),
    session_id VARCHAR(128),
    operator_id VARCHAR(64),
    prompt_category VARCHAR(32) NOT NULL,
    prompt_decision VARCHAR(32) NOT NULL,
    model_name VARCHAR(128),
    prompt_hash VARCHAR(128),
    prompt_preview VARCHAR(1024),
    response_hash VARCHAR(128),
    response_preview VARCHAR(1024),
    refusal_reason VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_console_ai_audit_request UNIQUE (tenant_id, request_id)
);

CREATE TABLE IF NOT EXISTS batch.alert_event (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    service_name VARCHAR(64) NOT NULL,
    alert_type VARCHAR(64) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    title VARCHAR(512) NOT NULL,
    detail_json JSONB,
    dedup_fingerprint VARCHAR(128) NOT NULL,
    occurrence_count INTEGER NOT NULL DEFAULT 1,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    trace_id VARCHAR(128),
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_alert_event_dedup UNIQUE (tenant_id, dedup_fingerprint),
    CONSTRAINT ck_alert_event_severity CHECK (severity IN ('INFO', 'WARN', 'ERROR', 'CRITICAL')),
    CONSTRAINT ck_alert_event_status CHECK (status IN ('OPEN', 'ACKED', 'SUPPRESSED', 'CLOSED')),
    CONSTRAINT ck_alert_event_occurrence CHECK (occurrence_count > 0)
);

CREATE INDEX IF NOT EXISTS idx_alert_event_tenant_last_seen
    ON batch.alert_event (tenant_id, last_seen_at DESC);

CREATE INDEX IF NOT EXISTS idx_alert_event_type_severity
    ON batch.alert_event (tenant_id, alert_type, severity);

CREATE TABLE IF NOT EXISTS batch.file_channel_health (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    channel_code VARCHAR(128) NOT NULL,
    channel_type VARCHAR(32) NOT NULL,
    health_status VARCHAR(32) NOT NULL,
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    last_probe_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ,
    next_probe_at TIMESTAMPTZ,
    probe_message VARCHAR(1024),
    probe_evidence VARCHAR(1024),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_file_channel_health UNIQUE (tenant_id, channel_code),
    CONSTRAINT ck_file_channel_health_status CHECK (health_status IN ('HEALTHY', 'DEGRADED', 'UNHEALTHY')),
    CONSTRAINT ck_file_channel_health_failures CHECK (consecutive_failures >= 0)
);

CREATE INDEX IF NOT EXISTS idx_file_channel_health_next_probe
    ON batch.file_channel_health (health_status, next_probe_at);

CREATE TABLE IF NOT EXISTS batch.dead_letter_task (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id BIGINT NOT NULL,
    dead_letter_reason VARCHAR(1024),
    payload_ref VARCHAR(512),
    replay_status VARCHAR(32) NOT NULL DEFAULT 'NEW',
    replay_count INTEGER NOT NULL DEFAULT 0,
    last_replay_at TIMESTAMPTZ,
    last_replay_result VARCHAR(32),
    trace_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.outbox_event (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    event_key VARCHAR(256) NOT NULL,
    payload_json JSONB NOT NULL,
    publish_status VARCHAR(32) NOT NULL DEFAULT 'NEW',
    publish_attempt INTEGER NOT NULL DEFAULT 0,
    next_publish_at TIMESTAMPTZ,
    trace_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.event_outbox_retry (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    outbox_event_id BIGINT NOT NULL,
    event_key VARCHAR(256) NOT NULL,
    retry_attempt INTEGER NOT NULL DEFAULT 1,
    retry_status VARCHAR(32) NOT NULL,
    retry_reason VARCHAR(1024),
    next_retry_at TIMESTAMPTZ,
    trace_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.event_delivery_log (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    outbox_event_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    event_key VARCHAR(256) NOT NULL,
    target_topic VARCHAR(256) NOT NULL,
    target_worker_id VARCHAR(128),
    delivery_status VARCHAR(32) NOT NULL,
    delivery_attempt INTEGER NOT NULL DEFAULT 1,
    delivery_summary JSONB,
    error_message VARCHAR(1024),
    trace_id VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS batch.file_error_record (
    id                   BIGSERIAL PRIMARY KEY,
    tenant_id            VARCHAR(64)   NOT NULL,
    file_id              BIGINT,
    pipeline_instance_id BIGINT,
    pipeline_step_run_id BIGINT,
    record_no            BIGINT,
    error_code           VARCHAR(128)  NOT NULL,
    error_message        VARCHAR(1024),
    error_stage          VARCHAR(64),
    is_skipped           BOOLEAN       NOT NULL DEFAULT FALSE,
    skip_action          VARCHAR(32),
    raw_record           JSONB,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_file_error_record_tenant_file
    ON batch.file_error_record (tenant_id, file_id, error_stage, error_code);

CREATE INDEX IF NOT EXISTS idx_file_error_record_created_at
    ON batch.file_error_record (created_at DESC);

-- Align with Flyway V24 (orchestrator) — required by QuotaRuntimeStateRepository / scheduled reconcile
CREATE TABLE IF NOT EXISTS batch.quota_runtime_state (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    quota_scope         VARCHAR(64)  NOT NULL,
    owner_code          VARCHAR(128) NOT NULL,
    quota_reset_policy  VARCHAR(32)  NOT NULL DEFAULT 'NONE',
    window_started_at   TIMESTAMPTZ,
    window_expires_at   TIMESTAMPTZ,
    peak_borrowed_count INTEGER      NOT NULL DEFAULT 0,
    last_reset_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_quota_runtime_state UNIQUE (tenant_id, quota_scope, owner_code),
    CONSTRAINT ck_quota_runtime_state_policy CHECK (quota_reset_policy IN ('NONE', 'CALENDAR_DAY', 'SLIDING_WINDOW')),
    CONSTRAINT ck_quota_runtime_state_peak CHECK (peak_borrowed_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_quota_runtime_state_expire
    ON batch.quota_runtime_state (window_expires_at);

CREATE INDEX IF NOT EXISTS idx_batch_day_instance_status
    ON batch.batch_day_instance (day_status, biz_date DESC);

-- Align with Flyway V26 — required by ApprovalCommandMapper / approval workflow ITs
CREATE TABLE IF NOT EXISTS batch.approval_command (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    approval_no         VARCHAR(128) NOT NULL,
    approval_type       VARCHAR(64)  NOT NULL,
    action_type         VARCHAR(64)  NOT NULL,
    target_type         VARCHAR(64)  NOT NULL,
    target_id           VARCHAR(128),
    payload_json        JSONB        NOT NULL,
    approval_status     VARCHAR(32)  NOT NULL,
    requester_id        VARCHAR(64),
    approver_id         VARCHAR(64),
    rejection_reason    VARCHAR(1024),
    approval_reason     VARCHAR(1024),
    source_trace_id     VARCHAR(128),
    source_idempotency_key VARCHAR(128),
    approved_at         TIMESTAMPTZ,
    executed_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_approval_command_tenant_no UNIQUE (tenant_id, approval_no),
    CONSTRAINT ck_approval_command_status CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXECUTED')),
    CONSTRAINT ck_approval_command_type CHECK (approval_type IN ('CATCH_UP', 'COMPENSATION', 'DLQ_REPLAY', 'DOWNLOAD')),
    CONSTRAINT ck_approval_command_action CHECK (action_type IN ('CATCH_UP', 'COMPENSATION', 'DLQ_REPLAY', 'DOWNLOAD', 'RETRY'))
);

CREATE INDEX IF NOT EXISTS idx_approval_command_status
    ON batch.approval_command (tenant_id, approval_status, created_at DESC);
