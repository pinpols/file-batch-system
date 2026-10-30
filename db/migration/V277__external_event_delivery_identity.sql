-- 同一来源事件可以向每个 Workflow 各投递一次。唯一键确保重试
-- 不依赖调用方生成的 HTTP 幂等键，并消除并发插入竞争。

ALTER TABLE batch.trigger_request
    ADD COLUMN external_event_source VARCHAR(128),
    ADD COLUMN external_event_id VARCHAR(256),
    ADD CONSTRAINT ck_trigger_request_external_source_identity CHECK (
        (external_event_source IS NULL AND external_event_id IS NULL)
        OR (
            trigger_type = 'EVENT'
            AND external_event_code IS NOT NULL
            AND external_event_source IS NOT NULL
            AND external_event_id IS NOT NULL
            AND length(btrim(external_event_source)) > 0
            AND length(btrim(external_event_id)) > 0
        )
    ) NOT VALID;

ALTER TABLE batch.trigger_request
    VALIDATE CONSTRAINT ck_trigger_request_external_source_identity;

CREATE UNIQUE INDEX uk_trigger_request_external_event_delivery
    ON batch.trigger_request (
        tenant_id,
        workflow_definition_id,
        external_event_source,
        external_event_id
    )
    WHERE external_event_source IS NOT NULL;

COMMENT ON COLUMN batch.trigger_request.external_event_source IS
    '发出事件的外部系统稳定身份。';
COMMENT ON COLUMN batch.trigger_request.external_event_id IS
    '在 external_event_source 范围内唯一的稳定事件标识。';
