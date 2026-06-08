-- Stage 6d pause simulation for wheel scheduler.
-- Required psql variable: job_code

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
