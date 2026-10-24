-- Persist Workflow as the explicit top-level Trigger target and bind direct Workflow Runs to
-- the accepted request. Internal Job-node requests retain their Job target and parent Run.
CREATE UNIQUE INDEX uk_workflow_definition_tenant_id
    ON batch.workflow_definition (tenant_id, id);

-- Workflow-targeted requests have no Job code.
ALTER TABLE batch.trigger_request
    -- squawk-ignore ban-drop-not-null
    ALTER COLUMN job_code DROP NOT NULL,
    ADD COLUMN workflow_definition_id BIGINT,
    ADD CONSTRAINT ck_trigger_request_target
        CHECK ((job_code IS NOT NULL) <> (workflow_definition_id IS NOT NULL)) NOT VALID,
    ADD CONSTRAINT fk_trigger_request_workflow_definition
        FOREIGN KEY (tenant_id, workflow_definition_id)
        REFERENCES batch.workflow_definition (tenant_id, id) NOT VALID;

CREATE UNIQUE INDEX uk_trigger_request_tenant_id
    ON batch.trigger_request (tenant_id, id);

ALTER TABLE batch.workflow_run
    ADD COLUMN trigger_request_id BIGINT,
    ADD COLUMN workflow_version_snapshot INTEGER,
    ADD COLUMN data_interval_start TIMESTAMPTZ,
    ADD COLUMN data_interval_end TIMESTAMPTZ,
    ADD COLUMN execution_snapshot JSONB,
    ADD CONSTRAINT fk_workflow_run_trigger_request
        FOREIGN KEY (tenant_id, trigger_request_id)
        REFERENCES batch.trigger_request (tenant_id, id) NOT VALID,
    ADD CONSTRAINT ck_workflow_run_data_interval
        CHECK (data_interval_start IS NULL OR data_interval_end IS NULL OR data_interval_start < data_interval_end) NOT VALID;

ALTER TABLE batch.trigger_request
    VALIDATE CONSTRAINT ck_trigger_request_target,
    VALIDATE CONSTRAINT fk_trigger_request_workflow_definition;

ALTER TABLE batch.workflow_run
    VALIDATE CONSTRAINT fk_workflow_run_trigger_request,
    VALIDATE CONSTRAINT ck_workflow_run_data_interval;

ALTER TABLE archive.workflow_run_archive
    ADD COLUMN trigger_request_id BIGINT,
    ADD COLUMN workflow_version_snapshot INTEGER,
    ADD COLUMN data_interval_start TIMESTAMPTZ,
    ADD COLUMN data_interval_end TIMESTAMPTZ,
    ADD COLUMN execution_snapshot JSONB;

CREATE UNIQUE INDEX uk_workflow_run_trigger_request
    ON batch.workflow_run (tenant_id, trigger_request_id)
    WHERE trigger_request_id IS NOT NULL;

COMMENT ON COLUMN batch.trigger_request.workflow_definition_id IS
    'Explicit Workflow target for a top-level trigger; null only for internal Job-node requests owned by parent_workflow_run_id.';
COMMENT ON COLUMN batch.workflow_run.trigger_request_id IS
    'Trigger Request that created this Workflow Run; unique per tenant for Kafka replay idempotency.';
COMMENT ON COLUMN batch.workflow_run.workflow_version_snapshot IS
    'Workflow definition version captured when this run was accepted.';
COMMENT ON COLUMN batch.workflow_run.data_interval_start IS
    'Inclusive start of the trigger data interval captured for this Workflow Run.';
COMMENT ON COLUMN batch.workflow_run.data_interval_end IS
    'Exclusive end of the trigger data interval captured for this Workflow Run.';
COMMENT ON COLUMN batch.workflow_run.execution_snapshot IS
    'Immutable graph and effective node execution configuration captured when this Workflow Run was accepted.';
