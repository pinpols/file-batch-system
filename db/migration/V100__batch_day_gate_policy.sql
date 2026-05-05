-- =========================================================
-- V100: batch day previous-day gate policies
-- =========================================================

ALTER TABLE batch.business_calendar
    ADD COLUMN IF NOT EXISTS day_rollover_policy VARCHAR(32) NOT NULL DEFAULT 'ALLOW_OVERLAP';

ALTER TABLE batch.business_calendar DROP CONSTRAINT IF EXISTS ck_business_calendar_day_rollover_policy;
ALTER TABLE batch.business_calendar
    ADD CONSTRAINT ck_business_calendar_day_rollover_policy
    CHECK (day_rollover_policy IN ('ALLOW_OVERLAP', 'WAIT_PREVIOUS_DAY', 'REJECT_IF_PREVIOUS_OPEN'));

COMMENT ON COLUMN batch.business_calendar.day_rollover_policy IS
    'Calendar-level previous batch day policy: ALLOW_OVERLAP / WAIT_PREVIOUS_DAY / REJECT_IF_PREVIOUS_OPEN.';

ALTER TABLE batch.job_definition
    ADD COLUMN IF NOT EXISTS previous_day_dependency_scope VARCHAR(32) NOT NULL DEFAULT 'INHERIT';

ALTER TABLE batch.job_definition DROP CONSTRAINT IF EXISTS ck_job_definition_previous_day_dependency_scope;
ALTER TABLE batch.job_definition
    ADD CONSTRAINT ck_job_definition_previous_day_dependency_scope
    CHECK (previous_day_dependency_scope IN ('INHERIT', 'NONE', 'SAME_JOB', 'SAME_JOB_GROUP', 'SAME_CALENDAR', 'CUSTOM_CHAIN'));

COMMENT ON COLUMN batch.job_definition.previous_day_dependency_scope IS
    'Job-level previous batch day dependency scope. INHERIT uses business_calendar.day_rollover_policy.';

ALTER TABLE batch.trigger_request DROP CONSTRAINT IF EXISTS ck_trigger_request_status;
ALTER TABLE batch.trigger_request
    ADD CONSTRAINT ck_trigger_request_status
    CHECK (request_status IN ('PENDING', 'PROCESSING', 'ACCEPTED', 'WAITING', 'DUPLICATE', 'REJECTED', 'LAUNCHED', 'FORWARD_FAILED', 'GIVE_UP'));

CREATE TABLE IF NOT EXISTS batch.batch_day_waiting_launch (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    calendar_code VARCHAR(128) NOT NULL,
    job_code VARCHAR(128) NOT NULL,
    biz_date DATE NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    trace_id VARCHAR(128),
    trigger_type VARCHAR(32) NOT NULL,
    wait_reason VARCHAR(128) NOT NULL,
    launch_payload JSONB NOT NULL,
    wait_status VARCHAR(32) NOT NULL DEFAULT 'WAITING',
    released_at TIMESTAMPTZ,
    released_by VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_batch_day_waiting_launch_request UNIQUE (tenant_id, request_id),
    CONSTRAINT ck_batch_day_waiting_launch_status CHECK (wait_status IN ('WAITING', 'RELEASED', 'REJECTED', 'CANCELLED'))
);

CREATE INDEX IF NOT EXISTS idx_batch_day_waiting_launch_status
    ON batch.batch_day_waiting_launch (wait_status, tenant_id, calendar_code, biz_date);

COMMENT ON TABLE batch.batch_day_waiting_launch IS
    'Launch intents blocked by previous batch day gates. They are not job_instance rows until released.';
