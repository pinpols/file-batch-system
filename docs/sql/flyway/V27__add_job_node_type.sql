-- =========================================================
-- V27 - Add JOB node type to workflow_node and workflow_node_run
--
-- JOB nodes represent inter-job dependencies within a workflow DAG.
-- When executed, the orchestrator launches the referenced job as a
-- child instance and waits for its completion before advancing.
-- The referenced job code is stored in workflow_node.related_job_code.
-- =========================================================

-- workflow_node definition table
ALTER TABLE batch.workflow_node
    DROP CONSTRAINT IF EXISTS ck_workflow_node_type;
ALTER TABLE batch.workflow_node
    ADD CONSTRAINT ck_workflow_node_type
        CHECK (node_type IN ('TASK', 'GATEWAY', 'FILE_STEP', 'START', 'END', 'JOB'));

-- workflow_node_run runtime table
ALTER TABLE batch.workflow_node_run
    DROP CONSTRAINT IF EXISTS ck_workflow_node_run_type;
ALTER TABLE batch.workflow_node_run
    ADD CONSTRAINT ck_workflow_node_run_type
        CHECK (node_type IN ('TASK', 'GATEWAY', 'FILE_STEP', 'START', 'END', 'JOB'));
