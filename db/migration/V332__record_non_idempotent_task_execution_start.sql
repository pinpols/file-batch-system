ALTER TABLE batch.job_task
    ADD COLUMN IF NOT EXISTS non_idempotent_started_at TIMESTAMPTZ;

-- archive 镜像必须与运行态保持同一列集合，避免启动期 schema drift 守护阻断部署。
ALTER TABLE archive.job_task_archive
    ADD COLUMN IF NOT EXISTS non_idempotent_started_at TIMESTAMPTZ;

COMMENT ON COLUMN batch.job_task.non_idempotent_started_at IS
    'First durable indication that a non-idempotent executor started for this task; expired leases become EXTERNAL_OUTCOME_UNKNOWN instead of automatic replay.';

COMMENT ON COLUMN archive.job_task_archive.non_idempotent_started_at IS
    'Mirror of batch.job_task.non_idempotent_started_at for archived task execution history.';
