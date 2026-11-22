-- =========================================================
-- V69: trigger_misfire_pending — MANUAL_APPROVAL catch-up 待审表
--
-- 当 trigger.catch_up_policy=MANUAL_APPROVAL 且发生 misfire 时,
-- 时间轮不自动补 fire,而是落 pending 行,等运维通过 console UI 审批。
-- 审批通过后:console 端点调 LaunchService 补 fire。
-- 审批拒绝/过期(默认 7 天):自动归档,不补 fire。
--
-- 关联文档: docs/architecture/quartz-replacement-design.md §9.4
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.trigger_misfire_pending (
    id                          BIGSERIAL    PRIMARY KEY,
    trigger_runtime_state_id    BIGINT       NOT NULL REFERENCES batch.trigger_runtime_state(id) ON DELETE CASCADE,
    tenant_id                   VARCHAR(64)  NOT NULL,
    job_code                    VARCHAR(128) NOT NULL,
    -- 错过的预定 fire 时刻
    scheduled_fire_time         TIMESTAMPTZ  NOT NULL,
    -- 实际发现 misfire 的时刻(用于计算延迟)
    detected_at                 TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status                      VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    approved_by                 VARCHAR(64),
    approved_at                 TIMESTAMPTZ,
    -- 审批拒绝时填,运维写明拒绝原因
    rejection_reason            VARCHAR(512),
    -- 审批通过后真正补 fire 的 trigger_request.id(便于追溯)
    catch_up_request_id         BIGINT,
    expires_at                  TIMESTAMPTZ  NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '7 days'),
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_trigger_misfire_pending UNIQUE (trigger_runtime_state_id, scheduled_fire_time),
    CONSTRAINT ck_trigger_misfire_pending_status CHECK (
        status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')
    )
);

CREATE INDEX IF NOT EXISTS idx_trigger_misfire_pending_status
    ON batch.trigger_misfire_pending (status, expires_at)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_trigger_misfire_pending_tenant_job
    ON batch.trigger_misfire_pending (tenant_id, job_code, detected_at DESC);

COMMENT ON TABLE batch.trigger_misfire_pending IS
    'MANUAL_APPROVAL 策略 misfire 落地表;运维 console 审批后补 fire';
COMMENT ON COLUMN batch.trigger_misfire_pending.status IS
    'PENDING=待审 / APPROVED=已批准并补 fire / REJECTED=运维拒绝 / EXPIRED=超 7d 自动归档';
COMMENT ON COLUMN batch.trigger_misfire_pending.expires_at IS
    '默认 7 天过期;过期后周期任务把 PENDING 改 EXPIRED';
