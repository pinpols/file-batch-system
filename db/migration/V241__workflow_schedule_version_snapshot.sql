-- Preserve Workflow schedule semantics in the existing definition-version history.
-- Older snapshots remain NULL because their historical schedule values were not captured.
ALTER TABLE batch.workflow_definition_version
    ADD COLUMN schedule_type VARCHAR(32),
    ADD COLUMN schedule_expr VARCHAR(256),
    ADD COLUMN timezone VARCHAR(64),
    ADD COLUMN trigger_mode VARCHAR(32),
    ADD COLUMN calendar_code VARCHAR(128),
    ADD COLUMN depends_on_job_code VARCHAR(128);

ALTER TABLE archive.workflow_definition_version_archive
    ADD COLUMN schedule_type VARCHAR(32),
    ADD COLUMN schedule_expr VARCHAR(256),
    ADD COLUMN timezone VARCHAR(64),
    ADD COLUMN trigger_mode VARCHAR(32),
    ADD COLUMN calendar_code VARCHAR(128),
    ADD COLUMN depends_on_job_code VARCHAR(128);

COMMENT ON COLUMN archive.workflow_definition_version_archive.schedule_type IS
    'Schedule type captured by full Workflow definition version; null for snapshots written before V241.';
COMMENT ON COLUMN archive.workflow_definition_version_archive.schedule_expr IS
    'Schedule expression captured by full Workflow definition version; null for snapshots written before V241.';
COMMENT ON COLUMN archive.workflow_definition_version_archive.timezone IS
    'Schedule timezone captured by full Workflow definition version; null for snapshots written before V241.';
COMMENT ON COLUMN archive.workflow_definition_version_archive.trigger_mode IS
    'Trigger mode captured by full Workflow definition version; null for snapshots written before V241.';
COMMENT ON COLUMN archive.workflow_definition_version_archive.calendar_code IS
    'Business calendar captured by full Workflow definition version; null for snapshots written before V241.';
COMMENT ON COLUMN archive.workflow_definition_version_archive.depends_on_job_code IS
    'Legacy Job dependency captured by full Workflow definition version; null for snapshots written before V241.';

COMMENT ON COLUMN batch.workflow_definition_version.schedule_type IS
    'Schedule type captured by full Workflow definition version; null for snapshots written before V241.';
COMMENT ON COLUMN batch.workflow_definition_version.schedule_expr IS
    'Schedule expression captured by full Workflow definition version; null for snapshots written before V241.';
COMMENT ON COLUMN batch.workflow_definition_version.timezone IS
    'Schedule timezone captured by full Workflow definition version; null for snapshots written before V241.';
COMMENT ON COLUMN batch.workflow_definition_version.trigger_mode IS
    'Trigger mode captured by full Workflow definition version; null for snapshots written before V241.';
COMMENT ON COLUMN batch.workflow_definition_version.calendar_code IS
    'Business calendar captured by full Workflow definition version; null for snapshots written before V241.';
COMMENT ON COLUMN batch.workflow_definition_version.depends_on_job_code IS
    'Legacy Job dependency captured by full Workflow definition version; null for snapshots written before V241.';
