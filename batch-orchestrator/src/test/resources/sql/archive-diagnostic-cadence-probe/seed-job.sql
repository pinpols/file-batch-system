-- 复用既有归档 IT 的最小 Job 定义形状，不创建无关 Workflow/Worker。
insert into batch.job_definition(
    tenant_id, job_code, job_name, job_type, schedule_type, timezone, retry_policy
) values (:tenantId, 'ARCHIVE_PROBE', 'Archive diagnostic probe', 'GENERAL', 'MANUAL', 'Asia/Shanghai', 'NONE')
returning id
