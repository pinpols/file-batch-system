-- =========================================================
-- V348：Kafka 消费有限重试耗尽后的持久恢复锚点。
--
-- 背景：TriggerLaunchConsumer 的瞬态失败在 `retryAttempts` 次后由 recoverer 跳过（提交 offset），
-- 若该请求尚未写出任何持久等待/prepare 记录（例如事务回滚），消息是唯一载体，被跳过后
-- 永久失去执行机会。本表保存原始 envelope 密文，由周期扫描幂等重投。
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.trigger_launch_recovery (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    envelope_ciphertext TEXT NOT NULL,
    trace_id VARCHAR(128),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    recovery_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    last_error_code VARCHAR(128),
    last_error_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_trigger_launch_recovery_request UNIQUE (tenant_id, request_id),
    CONSTRAINT ck_trigger_launch_recovery_status
        CHECK (recovery_status IN ('PENDING', 'DONE', 'REJECTED', 'DEAD'))
);

CREATE INDEX IF NOT EXISTS idx_trigger_launch_recovery_due
    ON batch.trigger_launch_recovery (recovery_status, next_attempt_at);

COMMENT ON TABLE batch.trigger_launch_recovery IS
    'Kafka 消费有限重试耗尽后的启动意图；由周期扫描幂等重投，重试有上限并保留最后错误码。';

COMMENT ON COLUMN batch.trigger_launch_recovery.envelope_ciphertext IS
    '原始 launch envelope 的密文（SecretPayloadProtector），避免敏感参数明文落库。';

COMMENT ON COLUMN batch.trigger_launch_recovery.recovery_status IS
    'PENDING=待重投 / DONE=已重投成功 / REJECTED=业务级永久拒绝 / DEAD=超过重试上限。';

COMMENT ON COLUMN batch.trigger_launch_recovery.trace_id IS
    '触发链路的 traceId，便于按链路排查恢复过程。';

COMMENT ON COLUMN batch.trigger_launch_recovery.attempts IS
    '已重投次数；达到上限后置为 DEAD 并停止重投。';

COMMENT ON COLUMN batch.trigger_launch_recovery.next_attempt_at IS
    '下一次重投的可用时间（退避后）；扫描只处理已到期的 PENDING 记录。';

COMMENT ON COLUMN batch.trigger_launch_recovery.last_error_code IS
    '最近一次重投失败的错误码（HTTP 状态或异常类名），用于运维定位。';
