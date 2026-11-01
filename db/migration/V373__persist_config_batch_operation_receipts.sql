-- 批量配置操作先记录原请求身份和目标顺序，逐项回执与对应租户的变更共同提交。
CREATE TABLE batch.config_batch_operation (
    audit_tenant_id VARCHAR(64) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    request_identity_hash VARCHAR(64) NOT NULL,
    operation_kind VARCHAR(32) NOT NULL,
    targets_json JSONB NOT NULL,
    response_metadata_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (audit_tenant_id, operation_id),
    CONSTRAINT uk_config_batch_operation_request UNIQUE (request_identity_hash),
    CONSTRAINT ck_config_batch_operation_targets CHECK (jsonb_typeof(targets_json) = 'array'),
    CONSTRAINT ck_config_batch_operation_metadata CHECK (jsonb_typeof(response_metadata_json) = 'object')
);

COMMENT ON TABLE batch.config_batch_operation IS
    '配置批量操作的原始请求身份和目标顺序；用于核对原操作，不能根据当前配置重新推导结果。';
COMMENT ON COLUMN batch.config_batch_operation.audit_tenant_id IS '拥有本次操作记录的审计租户，也是记录的行级隔离边界。';
COMMENT ON COLUMN batch.config_batch_operation.operation_id IS '原批量操作标识。';
COMMENT ON COLUMN batch.config_batch_operation.request_identity_hash IS '由原请求租户头、方法、路径和幂等键组成的身份摘要。';
COMMENT ON COLUMN batch.config_batch_operation.operation_kind IS '操作类型，用于区分租户复制及作业包入口。';
COMMENT ON COLUMN batch.config_batch_operation.targets_json IS '原请求目标列表，保留顺序和重复目标。';
COMMENT ON COLUMN batch.config_batch_operation.response_metadata_json IS '重建响应所需的非敏感元数据。';
COMMENT ON COLUMN batch.config_batch_operation.created_at IS '操作登记时间。';

ALTER TABLE batch.config_batch_operation ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.config_batch_operation FORCE ROW LEVEL SECURITY;
CREATE POLICY config_batch_operation_tenant_isolation
    ON batch.config_batch_operation
    USING (audit_tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (audit_tenant_id = current_setting('app.tenant_id', true));

CREATE TABLE batch.config_batch_target_receipt (
    audit_tenant_id VARCHAR(64) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    target_ordinal INTEGER NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    result_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (audit_tenant_id, operation_id, target_ordinal),
    CONSTRAINT fk_config_batch_receipt_operation
        FOREIGN KEY (audit_tenant_id, operation_id)
        REFERENCES batch.config_batch_operation (audit_tenant_id, operation_id) ON DELETE CASCADE,
    CONSTRAINT ck_config_batch_receipt_ordinal CHECK (target_ordinal >= 0),
    CONSTRAINT ck_config_batch_receipt_result_tenant
        CHECK (jsonb_typeof(result_json) = 'object'
            AND result_json ? 'tenantId'
            AND result_json ->> 'tenantId' IS NOT NULL
            AND result_json ->> 'tenantId' = tenant_id)
);

COMMENT ON TABLE batch.config_batch_target_receipt IS
    '配置批量操作的逐项确认结果；成功回执与目标变更同事务提交。';
COMMENT ON COLUMN batch.config_batch_target_receipt.audit_tenant_id IS '本次操作登记所属的审计租户。';
COMMENT ON COLUMN batch.config_batch_target_receipt.operation_id IS '原批量操作标识。';
COMMENT ON COLUMN batch.config_batch_target_receipt.target_ordinal IS '目标在原请求中的从零开始的位置，重复租户使用不同位置。';
COMMENT ON COLUMN batch.config_batch_target_receipt.tenant_id IS '该项事务所属的目标租户，也是回执行级隔离边界。';
COMMENT ON COLUMN batch.config_batch_target_receipt.result_json IS '已确认的目标原始结果，不根据当前配置反推。';
COMMENT ON COLUMN batch.config_batch_target_receipt.created_at IS '目标结果确认并记录的时间。';

ALTER TABLE batch.config_batch_target_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.config_batch_target_receipt FORCE ROW LEVEL SECURITY;
CREATE POLICY config_batch_receipt_tenant_isolation
    ON batch.config_batch_target_receipt
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
