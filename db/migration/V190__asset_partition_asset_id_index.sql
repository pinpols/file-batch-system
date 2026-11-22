-- =========================================================
-- V190 - asset_partition.asset_id 外键辅助索引
-- =========================================================
-- 背景:
--   asset_partition.asset_id REFERENCES data_asset(id) 无覆盖索引:
--   删除/更新 data_asset 行时 PG 需全表扫 asset_partition 校验 FK,
--   按 asset 维度反查分区也走不了索引。与既有 idx_asset_partition_*
--   一致带 tenant_id 前缀列,兼做租户内按 asset 反查。
-- =========================================================

CREATE INDEX IF NOT EXISTS idx_asset_partition_asset_id
    ON batch.asset_partition (tenant_id, asset_id);

COMMENT ON INDEX batch.idx_asset_partition_asset_id IS
    'asset_id 外键辅助索引(tenant_id 前缀):支撑 data_asset 级联校验与租户内按 asset 反查分区';
