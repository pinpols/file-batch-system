-- Job 的默认单作业 Workflow 是受管关联，不是第二个可编辑 Job。

CREATE UNIQUE INDEX IF NOT EXISTS uk_job_definition_tenant_id
    ON batch.job_definition (tenant_id, id);

ALTER TABLE batch.workflow_definition
    ADD COLUMN IF NOT EXISTS single_job_definition_id BIGINT;

CREATE UNIQUE INDEX IF NOT EXISTS uk_workflow_single_job_definition
    ON batch.workflow_definition (tenant_id, single_job_definition_id)
    WHERE single_job_definition_id IS NOT NULL;

ALTER TABLE batch.workflow_definition
    ADD CONSTRAINT fk_workflow_single_job_definition
        FOREIGN KEY (tenant_id, single_job_definition_id)
        REFERENCES batch.job_definition (tenant_id, id)
        NOT VALID;

ALTER TABLE batch.workflow_definition
    VALIDATE CONSTRAINT fk_workflow_single_job_definition;

COMMENT ON COLUMN batch.workflow_definition.single_job_definition_id IS
    '由平台维护的关联，指向此默认单作业 Workflow 所代表的 Job 定义；组合 Workflow 时为空。';
