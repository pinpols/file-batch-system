-- =========================================================
-- V176 - Alert escalation ladder (ops alert follow-through)
-- 说明：
-- 1) 记录未在确认时限内确认的 OPEN 告警已升级级别，供运维扫描逐级提升告警级别。
-- 2) 仅新增字段：已有记录默认级别为 0（从未升级）。
-- 3) alert_event 没有 archive.* 镜像表，因此不需要配套归档迁移
--    （已确认不存在 batch.alert_event_archive 表）。
-- =========================================================

ALTER TABLE batch.alert_event
    ADD COLUMN IF NOT EXISTS escalation_tier INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS escalated_at    TIMESTAMPTZ;

-- Sweep predicate: status='OPEN' AND escalation_tier < max AND last_seen_at old.
-- A partial index on OPEN rows keeps the recurring escalation scan cheap as the
-- table grows (most rows eventually move to ACKED/CLOSED and drop out).
CREATE INDEX IF NOT EXISTS idx_alert_event_escalation_scan
    ON batch.alert_event (last_seen_at)
    WHERE status = 'OPEN';
