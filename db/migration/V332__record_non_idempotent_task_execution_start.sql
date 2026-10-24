-- 记录非幂等任务首次开始外部执行的时刻，供租约丢失后的恢复决策使用。

ALTER TABLE batch.job_task
    ADD COLUMN IF NOT EXISTS non_idempotent_started_at TIMESTAMPTZ;

-- archive 镜像必须与运行态保持同一列集合，避免启动期 schema drift 守护阻断部署。

ALTER TABLE archive.job_task_archive
    ADD COLUMN IF NOT EXISTS non_idempotent_started_at TIMESTAMPTZ;

COMMENT ON COLUMN batch.job_task.non_idempotent_started_at IS
    '首次持久记录此任务已启动非幂等执行器；租约过期后状态转为 EXTERNAL_OUTCOME_UNKNOWN，避免自动重放。';

COMMENT ON COLUMN archive.job_task_archive.non_idempotent_started_at IS
    'batch.job_task.non_idempotent_started_at 的归档镜像，用于保留任务执行历史。';
