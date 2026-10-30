-- 将资产新鲜度策略绑定到同租户的作业定义生命周期。

ALTER TABLE batch.asset_freshness_policy
    ADD CONSTRAINT fk_asset_freshness_policy_job_definition_tenant
        FOREIGN KEY (tenant_id, asset_code)
        REFERENCES batch.job_definition (tenant_id, job_code)
        ON DELETE CASCADE NOT VALID;

ALTER TABLE batch.asset_freshness_policy
    VALIDATE CONSTRAINT fk_asset_freshness_policy_job_definition_tenant;

COMMENT ON CONSTRAINT fk_asset_freshness_policy_job_definition_tenant
    ON batch.asset_freshness_policy IS
    '租户范围内的所属 Job；监控策略依赖该 Job 配置。';
