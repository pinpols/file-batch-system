-- P2 tenant-routing: 租户 → biz 分片(placement key)映射表。
--
-- 路由分两层(见 docs/plans/biz-tiered-tenancy-plan):
--   1) 算法默认:未登记的租户由 HashAndSiloPlacementResolver 按 hash 自动落池化片(零维护)。
--   2) 表覆盖(本表):显式登记的租户用表里的 placement_key —— 用于 silo 独占指派、租户迁片/
--      再平衡这类「不该靠改配置+重启」的在线维护动作。表命中优先于 hash。
--
-- 只存「租户 → placement key」,**绝不存连接账密**:凭据走 secrets/vault(见 secrets/biz-shards/),
-- 与本表解耦(对标 Azure Shard Map Manager:catalog 存 location 不存 credential)。
--
-- 落在 platform 库 batch schema(worker 经 @Primary platform DataSource 读)。租户/运维维护。
-- PK = tenant_id 满足多租约束(所有 PK 含 tenant_id);本表属配置/字典性质,无需 archive 镜像。
--
-- ⚠️ 版本协调:若分区 PR(V170/V171)先合 main,本迁移合并时需 renumber 到下一空位(见
--    docs 关于 Flyway max-ver 冲突的约定)。本分支为 draft 暂不合 main。

CREATE TABLE IF NOT EXISTS batch.business_tenant_placement (
    tenant_id     VARCHAR(64)  PRIMARY KEY,
    placement_key VARCHAR(64)  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by    VARCHAR(128)
);

COMMENT ON TABLE batch.business_tenant_placement IS
    'P2 tenant-routing 租户→biz 分片(placement key)显式映射;表命中优先于 hash 默认;只存 key 不存账密。';
COMMENT ON COLUMN batch.business_tenant_placement.placement_key IS
    'biz 分片 key(如 shard-0 / silo-big),与 routing.shards 的 key + resolver 输出对应。';
COMMENT ON COLUMN batch.business_tenant_placement.updated_by IS
    '最后维护者(运维/租户管理流程标识),便于审计迁片操作。';
