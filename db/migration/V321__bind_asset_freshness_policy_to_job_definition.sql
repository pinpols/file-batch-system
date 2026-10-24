ALTER TABLE batch.asset_freshness_policy
    ADD CONSTRAINT fk_asset_freshness_policy_job_definition_tenant
        FOREIGN KEY (tenant_id, asset_code)
        REFERENCES batch.job_definition (tenant_id, job_code)
        ON DELETE CASCADE NOT VALID;

ALTER TABLE batch.asset_freshness_policy
    VALIDATE CONSTRAINT fk_asset_freshness_policy_job_definition_tenant;

COMMENT ON CONSTRAINT fk_asset_freshness_policy_job_definition_tenant
    ON batch.asset_freshness_policy IS
    'Tenant-scoped Job owner; the monitoring policy is configuration dependent on its Job';
