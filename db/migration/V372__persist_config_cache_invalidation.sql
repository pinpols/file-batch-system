-- 配置变更与失效意图同事务提交，Redis 故障期间保留待重放记录。
CREATE TABLE batch.config_cache_invalidation_outbox (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    config_type VARCHAR(40) NOT NULL,
    config_code VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    claim_token UUID,
    claim_until TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_config_cache_invalidation_claim CHECK ((claim_token IS NULL) = (claim_until IS NULL)),
    CONSTRAINT ck_config_cache_invalidation_attempts CHECK (attempts >= 0)
);

COMMENT ON TABLE batch.config_cache_invalidation_outbox IS '已提交配置的缓存失效意图；完成投递后删除。';
COMMENT ON COLUMN batch.config_cache_invalidation_outbox.id IS '待投递记录标识，不代表事务提交顺序。';
COMMENT ON COLUMN batch.config_cache_invalidation_outbox.tenant_id IS '失效范围所属租户。';
COMMENT ON COLUMN batch.config_cache_invalidation_outbox.config_type IS '缓存配置类型。';
COMMENT ON COLUMN batch.config_cache_invalidation_outbox.config_code IS '配置编码，星号表示该租户类型的全部编码。';
COMMENT ON COLUMN batch.config_cache_invalidation_outbox.created_at IS '失效意图产生时间。';
COMMENT ON COLUMN batch.config_cache_invalidation_outbox.next_attempt_at IS '允许下一次领取的时间。';
COMMENT ON COLUMN batch.config_cache_invalidation_outbox.claim_token IS '当前领取令牌，用于防止过期领取者确认新任务。';
COMMENT ON COLUMN batch.config_cache_invalidation_outbox.claim_until IS '领取租约截止时间。';
COMMENT ON COLUMN batch.config_cache_invalidation_outbox.attempts IS '已领取次数。';

CREATE INDEX idx_config_cache_invalidation_due
    ON batch.config_cache_invalidation_outbox (next_attempt_at, id);

-- 与现有配置版本栅栏一致，跨租户投递只授权迁移及平台运行账号。
ALTER TABLE batch.config_cache_invalidation_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.config_cache_invalidation_outbox FORCE ROW LEVEL SECURITY;
REVOKE ALL ON batch.config_cache_invalidation_outbox FROM PUBLIC;
REVOKE ALL ON SEQUENCE batch.config_cache_invalidation_outbox_id_seq FROM PUBLIC;
DO $$
BEGIN
    EXECUTE format('CREATE POLICY config_cache_invalidation_platform ON batch.config_cache_invalidation_outbox TO %I USING (true) WITH CHECK (true)', current_user);
END;
$$;

CREATE FUNCTION batch.record_config_cache_invalidation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, batch
AS $$
DECLARE
    old_tenant TEXT;
    new_tenant TEXT;
    old_code TEXT;
    new_code TEXT;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        old_tenant := to_jsonb(OLD) ->> 'tenant_id';
        old_code := CASE WHEN TG_ARGV[1] = '' THEN 'enabled-first' ELSE to_jsonb(OLD) ->> TG_ARGV[1] END;
        INSERT INTO batch.config_cache_invalidation_outbox (tenant_id, config_type, config_code)
        VALUES (old_tenant, TG_ARGV[0], old_code);
    END IF;
    IF TG_OP <> 'DELETE' THEN
        new_tenant := to_jsonb(NEW) ->> 'tenant_id';
        new_code := CASE WHEN TG_ARGV[1] = '' THEN 'enabled-first' ELSE to_jsonb(NEW) ->> TG_ARGV[1] END;
        IF TG_OP = 'INSERT' OR (new_tenant, new_code) IS DISTINCT FROM (old_tenant, old_code) THEN
            INSERT INTO batch.config_cache_invalidation_outbox (tenant_id, config_type, config_code)
            VALUES (new_tenant, TG_ARGV[0], new_code);
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION batch.record_config_cache_invalidation() FROM PUBLIC;

CREATE TRIGGER trg_job_definition_cache_invalidation AFTER INSERT OR UPDATE OR DELETE ON batch.job_definition
    FOR EACH ROW EXECUTE FUNCTION batch.record_config_cache_invalidation('job-definition', 'job_code');
CREATE TRIGGER trg_workflow_definition_cache_invalidation AFTER INSERT OR UPDATE OR DELETE ON batch.workflow_definition
    FOR EACH ROW EXECUTE FUNCTION batch.record_config_cache_invalidation('workflow-definition', 'workflow_code');
CREATE TRIGGER trg_business_calendar_cache_invalidation AFTER INSERT OR UPDATE OR DELETE ON batch.business_calendar
    FOR EACH ROW EXECUTE FUNCTION batch.record_config_cache_invalidation('business-calendar', 'calendar_code');
CREATE TRIGGER trg_batch_window_cache_invalidation AFTER INSERT OR UPDATE OR DELETE ON batch.batch_window
    FOR EACH ROW EXECUTE FUNCTION batch.record_config_cache_invalidation('batch-window', 'window_code');
CREATE TRIGGER trg_tenant_quota_policy_cache_invalidation AFTER INSERT OR UPDATE OR DELETE ON batch.tenant_quota_policy
    FOR EACH ROW EXECUTE FUNCTION batch.record_config_cache_invalidation('tenant-quota-policy', '');
