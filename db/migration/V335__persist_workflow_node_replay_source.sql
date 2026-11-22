-- 保存失败 JOB 节点重放所需的精确来源 Workflow 身份。

ALTER TABLE batch.compensation_command
    ADD COLUMN source_workflow_run_id BIGINT;

ALTER TABLE archive.compensation_command_archive
    ADD COLUMN source_workflow_run_id BIGINT;

ALTER TABLE batch.compensation_command
    DROP CONSTRAINT ck_compensation_command_type;

ALTER TABLE batch.compensation_command
    ADD CONSTRAINT ck_compensation_command_type
        CHECK (compensation_type IN ('JOB', 'WORKFLOW_NODE', 'STEP', 'PARTITION', 'FILE', 'BATCH', 'DLQ'))
        NOT VALID;

ALTER TABLE batch.compensation_command
    VALIDATE CONSTRAINT ck_compensation_command_type;

ALTER TABLE archive.compensation_command_archive
    DROP CONSTRAINT ck_compensation_command_type;

ALTER TABLE archive.compensation_command_archive
    ADD CONSTRAINT ck_compensation_command_type
        CHECK (compensation_type IN ('JOB', 'WORKFLOW_NODE', 'STEP', 'PARTITION', 'FILE', 'BATCH', 'DLQ'))
        NOT VALID;

ALTER TABLE archive.compensation_command_archive
    VALIDATE CONSTRAINT ck_compensation_command_type;

COMMENT ON COLUMN batch.compensation_command.source_workflow_run_id IS
    '精确重放失败 JOB 节点时的来源 WorkflowRun；归档后仍保留其历史身份。';

COMMENT ON COLUMN archive.compensation_command_archive.source_workflow_run_id IS
    '精确重放失败 JOB 节点所用来源 WorkflowRun 的归档身份。';
