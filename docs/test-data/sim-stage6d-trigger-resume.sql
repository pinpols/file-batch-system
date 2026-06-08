-- Stage 6d resume simulation for wheel scheduler.
-- Required psql variable: job_code

WITH target AS (
  UPDATE batch.job_definition
  SET enabled = true,
      updated_at = CURRENT_TIMESTAMP
  WHERE tenant_id = 'ta'
    AND job_code = :'job_code'
  RETURNING id, tenant_id, job_code, timezone
),
cleanup AS (
  DELETE FROM batch.trigger_runtime_state
  WHERE job_definition_id IN (SELECT id FROM target)
)
INSERT INTO batch.trigger_runtime_state (
    job_definition_id, tenant_id, job_code, next_fire_time,
    misfire_count, version, schedule_timezone,
    scheduled_local_date, scheduled_local_time, fire_sequence
)
SELECT id,
       tenant_id,
       job_code,
       date_trunc('second', now()) + interval '2 seconds',
       0,
       1,
       timezone,
       ((date_trunc('second', now()) + interval '2 seconds') AT TIME ZONE timezone)::date,
       ((date_trunc('second', now()) + interval '2 seconds') AT TIME ZONE timezone)::time,
       1
FROM target;
