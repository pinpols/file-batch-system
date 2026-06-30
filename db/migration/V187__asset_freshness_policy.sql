-- =========================================================
-- V187 - JOB asset freshness policy
-- =========================================================
-- 范围:
--   只为 BFS 产物 readiness 增加最小 freshness SLA 策略。
--   不做企业数据目录、字段级血缘、外部节假日同步或业务正确性裁判。
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.asset_freshness_policy (
    id                     BIGSERIAL PRIMARY KEY,
    tenant_id              VARCHAR(64)  NOT NULL,
    asset_code             VARCHAR(128) NOT NULL,
    asset_type             VARCHAR(32)  NOT NULL DEFAULT 'JOB',
    expected_by_local_time TIME         NOT NULL,
    timezone               VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    stale_after_seconds    INTEGER      NOT NULL DEFAULT 0,
    lookback_days          INTEGER      NOT NULL DEFAULT 1,
    severity               VARCHAR(16)  NOT NULL DEFAULT 'WARN',
    enabled                BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_asset_freshness_policy_tenant_asset
        UNIQUE (tenant_id, asset_code, asset_type),
    CONSTRAINT ck_asset_freshness_policy_type
        CHECK (asset_type IN ('JOB')),
    CONSTRAINT ck_asset_freshness_policy_stale_after
        CHECK (stale_after_seconds >= 0),
    CONSTRAINT ck_asset_freshness_policy_lookback
        CHECK (lookback_days BETWEEN 1 AND 31),
    CONSTRAINT ck_asset_freshness_policy_severity
        CHECK (severity IN ('INFO', 'WARN', 'ERROR', 'CRITICAL'))
);

CREATE INDEX IF NOT EXISTS idx_asset_freshness_policy_enabled
    ON batch.asset_freshness_policy (enabled, tenant_id, asset_code)
    WHERE enabled = TRUE;

COMMENT ON TABLE batch.asset_freshness_policy IS
    'BFS JOB asset freshness SLA 策略;用于生成 missing/stale alert_event,不参与 readiness 放行裁决';
COMMENT ON COLUMN batch.asset_freshness_policy.asset_code IS
    'JOB 类型下等于 job_code';
COMMENT ON COLUMN batch.asset_freshness_policy.expected_by_local_time IS
    '每天该业务日结果应生效的本地时间';
COMMENT ON COLUMN batch.asset_freshness_policy.stale_after_seconds IS
    'expected_by_local_time 之后的宽限秒数;超出后告警从 MISSING 升为 STALE';
COMMENT ON COLUMN batch.asset_freshness_policy.lookback_days IS
    '每轮向前扫描的业务日天数,包含当前本地日期';
