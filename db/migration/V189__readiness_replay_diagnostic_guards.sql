-- =========================================================
-- V189 - readiness / replay / diagnostic forward guards
-- =========================================================
-- 背景:
--   V185/V186 已进入主干,不能回改历史迁移。本迁移只做前向修正:
--   1) OUTPUTS_ONLY replay entry 允许同 source_instance_id 下多个 result_version_id;
--   2) asset_partition 的生效指针由 mapper 增加版本单调守卫,这里补充索引以支撑 latest-version 校验。
-- =========================================================

DROP INDEX IF EXISTS batch.uk_replay_entry_session_source_instance;

CREATE UNIQUE INDEX IF NOT EXISTS uk_replay_entry_session_source_instance
    ON batch.batch_day_replay_entry (session_id, tenant_id, source_instance_id)
    WHERE source_instance_id IS NOT NULL
      AND result_version_id IS NULL;

COMMENT ON INDEX batch.uk_replay_entry_session_source_instance IS
    '同一 replay session 内同一个 source_instance_id 只能物化一次;OUTPUTS_ONLY 按 result_version_id 去重,允许同 source instance 多版本';

CREATE INDEX IF NOT EXISTS idx_asset_partition_tenant_result_version
    ON batch.asset_partition (tenant_id, result_version_id)
    WHERE result_version_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_result_version_tenant_business_version
    ON batch.result_version (tenant_id, business_key, version_no DESC);

COMMENT ON INDEX batch.idx_asset_partition_tenant_result_version IS
    '支撑 asset_partition readiness 关联 result_version 校验 EFFECTIVE 与最新版本';

COMMENT ON INDEX batch.idx_result_version_tenant_business_version IS
    '支撑 readiness 禁止旧 EFFECTIVE 在更新版本 PENDING/FAILED 时继续被消费';
