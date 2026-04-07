-- D-1: Global idempotency layer — prevents duplicate execution of critical operations.
-- The UNIQUE constraint on (tenant_id, idempotency_key) is the core mechanism:
-- INSERT ... ON CONFLICT DO NOTHING ensures at-most-once semantics at the DB level.

CREATE TABLE IF NOT EXISTS batch.idempotency_record (
    id              BIGSERIAL    PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    idempotency_key VARCHAR(512) NOT NULL,
    result_payload  TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_idempotency_key UNIQUE (tenant_id, idempotency_key)
);

COMMENT ON TABLE batch.idempotency_record IS 'Global idempotency guard — each row represents one executed operation, keyed by tenant + business key';
COMMENT ON COLUMN batch.idempotency_record.idempotency_key IS 'Caller-defined unique key (e.g., trigger_request:tenantId:requestId, node_dispatch:workflowRunId:nodeCode)';
COMMENT ON COLUMN batch.idempotency_record.result_payload IS 'Optional JSON result for callers that need to retrieve the outcome of a prior execution';
