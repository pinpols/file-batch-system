-- Fresh deployments use Job nodes and no longer model FILE_STEP-to-Pipeline links.
ALTER TABLE batch.workflow_node
    -- squawk-ignore ban-drop-column
    DROP COLUMN related_pipeline_code;

-- Replace the node allowlist after retiring FILE_STEP; revalidated before the migration commits.
ALTER TABLE batch.workflow_node
    -- squawk-ignore ban-drop-constraint
    DROP CONSTRAINT ck_workflow_node_type;
ALTER TABLE batch.workflow_node
    ADD CONSTRAINT ck_workflow_node_type
        CHECK (node_type IN ('TASK', 'GATEWAY', 'START', 'END', 'WAIT', 'JOB')) NOT VALID;

ALTER TABLE batch.workflow_node
    VALIDATE CONSTRAINT ck_workflow_node_type;

-- Keep execution history on the same retired node-type contract as definitions.
ALTER TABLE batch.workflow_node_run
    -- squawk-ignore ban-drop-constraint
    DROP CONSTRAINT ck_workflow_node_run_type;
ALTER TABLE batch.workflow_node_run
    ADD CONSTRAINT ck_workflow_node_run_type
        CHECK (node_type IN ('TASK', 'GATEWAY', 'START', 'END', 'WAIT', 'JOB')) NOT VALID;

ALTER TABLE batch.workflow_node_run
    VALIDATE CONSTRAINT ck_workflow_node_run_type;
