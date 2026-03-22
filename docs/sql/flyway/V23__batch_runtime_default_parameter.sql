-- =========================================================
-- V23 - Baseline runtime default parameters (design §20.11)
-- Mirrors Spring YAML / env defaults; authoritative for ops docs.
-- Runtime services read configuration from YAML/env — this table is the audited catalog.
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.batch_runtime_default_parameter (
    id              BIGSERIAL PRIMARY KEY,
    module          VARCHAR(64)  NOT NULL,
    parameter_key   VARCHAR(128) NOT NULL,
    default_value   VARCHAR(512) NOT NULL,
    value_type      VARCHAR(16)  NOT NULL,
    unit            VARCHAR(32),
    yaml_path       VARCHAR(512) NOT NULL,
    env_var         VARCHAR(256),
    description     TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_batch_runtime_default_parameter UNIQUE (module, parameter_key),
    CONSTRAINT ck_batch_runtime_default_value_type CHECK (value_type IN (
        'INTEGER', 'BOOLEAN', 'STRING', 'MILLIS', 'SECONDS'
    ))
);

COMMENT ON TABLE batch.batch_runtime_default_parameter IS 'Catalog of default runtime tuning; keep in sync with docs/architecture/runtime-default-parameters.md';

INSERT INTO batch.batch_runtime_default_parameter (module, parameter_key, default_value, value_type, unit, yaml_path, env_var, description) VALUES
-- batch-orchestrator (application.yml)
('ORCHESTRATOR', 'outbox.batch_size', '100', 'INTEGER', 'events', 'batch.outbox.batch-size', 'BATCH_OUTBOX_BATCH_SIZE', 'Outbox poll batch size'),
('ORCHESTRATOR', 'outbox.poll_interval_millis', '5000', 'MILLIS', NULL, 'batch.outbox.poll-interval-millis', 'BATCH_OUTBOX_POLL_INTERVAL_MILLIS', 'Outbox poller interval'),
('ORCHESTRATOR', 'outbox.retry_delay_seconds', '60', 'SECONDS', NULL, 'batch.outbox.retry-delay-seconds', 'BATCH_OUTBOX_RETRY_DELAY_SECONDS', 'Delay before retrying failed outbox publish'),
('ORCHESTRATOR', 'outbox.max_retry_attempts', '5', 'INTEGER', NULL, 'batch.outbox.max-retry-attempts', 'BATCH_OUTBOX_MAX_RETRY_ATTEMPTS', 'Max publish attempts per outbox event'),
('ORCHESTRATOR', 'worker.drain.default_timeout_seconds', '600', 'SECONDS', NULL, 'batch.worker.drain.default-timeout-seconds', 'BATCH_WORKER_DRAIN_TIMEOUT_SECONDS', 'Default drain window before task takeover'),
('ORCHESTRATOR', 'worker.drain.check_interval_millis', '15000', 'MILLIS', NULL, 'batch.worker.drain.check-interval-millis', 'BATCH_WORKER_DRAIN_CHECK_INTERVAL_MILLIS', 'Scheduler scan for drain deadline expiry'),
('ORCHESTRATOR', 'worker.drain.enabled', 'true', 'BOOLEAN', NULL, 'batch.worker.drain.enabled', 'BATCH_WORKER_DRAIN_ENABLED', 'Enable drain timeout scheduler'),
('ORCHESTRATOR', 'partition_lease.expire_seconds', '60', 'SECONDS', NULL, 'batch.partition-lease.expire-seconds', 'BATCH_PARTITION_LEASE_EXPIRE_SECONDS', 'Partition lease TTL'),
('ORCHESTRATOR', 'partition_lease.reclaim_interval_millis', '15000', 'MILLIS', NULL, 'batch.partition-lease.reclaim-interval-millis', 'BATCH_PARTITION_LEASE_RECLAIM_INTERVAL_MILLIS', 'Expired lease reclaim cadence'),
('ORCHESTRATOR', 'retry.batch_size', '100', 'INTEGER', 'tasks', 'batch.retry.batch-size', 'BATCH_RETRY_BATCH_SIZE', 'Retry scheduler batch size'),
('ORCHESTRATOR', 'retry.poll_interval_millis', '10000', 'MILLIS', NULL, 'batch.retry.poll-interval-millis', 'BATCH_RETRY_POLL_INTERVAL_MILLIS', 'Retry scheduler poll interval'),
('ORCHESTRATOR', 'retry.fixed_delay_seconds', '60', 'SECONDS', NULL, 'batch.retry.fixed-delay-seconds', 'BATCH_RETRY_FIXED_DELAY_SECONDS', 'Base delay for retry scheduling'),
('ORCHESTRATOR', 'retry.exponential_multiplier', '2', 'INTEGER', NULL, 'batch.retry.exponential-multiplier', 'BATCH_RETRY_EXPONENTIAL_MULTIPLIER', 'Backoff multiplier'),
('ORCHESTRATOR', 'retry.max_delay_seconds', '3600', 'SECONDS', NULL, 'batch.retry.max-delay-seconds', 'BATCH_RETRY_MAX_DELAY_SECONDS', 'Backoff cap'),
('ORCHESTRATOR', 'retry.default_max_retry_count', '3', 'INTEGER', NULL, 'batch.retry.default-max-retry-count', 'BATCH_RETRY_DEFAULT_MAX_RETRY_COUNT', 'Default max retries per task'),
('ORCHESTRATOR', 'resource_scheduler.waiting_dispatch_batch_size', '100', 'INTEGER', NULL, 'batch.resource-scheduler.waiting-dispatch-batch-size', 'BATCH_RESOURCE_SCHEDULER_WAITING_DISPATCH_BATCH_SIZE', 'Waiting-queue release batch'),
('ORCHESTRATOR', 'resource_scheduler.waiting_dispatch_interval_millis', '10000', 'MILLIS', NULL, 'batch.resource-scheduler.waiting-dispatch-interval-millis', 'BATCH_RESOURCE_SCHEDULER_WAITING_DISPATCH_INTERVAL_MILLIS', 'Waiting-queue release interval'),
('ORCHESTRATOR', 'resource_scheduler.quota_reset_sliding_window_hours', '24', 'INTEGER', 'hours', 'batch.resource-scheduler.quota-reset-sliding-window-hours', 'BATCH_RESOURCE_SCHEDULER_QUOTA_RESET_SLIDING_WINDOW_HOURS', 'Burst quota sliding-window duration'),
('ORCHESTRATOR', 'resource_scheduler.quota_reset_scan_interval_millis', '60000', 'MILLIS', NULL, 'batch.resource-scheduler.quota-reset-scan-interval-millis', 'BATCH_RESOURCE_SCHEDULER_QUOTA_RESET_SCAN_INTERVAL_MILLIS', 'Quota runtime reset scan interval'),
('ORCHESTRATOR', 'scheduler.snapshot_persist_ms', '120000', 'MILLIS', NULL, 'batch.scheduler.snapshot-persist-ms', 'BATCH_SCHEDULER_SNAPSHOT_PERSIST_MS', 'Tenant scheduler snapshot write interval'),
('ORCHESTRATOR', 'sla.poll_interval_millis', '30000', 'MILLIS', NULL, 'batch.sla.poll-interval-millis', 'BATCH_SLA_POLL_INTERVAL_MILLIS', 'SLA checker interval'),
('ORCHESTRATOR', 'sla.batch_size', '200', 'INTEGER', NULL, 'batch.sla.batch-size', 'BATCH_SLA_BATCH_SIZE', 'SLA evaluation batch'),
('ORCHESTRATOR', 'file_governance.latency.poll_interval_millis', '30000', 'MILLIS', NULL, 'batch.file-governance.latency.poll-interval-millis', 'BATCH_FILE_GOVERNANCE_LATENCY_POLL_INTERVAL_MILLIS', 'File latency governance poll'),
('ORCHESTRATOR', 'file_governance.latency.arrival_delay_threshold_seconds', '600', 'SECONDS', NULL, 'batch.file-governance.latency.arrival-delay-threshold-seconds', 'BATCH_FILE_GOVERNANCE_ARRIVAL_DELAY_THRESHOLD_SECONDS', 'Arrival SLA threshold'),
('ORCHESTRATOR', 'file_governance.latency.processing_delay_threshold_seconds', '900', 'SECONDS', NULL, 'batch.file-governance.latency.processing-delay-threshold-seconds', 'BATCH_FILE_GOVERNANCE_PROCESSING_DELAY_THRESHOLD_SECONDS', 'Processing SLA threshold'),
('ORCHESTRATOR', 'file_governance.archive.cleanup_interval_millis', '60000', 'MILLIS', NULL, 'batch.file-governance.archive.cleanup-interval-millis', 'BATCH_FILE_GOVERNANCE_ARCHIVE_CLEANUP_INTERVAL_MILLIS', 'Archive cleanup scheduler'),
('ORCHESTRATOR', 'file_governance.archive.cleanup_batch_size', '100', 'INTEGER', NULL, 'batch.file-governance.archive.cleanup-batch-size', 'BATCH_FILE_GOVERNANCE_ARCHIVE_CLEANUP_BATCH_SIZE', 'Archive cleanup batch'),
('ORCHESTRATOR', 'file_governance.archive.retention_days', '7', 'INTEGER', 'days', 'batch.file-governance.archive.retention-days', 'BATCH_FILE_GOVERNANCE_ARCHIVE_RETENTION_DAYS', 'Soft retention before purge policy applies'),
('ORCHESTRATOR', 'file_governance.reconcile.poll_interval_millis', '60000', 'MILLIS', NULL, 'batch.file-governance.reconcile.poll-interval-millis', 'BATCH_FILE_GOVERNANCE_RECONCILE_POLL_INTERVAL_MILLIS', 'Object vs record reconcile poll'),
('ORCHESTRATOR', 'file_governance.reconcile.batch_size', '200', 'INTEGER', NULL, 'batch.file-governance.reconcile.batch-size', 'BATCH_FILE_GOVERNANCE_RECONCILE_BATCH_SIZE', 'Reconcile batch size'),

-- batch-worker-import / export (import-worker.yml, export-worker.yml, application.yml)
('WORKER_IMPORT', 'file_processing.streaming_enabled', 'true', 'BOOLEAN', NULL, 'batch.worker.import.file-processing.streaming-enabled', 'BATCH_WORKER_IMPORT_STREAMING_ENABLED', 'Enable NDJSON/streaming import path'),
('WORKER_IMPORT', 'file_processing.page_size', '1000', 'INTEGER', 'rows', 'batch.worker.import.file-processing.page-size', 'BATCH_WORKER_IMPORT_PAGE_SIZE', 'Parse/validate page size'),
('WORKER_IMPORT', 'file_processing.fetch_size', '1000', 'INTEGER', 'rows', 'batch.worker.import.file-processing.fetch-size', 'BATCH_WORKER_IMPORT_FETCH_SIZE', 'JDBC fetch size hints'),
('WORKER_IMPORT', 'file_processing.chunk_size', '500', 'INTEGER', 'rows', 'batch.worker.import.file-processing.chunk-size', 'BATCH_WORKER_IMPORT_CHUNK_SIZE', 'Load batch chunk; overridden by file_template_config.chunk_size when set'),
('WORKER_EXPORT', 'file_processing.streaming_enabled', 'true', 'BOOLEAN', NULL, 'batch.worker.export.file-processing.streaming-enabled', 'BATCH_WORKER_EXPORT_STREAMING_ENABLED', 'Streaming export writes'),
('WORKER_EXPORT', 'file_processing.page_size', '1000', 'INTEGER', 'rows', 'batch.worker.export.file-processing.page-size', 'BATCH_WORKER_EXPORT_PAGE_SIZE', 'Detail pagination page size'),
('WORKER_EXPORT', 'file_processing.fetch_size', '1000', 'INTEGER', 'rows', 'batch.worker.export.file-processing.fetch-size', 'BATCH_WORKER_EXPORT_FETCH_SIZE', 'Reserved / JDBC fetch alignment'),
('WORKER_EXPORT', 'file_processing.chunk_size', '500', 'INTEGER', 'rows', 'batch.worker.export.file-processing.chunk-size', 'BATCH_WORKER_EXPORT_CHUNK_SIZE', 'Flush chunk for delimited/json writers'),

-- batch-worker-dispatch
('WORKER_DISPATCH', 'circuit_breaker.failure_threshold', '5', 'INTEGER', 'failures', 'batch.worker.dispatch.circuit-breaker.failure-threshold', NULL, 'Failures before channel short-circuit'),
('WORKER_DISPATCH', 'circuit_breaker.cooldown_millis', '60000', 'MILLIS', NULL, 'batch.worker.dispatch.circuit-breaker.cooldown-millis', 'BATCH_DISPATCH_CB_COOLDOWN_MS', 'Cooldown while circuit open'),
('WORKER_DISPATCH', 'receipt_poll.interval_millis', '60000', 'MILLIS', NULL, 'batch.worker.dispatch.receipt-poll.interval-millis', 'BATCH_DISPATCH_RECEIPT_POLL_MS', 'Async receipt poll interval'),
('WORKER_DISPATCH', 'receipt_poll.batch_size', '50', 'INTEGER', 'records', 'batch.worker.dispatch.receipt-poll.batch-size', 'BATCH_DISPATCH_RECEIPT_POLL_BATCH', 'Receipt poll batch'),

-- Schema default (Flyway V13)
('SCHEMA', 'file_template_config.chunk_size', '500', 'INTEGER', 'rows', 'batch.file_template_config.chunk_size (column default)', NULL, 'DB default when template row has no override; CHECK chunk_size > 0')
ON CONFLICT (module, parameter_key) DO NOTHING;
