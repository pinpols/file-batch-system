-- Preserve pause/resume state in each immutable Workflow definition snapshot.
ALTER TABLE batch.workflow_definition_version
    ADD COLUMN schedule_enabled BOOLEAN;

ALTER TABLE archive.workflow_definition_version_archive
    ADD COLUMN schedule_enabled BOOLEAN;

COMMENT ON COLUMN batch.workflow_definition_version.schedule_enabled IS
    'Workflow trigger enablement captured in this version; null for snapshots written before V249.';

COMMENT ON COLUMN archive.workflow_definition_version_archive.schedule_enabled IS
    'Workflow trigger enablement captured in this version; null for snapshots written before V249.';
