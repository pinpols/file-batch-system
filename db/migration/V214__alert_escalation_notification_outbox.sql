-- V214：告警升级通知 Outbox。
--
-- 旧实现先发布 ALERT_ESCALATED，再推进 alert_event.escalation_notified_tier。
-- 如果并发 CAS 失败，本实例可能已经发出一条并不属于自己的状态变化通知。
--
-- 本表把状态变化与外部通知拆成两步：
--   1) 在同一事务内 CAS 推进 alert_event.escalation_notified_tier，并插入一条 outbox。
--   2) 后台 relay 消费本表，投递到既有 Console realtime/webhook 通知链路。

CREATE TABLE IF NOT EXISTS batch.alert_escalation_notification_outbox (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    alert_event_id BIGINT NOT NULL,
    escalation_tier INTEGER NOT NULL,
    stream VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json JSONB NOT NULL,
    publish_status VARCHAR(32) NOT NULL DEFAULT 'NEW',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_publish_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error VARCHAR(1024),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_alert_escalation_notification_outbox
        UNIQUE (tenant_id, alert_event_id, escalation_tier),
    CONSTRAINT ck_alert_escalation_notification_outbox_status
        CHECK (publish_status IN ('NEW', 'PUBLISHING', 'PUBLISHED', 'FAILED', 'GIVE_UP')),
    CONSTRAINT ck_alert_escalation_notification_outbox_attempt
        CHECK (attempt_count >= 0),
    CONSTRAINT ck_alert_escalation_notification_outbox_tier
        CHECK (escalation_tier > 0)
);

CREATE INDEX IF NOT EXISTS idx_alert_escalation_notification_outbox_poll
    ON batch.alert_escalation_notification_outbox (publish_status, next_publish_at, id);

COMMENT ON TABLE batch.alert_escalation_notification_outbox IS
    '告警升级通知 Outbox；与 escalation_notified_tier 水位线同事务写入，再异步投递 Console 通知链路。';
COMMENT ON COLUMN batch.alert_escalation_notification_outbox.alert_event_id IS
    '对应 batch.alert_event.id。';
COMMENT ON COLUMN batch.alert_escalation_notification_outbox.escalation_tier IS
    '本次已获得 CAS 所有权的升级层级。';
COMMENT ON COLUMN batch.alert_escalation_notification_outbox.event_type IS
    '投递到 Console 通知链路的事件类型，当前为 ALERT_ESCALATED。';
COMMENT ON COLUMN batch.alert_escalation_notification_outbox.payload_json IS
    'ALERT_ESCALATED 通知载荷 JSON。';
COMMENT ON COLUMN batch.alert_escalation_notification_outbox.publish_status IS
    '发布状态机：NEW、PUBLISHING、PUBLISHED、FAILED、GIVE_UP。';
