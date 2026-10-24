-- 周期性外部事件预期按日历和业务日期显式配置。

CREATE TABLE batch.external_event_expectation_definition (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    calendar_code            VARCHAR(128) NOT NULL,
    workflow_definition_id   BIGINT       NOT NULL,
    event_code               VARCHAR(128) NOT NULL,
    expected_local_time      TIME         NOT NULL,
    due_local_time           TIME,
    enabled                  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_external_event_expectation_workflow
        FOREIGN KEY (tenant_id, workflow_definition_id)
        REFERENCES batch.workflow_definition (tenant_id, id),
    CONSTRAINT uk_external_event_expectation_definition
        UNIQUE (tenant_id, calendar_code, workflow_definition_id, event_code),
    CONSTRAINT ck_external_event_expectation_due_time
        CHECK (due_local_time IS NULL OR due_local_time > expected_local_time)
);

CREATE INDEX idx_external_event_expectation_enabled
    ON batch.external_event_expectation_definition (tenant_id, calendar_code, enabled, workflow_definition_id);

ALTER TABLE batch.external_event_expectation_definition ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.external_event_expectation_definition FORCE ROW LEVEL SECURITY;
CREATE POLICY external_event_expectation_tenant_isolation
    ON batch.external_event_expectation_definition
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

COMMENT ON TABLE batch.external_event_expectation_definition IS
    '租户日历每个已开启业务日对应的周期性外部事件预期项。';
