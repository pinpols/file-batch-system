-- 派发和真实执行入口分别计时；不使用 CLAIM 时间回填，以免把批量排队伪装成实际执行。

ALTER TABLE batch.job_instance ADD COLUMN execution_started_at TIMESTAMPTZ;
ALTER TABLE archive.job_instance_archive ADD COLUMN execution_started_at TIMESTAMPTZ;

COMMENT ON COLUMN batch.job_instance.execution_started_at IS
    '当前租约所有者在执行线程进入适配器前确认的首次执行入口时间；与 started_at 派发时间独立。';
COMMENT ON COLUMN archive.job_instance_archive.execution_started_at IS
    '归档保留的首次实际执行入口时间，不从派发或认领时间推算。';
