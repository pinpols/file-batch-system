-- =========================================================
-- V357：保存告警关闭意图和通知轮询水位。
-- 说明：历史 CLOSED 不回填关闭意图，避免部署后无依据地重发历史恢复通知。
-- =========================================================

ALTER TABLE batch.alert_event
    ADD COLUMN closed_at TIMESTAMPTZ,
    ADD COLUMN close_reason VARCHAR(32),
    ADD COLUMN am_last_reemit_at TIMESTAMPTZ;

COMMENT ON COLUMN batch.alert_event.closed_at IS
    '本次关闭意图的数据库时刻；历史空值不自动制造恢复通知。';
COMMENT ON COLUMN batch.alert_event.close_reason IS
    '关闭原因：MANUAL 为人工关闭，RECOVERED 为当前违约恢复，POLICY_DISABLED 为规则主动撤销。';
COMMENT ON COLUMN batch.alert_event.am_last_reemit_at IS
    '最近一次周期重发尝试时刻，仅用于公平轮询，不表示最终渠道送达。';
