-- =========================================================
-- V80: trigger_outbox_event
--
-- ADR-010: Trigger → Orchestrator 异步解耦
-- 详见 docs/architecture/adr/ADR-010-trigger-async-decoupling.md
--
-- 用途:
--   batch-trigger 在 Quartz / wheel fire 后,与 trigger_request 同事务写入本表(PENDING),
--   TriggerOutboxRelay 周期扫描发布到 Kafka topic batch.trigger.launch.v1,
--   orchestrator 端 TriggerLaunchConsumer 消费后调用现有 launch 内部 API。
--
-- 设计参考:
--   - 模式复用 ADR-002 transactional-outbox(同事务写表 + 周期发布)
--   - 状态枚举对齐 batch_common.enums.OutboxPublishStatus(PENDING/PUBLISHING/PUBLISHED/FAILED/GIVE_UP)
--
-- 不变量:
--   - 与 trigger_request INSERT 必须同事务(否则 trigger_request 落库但事件丢失)
--   - (tenant_id, request_id) 唯一,防 trigger 端意外双写
--   - orchestrator 不直写本表(模块边界)
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.trigger_outbox_event (
    id              BIGSERIAL    PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    request_id      VARCHAR(128) NOT NULL,
    topic           VARCHAR(128) NOT NULL DEFAULT 'batch.trigger.launch.v1',
    payload         JSONB        NOT NULL,
    publish_status  VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    publish_attempt INTEGER      NOT NULL DEFAULT 0,
    last_error      VARCHAR(2048),
    trace_id        VARCHAR(128),
    next_publish_at TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_trigger_outbox_status CHECK (
        publish_status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED', 'GIVE_UP')
    )
);

-- 防 trigger 端意外双写(同 tenantId+requestId 多次落事件 → 重复消费)
CREATE UNIQUE INDEX IF NOT EXISTS uk_trigger_outbox_event_tenant_request
    ON batch.trigger_outbox_event (tenant_id, request_id);

-- relay 扫描热点:status + next_publish_at,partial index 只覆盖待发状态
CREATE INDEX IF NOT EXISTS idx_trigger_outbox_event_pending
    ON batch.trigger_outbox_event (publish_status, next_publish_at)
    WHERE publish_status IN ('PENDING', 'FAILED');

COMMENT ON TABLE  batch.trigger_outbox_event IS 'ADR-010: trigger 异步发布事件表,与 trigger_request 同事务写入';
COMMENT ON COLUMN batch.trigger_outbox_event.payload IS 'LaunchEnvelope JSON: {launchRequest, dedupKey, traceId, sourceFireTime}';
COMMENT ON COLUMN batch.trigger_outbox_event.publish_status IS 'PENDING/PUBLISHING/PUBLISHED/FAILED/GIVE_UP, 对齐 OutboxPublishStatus';
COMMENT ON COLUMN batch.trigger_outbox_event.next_publish_at IS '退避用,最早可被 relay 扫到的时刻';
