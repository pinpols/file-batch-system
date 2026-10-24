-- 全新部署：设备通知意图与现有 Job 终态回执在同一事务中提交。

CREATE TABLE batch.console_push_job_delivery (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    terminal_event_key VARCHAR(256) NOT NULL,
    job_instance_id BIGINT NOT NULL,
    operator_id VARCHAR(128),
    record_type VARCHAR(16) NOT NULL CHECK (record_type IN ('DECISION', 'DEVICE')),
    subscription_id BIGINT,
    subscription_fingerprint VARCHAR(64),
    decision VARCHAR(32) CHECK (decision IN ('DISABLED','DRY_RUN','NO_OPERATOR','NO_SUBSCRIPTIONS','QUEUED')),
    event_type VARCHAR(64) NOT NULL,
    payload_json JSONB NOT NULL,
    delivery_status VARCHAR(16) CHECK (delivery_status IN ('PENDING','IN_FLIGHT','ACCEPTED','GONE','EXHAUSTED')),
    attempt INTEGER NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    next_retry_at TIMESTAMPTZ,
    delivery_claim_token UUID,
    delivery_claim_until TIMESTAMPTZ,
    http_status INTEGER,
    error_message VARCHAR(1024),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_job_push_recipient UNIQUE NULLS NOT DISTINCT (tenant_id,terminal_event_key,subscription_id),
    CONSTRAINT fk_job_push_receipt FOREIGN KEY (tenant_id,terminal_event_key)
        REFERENCES batch.workflow_terminal_notification_receipt (tenant_id,event_key),
    CONSTRAINT ck_job_push_record CHECK (
        (record_type='DECISION' AND subscription_id IS NULL AND subscription_fingerprint IS NULL
            AND decision IS NOT NULL AND delivery_status IS NULL)
        OR (record_type='DEVICE' AND subscription_id IS NOT NULL AND subscription_id > 0
            AND subscription_fingerprint IS NOT NULL AND length(subscription_fingerprint)=64
            AND operator_id IS NOT NULL AND length(operator_id)>0
            AND decision IS NULL AND delivery_status IS NOT NULL)),
    CONSTRAINT ck_job_push_claim CHECK ((delivery_claim_token IS NULL) = (delivery_claim_until IS NULL))
);
COMMENT ON TABLE batch.console_push_job_delivery IS 'Job 终态 Web Push 首次接纳决定及冻结设备投递；provider 接纳不代表浏览器已读。';
COMMENT ON COLUMN batch.console_push_job_delivery.id IS '决定或设备意图的持久身份。';
COMMENT ON COLUMN batch.console_push_job_delivery.tenant_id IS 'Job 事实与收件设备所属租户。';
COMMENT ON COLUMN batch.console_push_job_delivery.terminal_event_key IS '原 Job 终态事实键，与消费回执同租户关联。';
COMMENT ON COLUMN batch.console_push_job_delivery.job_instance_id IS '原 Job 实例身份，不代替 Workflow 根身份。';
COMMENT ON COLUMN batch.console_push_job_delivery.operator_id IS '事实中冻结的提交人；不从当前配置重新推算。';
COMMENT ON COLUMN batch.console_push_job_delivery.record_type IS '不可变首次决定或逐设备待投递意图。';
COMMENT ON COLUMN batch.console_push_job_delivery.subscription_id IS '首次选中的设备订阅；不设外键以允许用户撤销订阅且保留投递证据。';
COMMENT ON COLUMN batch.console_push_job_delivery.subscription_fingerprint IS '原 tenant/user/device/endpoint/keys 的 SHA-256 摘要，不复制设备凭据。';
COMMENT ON COLUMN batch.console_push_job_delivery.decision IS '首次接纳时的不可变开关/无目标/已排队决定；不是当前送达状态。';
COMMENT ON COLUMN batch.console_push_job_delivery.delivery_status IS '设备投递状态；ACCEPTED 只表示 provider 已接纳。';
COMMENT ON COLUMN batch.console_push_job_delivery.event_type IS 'Job 原终态的推送展示分类，不是新业务事件总线编码。';
COMMENT ON COLUMN batch.console_push_job_delivery.payload_json IS '首次接纳冻结的通知载荷，不含 endpoint 或设备密钥。';
COMMENT ON COLUMN batch.console_push_job_delivery.attempt IS '设备投递尝试次数。';
COMMENT ON COLUMN batch.console_push_job_delivery.next_retry_at IS '下次允许尝试时间，空值表示不自动重投。';
COMMENT ON COLUMN batch.console_push_job_delivery.delivery_claim_token IS '认领身份，旧执行者回写必须被围栏拒绝。';
COMMENT ON COLUMN batch.console_push_job_delivery.delivery_claim_until IS '在途认领期限，过期不代表远端未接纳。';
COMMENT ON COLUMN batch.console_push_job_delivery.http_status IS '已观察到的 provider HTTP 状态；未观察到则为空。';
COMMENT ON COLUMN batch.console_push_job_delivery.error_message IS '有界脱敏的失败/未知结果原因。';
COMMENT ON COLUMN batch.console_push_job_delivery.created_at IS '首次接纳或创建设备意图的时间。';
CREATE INDEX idx_job_push_pending ON batch.console_push_job_delivery (tenant_id,next_retry_at,id)
    WHERE record_type='DEVICE' AND delivery_status='PENDING';
CREATE INDEX idx_job_push_in_flight ON batch.console_push_job_delivery (tenant_id,delivery_claim_until,id)
    WHERE delivery_status='IN_FLIGHT';
ALTER TABLE batch.console_push_job_delivery ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.console_push_job_delivery FORCE ROW LEVEL SECURITY;
CREATE POLICY console_push_job_delivery_tenant_isolation ON batch.console_push_job_delivery
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

-- 此迁移尚未发布；部署从空数据库开始。
-- 新模型中，回看扫描器及其发送前去重表已没有运行时使用方。
-- 仅用于全新库的退役守卫：检查前先加锁，防止并发写入绕过检查。

LOCK TABLE batch.console_push_job_notification IN ACCESS EXCLUSIVE MODE;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM batch.console_push_job_notification LIMIT 1) THEN
        RAISE EXCEPTION 'Job Push cutover requires an empty legacy ledger and a fresh database';
    END IF;
END;
$$;
-- 只豁免这张已退役、加锁且确认为空的账本：全新模型不再发布旧客户端。
-- 该逻辑通过两种夹具精确测试：有数据时拒绝且保留数据，无数据时允许通过。
-- squawk-ignore ban-drop-table
DROP TABLE batch.console_push_job_notification;
