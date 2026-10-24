-- Workflow Run 必须引用由同一租户拥有的定义。
-- V247 已提供被引用的 (tenant_id, id) 唯一键。

ALTER TABLE batch.workflow_run
    DROP CONSTRAINT IF EXISTS workflow_run_workflow_definition_id_fkey,
    ADD CONSTRAINT fk_workflow_run_workflow_definition_tenant
        FOREIGN KEY (tenant_id, workflow_definition_id)
        REFERENCES batch.workflow_definition (tenant_id, id)
        NOT VALID;

ALTER TABLE batch.workflow_run
    VALIDATE CONSTRAINT fk_workflow_run_workflow_definition_tenant;
