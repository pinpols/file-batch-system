-- 同一认领下的执行入口使用稳定标识，避免回报待交付时重复派发再次进入业务。
ALTER TABLE batch.job_task ADD COLUMN execution_attempt_id varchar(64);
ALTER TABLE archive.job_task_archive ADD COLUMN execution_attempt_id varchar(64);

COMMENT ON COLUMN batch.job_task.execution_attempt_id IS
    '当前任务认领内已确认的执行尝试标识；重新认领时清空';
COMMENT ON COLUMN archive.job_task_archive.execution_attempt_id IS
    '归档任务最终认领的执行尝试标识';
