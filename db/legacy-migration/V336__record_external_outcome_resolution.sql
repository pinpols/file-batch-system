-- 记录操作员已核实外部动作未执行的任务尝试版本。审计证据保留在
-- job_execution_log 中，并随任务历史一同归档。

ALTER TABLE batch.job_task
    ADD COLUMN external_outcome_resolution_version BIGINT,
    ADD COLUMN external_outcome_resolution_trigger_request_id BIGINT,
    ADD COLUMN external_outcome_resolution_consumed_at TIMESTAMPTZ;

ALTER TABLE archive.job_task_archive
    ADD COLUMN external_outcome_resolution_version BIGINT,
    ADD COLUMN external_outcome_resolution_trigger_request_id BIGINT,
    ADD COLUMN external_outcome_resolution_consumed_at TIMESTAMPTZ;

ALTER TABLE batch.job_task
    ADD CONSTRAINT fk_job_task_external_outcome_resolution_request
        FOREIGN KEY (external_outcome_resolution_trigger_request_id)
        REFERENCES batch.trigger_request (id)
        ON DELETE SET NULL
        NOT VALID;

ALTER TABLE batch.job_task
    VALIDATE CONSTRAINT fk_job_task_external_outcome_resolution_request;

ALTER TABLE batch.job_task
    ADD CONSTRAINT ck_job_task_external_outcome_resolution_consumed
        CHECK (
            external_outcome_resolution_consumed_at IS NULL
            OR external_outcome_resolution_version IS NOT NULL
        ) NOT VALID;

ALTER TABLE batch.job_task
    VALIDATE CONSTRAINT ck_job_task_external_outcome_resolution_consumed;

COMMENT ON COLUMN batch.job_task.external_outcome_resolution_version IS
    '经人工核实未执行的 EXTERNAL_OUTCOME_UNKNOWN 任务版本；每次失败尝试仅授权一次。';

COMMENT ON COLUMN batch.job_task.external_outcome_resolution_trigger_request_id IS
    '获批后唯一可消费该未执行核实授权的根 Workflow 启动请求。';

COMMENT ON COLUMN batch.job_task.external_outcome_resolution_consumed_at IS
    'Worker 开始唯一获批外部重试的消费时间；即使 Worker 随后崩溃也不恢复授权。';

COMMENT ON COLUMN archive.job_task_archive.external_outcome_resolution_version IS
    '归档的任务版本；其 EXTERNAL_OUTCOME_UNKNOWN 已经人工核实为未执行。';

COMMENT ON COLUMN archive.job_task_archive.external_outcome_resolution_trigger_request_id IS
    '获批后唯一可消费该未执行核实授权的根 Workflow 启动请求。';

COMMENT ON COLUMN archive.job_task_archive.external_outcome_resolution_consumed_at IS
    'Worker 开始唯一获批外部重试的消费时间；即使 Worker 随后崩溃也不恢复授权。';
