-- Stage 6d 时间轮调度器的暂停模拟。
-- 必填 psql 变量:job_code

WITH target AS (
  UPDATE batch.job_definition
  SET enabled = false,
      updated_at = CURRENT_TIMESTAMP
  WHERE tenant_id = 'ta'
    AND job_code = :'job_code'
  RETURNING id
)
DELETE FROM batch.trigger_runtime_state
WHERE job_definition_id IN (SELECT id FROM target);
