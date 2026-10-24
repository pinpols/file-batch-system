-- Fresh-database Workflow terminal subscriptions: inbox deduplication and recoverable delivery.
CREATE TABLE batch.workflow_terminal_notification_receipt (
    tenant_id VARCHAR(64) NOT NULL,
    event_key VARCHAR(256) NOT NULL,
    payload_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, event_key)
);
COMMENT ON TABLE batch.workflow_terminal_notification_receipt IS
    'Workflow 终态通知消费回执；与收件人待投递记录同事务提交，重投不重新展开订阅。';
COMMENT ON COLUMN batch.workflow_terminal_notification_receipt.tenant_id IS '事件所属租户。';
COMMENT ON COLUMN batch.workflow_terminal_notification_receipt.event_key IS '稳定的租户/WorkflowRun/terminal 事件身份。';
COMMENT ON COLUMN batch.workflow_terminal_notification_receipt.payload_json IS '首次消费的不可变终态载荷。';
COMMENT ON COLUMN batch.workflow_terminal_notification_receipt.created_at IS '首次消费提交时间。';

ALTER TABLE batch.webhook_delivery_log
    ADD COLUMN terminal_event_key VARCHAR(256),
    ADD COLUMN delivery_claim_token UUID,
    ADD COLUMN delivery_claim_until TIMESTAMPTZ;
ALTER TABLE batch.notification_delivery_log
    ADD COLUMN terminal_event_key VARCHAR(256),
    ADD COLUMN next_retry_at TIMESTAMPTZ,
    ADD COLUMN delivery_claim_token UUID,
    ADD COLUMN delivery_claim_until TIMESTAMPTZ;

CREATE UNIQUE INDEX uk_webhook_terminal_recipient
    ON batch.webhook_delivery_log (tenant_id, subscription_id, terminal_event_key)
    WHERE terminal_event_key IS NOT NULL;
CREATE UNIQUE INDEX uk_notification_terminal_recipient
    ON batch.notification_delivery_log (tenant_id, rule_id, terminal_event_key)
    WHERE terminal_event_key IS NOT NULL;
CREATE INDEX idx_webhook_terminal_pending
    ON batch.webhook_delivery_log (next_retry_at, id)
    WHERE terminal_event_key IS NOT NULL AND next_retry_at IS NOT NULL;
CREATE INDEX idx_notification_terminal_pending
    ON batch.notification_delivery_log (next_retry_at, id)
    WHERE terminal_event_key IS NOT NULL AND next_retry_at IS NOT NULL;

COMMENT ON COLUMN batch.webhook_delivery_log.terminal_event_key IS '可靠 Workflow 终态投递的稳定事件身份。';
COMMENT ON COLUMN batch.webhook_delivery_log.delivery_claim_token IS '本轮投递认领 token，回写必须匹配。';
COMMENT ON COLUMN batch.webhook_delivery_log.delivery_claim_until IS '认领租约截止时间，过期可恢复。';
COMMENT ON COLUMN batch.notification_delivery_log.terminal_event_key IS '可靠 Workflow 终态投递的稳定事件身份。';
COMMENT ON COLUMN batch.notification_delivery_log.next_retry_at IS '可靠终态通知下次投递时间；空值表示无需自动投递。';
COMMENT ON COLUMN batch.notification_delivery_log.delivery_claim_token IS '本轮投递认领 token，回写必须匹配。';
COMMENT ON COLUMN batch.notification_delivery_log.delivery_claim_until IS '认领租约截止时间，过期可恢复。';
ALTER TABLE batch.workflow_terminal_notification_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.workflow_terminal_notification_receipt FORCE ROW LEVEL SECURITY;
CREATE POLICY workflow_terminal_notification_tenant_isolation
    ON batch.workflow_terminal_notification_receipt
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

-- Only terminal-specific rows reference the receipt; generic historical delivery rows keep NULL.
CREATE INDEX idx_webhook_terminal_receipt
    ON batch.webhook_delivery_log (tenant_id, terminal_event_key) WHERE terminal_event_key IS NOT NULL;
CREATE INDEX idx_notification_terminal_receipt
    ON batch.notification_delivery_log (tenant_id, terminal_event_key) WHERE terminal_event_key IS NOT NULL;
ALTER TABLE batch.webhook_delivery_log
    ADD CONSTRAINT fk_webhook_terminal_receipt FOREIGN KEY (tenant_id, terminal_event_key)
    REFERENCES batch.workflow_terminal_notification_receipt (tenant_id, event_key) NOT VALID;
ALTER TABLE batch.notification_delivery_log
    ADD CONSTRAINT fk_notification_terminal_receipt FOREIGN KEY (tenant_id, terminal_event_key)
    REFERENCES batch.workflow_terminal_notification_receipt (tenant_id, event_key) NOT VALID;
ALTER TABLE batch.webhook_delivery_log VALIDATE CONSTRAINT fk_webhook_terminal_receipt;
ALTER TABLE batch.notification_delivery_log VALIDATE CONSTRAINT fk_notification_terminal_receipt;
