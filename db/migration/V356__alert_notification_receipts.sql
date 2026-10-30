-- =========================================================
-- V356：记录告警逐实体、逐接收渠道的通知成功回执。
-- 说明：仅最终渠道明确成功后写入；Alertmanager 接收成功不等于通知送达。
-- =========================================================

CREATE TABLE batch.alert_notification_receipt (
    tenant_id VARCHAR(64) NOT NULL,
    dedup_fingerprint VARCHAR(128) NOT NULL,
    receiver_code VARCHAR(128) NOT NULL,
    firing_delivered_at TIMESTAMPTZ,
    resolved_delivered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_alert_notification_receipt
        PRIMARY KEY (tenant_id, dedup_fingerprint, receiver_code),
    CONSTRAINT fk_alert_notification_receipt_event
        FOREIGN KEY (tenant_id, dedup_fingerprint)
        REFERENCES batch.alert_event (tenant_id, dedup_fingerprint) ON DELETE CASCADE,
    CONSTRAINT ck_alert_notification_receipt_delivered
        CHECK (firing_delivered_at IS NOT NULL OR resolved_delivered_at IS NOT NULL)
);

ALTER TABLE batch.alert_notification_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.alert_notification_receipt FORCE ROW LEVEL SECURITY;
CREATE POLICY alert_notification_receipt_tenant_isolation ON batch.alert_notification_receipt
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

COMMENT ON TABLE batch.alert_notification_receipt IS
    '告警最终通知成功回执；保留每个实体和接收渠道的发生/恢复证据，跟随原告警删除。';
COMMENT ON COLUMN batch.alert_notification_receipt.tenant_id IS '通知所属租户，与原告警复合外键绑定。';
COMMENT ON COLUMN batch.alert_notification_receipt.dedup_fingerprint IS '原告警的稳定去重指纹，不使用 Alertmanager 自身的指纹替代。';
COMMENT ON COLUMN batch.alert_notification_receipt.receiver_code IS '已成功投递的 Alertmanager 接收渠道。';
COMMENT ON COLUMN batch.alert_notification_receipt.firing_delivered_at IS '发生通知首次明确成功的数据库时刻，空表示尚无送达证据。';
COMMENT ON COLUMN batch.alert_notification_receipt.resolved_delivered_at IS '恢复通知最近一次明确成功的数据库时刻，与发生回执分别保存。';
COMMENT ON COLUMN batch.alert_notification_receipt.created_at IS '首条成功回执的写入时刻。';
COMMENT ON COLUMN batch.alert_notification_receipt.updated_at IS '最近成功回执的写入时刻。';
