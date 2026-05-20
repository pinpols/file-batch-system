-- =========================================================
-- V134: event_delivery_log.outbox_event_id 加 FK 索引
--
-- 依据: docs/analysis/dba-schema-review-2026-05-20.md §3.1 / Quick wins §8
--
-- 背景:
--   V21 创建 batch.event_delivery_log 时 outbox_event_id 已建 FK,
--   但 PG 不会为 FK 自动建索引。scripts/db/cleanup-outbox-events.sql 删除
--   outbox_event 前要先 DELETE event_delivery_log WHERE outbox_event_id IN (...),
--   缺索引会退化为 seq scan。outbox_event 体量上亿后清理会显著拖慢。
--
-- 影响:
--   纯 ADD INDEX,无破坏性。建索引期间表持续可写(用 CONCURRENTLY)。
-- =========================================================

-- NB: Flyway 默认事务包裹会拒绝 CREATE INDEX CONCURRENTLY,本迁移需在
-- batch-common Flyway 配置中以 mixed=false + transactional=false 单独执行;
-- 若团队规范要求 in-tx 迁移,改为去掉 CONCURRENTLY(短锁,事件表写入会瞬时阻塞)。
CREATE INDEX IF NOT EXISTS idx_event_delivery_log_outbox_event_id
    ON batch.event_delivery_log (outbox_event_id);

-- archive 镜像表的同名索引已在 V71 创建(idx_event_delivery_log_archive_outbox),
-- 此处无需重复。
