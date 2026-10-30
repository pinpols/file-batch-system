-- 将 Workflow 持久化为 Trigger 的显式顶层目标，并把直接启动的 Workflow Run 关联到已接收的请求。
-- 内部 Job 节点请求仍保留 Job 目标及父 Run。

CREATE UNIQUE INDEX uk_workflow_definition_tenant_id
    ON batch.workflow_definition (tenant_id, id);

-- 以 Workflow 为目标的请求没有 Job code。

ALTER TABLE batch.trigger_request
    -- squawk-ignore ban-drop-not-null
    ALTER COLUMN job_code DROP NOT NULL,
    ADD COLUMN workflow_definition_id BIGINT,
    ADD CONSTRAINT ck_trigger_request_target
        CHECK ((job_code IS NOT NULL) <> (workflow_definition_id IS NOT NULL)) NOT VALID,
    ADD CONSTRAINT fk_trigger_request_workflow_definition
        FOREIGN KEY (tenant_id, workflow_definition_id)
        REFERENCES batch.workflow_definition (tenant_id, id) NOT VALID;

CREATE UNIQUE INDEX uk_trigger_request_tenant_id
    ON batch.trigger_request (tenant_id, id);

ALTER TABLE batch.workflow_run
    ADD COLUMN trigger_request_id BIGINT,
    ADD COLUMN workflow_version_snapshot INTEGER,
    ADD COLUMN data_interval_start TIMESTAMPTZ,
    ADD COLUMN data_interval_end TIMESTAMPTZ,
    ADD COLUMN execution_snapshot JSONB,
    ADD CONSTRAINT fk_workflow_run_trigger_request
        FOREIGN KEY (tenant_id, trigger_request_id)
        REFERENCES batch.trigger_request (tenant_id, id) NOT VALID,
    ADD CONSTRAINT ck_workflow_run_data_interval
        CHECK (data_interval_start IS NULL OR data_interval_end IS NULL OR data_interval_start < data_interval_end) NOT VALID;

ALTER TABLE batch.trigger_request
    VALIDATE CONSTRAINT ck_trigger_request_target,
    VALIDATE CONSTRAINT fk_trigger_request_workflow_definition;

ALTER TABLE batch.workflow_run
    VALIDATE CONSTRAINT fk_workflow_run_trigger_request,
    VALIDATE CONSTRAINT ck_workflow_run_data_interval;

ALTER TABLE archive.workflow_run_archive
    ADD COLUMN trigger_request_id BIGINT,
    ADD COLUMN workflow_version_snapshot INTEGER,
    ADD COLUMN data_interval_start TIMESTAMPTZ,
    ADD COLUMN data_interval_end TIMESTAMPTZ,
    ADD COLUMN execution_snapshot JSONB;

CREATE UNIQUE INDEX uk_workflow_run_trigger_request
    ON batch.workflow_run (tenant_id, trigger_request_id)
    WHERE trigger_request_id IS NOT NULL;

COMMENT ON COLUMN batch.trigger_request.workflow_definition_id IS
    '顶层触发指定的 Workflow 目标；仅由 parent_workflow_run_id 所属的内部 Job 节点请求为空。';
COMMENT ON COLUMN batch.workflow_run.trigger_request_id IS
    '创建此 WorkflowRun 的 TriggerRequest；在租户内唯一，用于保证 Kafka 重投幂等。';
COMMENT ON COLUMN batch.workflow_run.workflow_version_snapshot IS
    '本次运行受理时冻结的 Workflow 定义版本。';
COMMENT ON COLUMN batch.workflow_run.data_interval_start IS
    '此 WorkflowRun 冻结的触发数据区间起点，包含该时刻。';
COMMENT ON COLUMN batch.workflow_run.data_interval_end IS
    '此 WorkflowRun 冻结的触发数据区间终点，不包含该时刻。';
COMMENT ON COLUMN batch.workflow_run.execution_snapshot IS
    'WorkflowRun 受理时冻结的不可变 DAG 和节点有效执行配置。';
