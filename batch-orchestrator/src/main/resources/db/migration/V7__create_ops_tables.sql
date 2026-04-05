-- =========================================================
-- V6 - Create ops, retry, dead letter and outbox tables
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.job_execution_log (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    job_instance_id          BIGINT       REFERENCES batch.job_instance(id),
    job_partition_id         BIGINT       REFERENCES batch.job_partition(id),
    log_level                VARCHAR(16)  NOT NULL,
    log_type                 VARCHAR(32)  NOT NULL,
    trace_id                 VARCHAR(128),
    message                  VARCHAR(2048) NOT NULL,
    detail_ref               VARCHAR(512),
    extra_json               JSONB,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_job_execution_log_level CHECK (log_level IN ('DEBUG', 'INFO', 'WARN', 'ERROR')),
    CONSTRAINT ck_job_execution_log_type CHECK (log_type IN ('SYSTEM', 'BUSINESS', 'RETRY', 'ALARM', 'AUDIT'))
);

CREATE TABLE IF NOT EXISTS batch.retry_schedule (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    related_type             VARCHAR(32)  NOT NULL,
    related_id               BIGINT       NOT NULL,
    retry_policy             VARCHAR(32)  NOT NULL,
    retry_count              INTEGER      NOT NULL DEFAULT 0,
    max_retry_count          INTEGER      NOT NULL DEFAULT 0,
    next_retry_at            TIMESTAMPTZ  NOT NULL,
    retry_status             VARCHAR(32)  NOT NULL,
    dedup_key                VARCHAR(256) NOT NULL,
    last_error_code          VARCHAR(64),
    last_error_message       VARCHAR(1024),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_retry_schedule_tenant_dedup UNIQUE (tenant_id, dedup_key),
    CONSTRAINT ck_retry_schedule_related_type CHECK (related_type IN ('JOB_INSTANCE', 'JOB_PARTITION', 'JOB_TASK', 'PIPELINE_INSTANCE', 'FILE_DISPATCH')),
    CONSTRAINT ck_retry_schedule_policy CHECK (retry_policy IN ('FIXED', 'EXPONENTIAL')),
    CONSTRAINT ck_retry_schedule_status CHECK (retry_status IN ('WAITING', 'RUNNING', 'SUCCESS', 'FAILED', 'EXHAUSTED', 'CANCELLED')),
    CONSTRAINT ck_retry_schedule_retry_count CHECK (retry_count >= 0),
    CONSTRAINT ck_retry_schedule_max_retry_count CHECK (max_retry_count >= 0)
);

CREATE TABLE IF NOT EXISTS batch.dead_letter_task (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    source_type              VARCHAR(32)  NOT NULL,
    source_id                BIGINT       NOT NULL,
    dead_letter_reason       VARCHAR(1024),
    payload_ref              VARCHAR(512),
    replay_status            VARCHAR(32)  NOT NULL DEFAULT 'NEW',
    replay_count             INTEGER      NOT NULL DEFAULT 0,
    last_replay_at           TIMESTAMPTZ,
    last_replay_result       VARCHAR(32),
    trace_id                 VARCHAR(128),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_dead_letter_source_type CHECK (source_type IN ('JOB_INSTANCE', 'JOB_PARTITION', 'JOB_TASK', 'PIPELINE_INSTANCE', 'FILE_DISPATCH', 'MQ_MESSAGE')),
    CONSTRAINT ck_dead_letter_replay_status CHECK (replay_status IN ('NEW', 'REPLAYING', 'SUCCESS', 'FAILED', 'GIVE_UP')),
    CONSTRAINT ck_dead_letter_replay_count CHECK (replay_count >= 0)
);

CREATE TABLE IF NOT EXISTS batch.outbox_event (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    aggregate_type           VARCHAR(64)  NOT NULL,
    aggregate_id             BIGINT       NOT NULL,
    event_type               VARCHAR(64)  NOT NULL,
    event_key                VARCHAR(256) NOT NULL,
    payload_json             JSONB        NOT NULL,
    publish_status           VARCHAR(32)  NOT NULL DEFAULT 'NEW',
    publish_attempt          INTEGER      NOT NULL DEFAULT 0,
    next_publish_at          TIMESTAMPTZ,
    trace_id                 VARCHAR(128),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_outbox_event_key UNIQUE (tenant_id, event_key),
    CONSTRAINT ck_outbox_publish_status CHECK (publish_status IN ('NEW', 'PUBLISHING', 'PUBLISHED', 'FAILED', 'GIVE_UP')),
    CONSTRAINT ck_outbox_publish_attempt CHECK (publish_attempt >= 0)
);
