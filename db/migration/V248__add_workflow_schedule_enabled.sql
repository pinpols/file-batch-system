-- Schedule pause/resume is independent from both Job execution eligibility and
-- Workflow definition availability. Keep existing scheduled definitions active.
ALTER TABLE batch.workflow_definition
    ADD COLUMN schedule_enabled BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN batch.workflow_definition.schedule_enabled IS
    'Workflow trigger enablement; independent from workflow_definition.enabled and job_definition.enabled.';
