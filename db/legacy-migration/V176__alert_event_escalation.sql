-- =========================================================
-- V176：增加告警升级阶梯，补齐运维告警跟进流程。
-- 说明：
-- 1) 记录未在确认时限内确认的 OPEN 告警已升级级别，供运维扫描逐级提升告警级别。
-- 2) 仅新增字段：已有记录默认级别为 0（从未升级）。
-- 3) alert_event 没有 archive.* 镜像表，因此不需要配套归档迁移
--    （已确认不存在 batch.alert_event_archive 表）。
-- =========================================================

ALTER TABLE batch.alert_event
    ADD COLUMN IF NOT EXISTS escalation_tier INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS escalated_at    TIMESTAMPTZ;

-- 扫描条件：status='OPEN' AND escalation_tier < max，且 last_seen_at 早于阈值。
-- OPEN 行上的部分索引可降低周期性升级扫描的成本，避免表增长后扫描开销
-- 持续上升（大多数记录最终会转为 ACKED/CLOSED 并退出扫描范围）。

CREATE INDEX IF NOT EXISTS idx_alert_event_escalation_scan
    ON batch.alert_event (last_seen_at)
    WHERE status = 'OPEN';
