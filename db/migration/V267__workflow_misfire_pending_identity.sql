-- Manual misfire approval is attached to the scheduled Workflow occurrence.
-- The optional job_code remains execution-target context only.
-- Misfire rows are owned by a Workflow occurrence.
ALTER TABLE batch.trigger_misfire_pending
    -- squawk-ignore ban-drop-not-null
    ALTER COLUMN job_code DROP NOT NULL,
    ADD COLUMN workflow_definition_id BIGINT,
    ADD CONSTRAINT fk_trigger_misfire_pending_workflow
        FOREIGN KEY (tenant_id, workflow_definition_id)
        REFERENCES batch.workflow_definition (tenant_id, id) NOT VALID,
    ADD CONSTRAINT ck_trigger_misfire_pending_workflow_present
        CHECK (workflow_definition_id IS NOT NULL) NOT VALID;

ALTER TABLE batch.trigger_misfire_pending
    VALIDATE CONSTRAINT fk_trigger_misfire_pending_workflow,
    VALIDATE CONSTRAINT ck_trigger_misfire_pending_workflow_present;

DROP INDEX IF EXISTS batch.uk_trigger_misfire_pending_quartz_fire;
DROP INDEX IF EXISTS batch.idx_trigger_misfire_pending_tenant_job;

CREATE UNIQUE INDEX uk_trigger_misfire_pending_workflow_fire
    ON batch.trigger_misfire_pending (tenant_id, workflow_definition_id, scheduled_fire_time);

CREATE INDEX idx_trigger_misfire_pending_tenant_workflow
    ON batch.trigger_misfire_pending (tenant_id, workflow_definition_id, detected_at DESC);
