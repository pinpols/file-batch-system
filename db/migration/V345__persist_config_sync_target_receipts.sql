-- 配置同步的目标提交回执与聚合审计分开提交；审计补录只能核对回执，不能再次导入。

CREATE UNIQUE INDEX uk_config_sync_log_tenant_id
    ON batch.config_sync_log (tenant_id, id);

CREATE UNIQUE INDEX uk_config_sync_import_request
    ON batch.config_sync_log (tenant_id, (detail_json ->> 'requestKeyHash'))
    WHERE sync_direction = 'IMPORT' AND detail_json ? 'requestKeyHash';
COMMENT ON INDEX batch.uk_config_sync_import_request IS
    '配置导入请求身份；目标提交后审计失败也不能用同一 HTTP 请求重复导入。';

CREATE TABLE batch.config_sync_target_receipt (
    audit_tenant_id VARCHAR(64) NOT NULL,
    log_id BIGINT NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    result_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (audit_tenant_id, log_id, tenant_id),
    CONSTRAINT fk_config_sync_receipt_log
        FOREIGN KEY (audit_tenant_id, log_id)
        REFERENCES batch.config_sync_log (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_config_sync_receipt_result_tenant
        CHECK (jsonb_typeof(result_json) = 'object'
            AND result_json ? 'tenantId'
            AND result_json ->> 'tenantId' IS NOT NULL
            AND result_json ->> 'tenantId' = tenant_id)
);

CREATE INDEX idx_config_sync_receipt_target
    ON batch.config_sync_target_receipt (tenant_id, audit_tenant_id, log_id);

COMMENT ON TABLE batch.config_sync_target_receipt IS
    '配置导入的不可变目标提交结果；成功回执与目标配置同事务提交，供聚合审计恢复核对。';
COMMENT ON COLUMN batch.config_sync_target_receipt.audit_tenant_id IS '发起同步且拥有聚合审计的租户。';
COMMENT ON COLUMN batch.config_sync_target_receipt.log_id IS '本次导入的原始聚合审计记录 ID。';
COMMENT ON COLUMN batch.config_sync_target_receipt.tenant_id IS '本次目标配置事务所属租户，也是回执隔离边界。';
COMMENT ON COLUMN batch.config_sync_target_receipt.result_json IS '已确认的目标执行结果，包含目标租户和原始计数；不得由当前配置反推。';
COMMENT ON COLUMN batch.config_sync_target_receipt.created_at IS '目标执行结果确认并持久化的时间。';

ALTER TABLE batch.config_sync_target_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.config_sync_target_receipt FORCE ROW LEVEL SECURITY;
CREATE POLICY config_sync_receipt_tenant_isolation
    ON batch.config_sync_target_receipt
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
