-- A Workflow Run must reference a definition owned by the same tenant.
-- V247 provides the referenced (tenant_id, id) unique key.
ALTER TABLE batch.workflow_run
    DROP CONSTRAINT IF EXISTS workflow_run_workflow_definition_id_fkey,
    ADD CONSTRAINT fk_workflow_run_workflow_definition_tenant
        FOREIGN KEY (tenant_id, workflow_definition_id)
        REFERENCES batch.workflow_definition (tenant_id, id)
        NOT VALID;

ALTER TABLE batch.workflow_run
    VALIDATE CONSTRAINT fk_workflow_run_workflow_definition_tenant;
