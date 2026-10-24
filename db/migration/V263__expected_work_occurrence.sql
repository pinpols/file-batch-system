-- Durable event/file work expectations used by empty-day settlement.
-- Producers reconcile their configured expectation identity instead of inferring
-- missing work from file_record or trigger_request rows.
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
    'Configured expected event/file work for one tenant calendar business date; open rows block automatic empty-day settlement';
COMMENT ON COLUMN batch.expected_work_occurrence.source_key IS
    'Stable identity supplied by the expectation producer, such as configured file group plus its occurrence';
COMMENT ON COLUMN batch.expected_work_occurrence.workflow_definition_id IS
    'Workflow whose business-day obligation this occurrence represents';
COMMENT ON COLUMN batch.expected_work_occurrence.source_type IS
    'Producer category that owns this expected-work obligation';
COMMENT ON COLUMN batch.expected_work_occurrence.occurrence_status IS
    'Expected work lifecycle from arrival through satisfaction, expiry, or cancellation';
COMMENT ON COLUMN batch.expected_work_occurrence.generation IS
    'Monotonic obligation generation; reopening creates a new EXPECTED row and preserves the prior terminal row';
