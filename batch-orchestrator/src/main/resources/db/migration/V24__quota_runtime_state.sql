CREATE TABLE IF NOT EXISTS batch.quota_runtime_state (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    quota_scope         VARCHAR(64)  NOT NULL,
    owner_code          VARCHAR(128) NOT NULL,
    quota_reset_policy  VARCHAR(32)  NOT NULL DEFAULT 'NONE',
    window_started_at   TIMESTAMPTZ,
    window_expires_at   TIMESTAMPTZ,
    peak_borrowed_count INTEGER      NOT NULL DEFAULT 0,
    last_reset_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_quota_runtime_state UNIQUE (tenant_id, quota_scope, owner_code),
    CONSTRAINT ck_quota_runtime_state_policy CHECK (quota_reset_policy IN ('NONE', 'CALENDAR_DAY', 'SLIDING_WINDOW')),
    CONSTRAINT ck_quota_runtime_state_peak CHECK (peak_borrowed_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_quota_runtime_state_expire
    ON batch.quota_runtime_state (window_expires_at);
