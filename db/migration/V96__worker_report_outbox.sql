-- ADR-015 Phase 2: worker REPORT 缓冲表（平台 PG；由 orchestrator flyway 迁移，worker 仅读写）

CREATE TABLE IF NOT EXISTS batch.worker_report_outbox (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    task_id BIGINT NOT NULL,
    partition_invocation_id VARCHAR(128),
    trace_id VARCHAR(128),
    payload_json JSONB NOT NULL,
    publish_status VARCHAR(32) NOT NULL,
    attempt_count INTEGER NOT NULL,
    next_attempt_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT uq_worker_report_outbox_tenant_task UNIQUE (tenant_id, task_id)
);

CREATE INDEX IF NOT EXISTS idx_worker_report_outbox_poll
    ON batch.worker_report_outbox (publish_status, next_attempt_at);
