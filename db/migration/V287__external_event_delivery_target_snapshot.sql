-- 首次接收外部事件时，冻结目标集合和请求载荷。

CREATE TABLE batch.external_event_delivery (
    id                              BIGSERIAL PRIMARY KEY,
    tenant_id                       VARCHAR(64)  NOT NULL,
    event_source                    VARCHAR(128) NOT NULL,
    event_id                        VARCHAR(256) NOT NULL,
    event_code                      VARCHAR(128) NOT NULL,
    biz_date                        DATE         NOT NULL,
    request_id                      VARCHAR(128) NOT NULL,
    payload_snapshot                JSONB        NOT NULL,
    target_workflow_definition_ids  JSONB        NOT NULL,
    trace_id                        VARCHAR(128) NOT NULL,
    created_at                      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_external_event_delivery_identity
        UNIQUE (tenant_id, event_source, event_id),
    CONSTRAINT uk_external_event_delivery_tenant_id
        UNIQUE (tenant_id, id),
    CONSTRAINT uk_external_event_delivery_request
        UNIQUE (tenant_id, request_id),
    CONSTRAINT ck_external_event_delivery_targets
        CHECK (CASE
            WHEN jsonb_typeof(target_workflow_definition_ids) = 'array'
                THEN jsonb_array_length(target_workflow_definition_ids) > 0
            ELSE false
        END)
);

CREATE TABLE batch.external_event_delivery_alias (
    tenant_id        VARCHAR(64)  NOT NULL,
    idempotency_key  VARCHAR(256) NOT NULL,
    delivery_id      BIGINT       NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_external_event_delivery_alias
        PRIMARY KEY (tenant_id, idempotency_key),
    CONSTRAINT fk_external_event_delivery_alias_delivery
        FOREIGN KEY (tenant_id, delivery_id)
        REFERENCES batch.external_event_delivery (tenant_id, id)
        ON DELETE CASCADE
);

CREATE INDEX idx_external_event_delivery_alias_delivery
    ON batch.external_event_delivery_alias (tenant_id, delivery_id);

ALTER TABLE batch.external_event_delivery ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.external_event_delivery FORCE ROW LEVEL SECURITY;
CREATE POLICY external_event_delivery_tenant_isolation
    ON batch.external_event_delivery
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE batch.external_event_delivery_alias ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.external_event_delivery_alias FORCE ROW LEVEL SECURITY;
CREATE POLICY external_event_delivery_alias_tenant_isolation
    ON batch.external_event_delivery_alias
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

COMMENT ON TABLE batch.external_event_delivery IS
    '已受理的外部事件及其不可变载荷和首次受理时冻结的 Workflow 目标快照。';

COMMENT ON TABLE batch.external_event_delivery_alias IS
    '租户范围内用于定位外部事件投递的 HTTP 幂等键。';

COMMENT ON COLUMN batch.external_event_delivery.payload_snapshot IS
    '外部事件首次受理时冻结的规范化请求载荷。';

COMMENT ON COLUMN batch.external_event_delivery.trace_id IS
    '首次受理投递的链路标识；幂等重投时保持不变。';

COMMENT ON COLUMN batch.external_event_delivery_alias.idempotency_key IS
    '用于查询事件级投递状态的租户范围 HTTP Idempotency-Key。';
