-- 健康状态由已配置的通道运行情况派生。软删除会保留
-- 配置行和健康快照；物理删除则会一并移除该状态。

ALTER TABLE batch.file_channel_health
    ADD CONSTRAINT fk_file_channel_health_config_tenant
        FOREIGN KEY (tenant_id, channel_code)
        REFERENCES batch.file_channel_config (tenant_id, channel_code)
        ON DELETE CASCADE
        NOT VALID;

ALTER TABLE batch.file_channel_health
    VALIDATE CONSTRAINT fk_file_channel_health_config_tenant;

COMMENT ON CONSTRAINT fk_file_channel_health_config_tenant
    ON batch.file_channel_health IS
    '健康状态必须关联同一租户中的通道配置，并随该通道一并删除。';
