-- =====================================================================
-- V251：持久化日历依赖等待状态，使其跨越截止时间和进程重启后仍可恢复。
-- 仅做增量扩展：保留现有 calendar_dependency 和 batch_day_instance 标识。
-- 并保持原有状态语义不变。
-- =====================================================================

CREATE TABLE IF NOT EXISTS batch.calendar_dependency_wait (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    calendar_code       VARCHAR(128) NOT NULL,
    biz_date            DATE         NOT NULL,
    wait_status         VARCHAR(16)  NOT NULL DEFAULT 'WAITING',
    blocked_reason      VARCHAR(512) NOT NULL,
    first_waiting_at    TIMESTAMPTZ  NOT NULL,
    last_checked_at     TIMESTAMPTZ  NOT NULL,
    attempt_count       INTEGER      NOT NULL DEFAULT 1,
    cutoff_at           TIMESTAMPTZ,
    sla_deadline_at     TIMESTAMPTZ,
    timezone_snapshot   VARCHAR(64)  NOT NULL,
    dst_policy_snapshot VARCHAR(128) NOT NULL,
    opened_at           TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_calendar_dependency_wait UNIQUE (tenant_id, calendar_code, biz_date),
    CONSTRAINT ck_calendar_dependency_wait_status
        CHECK (wait_status IN ('WAITING', 'OPENED', 'SKIPPED')),
    CONSTRAINT ck_calendar_dependency_wait_attempts CHECK (attempt_count > 0)
);

CREATE INDEX IF NOT EXISTS idx_calendar_dependency_wait_due
    ON batch.calendar_dependency_wait (tenant_id, calendar_code, wait_status, last_checked_at, id);

ALTER TABLE batch.calendar_dependency_wait ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.calendar_dependency_wait FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS calendar_dependency_wait_tenant_isolation
    ON batch.calendar_dependency_wait;
CREATE POLICY calendar_dependency_wait_tenant_isolation
    ON batch.calendar_dependency_wait
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

COMMENT ON TABLE batch.calendar_dependency_wait IS
    '跨日历依赖等待事实；保留原业务日、等待原因和尝试记录，供开启调度器重启后重检并恢复';
COMMENT ON COLUMN batch.calendar_dependency_wait.wait_status IS
    'WAITING 等待依赖；OPENED 已原子创建批量日；SKIPPED 被活动灾难日 SKIP 覆盖';
COMMENT ON COLUMN batch.calendar_dependency_wait.biz_date IS
    '等待期间固定的有效业务日；重检不按当前时钟重新计算，避免跨 cutoff 丢日';
COMMENT ON COLUMN batch.calendar_dependency_wait.first_waiting_at IS
    '首次发现上游未就绪的时刻，用于等待时长和超时告警';
COMMENT ON COLUMN batch.calendar_dependency_wait.attempt_count IS
    '依赖重检次数；可用于等待时长/重试频率观测，不代表执行尝试';
COMMENT ON COLUMN batch.calendar_dependency_wait.timezone_snapshot IS
    '进入等待时使用的业务日历时区快照；恢复检查不随配置变化漂移';
COMMENT ON COLUMN batch.calendar_dependency_wait.dst_policy_snapshot IS
    '进入等待时使用的夏令时策略快照；恢复检查沿用原始时点解释规则';
