-- ============================================================================
-- V146: 软删除表 UNIQUE 与"复活"语义文档化 — 实质改动在 mapper.xml
-- ============================================================================
--
-- 背景: V144/V145 引入 is_deleted 软删后, 原 UNIQUE 约束(tenant_id, code) 仍包含所有行,
-- 再次创建同 code 会撞 UNIQUE。
--
-- 设计选择: **保留全局 UNIQUE**(不改 partial), 配套 upsert 走"ON CONFLICT 复活":
--   ON CONFLICT (...) DO UPDATE SET ..., is_deleted = false
-- 当 recreate 同 code 时, upsert 命中已删除的旧行, 把字段覆盖回新值 + is_deleted 复位 false,
-- id 保留(避免外键悬空), 语义清晰("软删的同 code 在重建后自动恢复, 不会产生幽灵双行")。
--
-- 为什么不用 partial UNIQUE (WHERE is_deleted = false):
--   partial UNIQUE 允许"同 code 软删行 + 同 code 活跃行"共存, 纯 INSERT 路径会
--   形成两行不同 id 的并存(旧 deleted + 新 active)。看似"recreate 成功"但破坏了
--   "同一逻辑实体单一持久身份"约束, 外键引用 / 历史关联会指向旧 id, 后续无法理清。
--
-- 本迁移本身只加文档注释; 实质改动在同 PR 的 mapper.xml:
--   - FileChannelConfigMapper.upsertFileChannelConfig
--   - FileTemplateConfigMapper.upsertFileTemplateConfig
--   - AlertRoutingConfigMapper.upsertAlertRoutingConfig
--   - NotificationChannelMapper.insert (改 upsert 语义)
--   - ConsoleWebhookSubscriptionMapper.insert (改 upsert 语义)
-- 每个 ON CONFLICT 子句加 `is_deleted = false` 显式复活。
--
-- V145 的 idx_*_active partial index 是查询加速索引(只 SELECT 用), 不与本约束冲突, 保留。
-- ============================================================================

COMMENT ON COLUMN batch.file_template_config.is_deleted IS
    '软删除标记(true = 已删除);业务 SELECT 默认过滤,delete 路径 UPDATE,upsert 路径 ON CONFLICT 复活 is_deleted=false。CommonFragments.xml#activePredicate';
COMMENT ON COLUMN batch.file_channel_config.is_deleted IS
    '软删除标记(同 file_template_config); recreate 同 channel_code 走 upsert 自动复活, 不产生幽灵双行';
COMMENT ON COLUMN batch.notification_channel.is_deleted IS
    '软删除标记(同上); recreate 同 channel_code 走 upsert 自动复活';
COMMENT ON COLUMN batch.webhook_subscription.is_deleted IS
    '软删除标记(同上); recreate 同 name 走 upsert 自动复活';
COMMENT ON COLUMN batch.alert_routing_config.is_deleted IS
    '软删除标记(同上); recreate 同 route_code 走 upsert 自动复活';
