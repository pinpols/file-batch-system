-- =========================================================
-- V27 - Add JOB node type to workflow_node and workflow_node_run
-- Notes:
-- 1) Allow workflow nodes to reference another job as a child execution.
-- 2) Keep node_type check constraints aligned on definition and runtime tables.
-- =========================================================

ALTER TABLE batch.workflow_node
    DROP CONSTRAINT IF EXISTS ck_workflow_node_type;
ALTER TABLE batch.workflow_node
    ADD CONSTRAINT ck_workflow_node_type
        CHECK (node_type IN ('TASK', 'GATEWAY', 'FILE_STEP', 'START', 'END', 'JOB'));

ALTER TABLE batch.workflow_node_run
    DROP CONSTRAINT IF EXISTS ck_workflow_node_run_type;
ALTER TABLE batch.workflow_node_run
    ADD CONSTRAINT ck_workflow_node_run_type
        CHECK (node_type IN ('TASK', 'GATEWAY', 'FILE_STEP', 'START', 'END', 'JOB'));
