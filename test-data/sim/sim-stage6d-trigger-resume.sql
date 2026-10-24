-- Stage 6d Quartz 调度器的恢复模拟；脚本随后调用 management/register。
-- 必填 psql 变量:workflow_code

UPDATE batch.workflow_definition
SET schedule_enabled = true,
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'ta'
  AND workflow_code = :'workflow_code';
