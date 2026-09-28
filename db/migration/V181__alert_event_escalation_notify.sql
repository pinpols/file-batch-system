-- =========================================================
-- V181 - Alert escalation notify watermark (close the last-mile loop)
-- 说明：
-- 1) V176 增加 escalation_tier/escalated_at 后，告警超过确认时限仍未确认时，运维扫描会通过日志和指标提升可见性。
--    但该升级链路不会主动通知用户。本字段使控制台通知器能够在每次级别提升时仅发送一次站内 Webhook，
--    并记录已通知到的最高级别。
-- 2) escalation_notified_tier 表示已发送通知的最高 escalation_tier。通知器筛选
--    escalation_tier > escalation_notified_tier 的记录，完成派发后通过 CAS 推进水位。
-- 3) 仅新增字段：已有记录默认通知级别为 0，并在下次升级时通知，不追溯发送历史通知，避免重复打扰；
--    级别为 0 的 OPEN 告警满足 escalation_tier=0（0 > 0 为 false）。
-- 4) alert_event 没有 archive.* 镜像表，因此不需要配套归档迁移
--    （已确认不存在 batch.alert_event_archive 表）。
-- =========================================================

ALTER TABLE batch.alert_event
    ADD COLUMN IF NOT EXISTS escalation_notified_tier INTEGER NOT NULL DEFAULT 0;

-- 通知扫描条件：status='OPEN' AND escalation_tier > escalation_notified_tier。
-- 针对 OPEN 记录创建部分索引，避免告警表增大后周期性通知扫描成本持续上升；
-- 大多数告警会进入 ACKED/CLOSED 状态并从部分索引中排除。
CREATE INDEX IF NOT EXISTS idx_alert_event_escalation_notify
    ON batch.alert_event (escalation_tier, escalation_notified_tier)
    WHERE status = 'OPEN';
