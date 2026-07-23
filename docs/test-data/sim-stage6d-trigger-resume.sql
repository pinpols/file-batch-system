-- Stage 6d Quartz 调度器的恢复模拟；脚本随后调用 management/register。
-- 必填 psql 变量:job_code

UPDATE batch.job_definition
SET enabled = true,
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'ta'
  AND job_code = :'job_code';
