-- Stage 6d Quartz 调度器的暂停模拟；脚本随后调用 management/pause。
-- 必填 psql 变量:job_code

UPDATE batch.job_definition
SET enabled = false,
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'ta'
  AND job_code = :'job_code';
