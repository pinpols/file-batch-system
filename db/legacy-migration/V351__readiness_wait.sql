-- =========================================================
-- V351：Orchestrator 接管的 readiness 持久等待。
--
-- 背景：readiness 等待此前只存在于 Trigger 的 scheduled_occurrence / Quartz JobDataMap。
-- 本表让 Orchestrator 保存原依赖、版本、业务日与截止时间，由周期扫尾器在无 Quartz 回调时
-- 判定就绪并驱动恢复启动；超窗时终结等待并解除批日结算阻塞（ADR-048 ALIGN-5 Phase 2）。
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.readiness_wait (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    workflow_definition_id BIGINT NOT NULL,
    workflow_code VARCHAR(128) NOT NULL,
    calendar_code VARCHAR(128),
    schedule_version INTEGER NOT NULL,
    -- 原依赖集合，逗号分隔；与原 fire 身份一起构成一次等待。
    depends_on_job_codes TEXT NOT NULL,
    biz_date DATE NOT NULL,
    scheduled_fire_time TIMESTAMPTZ NOT NULL,
    deferred_since TIMESTAMPTZ NOT NULL,
    deadline TIMESTAMPTZ NOT NULL,
    wait_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    request_id VARCHAR(128),
    trace_id VARCHAR(128),
    last_error_code VARCHAR(128),
    released_at TIMESTAMPTZ,
    timed_out_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_readiness_wait_identity
        UNIQUE (tenant_id, workflow_definition_id, scheduled_fire_time),
    CONSTRAINT ck_readiness_wait_status
        CHECK (wait_status IN ('PENDING', 'RELEASED', 'TIMED_OUT', 'CANCELLED'))
);

CREATE INDEX IF NOT EXISTS idx_readiness_wait_due
    ON batch.readiness_wait (wait_status, deadline);

CREATE INDEX IF NOT EXISTS idx_readiness_wait_settlement
    ON batch.readiness_wait (tenant_id, calendar_code, biz_date, wait_status);

COMMENT ON TABLE batch.readiness_wait IS
    'Orchestrator 接管的 readiness 持久等待；超窗终结后不再阻塞批日结算。';

COMMENT ON COLUMN batch.readiness_wait.depends_on_job_codes IS
    '等待的上游 job code 集合（原依赖，逗号分隔）；全部依赖同批次日 EFFECTIVE 才释放。';

COMMENT ON COLUMN batch.readiness_wait.deadline IS
    '等待截止时间；扫尾器在无 Quartz 回调时据此终结等待。';

COMMENT ON COLUMN batch.readiness_wait.wait_status IS
    'PENDING=等待中 / RELEASED=已就绪并驱动恢复 / TIMED_OUT=超窗终结 / CANCELLED=已被正常 fire 取消。';

COMMENT ON COLUMN batch.readiness_wait.trace_id IS
    '触发链路的 traceId，便于按链路排查移交与恢复过程。';

COMMENT ON COLUMN batch.readiness_wait.request_id IS
    '释放后由 Trigger 重建的 launch requestId；PENDING 时为空。';

COMMENT ON COLUMN batch.readiness_wait.last_error_code IS
    '最近一次推进失败或终结的原因码（如 readiness-window-exceeded / fired-by-trigger）。';

COMMENT ON COLUMN batch.readiness_wait.released_at IS
    '就绪并成功驱动恢复启动的时间。';

COMMENT ON COLUMN batch.readiness_wait.timed_out_at IS
    '超窗终结的时间；从此批日结算不再被该等待阻塞。';

COMMENT ON COLUMN batch.readiness_wait.tenant_id IS
    '租户；等待与恢复均在该租户上下文内推进。';

COMMENT ON COLUMN batch.readiness_wait.workflow_definition_id IS
    '被延迟的 workflow 定义 ID（原 fire 身份之一，与 scheduled_fire_time 共同唯一）。';

COMMENT ON COLUMN batch.readiness_wait.workflow_code IS
    '被延迟的 workflow code，用于审计与恢复调用。';

COMMENT ON COLUMN batch.readiness_wait.calendar_code IS
    '业务日历 code，用于与批日实例/结算口径对齐。';

COMMENT ON COLUMN batch.readiness_wait.schedule_version IS
    '发起等待时的调度版本；恢复时用于识别定义是否已变更。';

COMMENT ON COLUMN batch.readiness_wait.biz_date IS
    '对齐后的业务日；就绪判定与结算均按该业务日。';

COMMENT ON COLUMN batch.readiness_wait.scheduled_fire_time IS
    '原始计划 fire 时间；恢复启动必须保持该身份，不重新计算。';

COMMENT ON COLUMN batch.readiness_wait.deferred_since IS
    '等待起算时间（首次 defer 的实际回调时间）；与 deadline 共同表达等待窗口。';
