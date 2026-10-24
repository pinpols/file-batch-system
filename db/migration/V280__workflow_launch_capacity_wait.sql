CREATE TABLE batch.workflow_launch_wait (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    workflow_definition_id BIGINT NOT NULL REFERENCES batch.workflow_definition(id),
    biz_date DATE NOT NULL,
    dry_run BOOLEAN NOT NULL DEFAULT FALSE,
    launch_payload JSONB NOT NULL,
    wait_status VARCHAR(24) NOT NULL DEFAULT 'WAITING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_workflow_launch_wait_request UNIQUE (tenant_id, request_id),
    CONSTRAINT fk_workflow_launch_wait_request FOREIGN KEY (tenant_id, request_id)
        REFERENCES batch.trigger_request(tenant_id, request_id) ON DELETE CASCADE,
    CONSTRAINT ck_workflow_launch_wait_status CHECK (wait_status IN ('WAITING', 'RESUMED', 'REJECTED')),
    CONSTRAINT ck_workflow_launch_wait_attempt CHECK (attempt_count >= 0)
);

CREATE INDEX idx_workflow_launch_wait_due
    ON batch.workflow_launch_wait (tenant_id, next_attempt_at, created_at, id)
    WHERE wait_status = 'WAITING';

ALTER TABLE batch.workflow_launch_wait ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.workflow_launch_wait FORCE ROW LEVEL SECURITY;
CREATE POLICY workflow_launch_wait_tenant_isolation ON batch.workflow_launch_wait
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

COMMENT ON TABLE batch.workflow_launch_wait IS
    'Orchestrator-owned durable wait for a Workflow business-day execution slot; preserves the original request without blocking Kafka consumption';

COMMENT ON COLUMN batch.workflow_launch_wait.launch_payload IS
    'Original accepted LaunchRequest snapshot used to resume the same tenant/request without reconstructing parameters or dates';
COMMENT ON COLUMN batch.workflow_launch_wait.wait_status IS
    'WAITING awaits execution capacity; RESUMED has been admitted or deduplicated; REJECTED failed admission and must not resume';
