-- ============================================================================
-- file_template_config 软删除字段(② 首个软删除 pilot)
-- ============================================================================
--
-- 动机:模板配错 / 误删恢复成本极高(filed_template 是导入 / 导出 的核心
-- 蓝图)。物理 DELETE 走 archive 也只能事后取证,无法 1-click 撤销。
-- 引入 is_deleted boolean 列后,delete 路径改 UPDATE SET is_deleted=true,
-- 业务 query 全部加 activePredicate 过滤。
--
-- 约定参见 docs/coding-conventions.md §9.6 软删除约定(opt-in)。
--
-- 兼容性:
-- - 新列 NOT NULL DEFAULT false,既有行全部视为活动状态,零业务影响
-- - 现有 SELECT 不加 is_deleted 过滤的暂时仍读全部,后续 mapper xml 同步加
--   activePredicate 谓词,该 commit 一并完成
-- - file_template_config 未入 ArchiveSchemaDriftCheck.ARCHIVED_TABLES,无归档
--   表对齐压力(若未来纳入归档,同步加列)
-- ============================================================================

ALTER TABLE batch.file_template_config
  ADD COLUMN is_deleted boolean NOT NULL DEFAULT false;

-- 用于 partial index 加速「列表只读 active」查询,避免每次全表扫
CREATE INDEX IF NOT EXISTS idx_file_template_config_active
  ON batch.file_template_config (tenant_id, template_code)
  WHERE is_deleted = false;

COMMENT ON COLUMN batch.file_template_config.is_deleted IS
  '软删除标记(true = 已删除,业务 SELECT 默认过滤)。CommonFragments.xml#activePredicate 谓词同步加。';
