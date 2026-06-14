-- P2 tenant-routing: biz 分片目录(shard catalog)。对标 Azure Shard Map Manager 的「shards 目录」层。
--
-- 登记「有哪些 placement 片 + 各片位置(host/port/db)+ 状态」,作为:
--   1) console placement 指派的 key 白名单权威来源(取代依赖 worker 配置 routing.shards);
--   2) 前端「分片列表」视图的数据源。
--
-- **只存位置,绝不存账密**:连接凭据走 secrets/vault,本表 secret_ref 仅记凭据的引用名(如 placement_key),
-- 与 business_tenant_placement 的凭据分离原则一致。
--
-- 生效边界:本表是「登记/可见/校验」层。worker 实际连接池仍由各 worker 的 routing.shards 配置 + secrets
-- 在启动时构建;改本表不会让运行中的 worker 动态增减池(那需重启重建池)。两者应保持一致,本表为人审/校验的真相源。
--
-- 落 platform 库 batch schema。属系统配置表(CLAUDE.md §多租隔离 豁免类①),PK=placement_key,无 tenant_id;
-- 配置/字典性质,无需 archive 镜像。

CREATE TABLE IF NOT EXISTS batch.business_shard_catalog (
    placement_key VARCHAR(64)  PRIMARY KEY,
    host          VARCHAR(255) NOT NULL,
    port          INTEGER      NOT NULL DEFAULT 5432,
    db_name       VARCHAR(64)  NOT NULL DEFAULT 'batch_business',
    secret_ref    VARCHAR(128),
    pool_max_size INTEGER,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    description   VARCHAR(512),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by    VARCHAR(128)
);

COMMENT ON TABLE batch.business_shard_catalog IS
    'P2 tenant-routing biz 分片目录:placement key→位置(host/port/db)+状态;只存位置不存账密(secret_ref 仅引用名)。';
COMMENT ON COLUMN batch.business_shard_catalog.secret_ref IS
    '凭据引用名(secrets/vault 里的 key,如 placement_key);本表不存任何账密明文。';
COMMENT ON COLUMN batch.business_shard_catalog.pool_max_size IS
    '建议每片连接池上限(供运维参考 / 与 routing.shardMaximumPoolSize 对齐);worker 实际池由其配置构建。';
COMMENT ON COLUMN batch.business_shard_catalog.enabled IS
    '是否可作为 placement 指派目标;false 的片不进 placement key 白名单(但存量指派不受影响)。';
