-- =========================================================
-- V185 - BFS 最小资产分区物化读模型
-- =========================================================
-- 范围:
--   data_asset / asset_partition 只承载 BFS 产物新鲜度查询和 readiness 判断。
--   权威版本链仍是 ADR-017 的 batch.result_version;本迁移不做企业数据目录、
--   字段级血缘、记录级血缘或数据治理平台能力。
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.data_asset (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      VARCHAR(64)  NOT NULL,
    asset_code     VARCHAR(128) NOT NULL,
    asset_type     VARCHAR(32)  NOT NULL DEFAULT 'JOB',
    display_name   VARCHAR(256),
    owner_job_code VARCHAR(128),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_data_asset_tenant_code_type UNIQUE (tenant_id, asset_code, asset_type),
    CONSTRAINT ck_data_asset_type CHECK (asset_type IN ('JOB')) NOT VALID
);

ALTER TABLE batch.data_asset VALIDATE CONSTRAINT ck_data_asset_type;

CREATE INDEX IF NOT EXISTS idx_data_asset_tenant_type_code
    ON batch.data_asset (tenant_id, asset_type, asset_code);

COMMENT ON TABLE batch.data_asset IS
    'BFS 最小资产目录:当前只登记 JOB 产物,用于 asset partition readiness,不是企业数据目录';
COMMENT ON COLUMN batch.data_asset.asset_code IS '资产编码;JOB 类型下等于 job_code';
COMMENT ON COLUMN batch.data_asset.asset_type IS '资产类型;当前只允许 JOB';
COMMENT ON COLUMN batch.data_asset.owner_job_code IS '产出该资产的 BFS job_code';

CREATE TABLE IF NOT EXISTS batch.asset_partition (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    asset_id          BIGINT       NOT NULL REFERENCES batch.data_asset (id),
    asset_code        VARCHAR(128) NOT NULL,
    partition_key     VARCHAR(256) NOT NULL,
    biz_date          DATE         NOT NULL,
    freshness_status  VARCHAR(32)  NOT NULL,
    result_version_id BIGINT       REFERENCES batch.result_version (id),
    business_key      VARCHAR(256) NOT NULL,
    job_instance_id   BIGINT,
    effective_at      TIMESTAMPTZ,
    payload_storage   VARCHAR(32),
    payload_ref       VARCHAR(512),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_asset_partition_tenant_asset_partition
        UNIQUE (tenant_id, asset_code, partition_key),
    CONSTRAINT ck_asset_partition_freshness_status
        CHECK (freshness_status IN ('EFFECTIVE')) NOT VALID
);

ALTER TABLE batch.asset_partition VALIDATE CONSTRAINT ck_asset_partition_freshness_status;

CREATE INDEX IF NOT EXISTS idx_asset_partition_tenant_asset_biz_date
    ON batch.asset_partition (tenant_id, asset_code, biz_date);
CREATE INDEX IF NOT EXISTS idx_asset_partition_tenant_freshness_updated
    ON batch.asset_partition (tenant_id, freshness_status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_asset_partition_tenant_business_key
    ON batch.asset_partition (tenant_id, business_key);

COMMENT ON TABLE batch.asset_partition IS
    'BFS 最小资产分区新鲜度读模型;由 EFFECTIVE result_version 物化刷新';
COMMENT ON COLUMN batch.asset_partition.partition_key IS
    '分区键;JOB 类型第一阶段固定为 biz_date ISO 字符串';
COMMENT ON COLUMN batch.asset_partition.freshness_status IS
    '新鲜度状态;当前只物化 EFFECTIVE,非生效版本不写入本表';
COMMENT ON COLUMN batch.asset_partition.result_version_id IS
    '当前分区对应的 EFFECTIVE result_version.id';
