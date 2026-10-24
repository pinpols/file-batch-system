-- 允许运维人员暂停单个日历的自动积压补扫，而不影响每日开日流程。

CREATE TABLE batch.batch_day_catchup_control (
    tenant_id       VARCHAR(64)  NOT NULL,
    calendar_code   VARCHAR(128) NOT NULL,
    paused          BOOLEAN      NOT NULL DEFAULT FALSE,
    paused_at       TIMESTAMPTZ,
    paused_by       VARCHAR(128),
    pause_reason    VARCHAR(1024),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_batch_day_catchup_control PRIMARY KEY (tenant_id, calendar_code),
    CONSTRAINT ck_batch_day_catchup_control_state CHECK (
        (paused AND paused_at IS NOT NULL AND paused_by IS NOT NULL AND pause_reason IS NOT NULL)
        OR (NOT paused)
    )
);

ALTER TABLE batch.batch_day_catchup_control ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.batch_day_catchup_control FORCE ROW LEVEL SECURITY;
CREATE POLICY batch_day_catchup_control_tenant_isolation
    ON batch.batch_day_catchup_control
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

CREATE TABLE batch.batch_day_catchup_control_event (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      VARCHAR(64)  NOT NULL,
    calendar_code  VARCHAR(128) NOT NULL,
    action         VARCHAR(16)  NOT NULL,
    operator_id    VARCHAR(128) NOT NULL,
    reason         VARCHAR(1024) NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_batch_day_catchup_control_event_action CHECK (action IN ('PAUSE', 'RESUME'))
);

CREATE INDEX idx_batch_day_catchup_control_event_recent
    ON batch.batch_day_catchup_control_event (tenant_id, calendar_code, occurred_at DESC, id DESC);

ALTER TABLE batch.batch_day_catchup_control_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch.batch_day_catchup_control_event FORCE ROW LEVEL SECURITY;
CREATE POLICY batch_day_catchup_control_event_tenant_isolation
    ON batch.batch_day_catchup_control_event
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

COMMENT ON TABLE batch.batch_day_catchup_control IS
    '每租户/日历的停机补齐暂停状态；只暂停历史 catch-up，不影响当前业务日正常开启';
COMMENT ON TABLE batch.batch_day_catchup_control_event IS
    '日切补齐暂停/恢复的操作者和原因审计历史';
