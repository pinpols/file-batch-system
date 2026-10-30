CREATE TABLE IF NOT EXISTS batch.sim_tenant_fixture_import_state (
    tenant_id       VARCHAR(64) PRIMARY KEY,
    fixture_sha256  CHAR(64) NOT NULL,
    applied_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE batch.sim_tenant_fixture_import_state IS
    '本地 SIM 测试框架用于租户配置包 fixture 的幂等记录表。';
