-- 外部事件来源标识可用于去重，即使其 HTTP 幂等键
-- 与首次尝试不同。状态查询仍覆盖每个已接收的键。

CREATE TABLE batch.trigger_request_idempotency_alias (
    tenant_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_trigger_request_idempotency_alias PRIMARY KEY (tenant_id, idempotency_key),
    CONSTRAINT fk_trigger_request_idempotency_alias_request
        FOREIGN KEY (tenant_id, request_id)
        REFERENCES batch.trigger_request (tenant_id, request_id)
        ON DELETE CASCADE
);

COMMENT ON TABLE batch.trigger_request_idempotency_alias IS
    '额外的调用方幂等键，解析到按外部事件身份去重的请求。';

COMMENT ON COLUMN batch.trigger_request_idempotency_alias.idempotency_key IS
    '租户范围内的调用方重试键；将已受理的外部事件投递解析到原业务请求 ID。';
