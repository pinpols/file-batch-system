-- 批量日补跑的原请求和候选必须先冻结，结果不明时不能重新推导候选或自动重发。
CREATE TABLE batch.batch_day_catchup_operation (
    tenant_id VARCHAR(64) NOT NULL,
    request_key_hash VARCHAR(64) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    request_json JSONB NOT NULL,
    snapshot_json JSONB NOT NULL,
    items_json JSONB NOT NULL,
    operation_status VARCHAR(16) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, request_key_hash),
    CONSTRAINT ck_batch_day_catchup_key_hash CHECK (request_key_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_batch_day_catchup_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_batch_day_catchup_request_object CHECK (jsonb_typeof(request_json) = 'object'),
    CONSTRAINT ck_batch_day_catchup_snapshot_object CHECK (jsonb_typeof(snapshot_json) = 'object'),
    CONSTRAINT ck_batch_day_catchup_items_array CHECK (jsonb_typeof(items_json) = 'array'),
    CONSTRAINT ck_batch_day_catchup_status CHECK (operation_status IN ('PREPARED', 'RUNNING', 'COMPLETE', 'UNKNOWN')),
    CONSTRAINT ck_batch_day_catchup_revision CHECK (revision >= 0)
);

COMMENT ON TABLE batch.batch_day_catchup_operation IS
    '批量日补跑原请求、冻结候选与逐项对账状态；结果不明时不得据当前候选重新提交。';
COMMENT ON COLUMN batch.batch_day_catchup_operation.tenant_id IS '发起请求的租户，也是行级隔离边界。';
COMMENT ON COLUMN batch.batch_day_catchup_operation.request_key_hash IS '原请求幂等键的不可逆摘要，租户内唯一。';
COMMENT ON COLUMN batch.batch_day_catchup_operation.request_hash IS '规范化原请求的摘要，用于拒绝同键不同载荷。';
COMMENT ON COLUMN batch.batch_day_catchup_operation.request_json IS '规范化原请求；仅保存恢复所需的信息。';
COMMENT ON COLUMN batch.batch_day_catchup_operation.snapshot_json IS '首次受理时冻结的业务日、日历、策略和候选上下文。';
COMMENT ON COLUMN batch.batch_day_catchup_operation.items_json IS '冻结的逐项身份和已确认结果；不明结果不能视为未提交。';
COMMENT ON COLUMN batch.batch_day_catchup_operation.operation_status IS 'PREPARED、RUNNING、COMPLETE 或 UNKNOWN。';
COMMENT ON COLUMN batch.batch_day_catchup_operation.revision IS '并发更新使用的版本围栏。';
COMMENT ON COLUMN batch.batch_day_catchup_operation.created_at IS '原请求记录创建时间。';
COMMENT ON COLUMN batch.batch_day_catchup_operation.updated_at IS '请求或对账状态最后更新时间。';

ALTER TABLE batch.batch_day_catchup_operation ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.batch_day_catchup_operation FORCE ROW LEVEL SECURITY;
CREATE POLICY batch_day_catchup_operation_tenant_isolation
    ON batch.batch_day_catchup_operation
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
