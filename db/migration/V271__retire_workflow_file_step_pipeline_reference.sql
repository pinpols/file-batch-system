-- 全新部署改用 Job 节点，不再建模 FILE_STEP 到 Pipeline 的关联。

ALTER TABLE batch.workflow_node
    -- squawk-ignore ban-drop-column
    DROP COLUMN related_pipeline_code;

-- 移除 FILE_STEP 后更新节点允许值列表；在迁移提交前重新校验。

ALTER TABLE batch.workflow_node
    DROP CONSTRAINT ck_workflow_node_type;
ALTER TABLE batch.workflow_node
    ADD CONSTRAINT ck_workflow_node_type
        CHECK (node_type IN ('TASK', 'GATEWAY', 'START', 'END', 'WAIT', 'JOB')) NOT VALID;

ALTER TABLE batch.workflow_node
    VALIDATE CONSTRAINT ck_workflow_node_type;

-- 定义和执行历史保持相同的已退役节点类型契约。

ALTER TABLE batch.workflow_node_run
    DROP CONSTRAINT ck_workflow_node_run_type;
ALTER TABLE batch.workflow_node_run
    ADD CONSTRAINT ck_workflow_node_run_type
        CHECK (node_type IN ('TASK', 'GATEWAY', 'START', 'END', 'WAIT', 'JOB')) NOT VALID;

ALTER TABLE batch.workflow_node_run
    VALIDATE CONSTRAINT ck_workflow_node_run_type;
