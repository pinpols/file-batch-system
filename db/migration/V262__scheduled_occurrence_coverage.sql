-- 覆盖水位证明截至某一时刻的所有定时触发都已生成投影。
-- 所需调度缺少最新水位时，日结必须拒绝放行。

CREATE TABLE batch.scheduled_occurrence_coverage (
    tenant_id             VARCHAR(64)  NOT NULL,
    workflow_definition_id BIGINT      NOT NULL,
    schedule_version      INTEGER      NOT NULL,
    calendar_code         VARCHAR(128),
    coverage_from_at      TIMESTAMPTZ  NOT NULL,
    coverage_through_at   TIMESTAMPTZ  NOT NULL,
    last_success_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_scheduled_occurrence_coverage
        PRIMARY KEY (tenant_id, workflow_definition_id),
    CONSTRAINT fk_scheduled_occurrence_coverage_workflow
        FOREIGN KEY (tenant_id, workflow_definition_id)
        REFERENCES batch.workflow_definition (tenant_id, id)
);

CREATE INDEX idx_scheduled_occurrence_coverage_calendar
    ON batch.scheduled_occurrence_coverage
       (tenant_id, calendar_code, coverage_through_at);

COMMENT ON TABLE batch.scheduled_occurrence_coverage IS
    '最近一次成功投影 Quartz 触发记录的覆盖水位；缺失或调度版本过期时禁止结算空日。';
COMMENT ON COLUMN batch.scheduled_occurrence_coverage.schedule_version IS
    '记录的覆盖区间内已完整投影的 Workflow 调度版本。';
