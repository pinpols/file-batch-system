-- ============================================================
-- V83: trigger_outbox_event 唯一索引提升为唯一约束
-- ============================================================
-- 背景：
--   V80 创建 trigger_outbox_event 时用 CREATE UNIQUE INDEX 实现 (tenant_id, request_id)
--   唯一性。SQL 标准推荐用 UNIQUE CONSTRAINT —— pg_dump 恢复顺序、planner 推导、
--   ON CONFLICT 子句都对 CONSTRAINT 更友好。
--
-- 影响：
--   纯 schema 变更,索引 → 约束在 PG 内部都是 b-tree unique index 实现,逻辑等价。
--   index 名 / constraint 名保持 uk_trigger_outbox_event_tenant_request 不变,
--   ON CONFLICT (tenant_id, request_id) 行为不受影响。
-- ============================================================

DROP INDEX IF EXISTS batch.uk_trigger_outbox_event_tenant_request;

ALTER TABLE batch.trigger_outbox_event
    ADD CONSTRAINT uk_trigger_outbox_event_tenant_request UNIQUE (tenant_id, request_id);
