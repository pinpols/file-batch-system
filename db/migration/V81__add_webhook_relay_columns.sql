-- ADR §5.11 / project-assessment-2026-04-30 §6.5: Webhook durability —
-- WebhookDeliveryRelay 周期扫 EXHAUSTED + next_retry_at <= now() 行重投,达 ABSOLUTE_MAX 标 GIVE_UP。
-- delivery_status CHECK 加 GIVE_UP;为 relay 扫描加 (delivery_status, next_retry_at) 索引。

ALTER TABLE batch.webhook_delivery_log
    DROP CONSTRAINT IF EXISTS ck_webhook_delivery_status;

ALTER TABLE batch.webhook_delivery_log
    ADD CONSTRAINT ck_webhook_delivery_status CHECK (
        delivery_status IN ('PENDING', 'SUCCESS', 'FAILED', 'EXHAUSTED', 'GIVE_UP')
    );

CREATE INDEX IF NOT EXISTS idx_webhook_delivery_log_retry_eligible
    ON batch.webhook_delivery_log (delivery_status, next_retry_at)
    WHERE delivery_status = 'EXHAUSTED' AND next_retry_at IS NOT NULL;
