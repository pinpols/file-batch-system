-- 已闭合终态通知族沿租户既有 console_operation_audit 归档策略迁移。
-- 不新增保留期限，不删除冷存；未决、耗尽待人工处理或仍认领中的族继续保留热表。

CREATE INDEX idx_terminal_notification_receipt_archive_candidate
    ON batch.workflow_terminal_notification_receipt (tenant_id, created_at, event_key);
COMMENT ON INDEX batch.idx_terminal_notification_receipt_archive_candidate IS
    '按租户与时间顺序有界选择待归档通知族，避免逐批对租户全部回执重新排序。';

CREATE TABLE archive.workflow_terminal_notification_receipt_archive (
    LIKE batch.workflow_terminal_notification_receipt INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING CONSTRAINTS INCLUDING COMMENTS,
    PRIMARY KEY (tenant_id, event_key)
);
COMMENT ON TABLE archive.workflow_terminal_notification_receipt_archive IS 'Workflow 终态消费回执冷存；永久保留已消费身份，迟到事件不能重新展开投递。';
ALTER TABLE archive.workflow_terminal_notification_receipt_archive ENABLE ROW LEVEL SECURITY;
ALTER TABLE archive.workflow_terminal_notification_receipt_archive FORCE ROW LEVEL SECURITY;
CREATE POLICY workflow_terminal_notification_receipt_cold_tenant_isolation
    ON archive.workflow_terminal_notification_receipt_archive
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

CREATE TABLE archive.webhook_delivery_log_archive (
    LIKE batch.webhook_delivery_log INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING CONSTRAINTS INCLUDING COMMENTS,
    PRIMARY KEY (id),
    CONSTRAINT fk_webhook_delivery_log_cold_receipt
        FOREIGN KEY (tenant_id, terminal_event_key)
        REFERENCES archive.workflow_terminal_notification_receipt_archive (tenant_id, event_key)
);
COMMENT ON TABLE archive.webhook_delivery_log_archive IS 'Workflow 终态 Webhook 投递冷存；仅收纳已闭合通知族。';
-- 冷存只接受已有记录的原 ID，不能从热表序列创建新业务身份。
ALTER TABLE archive.webhook_delivery_log_archive ALTER COLUMN id DROP DEFAULT;
CREATE INDEX idx_webhook_delivery_log_cold_tenant_time
    ON archive.webhook_delivery_log_archive (tenant_id, created_at DESC);
CREATE INDEX idx_webhook_delivery_log_cold_receipt
    ON archive.webhook_delivery_log_archive (tenant_id, terminal_event_key);
ALTER TABLE archive.webhook_delivery_log_archive ENABLE ROW LEVEL SECURITY;
ALTER TABLE archive.webhook_delivery_log_archive FORCE ROW LEVEL SECURITY;
CREATE POLICY webhook_delivery_log_cold_tenant_isolation
    ON archive.webhook_delivery_log_archive
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

CREATE TABLE archive.notification_delivery_log_archive (
    LIKE batch.notification_delivery_log INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING CONSTRAINTS INCLUDING COMMENTS,
    PRIMARY KEY (id),
    CONSTRAINT fk_notification_delivery_log_cold_receipt
        FOREIGN KEY (tenant_id, terminal_event_key)
        REFERENCES archive.workflow_terminal_notification_receipt_archive (tenant_id, event_key)
);
COMMENT ON TABLE archive.notification_delivery_log_archive IS 'Workflow 终态订阅规则投递冷存；保留原结果与人工恢复身份。';
-- 冷存只接受已有记录的原 ID，不能从热表序列创建新业务身份。
ALTER TABLE archive.notification_delivery_log_archive ALTER COLUMN id DROP DEFAULT;
CREATE INDEX idx_notification_delivery_log_cold_tenant_time
    ON archive.notification_delivery_log_archive (tenant_id, created_at DESC);
CREATE INDEX idx_notification_delivery_log_cold_receipt
    ON archive.notification_delivery_log_archive (tenant_id, terminal_event_key);
ALTER TABLE archive.notification_delivery_log_archive ENABLE ROW LEVEL SECURITY;
ALTER TABLE archive.notification_delivery_log_archive FORCE ROW LEVEL SECURITY;
CREATE POLICY notification_delivery_log_cold_tenant_isolation
    ON archive.notification_delivery_log_archive
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

CREATE TABLE archive.console_push_job_delivery_archive (
    LIKE batch.console_push_job_delivery INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING CONSTRAINTS INCLUDING COMMENTS,
    PRIMARY KEY (id),
    CONSTRAINT fk_console_push_job_delivery_cold_receipt
        FOREIGN KEY (tenant_id, terminal_event_key)
        REFERENCES archive.workflow_terminal_notification_receipt_archive (tenant_id, event_key)
);
COMMENT ON TABLE archive.console_push_job_delivery_archive IS 'Job 终态 Web Push 决定和设备投递冷存；与原通知回执一并迁移。';
-- 冷存只接受已有记录的原 ID，不能从热表序列创建新业务身份。
ALTER TABLE archive.console_push_job_delivery_archive ALTER COLUMN id DROP DEFAULT;
CREATE INDEX idx_console_push_job_delivery_cold_tenant_time
    ON archive.console_push_job_delivery_archive (tenant_id, created_at DESC);
CREATE INDEX idx_console_push_job_delivery_cold_receipt
    ON archive.console_push_job_delivery_archive (tenant_id, terminal_event_key);
ALTER TABLE archive.console_push_job_delivery_archive ENABLE ROW LEVEL SECURITY;
ALTER TABLE archive.console_push_job_delivery_archive FORCE ROW LEVEL SECURITY;
CREATE POLICY console_push_job_delivery_cold_tenant_isolation
    ON archive.console_push_job_delivery_archive
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

CREATE TABLE archive.console_operation_audit_archive (
    LIKE batch.console_operation_audit INCLUDING DEFAULTS INCLUDING GENERATED INCLUDING CONSTRAINTS INCLUDING COMMENTS,
    PRIMARY KEY (id)
);
COMMENT ON TABLE archive.console_operation_audit_archive IS '已闭合终态通知人工操作审计冷存；保留原请求幂等身份。';
-- 冷存只接受已有记录的原 ID，不能从热表序列创建新业务身份。
ALTER TABLE archive.console_operation_audit_archive ALTER COLUMN id DROP DEFAULT;
CREATE INDEX idx_console_operation_audit_cold_tenant_time
    ON archive.console_operation_audit_archive (tenant_id, created_at DESC);
ALTER TABLE archive.console_operation_audit_archive ENABLE ROW LEVEL SECURITY;
ALTER TABLE archive.console_operation_audit_archive FORCE ROW LEVEL SECURITY;
CREATE POLICY console_operation_audit_cold_tenant_isolation
    ON archive.console_operation_audit_archive
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

CREATE UNIQUE INDEX uq_terminal_notification_retry_cold_request
    ON archive.console_operation_audit_archive (tenant_id, aggregate_id, request_id)
    WHERE aggregate_type = 'NOTIFICATION_DELIVERY' AND action = 'NOTIFICATION_DELIVERY_RETRY';
COMMENT ON INDEX archive.uq_terminal_notification_retry_cold_request IS
    '终态投递人工重试的原请求身份；归档后仍禁止同一请求重新开启发送预算。';
