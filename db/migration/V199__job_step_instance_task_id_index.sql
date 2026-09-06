-- 删除 job_task 时会检查/级联 job_step_instance.job_task_id 外键。
-- V82 将唯一约束改为 (tenant_id, job_task_id) 以保证多租户隔离，不能再支持只按
-- job_task_id 的外键查找；为保留、归档和压测清理补充该访问路径。
CREATE INDEX IF NOT EXISTS idx_job_step_instance_task_id
    ON batch.job_step_instance (job_task_id);
