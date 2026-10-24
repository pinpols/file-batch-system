-- Move scheduling ownership toward Workflow definitions while retaining the Job fields
-- during the compatibility rollout. This is additive; Trigger still reads Job until the
-- Workflow editor and launch target have both been switched.
ALTER TABLE batch.workflow_definition
    ADD COLUMN schedule_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN schedule_expr VARCHAR(256),
    ADD COLUMN timezone VARCHAR(64),
    ADD COLUMN trigger_mode VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN calendar_code VARCHAR(128),
    ADD COLUMN depends_on_job_code VARCHAR(128);

ALTER TABLE batch.workflow_definition
    ADD CONSTRAINT ck_workflow_definition_schedule_type
        CHECK (schedule_type IN ('CRON', 'FIXED_RATE', 'MANUAL', 'EVENT', 'ONE_TIME')) NOT VALID,
    ADD CONSTRAINT ck_workflow_definition_trigger_mode
        CHECK (trigger_mode IN ('SCHEDULED', 'API', 'MANUAL', 'EVENT', 'MIXED')) NOT VALID,
    ADD CONSTRAINT ck_workflow_definition_cron_expr_required
        CHECK (
            schedule_type NOT IN ('CRON', 'FIXED_RATE')
                OR NULLIF(BTRIM(schedule_expr), '') IS NOT NULL
        ) NOT VALID;

ALTER TABLE batch.workflow_definition
    VALIDATE CONSTRAINT ck_workflow_definition_schedule_type,
    VALIDATE CONSTRAINT ck_workflow_definition_trigger_mode,
    VALIDATE CONSTRAINT ck_workflow_definition_cron_expr_required;

-- Existing Job schedules remain the active source until the Console and Trigger cutover.
-- Copy them into each explicit single-Job Workflow now so the next release can switch one
-- source atomically without inferring associations from matching codes.
UPDATE batch.workflow_definition wd
SET schedule_type = jd.schedule_type,
    schedule_expr = jd.schedule_expr,
    timezone = jd.timezone,
    trigger_mode = jd.trigger_mode,
    calendar_code = jd.calendar_code,
    depends_on_job_code = jd.depends_on_job_code,
    updated_at = CURRENT_TIMESTAMP
FROM batch.job_definition jd
WHERE jd.tenant_id = wd.tenant_id
  AND jd.id = wd.single_job_definition_id;

CREATE INDEX idx_workflow_definition_scheduled
    ON batch.workflow_definition (tenant_id, schedule_type, trigger_mode, workflow_code)
    WHERE enabled = TRUE AND schedule_type IN ('CRON', 'FIXED_RATE');

COMMENT ON COLUMN batch.workflow_definition.schedule_type IS
    'Workflow-level schedule type; the Workflow owns top-level trigger scheduling.';
COMMENT ON COLUMN batch.workflow_definition.schedule_expr IS
    'Workflow-level schedule expression used by the Workflow trigger scheduler.';
COMMENT ON COLUMN batch.workflow_definition.timezone IS
    'IANA timezone for Workflow trigger fire and business-date resolution.';
COMMENT ON COLUMN batch.workflow_definition.trigger_mode IS
    'Workflow trigger entry mode (SCHEDULED/API/MANUAL/EVENT/MIXED).';
COMMENT ON COLUMN batch.workflow_definition.calendar_code IS
    'Tenant business calendar used to resolve Workflow fire dates.';
COMMENT ON COLUMN batch.workflow_definition.depends_on_job_code IS
    'Legacy readiness dependency retained during migration; target contract will be a Workflow dependency.';
