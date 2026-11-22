-- 每个 Quartz 回调独立记账；历史记录不推断、不回填。
CREATE TABLE batch.scheduled_occurrence_fire (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    workflow_definition_id BIGINT NOT NULL,
    scheduled_fire_time TIMESTAMPTZ NOT NULL,
    fire_instance_id VARCHAR(95) NOT NULL,
    refire_count INTEGER NOT NULL,
    actual_fire_time TIMESTAMPTZ NOT NULL,
    fire_kind VARCHAR(32) NOT NULL,
    CONSTRAINT uk_scheduled_occurrence_fire_event UNIQUE
        (tenant_id, workflow_definition_id, scheduled_fire_time, fire_instance_id, refire_count),
    CONSTRAINT fk_scheduled_occurrence_fire_workflow
        FOREIGN KEY (tenant_id, workflow_definition_id)
        REFERENCES batch.workflow_definition (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_scheduled_occurrence_fire_kind CHECK
        (fire_kind IN ('SCHEDULED', 'CATCH_UP', 'MISFIRE_APPROVAL', 'READINESS_RETRY'))
);

CREATE INDEX idx_scheduled_occurrence_fire_lookup
    ON batch.scheduled_occurrence_fire
        (tenant_id, workflow_definition_id, scheduled_fire_time, actual_fire_time, id);

ALTER TABLE batch.scheduled_occurrence_fire ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.scheduled_occurrence_fire FORCE ROW LEVEL SECURITY;
CREATE POLICY scheduled_occurrence_fire_tenant_isolation
    ON batch.scheduled_occurrence_fire
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

COMMENT ON TABLE batch.scheduled_occurrence_fire IS
    'Quartz 回调的实际触发观察记录；可有多次重试，历史缺失和观察写入失败不会回填。';
