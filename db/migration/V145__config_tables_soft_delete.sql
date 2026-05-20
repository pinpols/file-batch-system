-- ============================================================================
-- V145: 4 张配置表统一加 is_deleted 软删除(② 推广 file_template_config pilot)
-- ============================================================================
--
-- 动机:与 V144 file_template_config 同源 —— 配置表误删恢复成本高,物理 DELETE 走
-- archive 只能事后取证,无法 1-click 撤销。本批扩到 4 张同语义配置表:
--
-- - batch.file_channel_config       (V6,SFTP/API/EMAIL 等文件派发渠道)
-- - batch.notification_channel      (V49,EMAIL/DINGTALK/WECOM 等通知渠道)
-- - batch.webhook_subscription      (V45,webhook 订阅)
-- - batch.alert_routing_config      (V43,告警路由)
--
-- 约定参见 docs/coding-conventions.md §9.6 软删除约定(opt-in)。
--
-- 兼容性:
-- - 新列 NOT NULL DEFAULT false,既有行视为活动,零业务影响
-- - 现有 SELECT 不加 is_deleted 过滤者读全部,本 commit 同步改 mapper.xml
-- - 这 4 张表均未入 ArchiveSchemaDriftCheck.ARCHIVED_TABLES,无归档列对齐压力
--
-- 索引选择:
-- - partial index WHERE is_deleted = false,加速"列表只读 active"查询
-- - 索引列与各表 UNIQUE 约束的 tenant_id + 业务代码列对齐
-- ============================================================================

-- file_channel_config
ALTER TABLE batch.file_channel_config
    ADD COLUMN is_deleted BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_file_channel_config_active
    ON batch.file_channel_config (tenant_id, channel_code)
    WHERE is_deleted = FALSE;
COMMENT ON COLUMN batch.file_channel_config.is_deleted IS
    '软删除标记(true = 已删除);业务 SELECT 默认过滤,delete 路径改 UPDATE。CommonFragments.xml#activePredicate';

-- notification_channel
ALTER TABLE batch.notification_channel
    ADD COLUMN is_deleted BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_notification_channel_active
    ON batch.notification_channel (tenant_id, channel_code)
    WHERE is_deleted = FALSE;
COMMENT ON COLUMN batch.notification_channel.is_deleted IS
    '软删除标记(true = 已删除);业务 SELECT 默认过滤,delete 路径改 UPDATE。CommonFragments.xml#activePredicate';

-- webhook_subscription
ALTER TABLE batch.webhook_subscription
    ADD COLUMN is_deleted BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_webhook_subscription_active
    ON batch.webhook_subscription (tenant_id, name)
    WHERE is_deleted = FALSE;
COMMENT ON COLUMN batch.webhook_subscription.is_deleted IS
    '软删除标记(true = 已删除);业务 SELECT 默认过滤,delete 路径改 UPDATE。CommonFragments.xml#activePredicate';

-- alert_routing_config
ALTER TABLE batch.alert_routing_config
    ADD COLUMN is_deleted BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_alert_routing_config_active
    ON batch.alert_routing_config (tenant_id, route_code)
    WHERE is_deleted = FALSE;
COMMENT ON COLUMN batch.alert_routing_config.is_deleted IS
    '软删除标记(true = 已删除);业务 SELECT 默认过滤,delete 路径改 UPDATE。CommonFragments.xml#activePredicate';
