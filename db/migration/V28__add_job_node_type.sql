-- =========================================================
-- V27 - Add JOB node type to workflow_node and workflow_node_run
-- 说明：
-- 1) 允许工作流节点引用其他作业作为子任务执行。
-- 2) 保持定义表与运行态表的 node_type CHECK 约束一致。
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
