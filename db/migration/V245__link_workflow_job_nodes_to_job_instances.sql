-- 让每个可执行 JOB 节点指向其所属的 Job Instance。对旧版 TASK/FILE_STEP 运行和历史记录保持可空；由于 job_instance 是分区表，此处不添加外键。
-- TASK/FILE_STEP 运行及历史记录保持可空；由于 job_instance 是分区表，此处不添加外键。

ALTER TABLE batch.workflow_node_run
    ADD COLUMN job_instance_id BIGINT;

ALTER TABLE archive.workflow_node_run_archive
    ADD COLUMN job_instance_id BIGINT;

CREATE UNIQUE INDEX uk_workflow_node_run_job_instance
    ON batch.workflow_node_run (job_instance_id)
    WHERE job_instance_id IS NOT NULL;

COMMENT ON COLUMN batch.workflow_node_run.job_instance_id IS
    '执行此 Workflow JOB 节点的 JobInstance；历史未关联节点及非 JOB 节点为空。';
COMMENT ON COLUMN archive.workflow_node_run_archive.job_instance_id IS
    'batch.workflow_node_run.job_instance_id 的归档镜像；未记录 JobInstance 关联时为空。';

-- 子 Job 启动只携带所属 Workflow 节点标识。Workflow 负责编排。
-- 根运行；节点完成不依赖虚构的父 Job、分区或任务。

ALTER TABLE batch.trigger_request
    ADD COLUMN parent_workflow_run_id BIGINT,
    ADD COLUMN parent_node_code VARCHAR(128);

COMMENT ON COLUMN batch.trigger_request.parent_workflow_run_id IS
    '内部 Job 节点启动所属的 WorkflowRun；顶层触发时为空。';
COMMENT ON COLUMN batch.trigger_request.parent_node_code IS
    '内部 Job 节点启动所属的 Workflow JOB 节点编码。';
