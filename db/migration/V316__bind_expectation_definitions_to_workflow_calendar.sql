-- 周期性预期定义必须归属于同一租户的 Workflow 日历。
-- 应用会在写入前执行此校验；此外，该外键还会串行化父日历变更
-- 与并发预期写入，并保护直接 SQL 写入路径。

CREATE UNIQUE INDEX uk_workflow_definition_tenant_id_calendar
    ON batch.workflow_definition (tenant_id, id, calendar_code);

CREATE INDEX idx_external_event_expectation_workflow_calendar_fk
    ON batch.external_event_expectation_definition (tenant_id, workflow_definition_id, calendar_code);

CREATE INDEX idx_file_group_expectation_workflow_calendar_fk
    ON batch.file_group_expectation_definition (tenant_id, workflow_definition_id, calendar_code);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM batch.external_event_expectation_definition expectation
          JOIN batch.workflow_definition workflow
            ON workflow.tenant_id = expectation.tenant_id
           AND workflow.id = expectation.workflow_definition_id
         WHERE workflow.calendar_code IS DISTINCT FROM expectation.calendar_code
    ) OR EXISTS (
        SELECT 1
          FROM batch.file_group_expectation_definition expectation
          JOIN batch.workflow_definition workflow
            ON workflow.tenant_id = expectation.tenant_id
           AND workflow.id = expectation.workflow_definition_id
         WHERE workflow.calendar_code IS DISTINCT FROM expectation.calendar_code
    ) THEN
        RAISE EXCEPTION 'expectation definitions do not match their Workflow calendar before V316';
    END IF;
END $$;

ALTER TABLE batch.external_event_expectation_definition
    ADD CONSTRAINT fk_external_event_expectation_workflow_calendar
        FOREIGN KEY (tenant_id, workflow_definition_id, calendar_code)
        REFERENCES batch.workflow_definition (tenant_id, id, calendar_code)
        ON UPDATE NO ACTION
        ON DELETE NO ACTION
        NOT VALID;

ALTER TABLE batch.file_group_expectation_definition
    ADD CONSTRAINT fk_file_group_expectation_workflow_calendar
        FOREIGN KEY (tenant_id, workflow_definition_id, calendar_code)
        REFERENCES batch.workflow_definition (tenant_id, id, calendar_code)
        ON UPDATE NO ACTION
        ON DELETE NO ACTION
        NOT VALID;

ALTER TABLE batch.external_event_expectation_definition
    VALIDATE CONSTRAINT fk_external_event_expectation_workflow_calendar;

ALTER TABLE batch.file_group_expectation_definition
    VALIDATE CONSTRAINT fk_file_group_expectation_workflow_calendar;

COMMENT ON CONSTRAINT fk_external_event_expectation_workflow_calendar
    ON batch.external_event_expectation_definition IS
    '预期项定义必须使用同一租户 Workflow 的日历；变更父日历前须先显式清理子项。V316 保留原有两列租户外键，并增加更严格的日历身份约束。';

COMMENT ON CONSTRAINT fk_file_group_expectation_workflow_calendar
    ON batch.file_group_expectation_definition IS
    '预期项定义必须使用同一租户 Workflow 的日历；变更父日历前须先显式清理子项。V316 保留原有两列租户外键，并增加更严格的日历身份约束。';
