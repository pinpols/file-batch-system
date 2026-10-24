-- Preserve the exact source Workflow identity for failed JOB node replay.
ALTER TABLE batch.compensation_command
    ADD COLUMN source_workflow_run_id BIGINT;

ALTER TABLE archive.compensation_command_archive
    ADD COLUMN source_workflow_run_id BIGINT;

ALTER TABLE batch.compensation_command
    DROP CONSTRAINT ck_compensation_command_type;

ALTER TABLE batch.compensation_command
    ADD CONSTRAINT ck_compensation_command_type
        CHECK (compensation_type IN ('JOB', 'WORKFLOW_NODE', 'STEP', 'PARTITION', 'FILE', 'BATCH', 'DLQ'));

ALTER TABLE archive.compensation_command_archive
    DROP CONSTRAINT ck_compensation_command_type;

ALTER TABLE archive.compensation_command_archive
    ADD CONSTRAINT ck_compensation_command_type
        CHECK (compensation_type IN ('JOB', 'WORKFLOW_NODE', 'STEP', 'PARTITION', 'FILE', 'BATCH', 'DLQ'));

COMMENT ON COLUMN batch.compensation_command.source_workflow_run_id IS
    'Source WorkflowRun for a precise failed JOB node replay; historical identity is retained after archival.';

COMMENT ON COLUMN archive.compensation_command_archive.source_workflow_run_id IS
    'Archived source WorkflowRun identity for a precise failed JOB node replay.';
