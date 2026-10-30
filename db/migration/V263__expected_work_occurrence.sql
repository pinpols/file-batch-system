-- 供空业务日结算使用的持久化事件/文件工作预期。
-- 生产方应按已配置的预期标识进行协调，不应从
-- file_record 或 trigger_request 记录推断缺失工作。

CREATE TABLE batch.expected_work_occurrence (
    id                    BIGSERIAL PRIMARY KEY,
    tenant_id             VARCHAR(64)  NOT NULL,
    calendar_code         VARCHAR(128) NOT NULL,
    biz_date              DATE         NOT NULL,
    workflow_definition_id BIGINT      NOT NULL,
    source_type           VARCHAR(24)  NOT NULL,
    source_key            VARCHAR(256) NOT NULL,
    generation            INTEGER      NOT NULL DEFAULT 1,
    expected_at           TIMESTAMPTZ,
    due_at                TIMESTAMPTZ,
    occurrence_status     VARCHAR(24)  NOT NULL DEFAULT 'EXPECTED',
    trigger_request_id    VARCHAR(128),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_expected_work_workflow
        FOREIGN KEY (tenant_id, workflow_definition_id)
        REFERENCES batch.workflow_definition (tenant_id, id),
    CONSTRAINT uk_expected_work_identity
        UNIQUE (tenant_id, calendar_code, workflow_definition_id, source_type, source_key, biz_date, generation),
    CONSTRAINT ck_expected_work_generation_positive
        CHECK (generation > 0),
    CONSTRAINT ck_expected_work_source_type
        CHECK (source_type IN ('FILE_GROUP', 'EXTERNAL_EVENT', 'WORKFLOW')),
    CONSTRAINT ck_expected_work_status
        CHECK (occurrence_status IN ('EXPECTED', 'ARRIVED', 'SATISFIED', 'EXPIRED', 'CANCELLED'))
);

CREATE INDEX idx_expected_work_day_status
    ON batch.expected_work_occurrence (tenant_id, calendar_code, biz_date, occurrence_status);

ALTER TABLE batch.expected_work_occurrence ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.expected_work_occurrence FORCE ROW LEVEL SECURITY;
CREATE POLICY expected_work_tenant_isolation ON batch.expected_work_occurrence
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

COMMENT ON TABLE batch.expected_work_occurrence IS
    '某租户日历业务日预期到达的事件或文件工作；未终结的记录会阻止自动结算空日。';
COMMENT ON COLUMN batch.expected_work_occurrence.source_key IS
    '预期项生产者提供的稳定身份，例如文件组配置及其对应的发生序号。';
COMMENT ON COLUMN batch.expected_work_occurrence.workflow_definition_id IS
    '此预期项所代表业务日义务所属的 Workflow。';
COMMENT ON COLUMN batch.expected_work_occurrence.source_type IS
    '负责此预期工作义务的生产者类别。';
COMMENT ON COLUMN batch.expected_work_occurrence.occurrence_status IS
    '预期工作的生命周期，涵盖到达、满足、过期或取消。';
COMMENT ON COLUMN batch.expected_work_occurrence.generation IS
    '单调递增的义务代次；重新打开时创建新的 EXPECTED 记录，并保留此前的终态记录。';
