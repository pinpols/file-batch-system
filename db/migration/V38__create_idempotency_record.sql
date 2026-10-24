-- D-1：全局幂等层，防止关键操作重复执行。
-- (tenant_id, idempotency_key) 上的 UNIQUE 约束是核心幂等机制：
-- INSERT ... ON CONFLICT DO NOTHING 在数据库层保证操作至多执行一次。

CREATE TABLE IF NOT EXISTS batch.idempotency_record (
    id              BIGSERIAL    PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    idempotency_key VARCHAR(512) NOT NULL,
    result_payload  TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_idempotency_key UNIQUE (tenant_id, idempotency_key)
);

COMMENT ON TABLE batch.idempotency_record IS '全局幂等保护记录；每行代表一次已执行操作，按租户和业务键确定身份。';
COMMENT ON COLUMN batch.idempotency_record.idempotency_key IS '调用方定义的唯一幂等键，例如 trigger_request:tenantId:requestId 或 node_dispatch:workflowRunId:nodeCode。';
COMMENT ON COLUMN batch.idempotency_record.result_payload IS '可选的 JSON 结果；供调用方查询此前执行的结果。';
