-- =========================================================
-- V12 - Create compensation command and step runtime tables
-- Notes:
-- 1) Extend job_instance with rerun / retry / traceability fields.
-- 2) Create job_step_instance for step-level execution tracking.
-- 3) Create compensation_command for manual or automated compensation flow.
-- =========================================================

ALTER TABLE batch.job_instance
    ADD COLUMN IF NOT EXISTS batch_no VARCHAR(128),
    ADD COLUMN IF NOT EXISTS operator_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS rerun_flag BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS retry_flag BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS rerun_reason VARCHAR(512),
    ADD COLUMN IF NOT EXISTS related_file_id BIGINT,
    ADD COLUMN IF NOT EXISTS parent_instance_id BIGINT REFERENCES batch.job_instance(id),
    ADD COLUMN IF NOT EXISTS result_summary JSONB;

CREATE INDEX IF NOT EXISTS idx_job_instance_batch_lookup
    ON batch.job_instance (tenant_id, job_code, biz_date, batch_no);

CREATE INDEX IF NOT EXISTS idx_job_instance_related_file
    ON batch.job_instance (tenant_id, related_file_id);

CREATE TABLE IF NOT EXISTS batch.job_step_instance (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    job_instance_id          BIGINT       NOT NULL REFERENCES batch.job_instance(id),
    job_partition_id         BIGINT       REFERENCES batch.job_partition(id),
    job_task_id              BIGINT       NOT NULL REFERENCES batch.job_task(id),
    step_code                VARCHAR(128) NOT NULL,
    step_type                VARCHAR(64)  NOT NULL,
    step_status              VARCHAR(32)  NOT NULL,
    retry_count              INTEGER      NOT NULL DEFAULT 0,
    related_file_id          BIGINT,
    result_summary           JSONB,
    error_code               VARCHAR(64),
    error_message            VARCHAR(2048),
    version                  BIGINT       NOT NULL DEFAULT 0,
    started_at               TIMESTAMPTZ,
    finished_at              TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_job_step_instance_task UNIQUE (job_task_id),
    CONSTRAINT ck_job_step_instance_status CHECK (step_status IN ('CREATED', 'WAITING', 'READY', 'RUNNING', 'SUCCESS', 'FAILED', 'RETRYING', 'CANCELLED', 'TERMINATED')),
    CONSTRAINT ck_job_step_instance_retry CHECK (retry_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_job_step_instance_lookup
    ON batch.job_step_instance (tenant_id, job_instance_id, step_code, step_status);

CREATE TABLE IF NOT EXISTS batch.compensation_command (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    command_no               VARCHAR(128) NOT NULL,
    compensation_type        VARCHAR(32)  NOT NULL,
    target_id                BIGINT,
    job_code                 VARCHAR(128),
    biz_date                 DATE,
    batch_no                 VARCHAR(128),
    related_job_instance_id  BIGINT REFERENCES batch.job_instance(id),
    related_file_id          BIGINT,
    approval_id              VARCHAR(128),
    operator_id              VARCHAR(64),
    reason                   VARCHAR(1024),
    strategy                 VARCHAR(64),
    command_status           VARCHAR(32)  NOT NULL,
    trace_id                 VARCHAR(128),
    result_summary           JSONB,
    error_code               VARCHAR(64),
    error_message            VARCHAR(2048),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at              TIMESTAMPTZ,
    CONSTRAINT uk_compensation_command_tenant_no UNIQUE (tenant_id, command_no),
    CONSTRAINT ck_compensation_command_type CHECK (compensation_type IN ('JOB', 'STEP', 'PARTITION', 'FILE', 'BATCH', 'DLQ')),
    CONSTRAINT ck_compensation_command_status CHECK (command_status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_compensation_command_lookup
    ON batch.compensation_command (tenant_id, compensation_type, command_status, created_at desc);
